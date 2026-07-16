package com.feixue.chat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.feixue.chat.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线用户列表适配器 - 与桌面客户端右侧在线用户面板一致
 * 点击用户按钮发起私聊
 */
public class OnlineUserAdapter extends RecyclerView.Adapter<OnlineUserAdapter.UserViewHolder> {

    private List<String> users = new ArrayList<>();
    private OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(String username);
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_online_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        String username = users.get(position);
        holder.nameText.setText(username);
        holder.chatBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onUserClick(username);
            }
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    public void updateUsers(List<String> newUsers) {
        users.clear();
        users.addAll(newUsers);
        notifyDataSetChanged();
    }

    public void updateUsers(String userListStr) {
        users.clear();
        if (userListStr != null && !userListStr.isEmpty()) {
            String[] parts = userListStr.split(",");
            for (String u : parts) {
                String trimmed = u.trim();
                if (!trimmed.isEmpty()) {
                    users.add(trimmed);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<String> getUsers() {
        return users;
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView nameText;
        Button chatBtn;

        UserViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.tvUserName);
            chatBtn = itemView.findViewById(R.id.btnUserChat);
        }
    }
}
