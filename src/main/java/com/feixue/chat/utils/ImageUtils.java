package com.feixue.chat.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片传输工具 - 与 src/puh.java 功能一致
 * 支持图片分块传输（每块50KB）
 */
public class ImageUtils {

    private static final String TAG = "ImageUtils";
    public static final int CHUNK_SIZE = 50 * 1024; // 50KB per chunk
    public static final long CHUNK_TIMEOUT = 30 * 1000; // 30秒超时
    public static final long SEND_INTERVAL = 60 * 1000; // 1分钟间隔
    private static final long MAX_IMAGE_SIZE = 20 * 1024 * 1024; // 20MB

    private static long lastSendTime = 0;

    // 接收中的图片缓存
    private static final Map<String, ImageAssembly> receivingImages = new HashMap<>();

    /**
     * 检查是否可以发送图片
     */
    public static String canSendImage(File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            return "文件不存在";
        }
        if (imageFile.length() > MAX_IMAGE_SIZE) {
            return "图片过大，请选择20MB以内的图片";
        }
        long now = System.currentTimeMillis();
        if (now - lastSendTime < SEND_INTERVAL) {
            long remaining = (SEND_INTERVAL - (now - lastSendTime)) / 1000;
            return "发送过于频繁，请等待 " + remaining + " 秒";
        }
        return null;
    }

    public static void recordSend() {
        lastSendTime = System.currentTimeMillis();
    }

    /**
     * 将图片分块
     */
    public static List<byte[]> chunkImage(byte[] imageData) {
        List<byte[]> chunks = new ArrayList<>();
        int totalSize = imageData.length;
        int offset = 0;
        while (offset < totalSize) {
            int chunkSize = Math.min(CHUNK_SIZE, totalSize - offset);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(imageData, offset, chunk, 0, chunkSize);
            chunks.add(chunk);
            offset += chunkSize;
        }
        return chunks;
    }

    /**
     * 将Bitmap压缩为JPEG字节
     */
    public static byte[] bitmapToJpegBytes(Bitmap bitmap, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return bos.toByteArray();
    }

    /**
     * 开始接收图片块
     */
    public static synchronized void startImageAssembly(String imageId, int totalChunks, String fileName) {
        ImageAssembly assembly = new ImageAssembly(totalChunks, fileName);
        receivingImages.put(imageId, assembly);
    }

    /**
     * 接收一个图片块
     * @return true if image complete
     */
    public static synchronized boolean receiveImageChunk(String imageId, int chunkIndex, byte[] chunkData) {
        ImageAssembly assembly = receivingImages.get(imageId);
        if (assembly == null) {
            Log.w(TAG, "未知的图片ID: " + imageId);
            return false;
        }
        assembly.addChunk(chunkIndex, chunkData);
        if (assembly.isComplete()) {
            assembly.setComplete();
            return true;
        }
        return false;
    }

    /**
     * 获取已完成的图片数据
     */
    public static synchronized byte[] getAssembledImage(String imageId) {
        ImageAssembly assembly = receivingImages.get(imageId);
        if (assembly == null || !assembly.isComplete()) return null;
        return assembly.assemble();
    }

    /**
     * 获取完成的图片文件名
     */
    public static synchronized String getImageFileName(String imageId) {
        ImageAssembly assembly = receivingImages.get(imageId);
        if (assembly == null) return "image.jpg";
        return assembly.fileName;
    }

    /**
     * Base64编码图片数据
     */
    public static String encodeBase64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    /**
     * Base64解码
     */
    public static byte[] decodeBase64(String base64) {
        return Base64.decode(base64, Base64.NO_WRAP);
    }

    /**
     * 清理超时的图片接收
     */
    public static synchronized void cleanup() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ImageAssembly> entry : receivingImages.entrySet()) {
            if (now - entry.getValue().lastChunkTime > CHUNK_TIMEOUT) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            receivingImages.remove(id);
            Log.d(TAG, "清理过期图片: " + id);
        }
    }

    /**
     * 将Bitmap保存到文件
     */
    public static File saveBitmapToCache(Bitmap bitmap, File cacheDir, String fileName) {
        try {
            File imageFile = new File(cacheDir, fileName);
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
            return imageFile;
        } catch (IOException e) {
            Log.e(TAG, "保存图片失败", e);
            return null;
        }
    }

    /**
     * 图片装配器
     */
    private static class ImageAssembly {
        final byte[][] chunks;
        final int totalChunks;
        final String fileName;
        long lastChunkTime;
        boolean complete = false;
        int receivedCount = 0;

        ImageAssembly(int totalChunks, String fileName) {
            this.totalChunks = totalChunks;
            this.fileName = fileName != null ? fileName : "image.jpg";
            this.chunks = new byte[totalChunks][];
            this.lastChunkTime = System.currentTimeMillis();
        }

        synchronized void addChunk(int index, byte[] data) {
            if (index >= 0 && index < totalChunks && chunks[index] == null) {
                chunks[index] = data;
                receivedCount++;
                lastChunkTime = System.currentTimeMillis();
            }
        }

        synchronized boolean isComplete() {
            return receivedCount >= totalChunks;
        }

        synchronized void setComplete() {
            this.complete = true;
        }

        synchronized byte[] assemble() {
            // 计算总大小
            int totalSize = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) totalSize += chunk.length;
            }
            byte[] result = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    System.arraycopy(chunk, 0, result, offset, chunk.length);
                    offset += chunk.length;
                }
            }
            return result;
        }
    }

    // 清理线程控制
    private static Thread cleanupThread = null;

    public static void startCleanupThread() {
        if (cleanupThread != null && cleanupThread.isAlive()) return;
        cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    cleanup();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
}
