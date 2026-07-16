package com.feixue.chat.utils;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 音频录制/播放工具 - 使用原始PCM格式（8000Hz, 16bit, 单声道）
 * 与 Windows 桌面客户端 (src/ChatClient.java) 的语音格式完全兼容
 *
 * Windows 客户端使用:
 *   AudioFormat(8000, 16, 1, true, false) 即 8kHz, 16位, 单声道, signed, littleEndian
 *   录音: TargetDataLine -> raw PCM bytes
 *   播放: SourceDataLine  -> raw PCM bytes
 */
public class AudioRecorderUtil {

    private static final String TAG = "AudioRecorder";
    private static final int MAX_RECORD_SECONDS = 8;
    private static final String AUDIO_DIR = "voice_cache";

    // PCM格式参数，与Windows客户端完全一致
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private ByteArrayOutputStream pcmStream;
    private long startTime;
    private Context context;

    public AudioRecorderUtil(Context context) {
        this.context = context;
    }

    /**
     * 开始录音 - 使用 AudioRecord 录制原始PCM
     * 与Windows客户端格式完全一致: 8000Hz, 16bit, 单声道, signed PCM
     */
    public boolean startRecording() {
        // 检查录音权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (android.os.Process.myUid() == android.os.Process.myUid()) {
                // 旧版本权限已在安装时授予
            }
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_ENCODING);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "不支持的录音参数");
            return false;
        }

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_ENCODING, bufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败");
                return false;
            }

            pcmStream = new ByteArrayOutputStream();
            audioRecord.startRecording();
            isRecording = true;
            startTime = System.currentTimeMillis();

            // 在后台线程读取PCM数据
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                while (isRecording && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        synchronized (AudioRecorderUtil.this) {
                            if (isRecording) {
                                pcmStream.write(buffer, 0, read);
                            }
                        }
                    }
                }
            });
            recordingThread.setDaemon(true);
            recordingThread.start();

            Log.d(TAG, "开始PCM录音, 采样率: " + SAMPLE_RATE + "Hz, 16bit, 单声道");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "录音权限被拒绝", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "录音启动失败", e);
            return false;
        }
    }

    /**
     * 停止录音并返回Base64编码的原始PCM数据
     */
    public String stopRecording() {
        if (!isRecording) return null;
        isRecording = false;

        try {
            // 等待录音线程结束
            if (recordingThread != null) {
                try {
                    recordingThread.join(2000);
                } catch (InterruptedException e) {
                    recordingThread.interrupt();
                }
                recordingThread = null;
            }

            // 停止并释放AudioRecord
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "AudioRecord stop异常", e);
                }
                audioRecord.release();
                audioRecord = null;
            }

            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "录音时长: " + duration + "ms");

            byte[] pcmData;
            synchronized (this) {
                pcmData = pcmStream != null ? pcmStream.toByteArray() : null;
            }
            if (pcmData == null || pcmData.length == 0) {
                Log.w(TAG, "录音数据为空");
                return null;
            }

            // Base64编码原始PCM数据
            String base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP);
            pcmStream = null;

            Log.d(TAG, "PCM录音完成, 大小: " + pcmData.length + " 字节");
            return base64;
        } catch (Exception e) {
            Log.e(TAG, "录音处理失败", e);
            return null;
        }
    }

    /**
     * 取消录音（不发送）
     */
    public void cancelRecording() {
        if (!isRecording) return;
        isRecording = false;

        try {
            if (recordingThread != null) {
                recordingThread.interrupt();
                recordingThread = null;
            }
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException ignored) {}
                audioRecord.release();
                audioRecord = null;
            }
            pcmStream = null;
        } catch (Exception e) {
            Log.e(TAG, "取消录音失败", e);
        }
    }

    public long getRecordingDuration() {
        if (!isRecording) return 0;
        return System.currentTimeMillis() - startTime;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 播放原始PCM语音数据（使用AudioTrack，与Windows客户端SourceDataLine一致）
     *
     * @param context 上下文
     * @param base64Audio Base64编码的PCM音频数据
     * @param listener 播放完成回调（可选）
     */
    public static void playVoice(Context context, String base64Audio, OnPlayListener listener) {
        if (base64Audio == null || base64Audio.isEmpty()) {
            Log.w(TAG, "播放语音: 数据为空");
            return;
        }

        try {
            byte[] pcmData = Base64.decode(base64Audio, Base64.NO_WRAP);
            if (pcmData == null || pcmData.length == 0) {
                Log.w(TAG, "播放语音: 解码后数据为空");
                return;
            }

            Log.d(TAG, "播放PCM语音, 大小: " + pcmData.length + " 字节");

            int bufferSize = Math.max(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_ENCODING),
                    pcmData.length);

            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            android.media.AudioFormat af = new android.media.AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build();

            // 使用 MODE_STREAM + 先 play 再 write，兼容性更好
            AudioTrack audioTrack = new AudioTrack(attrs, af, bufferSize,
                    AudioTrack.MODE_STREAM, 0); // 0 = 自动生成 session ID

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack初始化失败");
                return;
            }

            audioTrack.play();
            audioTrack.write(pcmData, 0, pcmData.length);

            // 在后台线程等待播放完成，然后回调
            if (listener != null) {
                final long playDurationMs = (pcmData.length * 1000L) / (SAMPLE_RATE * 2); // PCM16=2bytes/frame
                new Thread(() -> {
                    try {
                        long waitEnd = System.currentTimeMillis() + playDurationMs + 500;
                        while (System.currentTimeMillis() < waitEnd) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                break;
                            }
                            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_STOPPED) {
                                break;
                            }
                        }
                    } finally {
                        audioTrack.stop();
                        audioTrack.release();
                        listener.onPlayComplete();
                    }
                }).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "播放PCM语音失败", e);
        }
    }

    public interface OnPlayListener {
        void onPlayComplete();
    }

    /**
     * 旧版兼容方法 - 使用MediaPlayer播放（保留但不再用于新数据）
     */
    @Deprecated
    public static void playVoiceLegacy(Context context, String base64Audio,
                                        android.media.MediaPlayer.OnCompletionListener listener) {
        try {
            byte[] audioData = Base64.decode(base64Audio, Base64.NO_WRAP);

            File tempDir = new File(context.getCacheDir(), AUDIO_DIR);
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, "play_temp.3gp");

            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(audioData);
            fos.close();

            android.media.MediaPlayer mediaPlayer = new android.media.MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            if (listener != null) {
                mediaPlayer.setOnCompletionListener(mp -> {
                    listener.onCompletion(mp);
                    mp.release();
                    if (tempFile.exists()) tempFile.delete();
                });
            } else {
                mediaPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    if (tempFile.exists()) tempFile.delete();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "旧版播放语音失败", e);
        }
    }
}
