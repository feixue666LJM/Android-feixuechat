package com.feixue.chat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.feixue.chat.utils.LiveVoiceAudioManager;
import com.feixue.chat.utils.P2PCallOverlayManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 聊天网络服务 - 管理TCP Socket连接
 * 与 fwq/ChatServer.java 服务器通信，协议与 src/ChatClient.java 一致
 *
 * 通信协议：
 * - 文本消息直接发送
 * - 语音: /voice|voiceId|base64音频
 * - 图片信息: /image_info|imageId|总块数|文件名
 * - 图片块: /image_chunk|imageId|块索引|块数据
 * - P2P: /p2p|目标密码|消息内容
 * - DeepSeek: /deepseek|问题
 */
public class ChatService extends Service {

    private static final String TAG = "ChatService";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private static final int HEARTBEAT_INTERVAL = 20000;
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final int INCOMING_CALL_NOTIFICATION_ID = 1002;
    private static final String SERVICE_CHANNEL_ID = "chat_service";
    private static final String CALL_CHANNEL_ID = "incoming_voice_call";
    public static final String EXTRA_SERVER_IP = "server_ip";
    public static final String EXTRA_SERVER_PORT = "server_port";
    public static final String EXTRA_NICKNAME = "nickname";
    public static final String EXTRA_GROUP_NAME = "group_name";
    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_PEER_PASSWORD = "peer_password";

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private Handler sendHandler;
    private HandlerThread sendThread;
    private Thread networkThread;
    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;
    private volatile boolean running = false;
    private volatile boolean sessionReady = false;
    private volatile int connectionId = 0;

    private String serverIp;
    private int serverPort;
    private String nickname;
    private String groupName;
    private String p2pPassword;

    private ChatCallback callback;
    private LiveVoiceAudioManager liveVoiceAudio;
    private volatile String liveVoiceMode;
    private volatile String liveVoicePeer;
    private P2PCallOverlayManager callOverlay;
    private NotificationManager notificationManager;

