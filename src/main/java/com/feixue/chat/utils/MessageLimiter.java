package com.feixue.chat.utils;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * 消息频率限制 - 与 src/namelog.java 功能一致
 *
 * 限制规则：
 * - 每分钟最多 5 条消息
 * - 30分钟最多 140 条消息
 * - 超过 140 条/30分钟 -> 禁言30分钟
 * - 禁止重复发送相同消息（最多2次）
 * - 消息最大 600 字节
 */
public class MessageLimiter {

    private static final int MAX_MESSAGES_PER_MINUTE = 5;
    private static final int MAX_MESSAGES_PER_30_MIN = 140;
    private static final long ONE_MINUTE_MS = 60 * 1000;
    private static final long THIRTY_MINUTES_MS = 30 * 60 * 1000;
    private static final long MUTE_DURATION_MS = 30 * 60 * 1000;
    private static final int MAX_BYTE_LENGTH = 600;

    private final Queue<Long> messageTimestamps = new LinkedList<>();
    private final Map<String, Integer> messageRepeatCount = new HashMap<>();
    private long muteUntil = 0;

    /**
     * 检查是否可以发送消息
     * @param content 消息内容
     * @return null 表示允许发送，非 null 为拒绝原因
     */
    public String checkSend(String content) {
        // 检查是否被禁言
        if (isMuted()) {
            long remaining = (muteUntil - System.currentTimeMillis()) / 1000;
            return "你已被禁言，剩余 " + remaining + " 秒";
        }

        // 清除过期记录
        cleanup();

        // 检查消息长度 (UTF-8字节)
        try {
            if (content.getBytes("UTF-8").length > MAX_BYTE_LENGTH) {
                return "消息过长，请控制在 " + MAX_BYTE_LENGTH + " 字节内";
            }
        } catch (Exception e) {
            return "编码错误";
        }

        // 检查重复消息
        String repeatKey = content;
        int count = messageRepeatCount.getOrDefault(repeatKey, 0);
        if (count >= 2) {
            return "请勿重复发送相同消息";
        }

        // 检查每分钟限制
        long now = System.currentTimeMillis();
        long oneMinuteAgo = now - ONE_MINUTE_MS;
        int countLastMinute = 0;
        for (long t : messageTimestamps) {
            if (t > oneMinuteAgo) countLastMinute++;
        }
        if (countLastMinute >= MAX_MESSAGES_PER_MINUTE) {
            return "发送过于频繁，请稍后再试";
        }

        // 检查30分钟限制
        int countLast30Min = messageTimestamps.size();
        if (countLast30Min >= MAX_MESSAGES_PER_30_MIN) {
            muteUntil = now + MUTE_DURATION_MS;
            return "发送频率过高，禁言30分钟";
        }

        return null; // 允许发送
    }

    /**
     * 记录已发送的消息
     */
    public void recordMessage(String content) {
        messageTimestamps.add(System.currentTimeMillis());
        String repeatKey = content;
        messageRepeatCount.put(repeatKey, messageRepeatCount.getOrDefault(repeatKey, 0) + 1);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        while (!messageTimestamps.isEmpty() && messageTimestamps.peek() < now - THIRTY_MINUTES_MS) {
            messageTimestamps.poll();
        }
    }

    public boolean isMuted() {
        return System.currentTimeMillis() < muteUntil;
    }

    public long getMuteRemainingSeconds() {
        if (!isMuted()) return 0;
        return (muteUntil - System.currentTimeMillis()) / 1000;
    }
}
