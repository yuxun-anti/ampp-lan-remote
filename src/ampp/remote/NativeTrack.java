package ampp.remote;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Locale;
import org.json.JSONObject;

final class NativeTrack {
    private final Constructor<?> song;
    private final Constructor<?> queue;
    private final Field id;
    private final Field title;
    private final Field artist;
    private final Field album;
    private final Field albumArtist;
    private final Field artwork;
    private final Field duration;
    private final Field type;
    private final Field available;

    NativeTrack(ClassLoader loader, Class<?> media) throws ReflectiveOperationException {
        Class<?> model = loader.loadClass("com.apple.android.music.playback.model.StoreMediaItem");
        song = model.getConstructor();
        queue = loader.loadClass("com.apple.android.music.playback.queue.PlaybackQueueItem")
                .getConstructor(media, long.class);
        id = access(model, "id", String.class);
        title = access(model, "title", String.class);
        artist = access(model, "artistName", String.class);
        album = access(model, "albumTitle", String.class);
        albumArtist = access(model, "albumArtistName", String.class);
        artwork = access(model, "artworkUrl", String.class);
        duration = access(model, "duration", long.class);
        type = access(model, "type", int.class);
        available = access(model, "available", boolean.class);
    }

    Object item(JSONObject data, long queueId) throws ReflectiveOperationException {
        return item(data, queueId, appleArtwork(data.optString("artworkUrl")));
    }

    Object item(JSONObject data, long queueId, String imageUrl) throws ReflectiveOperationException {
        Object row = song.newInstance();
        String mediaId = data.optString("mediaId");
        if (mediaId.matches("[0-9]{1,19}")) {
            try {
                if (Long.parseLong(mediaId) > 0) id.set(row, mediaId);
            } catch (NumberFormatException outOfRange) {
                // Unknown catalog ID. The original metadata can still be shown.
            }
        }
        title.set(row, data.optString("title"));
        artist.set(row, data.optString("artist"));
        album.set(row, data.optString("album"));
        albumArtist.set(row, data.optString("albumArtist"));
        artwork.set(row, imageUrl);
        duration.setLong(row, data.optLong("duration", -1L));
        type.setInt(row, 1);
        available.setBoolean(row, true);
        return queue.newInstance(row, queueId);
    }

    static String appleArtwork(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return host.toLowerCase(Locale.ROOT).endsWith(".mzstatic.com") ? address : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Field access(Class<?> model, String name, Class<?> type) throws ReflectiveOperationException {
        Field field = model.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }
}
