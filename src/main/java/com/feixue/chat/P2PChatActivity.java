package com.feixue.chat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.feixue.chat.adapter.MessageAdapter;
import com.feixue.chat.model.ChatMessage;

/**
 * 点对点私聊界面 - 与桌面客户端P2P私聊窗口一致
 *
 * 协议：/p2p|目标密码|消息内容
 * 验证：/p2p_verify|目标密码
 */
public class P2PChatActivity extends AppCompatActivity {

    private RecyclerView rvMessages;
    private EditText etInput;
    private Button btnSend;
    private Button btnRequestVoice, btnAcceptVoice, btnRejectVoice, btnEndVoice;
    private Button btnSpeakerphone;
    private android.widget.TextView tvVoiceStatus;
    private MessageAdapter messageAdapter;
    private ChatService chatService;
    private boolean serviceBound = false;

    private String peerName;
    private String peerPassword;
    private String myNickname;
    private String currentPeerPassword; // 当前正在聊天的对方密码
    private boolean incomingVoiceRequest;
    private boolean outgoingVoiceRequest;
    private boolean voiceActive;
    private boolean voiceConnecting;
    private final Handler voiceStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable voiceStatePoll = new Runnable() {
        @Override
        public void run() {
            syncVoiceState();
            voiceStateHandler.postDelayed(this, 500);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ChatService.LocalBinder binder = (ChatService.LocalBinder) service;
            chatService = binder.getService();
            syncVoiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            chatService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p_chat);

        initViews();
        setupAdapter();
        bindService();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask模式下复用已有Activity时更新界面
        handleIntent(intent);
    }

    /**
     * 处理Intent中的P2P聊天参数
     */
    private void handleIntent(Intent intent) {
        String newPeerName = intent.getStringExtra("peer_name");
        String newPeerPassword = intent.getStringExtra("peer_password");
        String newMyNickname = intent.getStringExtra("my_nickname");
        String initialMsg = intent.getStringExtra("initial_msg");
        boolean isIncoming = intent.getBooleanExtra("is_incoming", false);
        String voiceStatus = intent.getStringExtra("voice_status");
        boolean voiceRequest = intent.getBooleanExtra("voice_request", false);

        // 切换聊天对象时，清空消息列表并更新信息
        if (newPeerPassword != null && !newPeerPassword.equals(currentPeerPassword)) {
            currentPeerPassword = newPeerPassword;
            peerName = newPeerName;
            peerPassword = newPeerPassword;
            myNickname = newMyNickname;

            setTitle("私聊 - " + (peerName != null ? peerName : ""));
            messageAdapter.clear();
        }

        // 显示初始消息（来消息时）
        if (initialMsg != null && !initialMsg.isEmpty() && isIncoming) {
            ChatMessage msg = new ChatMessage(ChatMessage.TYPE_TEXT_RECEIVE, peerName, initialMsg);
            messageAdapter.addMessage(msg);
        }
        if (voiceRequest || "request".equals(voiceStatus)) {
            voiceActive = false;
            voiceConnecting = false;
            incomingVoiceRequest = true;
            outgoingVoiceRequest = false;
            tvVoiceStatus.setText("语音：对方申请通话");
        } else if ("request_sent".equals(voiceStatus)) {
            voiceActive = false;
            voiceConnecting = false;
            outgoingVoiceRequest = true;
            tvVoiceStatus.setText("语音：等待对方同意");
        } else if ("started".equals(voiceStatus)) {
            incomingVoiceRequest = false;
            outgoingVoiceRequest = false;
            voiceConnecting = false;
            voiceActive = true;
            tvVoiceStatus.setText("语音：通话中");
        } else if ("connecting".equals(voiceStatus)) {
            incomingVoiceRequest = false;
            outgoingVoiceRequest = false;
            voiceActive = false;
            voiceConnecting = true;
            tvVoiceStatus.setText("语音：正在连接");
        } else if ("ended".equals(voiceStatus)) {
            voiceActive = false;
            voiceConnecting = false;
            incomingVoiceRequest = false;
            outgoingVoiceRequest = false;
            tvVoiceStatus.setText("语音：已结束");
        } else if (voiceStatus != null && voiceStatus.startsWith("rejected:")) {
            voiceActive = false;
            voiceConnecting = false;
            incomingVoiceRequest = false;
            outgoingVoiceRequest = false;
            tvVoiceStatus.setText("语音：" + voiceStatus.substring("rejected:".length()));
        }
        refreshVoiceButtons();
    }

