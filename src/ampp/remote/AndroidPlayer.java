package ampp.remote;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import java.nio.charset.StandardCharsets;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import org.json.JSONException;
import org.json.JSONObject;

final class AndroidPlayer implements LanServer.Player {
    private final Context app;
    private MediaController lastController;
    private final String node;
    private volatile java.util.function.BiFunction<String, Long, Integer> nativeTransport = (name, at) -> null;
    private volatile java.util.function.ToIntFunction<String> nativeSelection = id -> 404;
    private volatile AlbumSelection albumSelection = (song, album, index) -> 404;

    interface AlbumSelection { int play(String song, String album, int index); }
    private volatile java.util.function.Supplier<JSONObject> nativeState = () -> null;
    private volatile java.util.function.BooleanSupplier mirror = () -> false;
    private volatile java.util.function.BooleanSupplier transferring = () -> false;
    private final ArtworkSource covers;

    AndroidPlayer(Context app, String node) {
        this.app = app;
        this.node = node;
        this.covers = new ArtworkSource(app);
    }

    void excludeWhen(java.util.function.BooleanSupplier mirror) {
        this.mirror = mirror;
    }

    void transferGate(java.util.function.BooleanSupplier transferring) { this.transferring = transferring; }

    void nativeState(java.util.function.Supplier<JSONObject> nativeState) {
        this.nativeState = nativeState;
    }

    void nativeTransport(java.util.function.BiFunction<String, Long, Integer> nativeTransport) {
        this.nativeTransport = nativeTransport;
    }

    void nativeSelection(java.util.function.ToIntFunction<String> nativeSelection) {
        this.nativeSelection = nativeSelection;
    }

    void nativeAlbumSelection(AlbumSelection selection) { albumSelection = selection; }

    @Override public synchronized int playAlbumSong(String song, String album, int index) {
        return mirror.getAsBoolean() || transferring.getAsBoolean() ? 409 : albumSelection.play(song, album, index);
    }

    @Override public synchronized int playCatalogSong(String id) {
        return mirror.getAsBoolean() || transferring.getAsBoolean() ? 409 : nativeSelection.applyAsInt(id);
    }

