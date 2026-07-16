package com.feixue.chat;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.feixue.chat.utils.ServerAddressCodec;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 登录界面 - 与 src/yemian/twoyemian.java 功能一致
 *
 * 支持两种账号输入方式：
 * 1. 从 Spinner 选择预设账号，自动填充账号输入框
 * 2. 手动在 EditText 中输入任意账号
 *
 * 账号-群组映射（与 Windows 客户端保持一致）：
 *   feixuechat  -> group_feixue
 *   ash         -> group_ash
 *   antiash     -> group_antiash
 *   binglin     -> group_binglin
 *   feixuehome  -> group_feixuehome
 *   toney       -> group_toney
 *   公共频道    -> group_public
 *   未匹配账号  -> group_{账号名}
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "login_prefs";
    private static final int LOGIN_TIMEOUT = 8000;
    private static final String PUBLIC_CHANNEL_GROUP = "group_public";

    // 账号和群组映射（与 Windows src/yemian/twoyemian.java 保持一致）
    private static final Map<String, String> ACCOUNT_GROUPS = new HashMap<>();

    static {
        ACCOUNT_GROUPS.put("feixuechat", "group_feixue");
        ACCOUNT_GROUPS.put("ash", "group_ash");
        ACCOUNT_GROUPS.put("antiash", "group_antiash");
        ACCOUNT_GROUPS.put("binglin", "group_binglin");
        ACCOUNT_GROUPS.put("feixuehome", "group_feixuehome");
        ACCOUNT_GROUPS.put("toney", "group_toney");
    }

    // 频道/群组选择列表（带友好名称）
    private static final String[][] CHANNEL_OPTIONS = {
        {"feixuechat",  "feixue频道"},
        {"ash",         "ash频道"},
        {"antiash",     "antiash频道"},
        {"binglin",     "binglin频道"},
        {"feixuehome",  "feixuehome频道"},
        {"toney",       "toney频道"},
    };

    private Spinner spinnerChannel;
    private EditText etServerAddress, etLoginPort, etChatPort, etAccount, etPassword;
    private CheckBox cbPublicChannel;
    private Button btnLogin;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedSettings();
        setupListeners();
        new Handler(Looper.getMainLooper()).postDelayed(this::requestBackgroundCallPermissions, 700);
    }

    private void initViews() {
        etLoginPort = findViewById(R.id.etLoginPort);
        etChatPort = findViewById(R.id.etChatPort);
        etServerAddress = findViewById(R.id.etServerAddress);
        etAccount = findViewById(R.id.etAccount);
        spinnerChannel = findViewById(R.id.spinnerChannel);
        etPassword = findViewById(R.id.etPassword);
        cbPublicChannel = findViewById(R.id.cbPublicChannel);
        btnLogin = findViewById(R.id.btnLogin);
        tvStatus = findViewById(R.id.tvStatus);

        // 设置频道选择下拉框（显示友好名称）
        String[] displayNames = new String[CHANNEL_OPTIONS.length];
        for (int i = 0; i < CHANNEL_OPTIONS.length; i++) {
            displayNames[i] = CHANNEL_OPTIONS[i][1];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerChannel.setAdapter(adapter);

        // 默认值
        etServerAddress.setText("db.eb.hg.eh");
        etLoginPort.setText("22233");
        etChatPort.setText("22233");
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedAddress = prefs.getString("server_address", "");
        if (!savedAddress.isEmpty()) {
            etServerAddress.setText(ServerAddressCodec.displayForm(savedAddress));
        }
        String savedAccount = prefs.getString("account", "");
        if (!savedAccount.isEmpty()) {
            etAccount.setText(savedAccount);
            // 尝试匹配预设频道
            for (int i = 0; i < CHANNEL_OPTIONS.length; i++) {
                if (CHANNEL_OPTIONS[i][0].equals(savedAccount)) {
                    spinnerChannel.setSelection(i);
                    break;
                }
            }
        }
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("server_address", ServerAddressCodec.displayForm(etServerAddress.getText().toString()));
        editor.putString("account", etAccount.getText().toString().trim());
        editor.apply();
    }

    /**
     * 根据账号名获取对应的群组名
     */
    private String getGroupForAccount(String account) {
        String group = ACCOUNT_GROUPS.get(account);
        return group != null ? group : "group_" + account;
    }

    /**
     * 获取频道Spinner当前选中的账号名
     */
    private String getSelectedChannelAccount() {
        int pos = spinnerChannel.getSelectedItemPosition();
        if (pos >= 0 && pos < CHANNEL_OPTIONS.length) {
            return CHANNEL_OPTIONS[pos][0];
        }
        return "";
    }

    private void setupListeners() {
        // 频道选择 -> 自动填充账号输入框
        spinnerChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                // 仅在账号为空或匹配预设时自动填充
                String currentText = etAccount.getText().toString().trim();
                String selectedAccount = CHANNEL_OPTIONS[position][0];
                // 如果当前账号为空，或者当前账号是某个预设账号，则自动填充
                if (currentText.isEmpty()) {
                    etAccount.setText(selectedAccount);
                } else {
                    for (String[] opt : CHANNEL_OPTIONS) {
                        if (opt[0].equals(currentText)) {
                            etAccount.setText(selectedAccount);
                            break;
                        }
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 公共频道复选框 - 勾选时隐藏密码
        cbPublicChannel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etPassword.setVisibility(isChecked ? android.view.View.GONE : android.view.View.VISIBLE);
            findViewById(R.id.tvPasswordLabel).setVisibility(isChecked ? android.view.View.GONE : android.view.View.VISIBLE);
            spinnerChannel.setEnabled(!isChecked);
        });

        // 登录按钮
        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void requestBackgroundCallPermissions() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 3001);
            return;
        }
        requestOverlayPermissionIfNeeded();
    }

    private void requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("开启悬浮来电窗")
                    .setMessage("允许肥雪的群聊显示悬浮来电窗口，应用退到后台时也能接听或拒绝语音通话。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 3001) {
            requestOverlayPermissionIfNeeded();
        }
    }

    /**
     * 尝试登录 - 连接服务器进行账号验证
     */
    private void attemptLogin() {
        final String serverIp;
        try {
            serverIp = ServerAddressCodec.decode(etServerAddress.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        String loginPortStr = etLoginPort.getText().toString().trim();
        String account = etAccount.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        boolean isPublic = cbPublicChannel.isChecked();

        // 验证输入
        if (loginPortStr.isEmpty()) {
            Toast.makeText(this, "请输入登录端口", Toast.LENGTH_SHORT).show();
            return;
        }
        if (account.isEmpty()) {
            Toast.makeText(this, "请输入账号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isPublic && password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }

        int loginPort;
        try {
            loginPort = Integer.parseInt(loginPortStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        // 确定群组名
        final String groupName = isPublic ? PUBLIC_CHANNEL_GROUP : getGroupForAccount(account);

        final String finalAccount = account;
        final String finalPassword = isPublic ? "" : password;

        btnLogin.setEnabled(false);
        tvStatus.setText("正在连接服务器...");
        tvStatus.setVisibility(android.view.View.VISIBLE);

        new Thread(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(serverIp, loginPort), LOGIN_TIMEOUT);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                OutputStream writer = socket.getOutputStream();

                // 发送登录命令
                String loginCmd;
                if (isPublic) {
                    loginCmd = "/login_public|" + finalAccount;
                } else {
                    loginCmd = "/login|" + finalAccount + "|" + finalPassword;
                }
                writer.write((loginCmd + "\n").getBytes("UTF-8"));
                writer.flush();

                // 读取响应
                String response = reader.readLine();
                final boolean success = response != null && response.startsWith("/login_result|success");

                reader.close();
                writer.close();
                socket.close();

                if (success) {
                    // 获取聊天端口
                    String chatPortStr = etChatPort.getText().toString().trim();
                    int chatPortTmp = 22233;
                    try {
                        chatPortTmp = Integer.parseInt(chatPortStr);
                        if (chatPortTmp < 1 || chatPortTmp > 65535) throw new NumberFormatException();
                    } catch (NumberFormatException ignored) {
                        chatPortTmp = 22233;
                    }
                    final int chatPort = chatPortTmp;

                    // 更新状态提示
                    runOnUiThread(() -> tvStatus.setText("正在连接聊天服务器..."));

                    // 测试连接聊天端口
                    Socket chatSocket = null;
                    try {
                        chatSocket = new Socket();
                        chatSocket.connect(new InetSocketAddress(serverIp, chatPort), LOGIN_TIMEOUT);
                        chatSocket.close();

                        // 聊天端口连接成功，进入聊天界面
                        runOnUiThread(() -> {
                            saveSettings();

                            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                            intent.putExtra("server_ip", serverIp);
                            intent.putExtra("chat_port", chatPort);
                            intent.putExtra("nickname", finalAccount);
                            intent.putExtra("group_name", groupName);
                            intent.putExtra("account", finalAccount);
                            startActivity(intent);
                            finish();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            tvStatus.setText("聊天服务器连接失败");
                            Toast.makeText(MainActivity.this, "聊天服务器不可达: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        tvStatus.setText("登录失败");
                        Toast.makeText(MainActivity.this, "账号或密码错误", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("连接失败: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        tvStatus.setVisibility(android.view.View.GONE);
                    }, 3000);
                });
            }
        }).start();
    }
}
