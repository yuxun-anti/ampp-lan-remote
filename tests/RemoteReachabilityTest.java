package ampp.remote;

import org.json.JSONObject;

public final class RemoteReachabilityTest {
    public static void main(String[] args) throws Exception {
        RemoteReachability link = new RemoteReachability();
        JSONObject noTrack = new JSONObject().put("ready", false);
        JSONObject unreachableRelay = new JSONObject().put("ready", false).put("outputUnavailable", true);

        check(!poll(link, null, 0), "the first missed poll should allow for recovery");
        check(!poll(link, null, 11999), "a short outage should preserve the remote session");
        check(!poll(link, noTrack, 12000), "an online peer without a track should reset the deadline");
        check(!poll(link, null, 13000), "a second outage gets a fresh grace period");
        check(!poll(link, unreachableRelay, 24999), "a relay failure within the grace period");
        check(poll(link, unreachableRelay, 25000), "sustained output loss should restore the local player");
        check(!poll(link, noTrack, 26000), "an online peer still counts as reachable when idle");

        Peer playing = new Peer(204, playing(true), playing(true), playing(false));
        check(PairedPlayback.pauseAndConfirm(playing), "wait for the playing peer to actually pause");
        check(playing.commands == 1 && playing.reads == 3, "send one pause and verify the resulting state");
        Peer offline = new Peer(503, "unreachable");
        check(!PairedPlayback.pauseAndConfirm(offline), "an offline peer is not confirmed paused");
        check(offline.commands == 0, "avoid a redundant command to an already unreachable device");
        Peer ignored = new Peer(204, playing(true));
        check(!PairedPlayback.pauseAndConfirm(ignored), "HTTP success alone does not confirm a pause");
        Peer paused = new Peer(204, playing(false));
        check(PairedPlayback.pauseAndConfirm(paused) && paused.commands == 0, "leave a paused peer alone");
    }

    private static String playing(boolean value) {
        return "{\"ready\":true,\"playing\":" + value + "}";
    }

    private static final class Peer implements LanServer.Player {
        private final int result;
        private final String[] states;
        int reads;
        int commands;

        Peer(int result, String... states) {
            this.result = result;
            this.states = states;
        }

        @Override public String state() { return states[Math.min(reads++, states.length - 1)]; }
        @Override public int command(String name, long position) {
            check("pause".equals(name), "exit must never start playback on the peer");
            commands++;
            return result;
        }
        @Override public byte[] artwork(String key) { return new byte[0]; }
    }

    private static boolean poll(RemoteReachability link, JSONObject state, long time) {
        return link.shouldRecover(PairedPlayback.outputReachable(state), time);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
