package dev.chatrelay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.*;
import java.util.concurrent.*;

final class ImageLoader {
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Map<String, Long> unavailable = new ConcurrentHashMap<>();
    private final Map<String, List<ImageView>> pending = new HashMap<>();
    private boolean closed;
    static Bitmap decode(byte[] data, int max) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > max || options.outHeight / options.inSampleSize > max) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
    void load(RelayApi api, String path, ImageView target) {
        String key = api.base + path; target.setTag(key);
        Bitmap bitmap = cache.get(key);
        if (bitmap != null) { target.setImageBitmap(bitmap); return; }
        if (closed || unavailable.getOrDefault(key, 0L) > System.currentTimeMillis()) return;
        if (pending.containsKey(key)) { pending.get(key).add(target); return; }
        pending.put(key, new ArrayList<>(Collections.singletonList(target)));
        pool.execute(() -> {
            Bitmap result = null;
            try { result = decode(api.bytes(path), path.endsWith("/avatar") ? 160 : 1000); }
            catch (Exception ignored) {}
            final Bitmap ready = result;
            main.post(() -> {
                List<ImageView> views = pending.remove(key);
                if (closed) return;
                if (ready != null) cache.put(key, ready); else unavailable.put(key, System.currentTimeMillis() + 60000);
                if (views != null && ready != null) for (ImageView view : views) if (key.equals(view.getTag())) view.setImageBitmap(ready);
            });
        });
    }
    void close() { closed = true; pool.shutdownNow(); cache.evictAll(); unavailable.clear(); pending.clear(); }
}
