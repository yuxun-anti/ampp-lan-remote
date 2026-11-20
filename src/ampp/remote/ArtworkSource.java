package ampp.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArtworkSource implements AutoCloseable {
    private static final byte[] MISSING = new byte[0];
    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String key = "";
    private String source;
    private Bitmap bitmap;
    private byte[] jpeg = MISSING;
    private boolean loading;
    private boolean closed;
    private long retryAfter;

    ArtworkSource(Context app) { this.app = app; }

    synchronized void update(String next, String url, Bitmap image) {
        if (!key.equals(next)) {
            key = next;
            jpeg = MISSING;
            retryAfter = 0;
        }
        source = libraryArtwork(url);
        bitmap = image;
    }

    synchronized byte[] get(String requested) {
        if (closed || key.isEmpty() || !requested.equals(key)) return MISSING;
        if (jpeg.length != 0) return jpeg;
        long now = SystemClock.elapsedRealtime();
        if (loading || now < retryAfter || (bitmap == null && source == null)) return MISSING;
        Bitmap available = bitmap;
        String uri = source;
        loading = true;
        worker.execute(() -> {
            byte[] encoded = MISSING;
            try {
                // The owner's artwork provider resolves downloaded or catalog covers.
                // It may perform I/O, so this runs outside the transport threads.
                Bitmap local = uri == null ? null : loadLibrary(uri);
                try {
                    encoded = encode(local != null ? local : available);
                } finally {
                    if (local != null) local.recycle();
                }
            } catch (RuntimeException unavailable) {
                // A later poll can retry once the original player has loaded its art.
            } finally {
                synchronized (ArtworkSource.this) {
                    loading = false;
                    if (!closed && requested.equals(key)) {
                        jpeg = encoded;
                        retryAfter = SystemClock.elapsedRealtime() + 2700;
                    }
                }
            }
        });
        return MISSING;
    }

    static String libraryArtwork(String address) {
        if (address == null || address.isEmpty()) return null;
        try {
            java.net.URI uri = java.net.URI.create(address);
            String path = uri.getPath();
            return "content".equals(uri.getScheme())
                    && "com.apple.android.music.provider.ArtworkProvider".equals(uri.getAuthority())
                    && path != null && path.matches("/library/[0-9]+/-?[0-9]+(?:/[0-9]+)?")
                    ? address : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Bitmap loadLibrary(String address) {
        Uri uri = Uri.parse(address);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
        if (options.outWidth <= 0 || options.outHeight <= 0
                || options.outWidth > 12000 || options.outHeight > 12000) return null;
        options.inJustDecodeBounds = false;
        int max = Math.max(options.outWidth, options.outHeight);
        int sample = 1;
        while (max / sample > 960) sample *= 2;
        options.inSampleSize = sample;
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            return input == null ? null : BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static byte[] encode(Bitmap image) {
        if (image == null || image.isRecycled() || image.getWidth() < 1 || image.getHeight() < 1) return MISSING;
        float scale = Math.min(1f, 480f / Math.max(image.getWidth(), image.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                Math.max(1, Math.round(image.getHeight() * scale)), true);
        try {
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, jpg);
            if (jpg.size() > 240000) {
                jpg.reset();
                small.compress(Bitmap.CompressFormat.JPEG, 60, jpg);
            }
            return jpg.size() <= 240000 ? jpg.toByteArray() : MISSING;
        } finally {
            if (small != image) small.recycle();
        }
    }

    @Override public synchronized void close() {
        closed = true;
        jpeg = MISSING;
        bitmap = null;
        worker.shutdownNow();
    }
}
