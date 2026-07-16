package com.feixue.chat.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Displays a small system-level incoming voice call window while the app is backgrounded. */
public final class P2PCallOverlayManager {
    public interface Listener {
        void onAccept(String peerName, String peerPassword);
        void onReject(String peerName, String peerPassword);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int density;
    private volatile View overlayView;
    private Listener listener;
    private String peerName;
    private String peerPassword;

    public P2PCallOverlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.density = Math.max(1, Math.round(this.context.getResources().getDisplayMetrics().density));
    }

    public boolean canShow() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public boolean show(String peerName, String peerPassword, Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final boolean[] result = new boolean[1];
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                result[0] = show(peerName, peerPassword, listener);
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result[0];
        }
        if (!canShow() || windowManager == null || peerPassword == null || peerPassword.isEmpty()) {
            return false;
        }
        hide();
        this.peerName = peerName == null ? "未知用户" : peerName;
        this.peerPassword = peerPassword;
        this.listener = listener;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(220, 220, 220));
        root.setBackground(background);

        TextView title = new TextView(context);
        title.setText("有人要求语音通话");
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView peer = new TextView(context);
        peer.setText(this.peerName);
        peer.setTextColor(Color.rgb(90, 90, 90));
        peer.setTextSize(14);
        peer.setGravity(Gravity.CENTER);
        root.addView(peer, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER);
        Button accept = new Button(context);
        accept.setText("接听");
        accept.setTextColor(Color.WHITE);
        accept.setBackgroundColor(Color.rgb(52, 168, 83));
        Button reject = new Button(context);
        reject.setText("拒绝");
        reject.setTextColor(Color.WHITE);
        reject.setBackgroundColor(Color.rgb(210, 70, 65));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        actionParams.setMargins(dp(4), dp(6), dp(4), 0);
        actions.addView(accept, actionParams);
        actions.addView(reject, actionParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(54)));

        accept.setOnClickListener(v -> {
            Listener current = this.listener;
            String name = this.peerName;
            String password = this.peerPassword;
            hide();
            if (current != null) current.onAccept(name, password);
        });
        reject.setOnClickListener(v -> {
            Listener current = this.listener;
            String name = this.peerName;
            String password = this.peerPassword;
            hide();
            if (current != null) current.onReject(name, password);
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(56);
        try {
            windowManager.addView(root, params);
            overlayView = root;
            return true;
        } catch (RuntimeException ignored) {
            overlayView = null;
            return false;
        }
    }

    public void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::hide);
            return;
        }
        View view = overlayView;
        overlayView = null;
        listener = null;
        peerName = null;
        peerPassword = null;
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException ignored) {
                // The system may already have removed the overlay during process shutdown.
            }
        }
    }

    public boolean isShowing() {
        return overlayView != null;
    }

    private int dp(int value) {
        return value * density;
    }
}