    public interface ChatCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String sender, String message);
        void onVoiceReceived(String sender, String voiceId, String base64Audio);
        void onImageInfo(String imageId, int totalChunks, String fileName);
        void onImageChunk(String imageId, int chunkIndex, String chunkData);
        void onP2PVerifyResult(boolean success);
        void onP2PMessage(String sender, String senderPassword, String message);
        void onP2PNotification(String sender, String senderPassword);
        void onOnlineUsers(String usersJson);
        void onDeepSeekAnswer(String answer);
        void onHistoryStart();
        void onHistoryMessage(String message);
        void onHistoryEnd();
        void onP2PPassword(String password);
        void onError(String error);
        default void onSessionReady() {}
        default void onGroupVoiceJoined(String group, int members) {}
        default void onGroupVoiceMembers(int members) {}
        default void onGroupVoiceLeft() {}
        default void onGroupVoiceDisabled(String group) {}
        default void onGroupVoiceAudio(String sender, String base64Pcm) {}
        default void onP2PVoiceRequest(String sender, String senderPassword) {}
        default void onP2PVoiceRequestSent(String target) {}
        default void onP2PVoiceStarted(String peer, String peerPassword) {}
        default void onP2PVoiceAudio(String sender, String base64Pcm) {}
        default void onP2PVoiceEnded(String peer) {}
        default void onP2PVoiceRejected(String peer, String reason) {}
        default void onLiveVoiceError(String error) {}
    }

    public class LocalBinder extends Binder {
        public ChatService getService() { return ChatService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        liveVoiceAudio = new LiveVoiceAudioManager(this);
        callOverlay = new P2PCallOverlayManager(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();
        promoteForeground(false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (IncomingCallActionReceiver.ACTION_ACCEPT.equals(action)
                    || IncomingCallActionReceiver.ACTION_REJECT.equals(action)) {
                handleIncomingCallAction(action,
                        intent.getStringExtra(EXTRA_PEER_NAME),
                        intent.getStringExtra(EXTRA_PEER_PASSWORD));
            } else {
                String ip = intent.getStringExtra(EXTRA_SERVER_IP);
                if (ip != null && !ip.isEmpty()) serverIp = ip;
                if (intent.hasExtra(EXTRA_SERVER_PORT)) serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, serverPort);
                String incomingNickname = intent.getStringExtra(EXTRA_NICKNAME);
                if (incomingNickname != null) nickname = incomingNickname;
                String incomingGroup = intent.getStringExtra(EXTRA_GROUP_NAME);
                if (incomingGroup != null) groupName = incomingGroup;
                if (!running && serverIp != null && serverPort > 0) {
                    connect(serverIp, serverPort);
                }
            }
        }
        return START_REDELIVER_INTENT;
    }

    public void setCallback(ChatCallback callback) {
        this.callback = callback;
        if (callback != null && sessionReady) {
            mainHandler.post(() -> {
                callback.onSessionReady();
                callback.onConnected();
            });
        }
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getNickname() { return nickname; }
    public String getP2pPassword() { return p2pPassword; }
    public boolean isRunning() { return running; }

    /**
     * 连接到聊天服务器
     */
    public void connect(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        if (networkThread != null && networkThread.isAlive()) {
            disconnect("新连接");
        }
        // 启动后台发送线程
        sendThread = new HandlerThread("SendThread");
        sendThread.start();
        sendHandler = new Handler(sendThread.getLooper());

        running = true;
        sessionReady = false;
        liveVoiceMode = null;
        liveVoicePeer = null;
        final int myConnId = ++connectionId;
        networkThread = new Thread(() -> networkLoop(myConnId));
        networkThread.setDaemon(true);
        networkThread.start();
    }

    /**
     * 断开连接
     */
    public void disconnect(String reason) {
        running = false;
        sessionReady = false;
        clearIncomingCallPrompt();
        stopLiveVoice();
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭socket异常", e);
        }
        // 停止发送线程
        if (sendThread != null) {
            sendThread.quit();
            sendThread = null;
            sendHandler = null;
        }
        if (callback != null) {
            mainHandler.post(() -> callback.onDisconnected(reason));
        }
    }

    private void networkLoop(int connId) {
        try {
            Log.d(TAG, "正在连接 " + serverIp + ":" + serverPort);
            socket = new Socket();
            socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer = socket.getOutputStream();
            Log.d(TAG, "已连接到服务器");

            // 发送版本信息
            sendCommand("/version|3.0.0");
            sendCommand("/group|" + (groupName != null ? groupName : "group_public"));
            sendCommand("/nickname|" + (nickname != null ? nickname : "Android用户"));

            // 启动心跳
            startHeartbeat();

            // 读取服务器消息
            String line;
            while (connId == connectionId && running && (line = reader.readLine()) != null) {
                handleServerMessage(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "网络异常: " + e.getMessage());
            if (connId == connectionId && running && callback != null) {
                mainHandler.post(() -> callback.onDisconnected("连接断开: " + e.getMessage()));
            }
        } finally {
            stopLiveVoice();
            cleanup(connId);
        }
    }

    private interface CallbackAction {
        void run(ChatCallback callback);
    }

    private void postCallback(CallbackAction action) {
        if (mainHandler == null || action == null) return;
        mainHandler.post(() -> {
            ChatCallback current = callback;
            if (current != null) action.run(current);
        });
    }

    private void handleServerMessage(String line) {
        Log.d(TAG, "收到消息: " + (line.length() > 100 ? line.substring(0, 100) + "..." : line));

        if (callback == null && !line.startsWith("/live_")
                && !line.startsWith("/session_ready|") && !line.startsWith("/p2p_password|")) {
            return;
        }

        try {
            if (line.startsWith("/p2p_password|")) {
                p2pPassword = line.substring("/p2p_password|".length());
                String finalPwd = p2pPassword;
                postCallback(cb -> cb.onP2PPassword(finalPwd));

            } else if (line.startsWith("/session_ready|")) {
                if ("success".equals(line.substring("/session_ready|".length()))) {
                    sessionReady = true;
                    postCallback(cb -> {
                        cb.onSessionReady();
                        cb.onConnected();
                    });
                }

            } else if (line.startsWith("/live_group_joined|")) {
                String[] parts = line.substring("/live_group_joined|".length()).split("\\|", 2);
                if (parts.length == 2) {
                    int members = parseInt(parts[1], 0);
                    startLiveVoice("GROUP", null);
                    postCallback(cb -> cb.onGroupVoiceJoined(parts[0], members));
                }

            } else if (line.startsWith("/live_group_members|")) {
                int members = parseInt(line.substring("/live_group_members|".length()), 0);
                postCallback(cb -> cb.onGroupVoiceMembers(members));

            } else if (line.equals("/live_group_left")) {
                stopLiveVoice();
                postCallback(ChatCallback::onGroupVoiceLeft);

            } else if (line.startsWith("/live_group_disabled|")) {
                stopLiveVoice();
                String group = line.substring("/live_group_disabled|".length());
                postCallback(cb -> cb.onGroupVoiceDisabled(group));

            } else if (line.startsWith("/live_group_audio|")) {
                String[] parts = line.substring("/live_group_audio|".length()).split("\\|", 2);
                if (parts.length == 2) {
                    liveVoiceAudio.enqueue(parts[1]);
                    postCallback(cb -> cb.onGroupVoiceAudio(parts[0], parts[1]));
                }

            } else if (line.startsWith("/live_p2p_request|")) {
                String[] parts = line.substring("/live_p2p_request|".length()).split("\\|", 2);
                if (parts.length == 2) {
                    String sender = parts[0];
                    String senderPassword = parts[1];
                    boolean overlayShown = showIncomingP2PCall(sender, senderPassword);
                    if (!overlayShown) {
                        showIncomingCallNotification(sender, senderPassword);
                    }
                    postCallback(cb -> cb.onP2PVoiceRequest(sender, senderPassword));
                }

            } else if (line.startsWith("/live_p2p_request_sent|")) {
                String target = line.substring("/live_p2p_request_sent|".length());
                    postCallback(cb -> cb.onP2PVoiceRequestSent(target));

            } else if (line.startsWith("/live_p2p_started|")) {
                String[] parts = line.substring("/live_p2p_started|".length()).split("\\|", 2);
                if (parts.length == 2) {
                    clearIncomingCallPrompt();
                    startLiveVoice("P2P", parts[0]);
                    postCallback(cb -> cb.onP2PVoiceStarted(parts[0], parts[1]));
                }

            } else if (line.startsWith("/live_p2p_audio|")) {
                String[] parts = line.substring("/live_p2p_audio|".length()).split("\\|", 2);
                if (parts.length == 2) {
                    liveVoiceAudio.enqueue(parts[1]);
                    postCallback(cb -> cb.onP2PVoiceAudio(parts[0], parts[1]));
                }

            } else if (line.startsWith("/live_p2p_ended|")) {
                String peer = line.substring("/live_p2p_ended|".length());
                clearIncomingCallPrompt();
                stopLiveVoice();
                postCallback(cb -> cb.onP2PVoiceEnded(peer));

            } else if (line.startsWith("/live_p2p_rejected|")) {
                String[] parts = line.substring("/live_p2p_rejected|".length()).split("\\|", 2);
                if (parts.length >= 1) {
                    clearIncomingCallPrompt();
                    String reason = parts.length == 2 ? parts[1] : "对方未接听";
                    postCallback(cb -> cb.onP2PVoiceRejected(parts[0], reason));
                }

            } else if (line.startsWith("/live_p2p_rejected_ack|")) {
                String peer = line.substring("/live_p2p_rejected_ack|".length());
                clearIncomingCallPrompt();
                postCallback(cb -> cb.onP2PVoiceRejected(peer, "已拒绝语音申请"));

            } else if (line.startsWith("/live_p2p_cancelled|")) {
                String peer = line.substring("/live_p2p_cancelled|".length());
                clearIncomingCallPrompt();
                postCallback(cb -> cb.onP2PVoiceRejected(peer, "对方已取消或离线"));

            } else if (line.startsWith("/live_voice_error|")) {
                String error = line.substring("/live_voice_error|".length());
                postCallback(cb -> cb.onLiveVoiceError(error));

            } else if (line.startsWith("/p2p_verify_result|")) {
                boolean success = "success".equals(line.substring("/p2p_verify_result|".length()));
                mainHandler.post(() -> callback.onP2PVerifyResult(success));

            } else if (line.startsWith("/p2p_notification|")) {
                String[] parts = line.substring("/p2p_notification|".length()).split("\\|", 2);
                if (parts.length >= 2) {
                    mainHandler.post(() -> callback.onP2PNotification(parts[0], parts[1]));
                }

            } else if (line.startsWith("/p2p_msg|")) {
                String inner = line.substring("/p2p_msg|".length());
                int idx1 = inner.indexOf('|');
                if (idx1 > 0) {
                    String sender = inner.substring(0, idx1);
                    String rest = inner.substring(idx1 + 1);
                    int idx2 = rest.indexOf('|');
                    if (idx2 > 0) {
                        String senderPwd = rest.substring(0, idx2);
                        String msg = rest.substring(idx2 + 1);
                        mainHandler.post(() -> callback.onP2PMessage(sender, senderPwd, msg));
                    }
                }

            } else if (line.startsWith("/voice_with_sender|")) {
                String inner = line.substring("/voice_with_sender|".length());
                String[] parts = inner.split("\\|", 3);
                if (parts.length >= 3) {
                    mainHandler.post(() -> callback.onVoiceReceived(parts[0], parts[1], parts[2]));
                }

            } else if (line.startsWith("/voice|")) {
                String inner = line.substring("/voice|".length());
                String[] parts = inner.split("\\|", 2);
                if (parts.length >= 2) {
                    mainHandler.post(() -> callback.onVoiceReceived("", parts[0], parts[1]));
                }

            } else if (line.startsWith("/image_info|")) {
                String inner = line.substring("/image_info|".length());
                String[] parts = inner.split("\\|", 3);
                if (parts.length >= 3) {
                    try {
                        int totalChunks = Integer.parseInt(parts[1]);
                        mainHandler.post(() -> callback.onImageInfo(parts[0], totalChunks, parts[2]));
                    } catch (NumberFormatException ignored) {}
                }

            } else if (line.startsWith("/image_chunk|")) {
                String inner = line.substring("/image_chunk|".length());
                // 格式: imageId|chunkIndex|data
                int idx1 = inner.indexOf('|');
                if (idx1 > 0) {
                    String imageId = inner.substring(0, idx1);
                    String rest = inner.substring(idx1 + 1);
                    int idx2 = rest.indexOf('|');
                    if (idx2 > 0) {
                        String chunkIdxStr = rest.substring(0, idx2);
                        String chunkData = rest.substring(idx2 + 1);
                        try {
                            int chunkIndex = Integer.parseInt(chunkIdxStr);
                            mainHandler.post(() -> callback.onImageChunk(imageId, chunkIndex, chunkData));
                        } catch (NumberFormatException ignored) {}
                    }
                }

            } else if (line.startsWith("/online_users|")) {
                String users = line.substring("/online_users|".length());
                mainHandler.post(() -> callback.onOnlineUsers(users));

            } else if (line.startsWith("/deepseek_answer|")) {
                String answer = line.substring("/deepseek_answer|".length());
                mainHandler.post(() -> callback.onDeepSeekAnswer(answer));

            } else if (line.startsWith("/history|start")) {
                mainHandler.post(() -> callback.onHistoryStart());

            } else if (line.startsWith("/history|end")) {
                mainHandler.post(() -> callback.onHistoryEnd());

            } else if (line.startsWith("/history|")) {
                String msg = line.substring("/history|".length());
                mainHandler.post(() -> callback.onHistoryMessage(msg));

            } else if (line.startsWith("/version_check|")) {
                // 版本检查结果
                String result = line.substring("/version_check|".length());
                if (!"success".equals(result)) {
                    mainHandler.post(() -> callback.onError("版本不兼容"));
                }

            } else if (line.startsWith("/pong")) {
                // 心跳响应 - 忽略

            } else if (line.startsWith("/server_shutdown|")) {
                String msg = line.substring("/server_shutdown|".length());
                mainHandler.post(() -> {
                    callback.onError("服务器关闭: " + msg);
                    callback.onDisconnected("服务器关闭");
                });

            } else if (line.startsWith("[")) {
                // 普通聊天消息: [发送者] 消息内容
                int idx = line.indexOf("] ");
                if (idx > 0) {
                    String sender = line.substring(1, idx);
                    String msg = line.substring(idx + 2);
                    mainHandler.post(() -> callback.onMessageReceived(sender, msg));
                }

            } else {
                Log.w(TAG, "未知消息格式: " + line);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理消息异常", e);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) return;
        NotificationChannel serviceChannel = new NotificationChannel(
                SERVICE_CHANNEL_ID, "聊天连接", NotificationManager.IMPORTANCE_LOW);
        serviceChannel.setDescription("保持聊天连接和语音来电接收");
        NotificationChannel callChannel = new NotificationChannel(
                CALL_CHANNEL_ID, "语音来电", NotificationManager.IMPORTANCE_HIGH);
        callChannel.setDescription("显示私聊语音来电");
        callChannel.enableVibration(true);
        notificationManager.createNotificationChannel(serviceChannel);
        notificationManager.createNotificationChannel(callChannel);
    }

    private Notification buildServiceNotification() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1001, intent, pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, SERVICE_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("肥雪的群聊")
                .setContentText("聊天服务运行中，可接收语音来电")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private boolean promoteForeground(boolean withMicrophone) {
        try {
            Notification notification = buildServiceNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (withMicrophone) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, type);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "更新前台服务类型失败", e);
            return false;
        }
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    public boolean showIncomingP2PCall(String sender, String senderPassword) {
        if (callOverlay == null) return false;
        return callOverlay.show(sender, senderPassword, new P2PCallOverlayManager.Listener() {
            @Override
            public void onAccept(String peerName, String peerPassword) {
                acceptP2PVoice(peerPassword);
                openP2PCallActivity(peerName, peerPassword, "connecting");
            }

            @Override
            public void onReject(String peerName, String peerPassword) {
                rejectP2PVoice(peerPassword);
                cancelIncomingCallNotification();
            }
        });
    }

    public boolean hasIncomingCallOverlay() {
        return callOverlay != null && callOverlay.isShowing();
    }

    private void clearIncomingCallPrompt() {
        if (callOverlay != null) callOverlay.hide();
        cancelIncomingCallNotification();
    }

    private void showIncomingCallNotification(String sender, String senderPassword) {
        if (notificationManager == null) return;
        Intent content = new Intent(this, P2PChatActivity.class)
                .putExtra(EXTRA_PEER_NAME, sender)
                .putExtra(EXTRA_PEER_PASSWORD, senderPassword)
                .putExtra("my_nickname", nickname)
                .putExtra("voice_status", "request")
                .putExtra("voice_request", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1002,
                content, pendingIntentFlags());

        Intent accept = new Intent(this, IncomingCallActionReceiver.class)
                .setAction(IncomingCallActionReceiver.ACTION_ACCEPT)
                .putExtra(EXTRA_PEER_NAME, sender)
                .putExtra(EXTRA_PEER_PASSWORD, senderPassword);
        Intent reject = new Intent(this, IncomingCallActionReceiver.class)
                .setAction(IncomingCallActionReceiver.ACTION_REJECT)
                .putExtra(EXTRA_PEER_NAME, sender)
                .putExtra(EXTRA_PEER_PASSWORD, senderPassword);
        int requestCode = Math.abs(senderPassword.hashCode());
        PendingIntent acceptIntent = PendingIntent.getBroadcast(this, requestCode + 1,
                accept, pendingIntentFlags());
        PendingIntent rejectIntent = PendingIntent.getBroadcast(this, requestCode + 2,
                reject, pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CALL_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("有人要求语音通话")
                .setContentText(sender + " 请求与你通话")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_call,
                        "接听", acceptIntent).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        "拒绝", rejectIntent).build())
                .build();
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification);
    }

    private void handleIncomingCallAction(String action, String sender, String password) {
        cancelIncomingCallNotification();
        if (callOverlay != null) callOverlay.hide();
        if (password == null || password.isEmpty()) return;
        if (IncomingCallActionReceiver.ACTION_ACCEPT.equals(action)) {
            acceptP2PVoice(password);
            openP2PCallActivity(sender, password, "connecting");
        } else if (IncomingCallActionReceiver.ACTION_REJECT.equals(action)) {
            rejectP2PVoice(password);
        }
    }

    private void cancelIncomingCallNotification() {
        if (notificationManager != null) notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID);
    }

    private void openP2PCallActivity(String peerName, String peerPassword, String status) {
        Intent intent = new Intent(this, P2PChatActivity.class)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_PEER_PASSWORD, peerPassword)
                .putExtra("my_nickname", nickname)
                .putExtra("voice_status", status)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ========== 发送方法 ==========

    public boolean isSessionReady() {
        return sessionReady;
    }

    public boolean isLiveVoiceActive() {
        return liveVoiceAudio != null && liveVoiceAudio.isActive();
    }

    public boolean isP2PVoiceActive(String peer) {
        return isLiveVoiceActive() && "P2P".equals(liveVoiceMode)
                && (peer == null || peer.equals(liveVoicePeer));
    }

    public boolean setSpeakerphoneOn(boolean enabled) {
        return liveVoiceAudio != null && liveVoiceAudio.setSpeakerphone(enabled);
    }

    public boolean isSpeakerphoneOn() {
        return liveVoiceAudio != null && liveVoiceAudio.isSpeakerphoneOn();
    }

    public void joinGroupVoice() {
        postSendCommand("/live_group_join");
    }

    public void leaveGroupVoice() {
        postSendCommand("/live_group_leave");
        stopLiveVoice();
    }

    public void requestP2PVoice(String targetPassword) {
        postSendCommand("/live_p2p_request|" + targetPassword);
    }

    public void acceptP2PVoice(String requesterPassword) {
        postSendCommand("/live_p2p_accept|" + requesterPassword);
    }

    public void rejectP2PVoice(String requesterPassword) {
        postSendCommand("/live_p2p_reject|" + requesterPassword);
    }

    public void endP2PVoice() {
        postSendCommand("/live_p2p_end");
        stopLiveVoice();
    }

    private void startLiveVoice(String mode, String peer) {
        if (liveVoiceAudio == null || liveVoiceAudio.isActive()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postCallback(cb -> cb.onLiveVoiceError("请先授予麦克风权限"));
            return;
        }
        if (!promoteForeground(true)) {
            postCallback(cb -> cb.onLiveVoiceError("系统不允许在后台启动麦克风"));
            return;
        }
        liveVoiceMode = mode;
        liveVoicePeer = peer;
        boolean started = liveVoiceAudio.start(base64Pcm -> {
            if ("GROUP".equals(liveVoiceMode)) {
                postSendCommand("/live_group_audio|" + base64Pcm);
            } else if ("P2P".equals(liveVoiceMode)) {
                postSendCommand("/live_p2p_audio|" + base64Pcm);
            }
        });
        if (!started) {
            liveVoiceMode = null;
            liveVoicePeer = null;
            promoteForeground(false);
            if (callback != null) {
                mainHandler.post(() -> callback.onLiveVoiceError("无法打开麦克风或扬声器"));
            }
        }
    }

    private void stopLiveVoice() {
        liveVoiceMode = null;
        liveVoicePeer = null;
        if (liveVoiceAudio != null) liveVoiceAudio.stop();
        promoteForeground(false);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void sendTextMessage(String message) {
        postSendCommand(message);
    }

    public void sendVoice(String voiceId, String base64Audio) {
        postSendCommand("/voice|" + voiceId + "|" + base64Audio);
    }

    public void sendImageInfo(String imageId, int totalChunks, String fileName) {
        postSendCommand("/image_info|" + imageId + "|" + totalChunks + "|" + fileName);
    }

    public void sendImageChunk(String imageId, int chunkIndex, String chunkData) {
        postSendCommand("/image_chunk|" + imageId + "|" + chunkIndex + "|" + chunkData);
    }

    public void sendP2PVerify(String targetPassword) {
        postSendCommand("/p2p_verify|" + targetPassword);
    }

    public void sendP2PMessage(String targetPassword, String message) {
        postSendCommand("/p2p|" + targetPassword + "|" + message);
    }

    public void sendDeepSeek(String question) {
        postSendCommand("/deepseek|" + question);
    }

    public void sendPing() {
        postSendCommand("/ping");
    }

    /**
     * 在后台发送线程中执行网络写入
     */
    private void postSendCommand(String command) {
        Handler h = sendHandler;
        if (h != null) {
            h.post(() -> sendCommand(command));
        }
    }

    private void sendCommand(String command) {
        OutputStream out = writer;
        if (out == null) return;
        try {
            out.write((command + "\n").getBytes("UTF-8"));
            out.flush();
            Log.d(TAG, "发送: " + (command.length() > 100 ? command.substring(0, 100) + "..." : command));
        } catch (Exception e) {
            Log.e(TAG, "发送失败", e);
            if (running && callback != null) {
                mainHandler.post(() -> callback.onError("发送失败: " + e.getMessage()));
            }
        }
    }

    private void startHeartbeat() {
        Thread heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    if (running) sendPing();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void cleanup(int connId) {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "清理异常", e);
        }
        // 只有当前连接还是本连接时，才清空共享字段
        // 防止旧连接的finally块覆盖新连接已设置的writer/reader/socket
        if (connId == connectionId) {
            reader = null;
            writer = null;
            socket = null;
        }
    }

    @Override
    public void onDestroy() {
        disconnect("服务销毁");
        if (callOverlay != null) callOverlay.hide();
        cancelIncomingCallNotification();
        stopForeground(true);
        super.onDestroy();
    }
}
