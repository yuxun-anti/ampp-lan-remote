package ampp.remote;

import org.json.JSONObject;

public final class TrackSnapshotTest {
    public static void main(String[] args) throws Exception {
        TrackSnapshot beforeEnd = sample(207500, 208000, true, 1, 1000);
        TrackSnapshot stillAtEnd = sample(208000, 208000, true, 1, 1650);
        TrackSnapshot nextTrack = sample(240, 195000, true, 1, 1650);
        TrackSnapshot repeatedTrack = sample(240, 208000, true, 1, 1650);
        check(TrackSnapshot.wrappedAtEnd(beforeEnd, nextTrack), "Refresh after automatic advance");
        check(TrackSnapshot.wrappedAtEnd(beforeEnd, repeatedTrack), "Refresh after repeat-one restarts");
        check(!TrackSnapshot.wrappedAtEnd(beforeEnd, stillAtEnd), "Do not infer a track change from the clock");
        check(!TrackSnapshot.wrappedAtEnd(beforeEnd, sample(240, 208000, false, 1, 1650)),
                "A pause does not count as an automatic restart");
        check(!TrackSnapshot.wrappedAtEnd(sample(20000, 208000, true, 1, 1000), repeatedTrack),
                "A jump from the middle is not an end-of-track restart");
        check(sample(500, 208000, true, 0.5, 1000).position(2000) == 1000, "Project at the remote rate");
    }

    private static TrackSnapshot sample(long position, long duration, boolean playing, double rate, long at)
            throws Exception {
        return new TrackSnapshot(new JSONObject().put("position", position).put("duration", duration)
                .put("playing", playing).put("speed", rate), at);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