    private void initViews() {
        rvMessages = findViewById(R.id.rvMessagesP2P);
        etInput = findViewById(R.id.etInputP2P);
        btnSend = findViewById(R.id.btnSendP2P);
        btnRequestVoice = findViewById(R.id.btnRequestVoice);
        btnAcceptVoice = findViewById(R.id.btnAcceptVoice);
        btnRejectVoice = findViewById(R.id.btnRejectVoice);
        btnEndVoice = findViewById(R.id.btnEndVoice);
        btnSpeakerphone = findViewById(R.id.btnSpeakerphone);
        tvVoiceStatus = findViewById(R.id.tvP2PVoiceStatus);

        btnSend.setOnClickListener(v -> sendP2PMessage());
        btnRequestVoice.setOnClickListener(v -> requestVoice());
        btnAcceptVoice.setOnClickListener(v -> acceptVoice());
        btnRejectVoice.setOnClickListener(v -> rejectVoice());
        btnEndVoice.setOnClickListener(v -> endVoice());
        btnSpeakerphone.setOnClickListener(v -> toggleSpeakerphone());
        refreshVoiceButtons();
    }

    private void setupAdapter() {
        messageAdapter = new MessageAdapter();
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(messageAdapter);
    }

    private void sendP2PMessage() {
        String content = etInput.getText().toString().trim();
        if (content.isEmpty()) return;
        if (chatService == null) {
            Toast.makeText(this, "服务未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        chatService.sendP2PMessage(peerPassword, content);

        ChatMessage msg = new ChatMessage(ChatMessage.TYPE_TEXT_SEND, "我", content);
        messageAdapter.addMessage(msg);
        etInput.setText("");
        rvMessages.post(() -> rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1));
    }

    private void requestVoice() {
        if (chatService == null || peerPassword == null) {
            Toast.makeText(this, "私聊尚未连接", Toast.LENGTH_SHORT).show();
            return;
        }
        outgoingVoiceRequest = true;
        tvVoiceStatus.setText("语音：等待对方同意");
        refreshVoiceButtons();
        chatService.requestP2PVoice(peerPassword);
    }

    private void acceptVoice() {
        if (chatService == null || peerPassword == null || !incomingVoiceRequest) return;
        incomingVoiceRequest = false;
        voiceConnecting = true;
        tvVoiceStatus.setText("语音：正在连接");
        refreshVoiceButtons();
        chatService.acceptP2PVoice(peerPassword);
    }

    private void rejectVoice() {
        if (chatService == null || peerPassword == null || !incomingVoiceRequest) return;
        incomingVoiceRequest = false;
        voiceConnecting = false;
        tvVoiceStatus.setText("语音：已拒绝");
        refreshVoiceButtons();
        chatService.rejectP2PVoice(peerPassword);
    }

    private void endVoice() {
        if (chatService == null || (!voiceActive && !voiceConnecting)) return;
        voiceActive = false;
        voiceConnecting = false;
        tvVoiceStatus.setText("语音：已结束");
        refreshVoiceButtons();
        chatService.endP2PVoice();
    }

    private void toggleSpeakerphone() {
        if (chatService == null || !voiceActive) return;
        boolean enable = !chatService.isSpeakerphoneOn();
        if (chatService.setSpeakerphoneOn(enable)) {
            tvVoiceStatus.setText(enable ? "语音：通话中（免提）" : "语音：通话中");
            refreshVoiceButtons();
        } else {
            Toast.makeText(this, "当前设备无法切换免提", Toast.LENGTH_SHORT).show();
        }
    }

    private void syncVoiceState() {
        if (chatService == null) return;
        boolean serviceActive = chatService.isP2PVoiceActive(peerName);
        if (serviceActive && !voiceActive) {
            voiceActive = true;
            voiceConnecting = false;
            incomingVoiceRequest = false;
            outgoingVoiceRequest = false;
            tvVoiceStatus.setText(chatService.isSpeakerphoneOn()
                    ? "语音：通话中（免提）" : "语音：通话中");
        } else if (!serviceActive && voiceActive) {
            voiceActive = false;
            voiceConnecting = false;
            tvVoiceStatus.setText("语音：已结束");
        }
        refreshVoiceButtons();
    }

    private void refreshVoiceButtons() {
        if (btnRequestVoice == null) return;
        boolean busy = voiceActive || voiceConnecting || incomingVoiceRequest || outgoingVoiceRequest;
        btnRequestVoice.setEnabled(!busy && peerPassword != null);
        btnAcceptVoice.setEnabled(incomingVoiceRequest && !voiceActive && !voiceConnecting);
        btnRejectVoice.setEnabled(incomingVoiceRequest && !voiceActive && !voiceConnecting);
        btnEndVoice.setEnabled(voiceActive || voiceConnecting);
        btnSpeakerphone.setEnabled(voiceActive);
        btnSpeakerphone.setText(chatService != null && chatService.isSpeakerphoneOn() ? "听筒" : "免提");
    }

    private void bindService() {
        Intent intent = new Intent(this, ChatService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        serviceBound = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        voiceStateHandler.removeCallbacks(voiceStatePoll);
        voiceStateHandler.post(voiceStatePoll);
    }

    @Override
    protected void onStop() {
        voiceStateHandler.removeCallbacks(voiceStatePoll);
        super.onStop();
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
