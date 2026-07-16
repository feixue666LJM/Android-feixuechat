package com.feixue.chat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 图片查看器 - 与 src/ph/lookph.java 功能一致
 * 支持缩放查看、全屏显示
 */
public class ImageViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_view);

        ImageView imageView = findViewById(R.id.ivFullImage);
        TextView tvFileName = findViewById(R.id.tvFileName);

        byte[] imageData = getIntent().getByteArrayExtra("image_data");
        String fileName = getIntent().getStringExtra("file_name");

        if (fileName != null) {
            tvFileName.setText(fileName);
            setTitle(fileName);
        }

        if (imageData != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (bitmap != null) {
                // 缩放以适应屏幕（参照桌面端：缩放至屏幕80%）
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int maxW = (int) (screenWidth * 0.9);
                int maxH = (int) (screenHeight * 0.85);

                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                float scale = Math.min((float) maxW / w, (float) maxH / h);
                if (scale < 1) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, (int) (w * scale), (int) (h * scale), true);
                }

                imageView.setImageBitmap(bitmap);
            }
        }
    }
}
