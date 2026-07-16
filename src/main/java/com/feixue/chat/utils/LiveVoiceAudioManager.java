package com.feixue.chat.utils;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Full-duplex 8kHz PCM audio with a small jitter buffer. No denoise processing is applied. */
public final class LiveVoiceAudioManager {
    private static final String TAG = "LiveVoiceAudio";
    public static final int SAMPLE_RATE = 8000;
    public static final int FRAME_BYTES = 320;
    private static final int QUEUE_CAPACITY = 50;
    private static final int PREBUFFER_FRAMES = 6;
    private static final int MAX_LATENCY_FRAMES = 15;
    private static final byte[] SILENCE = new byte[FRAME_BYTES];

    public interface FrameListener {
        void onFrame(String base64Pcm);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final ArrayBlockingQueue<byte[]> playbackQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Object lock = new Object();
    private volatile boolean active;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread captureThread;
    private Thread playbackThread;
    private boolean audioSessionConfigured;
    private boolean speakerphoneOn;
    private boolean previousSpeakerphoneOn;
    private int previousAudioMode = AudioManager.MODE_NORMAL;

    public LiveVoiceAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean start(FrameListener listener) {
        synchronized (lock) {
            if (active) return true;
            int minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minRecord <= 0 || minTrack <= 0) return false;
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minRecord * 2, FRAME_BYTES * 4));
                audioTrack = new AudioTrack(android.media.AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minTrack * 2, FRAME_BYTES * 8),
                        AudioTrack.MODE_STREAM);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED
                        || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    releaseLocked();
                    return false;
                }
                configureAudioSessionLocked();
                playbackQueue.clear();
                active = true;
                audioRecord.startRecording();
                audioTrack.play();
            } catch (Exception e) {
                Log.e(TAG, "实时语音初始化失败", e);
                releaseLocked();
                return false;
            }
        }

        captureThread = new Thread(() -> captureLoop(listener), "AndroidLiveVoiceCapture");
        captureThread.setDaemon(true);
        captureThread.start();
        playbackThread = new Thread(this::playbackLoop, "AndroidLiveVoicePlayback");
        playbackThread.setDaemon(true);
        playbackThread.start();
        return true;
    }

    private void captureLoop(FrameListener listener) {
        byte[] frame = new byte[FRAME_BYTES];
        while (active && !Thread.currentThread().isInterrupted()) {
            try {
                int read = audioRecord.read(frame, 0, frame.length);
                if (read <= 0 || !active) continue;
                byte[] normalized = read == frame.length ? frame.clone() : Arrays.copyOf(frame, frame.length);
                listener.onFrame(Base64.encodeToString(normalized, Base64.NO_WRAP));
            } catch (Exception e) {
                if (active) Log.e(TAG, "实时语音采集失败", e);
                break;
            }
        }
    }

    private void playbackLoop() {
        boolean primed = false;
        int underruns = 0;
        while (active && !Thread.currentThread().isInterrupted()) {
            try {
                if (!primed) {
                    while (active && playbackQueue.size() < PREBUFFER_FRAMES) {
                        Thread.sleep(5);
                    }
                    if (!active) break;
                    primed = true;
                }
                while (playbackQueue.size() > MAX_LATENCY_FRAMES) playbackQueue.poll();
                byte[] frame = playbackQueue.poll(30, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    audioTrack.write(SILENCE, 0, SILENCE.length);
                    if (++underruns >= 3) {
                        primed = false;
                        underruns = 0;
                    }
                } else {
                    audioTrack.write(frame, 0, frame.length);
                    underruns = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (active) Log.e(TAG, "实时语音播放失败", e);
                break;
            }
        }
    }

    public void enqueue(String base64Pcm) {
        if (!active || base64Pcm == null || base64Pcm.isEmpty()) return;
        try {
            byte[] frame = Base64.decode(base64Pcm, Base64.NO_WRAP);
            if (frame.length != FRAME_BYTES) frame = Arrays.copyOf(frame, FRAME_BYTES);
            while (playbackQueue.size() >= MAX_LATENCY_FRAMES) playbackQueue.poll();
            playbackQueue.offer(frame);
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed realtime audio frames.
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean setSpeakerphone(boolean enabled) {
        synchronized (lock) {
            if (!active || audioManager == null) return false;
            try {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(enabled);
                speakerphoneOn = enabled;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "切换免提失败", e);
                return false;
            }
        }
    }

    public boolean isSpeakerphoneOn() {
        synchronized (lock) {
            return speakerphoneOn;
        }
    }

    public void stop() {
        synchronized (lock) {
            active = false;
            releaseLocked();
        }
        if (captureThread != null) captureThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();
        playbackQueue.clear();
    }

    private void releaseLocked() {
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
        restoreAudioSessionLocked();
    }

    private void configureAudioSessionLocked() {
        if (audioManager == null || audioSessionConfigured) return;
        try {
            previousAudioMode = audioManager.getMode();
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            speakerphoneOn = false;
            audioSessionConfigured = true;
        } catch (Exception e) {
            Log.w(TAG, "初始化通话音频路由失败", e);
        }
    }

    private void restoreAudioSessionLocked() {
        if (audioManager == null || !audioSessionConfigured) return;
        try {
            audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
            audioManager.setMode(previousAudioMode);
        } catch (Exception e) {
            Log.w(TAG, "恢复通话音频路由失败", e);
        } finally {
            audioSessionConfigured = false;
            speakerphoneOn = false;
        }
    }
}
