package ampp.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

final class RemoteMediaCard implements AutoCloseable {
    interface Commands { void send(String action, long position); }

    interface Foreground {
        boolean show(int id, Notification notification);
        void end();
    }

    private static final String CHANNEL = "ampp_remote_playback";
    private static final String TAG = "AM++ remote playback";
    private static final int ID = 0x414d5052;
    private static final int RECEIVER_NOT_EXPORTED = 4;
    private static final long KNOWN_ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;

    private final Context app;
    private final String device;
    private String notifiedDevice;
    private final int smallIcon;
    private final Commands commands;
    private final Foreground foreground;
    private boolean promoted;
    private final NotificationManager notifications;
    private final MediaSession session;
    private final String action;
    private final PendingIntent openApp;
    private final PendingIntent previous;
    private final PendingIntent play;
    private final PendingIntent pause;
    private final PendingIntent next;
    private final BroadcastReceiver receiver;
    private boolean receiverInstalled;
    private boolean closed;
    private boolean attempted;
    private String title = "";
    private String artist = "";
    private String album = "";
    private String artwork;
    private Bitmap cover;
    private long duration;
    private long actions;
    private int status = PlaybackState.STATE_NONE;

    RemoteMediaCard(Context app, Handler handler, String device, Commands commands, Foreground foreground) {
        this.app = app;
        this.device = device;
        notifiedDevice = device;
        int nativeNote = app.getResources().getIdentifier(
                "app_icon_music_note_cropped_tight", "drawable", app.getPackageName());
        smallIcon = nativeNote != 0 ? nativeNote : android.R.drawable.ic_media_play;
        this.commands = commands;
        this.foreground = foreground;
        notifications = Objects.requireNonNull(app.getSystemService(NotificationManager.class));
        action = app.getPackageName() + ".ampp.REMOTE_MEDIA." + UUID.randomUUID();
        session = new MediaSession(app, "AM++ Remote Controls");

        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        openApp = launch == null ? null : PendingIntent.getActivity(app, ID, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        previous = buttonIntent("previous", 1);
        play = buttonIntent("play", 2);
        pause = buttonIntent("pause", 3);
        next = buttonIntent("next", 4);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (action.equals(intent.getAction())) send(intent.getStringExtra("command"), -1);
            }
        };

        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "AM++ 远程播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, new IntentFilter(action), null, handler, RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, new IntentFilter(action), null, handler);
            }
            receiverInstalled = true;

            // The only clients with a reference should be the notification surface
            // and its action buttons. Do not publish this as an active session.
            session.setActive(false);
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_FIXED, 1, 0) {});
            session.setSessionActivity(openApp);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { send("play", -1); }
                @Override public void onPause() { send("pause", -1); }
                @Override public void onSkipToPrevious() { send("previous", -1); }
                @Override public void onSkipToNext() { send("next", -1); }
                @Override public void onSeekTo(long position) { send("seek", position); }

                // Do not relay media keys received from a headset or system dispatcher.
                @Override public boolean onMediaButtonEvent(Intent intent) { return true; }
            }, handler);
        } catch (RuntimeException setupFailed) {
            close();
            throw setupFailed;
        }
    }

    private PendingIntent buttonIntent(String name, int code) {
        Intent intent = new Intent(action).setPackage(app.getPackageName()).putExtra("command", name);
        return PendingIntent.getBroadcast(app, code, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    synchronized void update(TrackSnapshot sample, String coverPath) {
        if (closed) return;
        JSONObject data = sample.data;
        int nowState = data == null ? PlaybackState.STATE_ERROR : sample.playing()
                ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long buttons = sample.actions() & KNOWN_ACTIONS;
        boolean notify = !attempted || status != nowState || actions != buttons;
        if (data != null) {
            String currentDevice = data.optString("outputName", device);
            notify |= !currentDevice.equals(notifiedDevice);
            notifiedDevice = currentDevice;
        }
        status = nowState;
        actions = buttons;

        String newTitle = data == null ? title : data.optString("title");
        String newArtist = data == null ? artist : data.optString("artist");
        String newAlbum = data == null ? album : data.optString("album");
        long newDuration = data == null ? duration : Math.max(0, data.optLong("duration", 0));
        boolean artChanged = !Objects.equals(coverPath, artwork);
        if (artChanged) {
            artwork = coverPath;
            cover = localCover(coverPath);
        }
        if (!attempted || artChanged || !title.equals(newTitle) || !artist.equals(newArtist)
                || !album.equals(newAlbum) || duration != newDuration) {
            title = newTitle;
            artist = newArtist;
            album = newAlbum;
            duration = newDuration;
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (cover != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
            session.setMetadata(metadata.build());
            notify = true;
        }

        long now = SystemClock.elapsedRealtime();
        PlaybackState.Builder state = new PlaybackState.Builder()
                .setActions(buttons)
                .setState(nowState, sample.position(now), sample.playing() ? 1f : 0f, now);
        if (data == null) state.setErrorMessage("播放设备暂时离线");
        session.setPlaybackState(state.build());
        if (notify) {
            postNotification();
            attempted = true;
        }
    }

    private void postNotification() {
        Notification.Builder builder = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title.isEmpty() ? "AM++ 远程播放" : title)
                .setContentText(artist.isEmpty() ? album : artist)
                .setSubText(status == PlaybackState.STATE_ERROR ? notifiedDevice + " · 离线" : "播放设备 · " + notifiedDevice)
                .setContentIntent(openApp)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        int count = 0;
        if (allowed(actions, "previous")) {
            add(builder, android.R.drawable.ic_media_previous, "上一首", previous);
            count++;
        }
        if (status == PlaybackState.STATE_PLAYING && allowed(actions, "pause")) {
            add(builder, android.R.drawable.ic_media_pause, "暂停", pause);
            count++;
        } else if (status == PlaybackState.STATE_PAUSED && allowed(actions, "play")) {
            add(builder, android.R.drawable.ic_media_play, "播放", play);
            count++;
        }
        if (allowed(actions, "next")) {
            add(builder, android.R.drawable.ic_media_next, "下一首", next);
            count++;
        }
        int[] compact = new int[count];
        for (int i = 0; i < count; i++) compact[i] = i;
        builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(compact));
        if (cover != null) builder.setLargeIcon(cover);
        Notification notification = builder.build();
        if (foreground != null && !attempted) {
            promoted = foreground.show(ID, notification);
            if (promoted) return;
        }
        if (promoted) notifications.notify(ID, notification);
        else notifications.notify(TAG, ID, notification);
    }

    private static void add(Notification.Builder builder, int icon, String name, PendingIntent action) {
        builder.addAction(new Notification.Action.Builder(Icon.createWithResource("android", icon), name, action).build());
    }

    private synchronized void send(String action, long position) {
        if (closed || !allowed(actions, action)) return;
        if ("seek".equals(action) && (position < 0 || duration <= 0 || position > duration)) return;
        if (("play".equals(action) && status != PlaybackState.STATE_PAUSED)
                || ("pause".equals(action) && status != PlaybackState.STATE_PLAYING)) return;
        commands.send(action, position);
    }

    static boolean allowed(long actions, String action) {
        if (action == null) return false;
        switch (action) {
            case "play": return (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
            case "pause": return (actions & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
            case "previous": return (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
            case "next": return (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
            case "seek": return (actions & PlaybackState.ACTION_SEEK_TO) != 0;
            default: return false;
        }
    }

    private static Bitmap localCover(String url) {
        if (url == null || !url.startsWith("file:///")) return null;
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inSampleSize = 2;
        return BitmapFactory.decodeFile(Uri.parse(url).getPath(), size);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (foreground != null) foreground.end();
            if (promoted) notifications.cancel(ID);
            else notifications.cancel(TAG, ID);
        } finally {
            try {
                if (receiverInstalled) app.unregisterReceiver(receiver);
            } finally {
                previous.cancel();
                play.cancel();
                pause.cancel();
                next.cancel();
                session.release();
            }
        }
    }
}
