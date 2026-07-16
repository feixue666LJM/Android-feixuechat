package com.feixue.chat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.feixue.chat.adapter.MessageAdapter;
import com.feixue.chat.adapter.OnlineUserAdapter;
import com.feixue.chat.model.ChatMessage;
import com.feixue.chat.utils.AudioRecorderUtil;
import com.feixue.chat.utils.ImageUtils;
import com.feixue.chat.utils.MessageLimiter;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 主聊天界面 - 与 src/ChatClient.java 功能一致
 *
 * 功能：
 * - 文字聊天（发送/接收）
 * - 语音消息（录制/播放）
 * - 图片传输（分块发送/接收）
 * - 在线用户列表（点击私聊）
 * - DeepSeek AI 问答
 * - 消息频率限制
 */
public class ChatActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_IMAGE = 1001;
    private static final int PERMISSION_RECORD_AUDIO = 2001;
    private static final int PERMISSION_STORAGE = 2002;

    // 网络服务
    private ChatService chatService;
    private boolean serviceBound = false;

    // UI
    private RecyclerView rvMessages, rvOnlineUsers;
    private EditText etInput;
    private Button btnSend, btnDeepSeek, btnConnect;
    private Button btnGroupVoice;
    private ImageButton btnImage;
    private ImageButton btnVoice;
    private TextView tvConnectionStatus, tvP2PPassword;
    private TextView tvGroupVoiceStatus;
    private FrameLayout voiceRecordPanel;

    // 适配器
    private MessageAdapter messageAdapter;
    private OnlineUserAdapter onlineUserAdapter;

    // 工具
    private MessageLimiter messageLimiter;
    private AudioRecorderUtil audioRecorder;

    // 状态
    private String serverIp;
    private int chatPort;
    private String nickname;
    private String groupName;
    private String account;
    private boolean isConnected = false;
    private boolean groupVoiceActive = false;

    // 图片接收状态
    private String currentImageId = null;
    private int currentImageTotalChunks = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ChatService.LocalBinder binder = (ChatService.LocalBinder) service;
            chatService = binder.getService();
            chatService.setNickname(nickname);
            chatService.setGroupName(groupName);
            chatService.setCallback(chatCallback);
            // 服务已经以前台模式启动时，避免绑定回调再次创建连接。
            if (!chatService.isRunning()) {
                chatService.connect(serverIp, chatPort);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            chatService = null;
            serviceBound = false;
        }
    };

    private final ChatService.ChatCallback chatCallback = new ChatService.ChatCallback() {
        @Override
        public void onConnected() {
            runOnUiThread(() -> {
                isConnected = true;
                tvConnectionStatus.setText("已连接");
                tvConnectionStatus.setTextColor(0xFF4CAF50);
                btnConnect.setText("断开");
                btnGroupVoice.setEnabled(true);
            });
        }

        @Override
        public void onDisconnected(String reason) {
            runOnUiThread(() -> {
                isConnected = false;
                tvConnectionStatus.setText("未连接");
                tvConnectionStatus.setTextColor(0xFFF44336);
                btnConnect.setText("连接");
                groupVoiceActive = false;
                tvGroupVoiceStatus.setText("频道语音：未加入");
                btnGroupVoice.setText("加入频道语音");
                btnGroupVoice.setEnabled(true);
            });
        }

        @Override
        public void onMessageReceived(String sender, String message) {
            runOnUiThread(() -> {
                ChatMessage msg = new ChatMessage(ChatMessage.TYPE_TEXT_RECEIVE, sender, message);
                messageAdapter.addMessage(msg);
                scrollToBottom();
            });
        }

        @Override
        public void onVoiceReceived(String sender, String voiceId, String base64Audio) {
            runOnUiThread(() -> {
                String displaySender = sender.isEmpty() ? "未知" : sender;
                ChatMessage msg = new ChatMessage(ChatMessage.TYPE_VOICE_RECEIVE, displaySender,
                        "语音|" + voiceId + "|" + base64Audio);
                messageAdapter.addMessage(msg);
                scrollToBottom();
            });
        }

        @Override
        public void onImageInfo(String imageId, int totalChunks, String fileName) {
            ImageUtils.startImageAssembly(imageId, totalChunks, fileName);
            currentImageId = imageId;
            currentImageTotalChunks = totalChunks;
        }

        @Override
        public void onImageChunk(String imageId, int chunkIndex, String chunkData) {
            byte[] data = Base64.decode(chunkData, Base64.NO_WRAP);
            boolean complete = ImageUtils.receiveImageChunk(imageId, chunkIndex, data);
            if (complete) {
                runOnUiThread(() -> {
                    byte[] imageBytes = ImageUtils.getAssembledImage(imageId);
                    String fileName = ImageUtils.getImageFileName(imageId);
                    ChatMessage msg = new ChatMessage(ChatMessage.TYPE_IMAGE_RECEIVE, "图片", fileName);
                    msg.setImageData(imageBytes);
                    msg.setFileName(fileName);
                    messageAdapter.addMessage(msg);
                    scrollToBottom();
                });
            }
        }

        @Override
        public void onP2PVerifyResult(boolean success) {
            // P2P验证在OnlineUserAdapter的点击事件中处理
        }

        @Override
        public void onP2PMessage(String sender, String senderPassword, String message) {
            runOnUiThread(() -> {
                Intent intent = new Intent(ChatActivity.this, P2PChatActivity.class);
                intent.putExtra("peer_name", sender);
                intent.putExtra("peer_password", senderPassword);
                intent.putExtra("my_nickname", nickname);
                intent.putExtra("initial_msg", message);
                intent.putExtra("is_incoming", true);
                startActivity(intent);
            });
        }

        @Override
        public void onP2PNotification(String sender, String senderPassword) {
            runOnUiThread(() -> {
                Toast.makeText(ChatActivity.this, sender + " 向你发起了私聊", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onGroupVoiceJoined(String group, int members) {
            runOnUiThread(() -> {
                groupVoiceActive = true;
                tvGroupVoiceStatus.setText("频道语音：" + members + " 人");
                btnGroupVoice.setText("退出频道语音");
                btnGroupVoice.setEnabled(true);
            });
        }

        @Override
        public void onGroupVoiceMembers(int members) {
            runOnUiThread(() -> {
                if (groupVoiceActive) tvGroupVoiceStatus.setText("频道语音：" + members + " 人");
            });
        }

        @Override
        public void onGroupVoiceLeft() {
            runOnUiThread(() -> {
                groupVoiceActive = false;
                tvGroupVoiceStatus.setText("频道语音：未加入");
                btnGroupVoice.setText("加入频道语音");
                btnGroupVoice.setEnabled(true);
            });
        }

        @Override
        public void onGroupVoiceDisabled(String group) {
            runOnUiThread(() -> {
                groupVoiceActive = false;
                tvGroupVoiceStatus.setText("频道语音：已关闭");
                btnGroupVoice.setText("频道语音已关闭");
                btnGroupVoice.setEnabled(false);
            });
        }

        @Override
        public void onP2PVoiceRequest(String sender, String senderPassword) {
            runOnUiThread(() -> {
                if (chatService == null || !chatService.hasIncomingCallOverlay()) {
                    openP2PVoiceActivity(sender, senderPassword, "request");
                }
            });
        }

        @Override
        public void onP2PVoiceRequestSent(String target) {
            runOnUiThread(() -> openP2PVoiceActivity(target, null, "request_sent"));
        }

        @Override
        public void onP2PVoiceStarted(String peer, String peerPassword) {
            runOnUiThread(() -> openP2PVoiceActivity(peer, peerPassword, "started"));
        }

        @Override
        public void onP2PVoiceEnded(String peer) {
            runOnUiThread(() -> openP2PVoiceActivity(peer, null, "ended"));
        }

        @Override
        public void onP2PVoiceRejected(String peer, String reason) {
            runOnUiThread(() -> openP2PVoiceActivity(peer, null, "rejected:" + reason));
        }

        @Override
        public void onLiveVoiceError(String error) {
            runOnUiThread(() -> {
                btnGroupVoice.setEnabled(true);
                if (!groupVoiceActive) {
                    tvGroupVoiceStatus.setText("频道语音：未加入");
                    btnGroupVoice.setText("加入频道语音");
                }
                Toast.makeText(ChatActivity.this, error, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onOnlineUsers(String usersJson) {
            runOnUiThread(() -> {
                onlineUserAdapter.updateUsers(usersJson);
            });
        }

        @Override
        public void onDeepSeekAnswer(String answer) {
            runOnUiThread(() -> {
                ChatMessage msg = new ChatMessage(ChatMessage.TYPE_DEEPSEEK, "DeepSeek", answer);
                messageAdapter.addMessage(msg);
                scrollToBottom();
            });
        }

        @Override
        public void onHistoryStart() {
            runOnUiThread(() -> {
                messageAdapter.clear();
                ChatMessage sysMsg = new ChatMessage(ChatMessage.TYPE_SYSTEM, "系统", "--- 聊天记录 ---");
                messageAdapter.addMessage(sysMsg);
            });
        }

        @Override
        public void onHistoryMessage(String message) {
            runOnUiThread(() -> {
                int idx = message.indexOf("] ");
                if (idx > 0) {
                    String sender = message.substring(1, idx);
                    String content = message.substring(idx + 2);
                    ChatMessage msg = new ChatMessage(ChatMessage.TYPE_TEXT_RECEIVE, sender, content);
                    messageAdapter.addMessage(msg);
                }
            });
        }

        @Override
        public void onHistoryEnd() {
            runOnUiThread(() -> {
                ChatMessage sysMsg = new ChatMessage(ChatMessage.TYPE_SYSTEM, "系统", "--- 以上为历史记录 ---");
                messageAdapter.addMessage(sysMsg);
            });
        }

        @Override
        public void onP2PPassword(String password) {
            runOnUiThread(() -> {
                tvP2PPassword.setText("私聊密码: " + password);
            });
        }

        @Override
        public void onError(String error) {
            runOnUiThread(() -> Toast.makeText(ChatActivity.this, error, Toast.LENGTH_SHORT).show());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // 获取传参
        Intent intent = getIntent();
        serverIp = intent.getStringExtra("server_ip");
        chatPort = intent.getIntExtra("chat_port", 22233);
        nickname = intent.getStringExtra("nickname");
        groupName = intent.getStringExtra("group_name");
        account = intent.getStringExtra("account");

        // 初始化工具
        messageLimiter = new MessageLimiter();
        audioRecorder = new AudioRecorderUtil(this);
        ImageUtils.startCleanupThread();

        initViews();
        setupAdapters();
        setupListeners();
        bindService();
    }

    private void initViews() {
        rvMessages = findViewById(R.id.rvMessages);
        rvOnlineUsers = findViewById(R.id.rvOnlineUsers);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        btnDeepSeek = findViewById(R.id.btnDeepSeek);
        btnImage = findViewById(R.id.btnImage);
        btnVoice = findViewById(R.id.btnVoice);
        btnConnect = findViewById(R.id.btnConnect);
        btnGroupVoice = findViewById(R.id.btnGroupVoice);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvP2PPassword = findViewById(R.id.tvP2PPassword);
        tvGroupVoiceStatus = findViewById(R.id.tvGroupVoiceStatus);
        voiceRecordPanel = findViewById(R.id.voiceRecordPanel);

        // 设置标题
        setTitle("肥雪的群聊 - " + (nickname != null ? nickname : ""));
    }

    private void setupAdapters() {
        // 消息列表
        messageAdapter = new MessageAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(messageAdapter);

        // 图片点击查看
        messageAdapter.setOnImageClickListener((imageData, fileName) -> {
            Intent intent = new Intent(ChatActivity.this, ImageViewActivity.class);
            intent.putExtra("image_data", imageData);
            intent.putExtra("file_name", fileName);
            startActivity(intent);
        });

        // 在线用户
        onlineUserAdapter = new OnlineUserAdapter();
        rvOnlineUsers.setLayoutManager(new LinearLayoutManager(this));
        rvOnlineUsers.setAdapter(onlineUserAdapter);

        // 在线用户点击 -> 私聊
        onlineUserAdapter.setOnUserClickListener(username -> {
            if (chatService == null || !isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
                return;
            }

            // 获取对方私聊密码 - 使用AlertDialog输入密码
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("与 " + username + " 私聊");
            builder.setMessage("请输入对方的私聊密码");
            final EditText pwdInput = new EditText(this);
            pwdInput.setHint("对方私聊密码");
            builder.setView(pwdInput);
            builder.setPositiveButton("确定", (dialog, which) -> {
                String targetPwd = pwdInput.getText().toString().trim();
                if (targetPwd.isEmpty()) {
                    Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 发送P2P验证请求
                chatService.sendP2PVerify(targetPwd);

                // 启动私聊界面
                Intent p2pIntent = new Intent(ChatActivity.this, P2PChatActivity.class);
                p2pIntent.putExtra("peer_name", username);
                p2pIntent.putExtra("peer_password", targetPwd);
                p2pIntent.putExtra("my_nickname", nickname);
                startActivity(p2pIntent);
            });
            builder.setNegativeButton("取消", null);
            builder.show();
        });
    }

    /**
     * 输入框字节数实时显示（参照src桌面端）
     */
    private void setupListeners() {
        // 输入框字节计数
        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    int bytes = s.toString().getBytes("UTF-8").length;
                    boolean overLimit = bytes > 600;
                    btnSend.setText("发送(" + bytes + "/600)");
                    btnSend.setTextColor(overLimit ? 0xFFF44336 : 0xFFFFFFFF);
                    btnSend.setEnabled(!overLimit && bytes > 0);
                } catch (Exception ignored) {}
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 发送按钮
        btnSend.setOnClickListener(v -> sendTextMessage());

        // 连接/断开按钮
        btnConnect.setOnClickListener(v -> {
            if (isConnected && chatService != null) {
                chatService.disconnect("用户断开");
            } else if (chatService != null) {
                chatService.connect(serverIp, chatPort);
            }
        });

        btnGroupVoice.setOnClickListener(v -> toggleGroupVoice());

        // 语音按钮 - 按住录音，松开发送（参照src桌面端）
        btnVoice.setOnTouchListener((v, event) -> {
            if (chatService != null && chatService.isLiveVoiceActive()) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    Toast.makeText(this, "实时语音中不能录制语音消息", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            if (!checkAudioPermission()) {
                requestAudioPermission();
                return false;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // 开始录音
                    voiceRecordPanel.setVisibility(View.VISIBLE);
                    audioRecorder.startRecording();
                    btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    voiceRecordPanel.setVisibility(View.GONE);
                    btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    if (audioRecorder.isRecording()) {
                        String base64Audio = audioRecorder.stopRecording();
                        if (base64Audio != null && chatService != null && isConnected) {
                            String voiceId = UUID.randomUUID().toString().substring(0, 8);
                            chatService.sendVoice(voiceId, base64Audio);
                            // 存储语音数据到消息中，便于本机回放 (格式: 语音|voiceId|base64Audio)
                            ChatMessage msg = new ChatMessage(ChatMessage.TYPE_VOICE_SEND, "我",
                                    "语音|" + voiceId + "|" + base64Audio);
                            messageAdapter.addMessage(msg);
                            scrollToBottom();
                        }
                    }
                    return true;
            }
            return false;
        });

        // 发送图片按钮
        btnImage.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.READ_MEDIA_IMAGES}, PERMISSION_STORAGE);
                    return;
                }
            }
            openImagePicker();
        });

        // DeepSeek AI按钮
        btnDeepSeek.setOnClickListener(v -> {
            if (chatService == null || !isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("DeepSeek AI (LITE)");
            builder.setMessage("请输入问题（最多300字符）");
            final EditText questionInput = new EditText(this);
            questionInput.setMaxLines(5);
            builder.setView(questionInput);
            builder.setPositiveButton("发送", (dialog, which) -> {
                String question = questionInput.getText().toString().trim();
                if (!question.isEmpty()) {
                    chatService.sendDeepSeek(question);
                    ChatMessage msg = new ChatMessage(ChatMessage.TYPE_DEEPSEEK, "我 -> AI", question);
                    messageAdapter.addMessage(msg);
                    scrollToBottom();
                }
            });
            builder.setNegativeButton("取消", null);
            builder.show();
        });
    }

    /**
     * 发送文本消息 - 包含频率限制检查
     */
    private void sendTextMessage() {
        String content = etInput.getText().toString().trim();
        if (content.isEmpty()) return;
        if (chatService == null || !isConnected) {
            Toast.makeText(this, "未连接到服务器", Toast.LENGTH_SHORT).show();
            return;
        }

        // 频率限制检查
        String limitResult = messageLimiter.checkSend(content);
        if (limitResult != null) {
            Toast.makeText(this, limitResult, Toast.LENGTH_SHORT).show();
            return;
        }

        // 发送
        chatService.sendTextMessage(content);
        messageLimiter.recordMessage(content);

        // 显示自己发送的消息
        ChatMessage msg = new ChatMessage(ChatMessage.TYPE_TEXT_SEND, "我", content);
        messageAdapter.addMessage(msg);
        scrollToBottom();

        etInput.setText("");
    }

    private void toggleGroupVoice() {
        if (chatService == null || !isConnected) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!checkAudioPermission()) {
            requestAudioPermission();
            return;
        }
        if (groupVoiceActive) {
            chatService.leaveGroupVoice();
            btnGroupVoice.setEnabled(false);
            tvGroupVoiceStatus.setText("频道语音：正在退出");
        } else {
            chatService.joinGroupVoice();
            btnGroupVoice.setEnabled(false);
            tvGroupVoiceStatus.setText("频道语音：正在加入");
        }
    }

    private void openP2PVoiceActivity(String peer, String password, String status) {
        Intent intent = new Intent(ChatActivity.this, P2PChatActivity.class);
        intent.putExtra("peer_name", peer);
        if (password != null) intent.putExtra("peer_password", password);
        intent.putExtra("my_nickname", nickname);
        intent.putExtra("voice_status", status);
        if ("request".equals(status)) intent.putExtra("voice_request", true);
        startActivity(intent);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                sendImage(uri);
            }
        }
    }

    /**
     * 发送图片 - 分块传输（参照src/puh.java）
     */
    private void sendImage(Uri uri) {
        if (chatService == null || !isConnected) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 读取图片
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            inputStream.close();
            byte[] imageBytes = bos.toByteArray();

            // 检查大小
            if (imageBytes.length > 20 * 1024 * 1024) {
                Toast.makeText(this, "图片过大", Toast.LENGTH_SHORT).show();
                return;
            }

            // 获取文件名
            String fileName = "image.jpg";
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
                cursor.close();
            }

            // 分块发送
            String imageId = UUID.randomUUID().toString().substring(0, 8);
            java.util.List<byte[]> chunks = ImageUtils.chunkImage(imageBytes);
            int totalChunks = chunks.size();

            // 发送图片信息头
            chatService.sendImageInfo(imageId, totalChunks, fileName);

            // 发送每个块
            for (int i = 0; i < totalChunks; i++) {
                String chunkBase64 = Base64.encodeToString(chunks.get(i), Base64.NO_WRAP);
                chatService.sendImageChunk(imageId, i, chunkBase64);
            }

            // 显示已发送的图片消息
            ChatMessage msg = new ChatMessage(ChatMessage.TYPE_IMAGE_SEND, "我", "图片已发送");
            msg.setImageData(imageBytes);
            msg.setFileName(fileName);
            messageAdapter.addMessage(msg);
            scrollToBottom();

            ImageUtils.recordSend();
            Toast.makeText(this, "图片已发送", Toast.LENGTH_SHORT).show();

        } catch (FileNotFoundException e) {
            Toast.makeText(this, "文件未找到", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean checkAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_RECORD_AUDIO);
    }

    private void scrollToBottom() {
        rvMessages.post(() -> {
            if (messageAdapter.getItemCount() > 0) {
                rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            }
        });
    }

    private void bindService() {
        Intent intent = new Intent(this, ChatService.class);
        intent.putExtra(ChatService.EXTRA_SERVER_IP, serverIp);
        intent.putExtra(ChatService.EXTRA_SERVER_PORT, chatPort);
        intent.putExtra(ChatService.EXTRA_NICKNAME, nickname);
        intent.putExtra(ChatService.EXTRA_GROUP_NAME, groupName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            androidx.core.content.ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        serviceBound = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
