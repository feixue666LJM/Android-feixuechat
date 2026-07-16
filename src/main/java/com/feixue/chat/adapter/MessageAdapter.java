package com.feixue.chat.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.feixue.chat.R;
import com.feixue.chat.model.ChatMessage;
import com.feixue.chat.utils.AudioRecorderUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<ChatMessage> messages = new ArrayList<>();
    private OnImageClickListener imageClickListener;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public interface OnImageClickListener {
        void onImageClick(byte[] imageData, String fileName);
    }

    public void setOnImageClickListener(OnImageClickListener listener) {
        this.imageClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case ChatMessage.TYPE_TEXT_SEND:
                return new TextViewHolder(inflater.inflate(R.layout.item_message_text, parent, false));
            case ChatMessage.TYPE_TEXT_RECEIVE:
                return new TextMessageHolder(inflater.inflate(R.layout.item_message_text, parent, false));
            case ChatMessage.TYPE_VOICE_SEND:
            case ChatMessage.TYPE_VOICE_RECEIVE:
                return new VoiceViewHolder(inflater.inflate(R.layout.item_message_voice, parent, false));
            case ChatMessage.TYPE_IMAGE_SEND:
            case ChatMessage.TYPE_IMAGE_RECEIVE:
                return new ImageViewHolder(inflater.inflate(R.layout.item_message_image, parent, false));
            case ChatMessage.TYPE_SYSTEM:
                return new SystemViewHolder(inflater.inflate(R.layout.item_message_system, parent, false));
            case ChatMessage.TYPE_DEEPSEEK:
                return new DeepSeekViewHolder(inflater.inflate(R.layout.item_message_deepseek, parent, false));
            default:
                return new TextViewHolder(inflater.inflate(R.layout.item_message_text, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        String timeStr = timeFormat.format(new Date(msg.getTimestamp()));

        switch (holder.getItemViewType()) {
            case ChatMessage.TYPE_TEXT_SEND:
                bindTextSend((TextViewHolder) holder, msg, timeStr);
                break;
            case ChatMessage.TYPE_TEXT_RECEIVE:
                bindTextReceive((TextMessageHolder) holder, msg, timeStr);
                break;
            case ChatMessage.TYPE_VOICE_SEND:
            case ChatMessage.TYPE_VOICE_RECEIVE:
                bindVoice((VoiceViewHolder) holder, msg, timeStr);
                break;
            case ChatMessage.TYPE_IMAGE_SEND:
            case ChatMessage.TYPE_IMAGE_RECEIVE:
                bindImage((ImageViewHolder) holder, msg, timeStr);
                break;
            case ChatMessage.TYPE_SYSTEM:
                bindSystem((SystemViewHolder) holder, msg);
                break;
            case ChatMessage.TYPE_DEEPSEEK:
                bindDeepSeek((DeepSeekViewHolder) holder, msg, timeStr);
                break;
        }
    }

    private void bindTextSend(TextViewHolder holder, ChatMessage msg, String time) {
        holder.senderText.setVisibility(View.GONE);
        holder.timeText.setText(time);
        holder.contentText.setText(msg.getContent());
        holder.contentText.setBackgroundResource(R.drawable.bg_chat_bubble_send);
        holder.bubbleContainer.setGravity(android.view.Gravity.END);
    }

    private void bindTextReceive(TextMessageHolder holder, ChatMessage msg, String time) {
        holder.senderText.setVisibility(View.VISIBLE);
        holder.senderText.setText(msg.getSender());
        holder.timeText.setText(time);
        holder.contentText.setText(msg.getContent());
        holder.contentText.setBackgroundResource(R.drawable.bg_chat_bubble_receive);
        holder.bubbleContainer.setGravity(android.view.Gravity.START);
    }

    private void bindVoice(VoiceViewHolder holder, ChatMessage msg, String time) {
        holder.timeText.setText(time);
        holder.senderText.setVisibility(msg.getType() == ChatMessage.TYPE_VOICE_RECEIVE ? View.VISIBLE : View.GONE);
        if (msg.getType() == ChatMessage.TYPE_VOICE_RECEIVE) {
            holder.senderText.setText(msg.getSender());
        }

        // 语音内容统一格式: "语音|voiceId|base64Audio"
        // 发送的消息也使用此格式，便于回放
        holder.playBtn.setOnClickListener(v -> {
            String content = msg.getContent();
            String base64Audio = extractBase64FromVoiceContent(content);
            if (base64Audio != null && !base64Audio.isEmpty()) {
                // 播放按钮闪烁反馈
                v.setAlpha(0.5f);
                v.postDelayed(() -> v.setAlpha(1.0f), 200);
                AudioRecorderUtil.playVoice(v.getContext(), base64Audio, () -> {
                    Log.d("VoicePlay", "语音播放完成");
                });
            }
        });
    }

    /**
     * 从语音消息内容中提取Base64音频数据
     * 格式支持:
     *   "语音|voiceId|base64Audio" - 标准格式
     *   "base64Audio" - 纯Base64格式（兼容）
     */
    private String extractBase64FromVoiceContent(String content) {
        if (content == null || content.isEmpty()) return null;
        if (content.startsWith("语音|")) {
            // 格式: 语音|voiceId|base64
            int lastBar = content.lastIndexOf('|');
            if (lastBar > 0 && lastBar < content.length() - 1) {
                return content.substring(lastBar + 1);
            }
            return null;
        } else {
            // 可能是纯Base64
            return content;
        }
    }

    private void bindImage(ImageViewHolder holder, ChatMessage msg, String time) {
        holder.timeText.setText(time);
        if (msg.getType() == ChatMessage.TYPE_IMAGE_RECEIVE) {
            holder.senderText.setVisibility(View.VISIBLE);
            holder.senderText.setText(msg.getSender());
        } else {
            holder.senderText.setVisibility(View.GONE);
        }

        // 预览缩略图
        if (msg.getImageData() != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(msg.getImageData(), 0, msg.getImageData().length);
            if (bitmap != null) {
                // 缩放预览
                int maxW = 200;
                int maxH = 200;
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                float scale = Math.min((float) maxW / w, (float) maxH / h);
                if (scale < 1) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, (int) (w * scale), (int) (h * scale), true);
                }
                holder.thumbImage.setImageBitmap(bitmap);
            }
        }

        holder.viewBtn.setOnClickListener(v -> {
            if (imageClickListener != null && msg.getImageData() != null) {
                imageClickListener.onImageClick(msg.getImageData(), msg.getFileName());
            }
        });
    }

    private void bindSystem(SystemViewHolder holder, ChatMessage msg) {
        holder.contentText.setText(msg.getContent());
    }

    private void bindDeepSeek(DeepSeekViewHolder holder, ChatMessage msg, String time) {
        holder.timeText.setText(time);
        holder.contentText.setText(msg.getContent());
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    public void addMessageAtStart(ChatMessage msg) {
        messages.add(0, msg);
        notifyItemInserted(0);
    }

    public void clear() {
        int size = messages.size();
        messages.clear();
        notifyItemRangeRemoved(0, size);
    }

    public ChatMessage getMessage(int position) {
        return messages.get(position);
    }

    // ============ ViewHolders ============

    static class TextViewHolder extends RecyclerView.ViewHolder {
        TextView senderText;
        TextView timeText;
        TextView contentText;
        LinearLayout bubbleContainer;

        TextViewHolder(View itemView) {
            super(itemView);
            senderText = itemView.findViewById(R.id.tvMessageSender);
            timeText = itemView.findViewById(R.id.tvMessageTime);
            contentText = itemView.findViewById(R.id.tvMessageContent);
            bubbleContainer = itemView.findViewById(R.id.llBubbleContainer);
        }
    }

    static class TextMessageHolder extends TextViewHolder {
        TextMessageHolder(View itemView) {
            super(itemView);
        }
    }

    static class VoiceViewHolder extends RecyclerView.ViewHolder {
        TextView timeText;
        TextView senderText;
        ImageButton playBtn;

        VoiceViewHolder(View itemView) {
            super(itemView);
            timeText = itemView.findViewById(R.id.tvMessageTime);
            senderText = itemView.findViewById(R.id.tvMessageSender);
            playBtn = itemView.findViewById(R.id.btnPlayVoice);
        }
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        TextView timeText;
        TextView senderText;
        ImageView thumbImage;
        android.widget.Button viewBtn;

        ImageViewHolder(View itemView) {
            super(itemView);
            timeText = itemView.findViewById(R.id.tvMessageTime);
            senderText = itemView.findViewById(R.id.tvMessageSender);
            thumbImage = itemView.findViewById(R.id.ivThumbnail);
            viewBtn = itemView.findViewById(R.id.btnImageView);
        }
    }

    static class SystemViewHolder extends RecyclerView.ViewHolder {
        TextView contentText;

        SystemViewHolder(View itemView) {
            super(itemView);
            contentText = itemView.findViewById(R.id.tvSystemContent);
        }
    }

    static class DeepSeekViewHolder extends RecyclerView.ViewHolder {
        TextView timeText;
        TextView contentText;

        DeepSeekViewHolder(View itemView) {
            super(itemView);
            timeText = itemView.findViewById(R.id.tvMessageTime);
            contentText = itemView.findViewById(R.id.tvDeepSeekContent);
        }
    }
}
