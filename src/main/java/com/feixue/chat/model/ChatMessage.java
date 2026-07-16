package com.feixue.chat.model;

/**
 * 聊天消息模型 - 与桌面客户端协议保持一致
 */
public class ChatMessage {
    public static final int TYPE_TEXT_SEND = 0;
    public static final int TYPE_TEXT_RECEIVE = 1;
    public static final int TYPE_VOICE_SEND = 2;
    public static final int TYPE_VOICE_RECEIVE = 3;
    public static final int TYPE_IMAGE_SEND = 4;
    public static final int TYPE_IMAGE_RECEIVE = 5;
    public static final int TYPE_SYSTEM = 6;
    public static final int TYPE_DEEPSEEK = 7;

    private int type;
    private String sender;
    private String content;
    private String imageId;
    private int totalChunks;
    private byte[] imageData;
    private String fileName;
    private long timestamp;

    public ChatMessage(int type, String sender, String content) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public ChatMessage(int type, String sender, String content, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
    }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
