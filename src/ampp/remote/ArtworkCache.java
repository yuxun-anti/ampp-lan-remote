package ampp.remote;

import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

final class ArtworkCache {
    private final File directory;

    ArtworkCache(File cache) { directory = new File(cache, "remote-covers"); }

    static boolean validKey(String art) {
        return art != null && art.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    String url(LanPeer peer, String art) {
        if (!validKey(art)) return null;
        String name = UUID.nameUUIDFromBytes((peer.authority + "/" + art)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".jpg";
        File cover = new File(directory, name);
        try {
            if (cover.isFile() && cover.length() >= 4 && cover.length() <= 240000) {
                cover.setLastModified(System.currentTimeMillis());
                return android.net.Uri.fromFile(cover).toString();
            }
            byte[] bytes = peer.artwork(art);
            if (!validJpeg(bytes)) return null;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            File temporary = File.createTempFile("download-", ".part", directory);
            try {
                Files.write(temporary.toPath(), bytes);
                Files.move(temporary.toPath(), cover.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
            trim();
            return android.net.Uri.fromFile(cover).toString();
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    static boolean validJpeg(byte[] bytes) {
        if (bytes.length < 4 || bytes.length > 240000
                || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= 480 && options.outHeight <= 480
                && "image/jpeg".equals(options.outMimeType);
    }

    private void trim() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null || files.length <= 28) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int index = 0; index < files.length - 28; index++) files[index].delete();
    }
}
