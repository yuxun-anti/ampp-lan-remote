package ampp.remote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;

public final class AlbumHandoffTest {
    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
    private static final String TARGET = "00000000-0000-0000-0000-000000000002";
    private static final AlbumHandoff FIRST = AlbumHandoff.fromQueue("100", 3, 2, 2, Arrays.asList("105", "106"));
    private static final AlbumHandoff NEXT_DISC = AlbumHandoff.fromQueue("100", 4, 2, 1, Collections.singletonList("106"));

    public static void main(String[] args) throws Exception {
        check(!FIRST.matches(AlbumHandoff.fromQueue("100", 3, 2, 2, Arrays.asList("106", "105"))), "Order matters");
        check(!FIRST.matches(AlbumHandoff.fromQueue("100", 3, 2, 1, Collections.singletonList("105"))), "Length matters");
        check(AlbumHandoff.fromQueue("100", 3, 2, 2, Collections.singletonList("105")) == null,
                "A partial queue cannot verify the album");
        check(AlbumHandoff.fromQueue("100", 5, 2, 0, Collections.emptyList()) != null, "Allow the final song");

        Player source = new Player(true, FIRST);
        Player target = new Player(false, FIRST);
        target.beforeStage = () -> check(source.playing, "Keep playing while the target prepares");
        check(move(source, target) == 200, "Identify a completed album transfer");
        check(target.playing && !source.playing && source.followed, "One device plays after handoff");
        check(target.position == 83210 && FIRST.matches(target.prepared), "Use final paused offset and album order");

        Player paused = new Player(false, FIRST);
        Player legacy = new Player(false, FIRST);
        legacy.albumSupport = false;
        check(move(paused, legacy) == 204 && !legacy.playing && legacy.prepared == null && legacy.position == 83000,
                "Old peers keep the single-song path and paused position");

        Player changing = new Player(true, FIRST);
        Player retry = new Player(false, FIRST);
        retry.beforeStage = () -> {
            if (retry.stages == 1) {
                changing.song = "105";
                changing.album = NEXT_DISC;
                changing.position = 100;
                retry.album = NEXT_DISC;
            }
        };
        check(move(changing, retry) == 200 && retry.stages == 2 && retry.cancels == 1,
                "Cancel the old stage and prepare the next track after crossing the disc boundary");
        check(retry.song.equals("105") && retry.position == 310 && NEXT_DISC.matches(retry.prepared),
                "Capture the new track and its finalized progress");

        Player untouched = new Player(true, FIRST);
        Player wrongOrder = new Player(false,
                AlbumHandoff.fromQueue("100", 3, 2, 2, Arrays.asList("106", "105")));
        check(move(untouched, wrongOrder) == 422 && untouched.playing && untouched.pauses == 0,
                "A destination with a different queue never interrupts the source");

        Player restored = new Player(true, FIRST);
        Player rejected = new Player(false, FIRST);
        rejected.commitStatus = 422;
        check(move(restored, rejected) == 412 && restored.playing && !rejected.playing && !restored.followed,
                "Abort a failed commit and restore the source");
    }

    private static int move(Player source, Player target) throws Exception {
        String token = LanServer.newToken();
        RoleHandoff receiver = new RoleHandoff(TARGET, Collections::emptyList, target);
        LanServer.Player endpoint = new LanServer.Player() {
            @Override public String state() { return target.local().toString(); }
            @Override public int command(String name, long position) { return 404; }
        };
        try (LanServer server = new LanServer((Inet4Address) InetAddress.getByName("127.0.0.1"),
                0, token, endpoint, Collections.emptyMap(), receiver)) {
            LanPeer peer = DiscoveryTest.loopbackPeer(server.url());
            RoleHandoff sender = new RoleHandoff(SOURCE,
                    () -> Collections.singletonList(new RemoteStore.Device("target", peer)), source);
            return sender.command("move-detail/" + TARGET);
        }
    }

    private static final class Player implements RoleHandoff.Playback {
        volatile boolean playing;
        volatile boolean followed;
        volatile String song = "104";
        volatile long position = 83000;
        volatile AlbumHandoff album;
        volatile AlbumHandoff prepared;
        boolean albumSupport = true;
        int stages;
        int cancels;
        int pauses;
        int commitStatus = 204;
        Runnable beforeStage = () -> {};

        Player(boolean playing, AlbumHandoff album) {
            this.playing = playing;
            this.album = album;
        }

        @Override public JSONObject local() {
            try {
                return new JSONObject().put("mediaId", song).put("queueEntry", song)
                        .put("position", position).put("playing", playing);
            } catch (org.json.JSONException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override public boolean setPlaying(boolean playing) {
            if (!playing) {
                position += 210;
                pauses++;
            }
            this.playing = playing;
            return true;
        }

        @Override public boolean supportsStaging() { return true; }
        @Override public boolean supportsAlbumHandoff() { return albumSupport; }
        @Override public AlbumHandoff albumQueue(JSONObject snapshot) {
            return song.equals(snapshot.optString("mediaId")) ? album : null;
        }

        @Override public int stage(String id, AlbumHandoff requested) {
            stages++;
            boolean valid = requested == null || requested.matches(album);
            beforeStage.run();
            if (!valid) return 422;
            song = id;
            prepared = requested;
            return 204;
        }

        @Override public int commit(String id, long position, boolean playing, AlbumHandoff requested) {
            if (commitStatus != 204) return commitStatus;
            check(song.equals(id) && (prepared == null ? requested == null : prepared.matches(requested)),
                    "Commit uses the same staged song and album");
            return take(id, position, playing);
        }

        @Override public int take(String id, long position, boolean playing) {
            this.song = id;
            this.position = position;
            this.playing = playing;
            return 204;
        }

        @Override public void cancelStage() { cancels++; }
        @Override public void follow(RemoteStore.Device destination) { followed = true; }
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
