package ampp.remote;

import android.media.session.PlaybackState;
import org.json.JSONObject;

final class TrackSnapshot {
    final JSONObject data;
    final long sampledAt;

    TrackSnapshot(JSONObject data, long sampledAt) {
        this.data = data;
        this.sampledAt = sampledAt;
    }

    boolean playing() { return data != null && data.optBoolean("playing"); }
    private long nativeActions() { return data == null ? 0L : data.optLong("actions"); }

    boolean restartsOnPrevious() {
        long actions = nativeActions();
        return (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0
                && (actions & PlaybackState.ACTION_SEEK_TO) != 0
                && data.optLong("duration") > 0;
    }

    long actions() {
        long actions = nativeActions();
        return restartsOnPrevious() ? actions | PlaybackState.ACTION_SKIP_TO_PREVIOUS : actions;
    }

    long position(long now) {
        if (data == null) return -1L;
        long offset = data.optLong("position", -1L);
        if (offset < 0L) return -1L;
        double rate = Math.max(0.0, Math.min(4.0, data.optDouble("speed", 1.0)));
        if (playing()) offset += (long) (Math.max(0L, now - sampledAt) * rate);
        long duration = data.optLong("duration", -1L);
        return duration > 0L ? Math.min(offset, duration) : offset;
    }

    static boolean sameTrack(JSONObject one, JSONObject two) {
        if (one == null || two == null) return false;
        String leftId = catalogId(one), rightId = catalogId(two);
        if (!leftId.isEmpty() && !rightId.isEmpty()) {
            if (!leftId.equals(rightId)) return false;
        } else if (!one.optString("title").equals(two.optString("title"))
                || !one.optString("artist").equals(two.optString("artist"))
                || (!one.optString("album").isEmpty() && !two.optString("album").isEmpty()
                    && !one.optString("album").equals(two.optString("album")))) {
            return false;
        }
        String left = one.optString("queueEntry"), right = two.optString("queueEntry");
        return left.isEmpty() || right.isEmpty() || left.equals(right);
    }

    private static String catalogId(JSONObject value) {
        String id = value.optString("mediaId");
        return id.matches("[0-9]{1,19}") && !"0".equals(id) ? id : "";
    }

    static boolean jumped(TrackSnapshot old, TrackSnapshot next) {
        if (old == null || next.data == null) return false;
        long before = old.position(next.sampledAt);
        long after = next.position(next.sampledAt);
        return before >= 0 && after >= 0 && Math.abs(before - after) >= 600;
    }
}