    @Override public synchronized String state() {
        try {
            MediaController controller = findController();
            PlaybackState playback = playback(controller);
            if (mirror.getAsBoolean()) return "{\"ready\":false}";
            JSONObject direct = nativeState.get();
            if (direct != null) {
                direct.put("node", node);
                MediaMetadata current = controller == null ? null : controller.getMetadata();
                if (current != null && (!direct.optString("title").equals(label(current, MediaMetadata.METADATA_KEY_TITLE))
                        || !direct.optString("artist").equals(label(current, MediaMetadata.METADATA_KEY_ARTIST)))) {
                    current = null;
                }
                String source = direct.optString("_artworkSource", null);
                direct.remove("_artworkSource");
                publishArtwork(direct, source, current);
                return direct.toString();
            }
            if (playback == null) {
                covers.update("", null, null);
                return "{\"ready\":false}";
            }
            MediaMetadata metadata = controller.getMetadata();
            long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = playback.getPosition();
            if (position >= 0 && playback.getState() == PlaybackState.STATE_PLAYING
                    && playback.getLastPositionUpdateTime() > 0) {
                long elapsed = Math.max(0, SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime());
                position += (long) (elapsed * playback.getPlaybackSpeed());
            }
            JSONObject result = new JSONObject();
            result.put("ready", true);
            result.put("node", node);
            result.put("title", label(metadata, MediaMetadata.METADATA_KEY_TITLE));
            result.put("mediaId", label(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
            result.put("artist", label(metadata, MediaMetadata.METADATA_KEY_ARTIST));
            result.put("album", label(metadata, MediaMetadata.METADATA_KEY_ALBUM));
            result.put("albumArtist", label(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            result.put("speed", playback.getPlaybackSpeed());
            publishArtwork(result, null, metadata);
            result.put("playing", playback.getState() == PlaybackState.STATE_PLAYING);
            result.put("position", duration > 0 ? Math.min(position, duration) : position);
            result.put("duration", duration);
            result.put("actions", playback.getActions() & (
                    PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SEEK_TO));
            return result.toString();
        } catch (RuntimeException | JSONException unavailable) {
            return "{\"ready\":false}";
        }
    }

    private void publishArtwork(JSONObject result, String source, MediaMetadata current) throws JSONException {
        if (source == null || source.isEmpty()) source = label(current, MediaMetadata.METADATA_KEY_ART_URI);
        if (source == null || source.isEmpty()) source = label(current, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (source == null || source.isEmpty()) source = label(current, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        String online = NativeTrack.appleArtwork(source);
        if (online != null) result.put("artworkUrl", online);
        String track = result.optString("mediaId") + "\n" + result.optString("title") + "\n"
                + result.optString("artist") + "\n" + result.optString("album");
        String key = java.util.UUID.nameUUIDFromBytes(track.getBytes(StandardCharsets.UTF_8)).toString();
        Bitmap image = current == null ? null : current.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (image == null && current != null) image = current.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (image == null && current != null) image = current.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        covers.update(key, source, image);
        result.put("art", key);
    }

    @Override public byte[] artwork(String requested) {
        return mirror.getAsBoolean() ? new byte[0] : covers.get(requested);
    }

    void closeArtwork() { covers.close(); }

    @Override public synchronized int command(String name, long position) {
        try {
            if (mirror.getAsBoolean() || transferring.getAsBoolean()) return 409;
            Integer result = nativeTransport.apply(name, position);
            if (result != null) return result;
            MediaController controller = findController();
            PlaybackState playback = playback(controller);
            if (playback == null) return 409;
            MediaController.TransportControls controls = controller.getTransportControls();
            long actions = playback.getActions();
            switch (name) {
                case "play":
                    if ((actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) == 0) return 409;
                    controls.play();
                    break;
                case "pause":
                    if ((actions & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) == 0) return 409;
                    controls.pause();
                    break;
                case "previous":
                    if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0) return 409;
                    controls.skipToPrevious();
                    break;
                case "next":
                    if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) == 0) return 409;
                    controls.skipToNext();
                    break;
                case "seek":
                    if ((actions & PlaybackState.ACTION_SEEK_TO) == 0) return 409;
                    MediaMetadata metadata = controller.getMetadata();
                    long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    if (duration <= 0 || position < 0 || position > duration) return 409;
                    controls.seekTo(position);
                    break;
                default:
                    return 404;
            }
            return 204;
        } catch (RuntimeException unavailable) {
            return 503;
        }
    }

    private MediaController findController() {
        if (mirror.getAsBoolean()) return null;
        NotificationManager notifications = app.getSystemService(NotificationManager.class);
        MediaController newest = null;
        long newestTime = Long.MIN_VALUE;
        if (notifications != null) {
            for (StatusBarNotification item : notifications.getActiveNotifications()) {
                if (!app.getPackageName().equals(item.getPackageName())) continue;
                Notification notification = item.getNotification();
                if (notification == null || notification.extras == null) continue;
                Parcelable value = notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (!(value instanceof MediaSession.Token)) continue;
                MediaController controller = new MediaController(app, (MediaSession.Token) value);
                if (!app.getPackageName().equals(controller.getPackageName())) continue;
                PlaybackState state = playback(controller);
                if (state == null) continue;
                if (state.getState() == PlaybackState.STATE_PLAYING) {
                    lastController = controller;
                    return controller;
                }
                if (item.getPostTime() > newestTime) {
                    newest = controller;
                    newestTime = item.getPostTime();
                }
            }
        }
        if (newest != null) lastController = newest;
        if (playback(lastController) == null) lastController = null;
        return lastController;
    }

    private static PlaybackState playback(MediaController controller) {
        if (controller == null) return null;
        PlaybackState state = controller.getPlaybackState();
        if (state == null || state.getState() == PlaybackState.STATE_NONE
                || state.getState() == PlaybackState.STATE_ERROR) return null;
        return state;
    }

    private static String label(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        String value = metadata.getString(key);
        return value == null ? "" : value;
    }
}
