package dev.chatrelay;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import java.io.*;
import java.util.concurrent.*;

public class MediaActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File videoFile;
    private VideoView video;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        TextView close = new TextView(this); close.setText("‹  Close"); close.setTextSize(18); close.setTextColor(Color.WHITE); close.setPadding(24,24,24,24); close.setOnClickListener(v -> finish()); root.addView(close);
        TextView loading = new TextView(this); loading.setText("Downloading securely…"); loading.setTextColor(Color.WHITE); loading.setPadding(24,24,24,24); root.addView(loading);
        setContentView(root);
        String path = getIntent().getStringExtra("path"); boolean isVideo = getIntent().getBooleanExtra("video", false);
        worker.execute(() -> {
            try {
                RelayApi api = new RelayApi(getSharedPreferences("relay",0).getString("url",""), SecretStore.load(this));
                byte[] bytes = api.bytes(path);
                if (isVideo) {
                    videoFile = File.createTempFile("relay-video-", ".mp4", getCacheDir());
                    try (OutputStream out = new FileOutputStream(videoFile)) { out.write(bytes); }
                    runOnUiThread(() -> {
                        if (isDestroyed()) { deleteVideo(); return; }
                        root.removeView(loading); video = new VideoView(this);
                        root.addView(video, new LinearLayout.LayoutParams(-1,0,1));
                        MediaController controls = new MediaController(this); controls.setAnchorView(video); video.setMediaController(controls);
                        video.setOnErrorListener((player, what, extra) -> { loading.setText("This video's codec cannot be played on this phone."); root.addView(loading); return true; });
                        video.setVideoPath(videoFile.getAbsolutePath()); video.setOnPreparedListener(player -> video.start());
                    });
                } else {
                    android.graphics.Bitmap bitmap = ImageLoader.decode(bytes, 2048);
                    if (bitmap == null) throw new IOException("This image could not be decoded.");
                    runOnUiThread(() -> { if (isDestroyed()) return; root.removeView(loading); ImageView image = new ImageView(this); image.setContentDescription("Message image"); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageBitmap(bitmap); root.addView(image, new LinearLayout.LayoutParams(-1,0,1)); });
                }
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) loading.setText(e.getMessage() == null ? "Attachment unavailable. Try again from the conversation." : e.getMessage()); }); }
        });
    }
    private void deleteVideo() { if (videoFile != null) videoFile.delete(); }
    @Override protected void onPause() { if (video != null) video.pause(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); if (video != null) video.stopPlayback(); deleteVideo(); super.onDestroy(); }
}
