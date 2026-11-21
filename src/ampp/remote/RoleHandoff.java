package ampp.remote;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.json.JSONObject;

final class RoleHandoff implements LanServer.Roles {
    static final int MOVE_TIMEOUT = 65000;
    private static final int TRACK_CHANGED = -1;

    interface Playback {
        JSONObject local();
        boolean setPlaying(boolean playing);
        int take(String id, long position, boolean playing);
        void follow(RemoteStore.Device destination);
        default boolean supportsStaging() { return false; }
        default boolean supportsAlbumHandoff() { return false; }
        default AlbumHandoff albumQueue(JSONObject snapshot) { return null; }
        default int stage(String id) { return 404; }
        default int stage(String id, AlbumHandoff album) { return album == null ? stage(id) : 404; }
        default int commit(String id, long position, boolean playing) { return 404; }
        default int commit(String id, long position, boolean playing, AlbumHandoff album) {
            return album == null ? commit(id, position, playing) : 404;
        }
        default void cancelStage() {}

    }

    private final String node;
    private final Supplier<List<RemoteStore.Device>> devices;
    private final Playback playback;
    private final ReentrantLock transaction = new ReentrantLock();
    private volatile String following = "";
    private volatile RemoteStore.Device output;
    private volatile String accepted = "";
    private final java.util.LinkedHashSet<String> committed = new java.util.LinkedHashSet<>();
    private volatile Stage pending;
    private String uncertain = "";

    private static final class Stage {
        final String ticket;
        final String song;
        final AlbumHandoff album;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Stage(String ticket, String song, AlbumHandoff album) {
            this.ticket = ticket;
            this.song = song;
            this.album = album;
        }

        boolean matches(String ticket, String song, AlbumHandoff album) {
            return this.ticket.equals(ticket) && this.song.equals(song)
                    && (this.album == null ? album == null : this.album.matches(album));
        }
    }


    RoleHandoff(String node, Supplier<List<RemoteStore.Device>> devices, Playback playback) {
        this.node = node;
        this.devices = devices;
        this.playback = playback;
    }

    boolean busy() {
        expireStage();
        return transaction.isLocked() || pending != null;
    }

    private void expireStage() {
        Stage stage = pending;
        if (stage == null || System.nanoTime() < stage.deadline || !transaction.tryLock()) return;
        try {
            if (pending == stage && !stage.ticket.equals(uncertain)) {
                pending = null;
                playback.cancelStage();
            }
        } finally {
            transaction.unlock();
        }
    }

    @Override public String status() {
        boolean reserved = busy();
        JSONObject local = playback.local();
        return "{\"node\":\"" + node + "\",\"staging\":" + playback.supportsStaging()
                + ",\"albumHandoff\":" + playback.supportsAlbumHandoff() + ",\"busy\":" + reserved
                + ",\"playing\":" + (local != null && local.optBoolean("playing"))
                + ",\"following\":\"" + following
                + "\",\"accepted\":\"" + accepted + "\"}";
    }

    @Override public String redirect() {
        RemoteStore.Device next = output;
        if (next == null) return null;
        JSONObject state = PairedPlayback.readRaw(next);
        try {
            if (state == null) state = new JSONObject().put("ready", false).put("outputUnavailable", true);
            String destination = state.optString("following", following);
            if (destination.isEmpty()) destination = following;
            state.put("following", destination);
            if (!state.has("outputName")) state.put("outputName", next.name);
            return state.toString();
        } catch (org.json.JSONException invalid) {
            return "{\"ready\":false}";
        }
    }

    @Override public LanPeer relay() {
        RemoteStore.Device next = output;
        return next == null ? null : next.peer;
    }

    void localSelected() {
        following = "";
        output = null;
    }

    RemoteStore.Device device(String target) {
        if (!validNode(target) || node.equals(target)) return null;
        for (RemoteStore.Device device : devices.get()) {
            JSONObject role = device.peer.role();
            if (role != null && target.equals(role.optString("node"))) return device;
        }
        return null;
    }

    @Override public int command(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length == 2 && ("move".equals(parts[0]) || "move-detail".equals(parts[0])) && validNode(parts[1])) {
            LanPeer next = relay();
            if (next != null) return next.changeRole(path, MOVE_TIMEOUT - 4000);
            return "move-detail".equals(parts[0]) ? moveDetailed(parts[1]) : move(parts[1]);
        }
        if (parts.length >= 2 && validNode(parts[1])) {
            String ticket = parts[1];
            if (parts.length == 2 && "check".equals(parts[0])) {
                LanPeer next = relay();
                if (next != null) return next.changeRole(path, 3300);
                if (busy()) return 409;
                JSONObject current = playback.local();
                if (current == null || !CatalogPlayback.validId(current.optString("mediaId"))) return 422;
                return device(ticket) == null ? 404 : 204;
            }
            if (parts.length == 3 && "stage".equals(parts[0])) {
                List<String> ids = Arrays.asList(parts[2].split(",", -1));
                if (ids.size() > 9 || !ids.stream().allMatch(CatalogPlayback::validId)) return 400;
                return stage(ticket, ids.get(0), null);
            }
            if (parts.length == 7 && "stage-album".equals(parts[0])) {
                AlbumHandoff album = AlbumHandoff.parse(parts[3], parts[4], parts[5], parts[6]);
                if (!CatalogPlayback.validId(parts[2]) || album == null) return 400;
                return stage(ticket, parts[2], album);
            }
            if (parts.length == 2 && "cancel".equals(parts[0])) return cancel(ticket);
            if (parts.length == 4 && "commit".equals(parts[0])
                    && parts[2].matches("[0-9]{1,10}") && ("0".equals(parts[3]) || "1".equals(parts[3]))) {
                return commit(ticket, Long.parseLong(parts[2]), "1".equals(parts[3]));
            }
        }
        if (parts.length != 5 || !"take".equals(parts[0]) || !validNode(parts[1])
                || !CatalogPlayback.validId(parts[2]) || !parts[3].matches("[0-9]{1,10}")
                || !("0".equals(parts[4]) || "1".equals(parts[4]))) return 400;
        return take(parts[1], parts[2], Long.parseLong(parts[3]), "1".equals(parts[4]));
    }

    int move(String destination) {
        int result = moveDetailed(destination);
        return result == 200 ? 204 : result;
    }

    int moveDetailed(String destination) {
        expireStage();
        if (!transaction.tryLock()) return 409;
        try {
            if (pending != null) return 409;
            RemoteStore.Device target = device(destination);
            if (target == null) return 404;
            JSONObject start = playback.local();
            String id = start == null ? "" : start.optString("mediaId");
            if (!CatalogPlayback.validId(id)) return 422;
            JSONObject capabilities = target.peer.role();
            if (capabilities != null && capabilities.optBoolean("staging")) {
                return stagedMove(target, destination, start, capabilities.optBoolean("albumHandoff"));
            }
            boolean playing = start.optBoolean("playing");
            if (playing && !playback.setPlaying(false)) return 503;
            JSONObject paused = playback.local();
            if (paused == null || paused.optBoolean("playing") || !id.equals(paused.optString("mediaId"))) return 503;
            long position = Math.max(0L, paused.optLong("position"));
            if (position > 9999999999L) {
                if (playing) playback.setPlaying(true);
                return 422;
            }
            String ticket = UUID.randomUUID().toString();
            int status = target.peer.changeRole("take/" + ticket + "/" + id + "/" + position
                    + "/" + (playing ? "1" : "0"), 22000);
            if (status == 0 || status == 503) {
                JSONObject targetRole = target.peer.role();
                if (targetRole != null && ticket.equals(targetRole.optString("accepted"))) status = 204;
            }
            if (status == 204) {
                publish(destination, target);
            } else if (playing && (status == 400 || status == 404 || status == 409 || status == 422)) {
                playback.setPlaying(true);
            }
            return status == 0 ? 503 : status;
        } finally {
            transaction.unlock();
        }
    }

    private int stagedMove(RemoteStore.Device target, String destination, JSONObject start, boolean albumSupported) {
        int status = prepareAndMove(target, destination, start, albumSupported);
        if (status != TRACK_CHANGED) return status;
        JSONObject current = playback.local();
        if (current == null || !CatalogPlayback.validId(current.optString("mediaId"))) return 422;
        status = prepareAndMove(target, destination, current, albumSupported);
        return status == TRACK_CHANGED ? 409 : status;
    }

    private int prepareAndMove(RemoteStore.Device target, String destination, JSONObject start, boolean albumSupported) {
        String id = start.optString("mediaId");
        AlbumHandoff album = albumSupported ? playback.albumQueue(start) : null;
        String ticket = UUID.randomUUID().toString();
        String stageRoute = album == null ? "stage/" + ticket + "/" + id : album.route(ticket, id);
        int prepared = target.peer.changeRole(stageRoute, 17000);
        if (prepared != 204) {
            target.peer.changeRole("cancel/" + ticket, 1800);
            if (album != null && prepared == 422 && !sameEntry(start, playback.local())) {
                return TRACK_CHANGED;
            }
            return prepared == 0 ? 503 : prepared;
        }

        JSONObject current = playback.local();
        if (!sameEntry(start, current) || (album != null && !album.matches(playback.albumQueue(current)))) {
            return target.peer.changeRole("cancel/" + ticket, 1800) == 204 ? TRACK_CHANGED : 409;
        }
        boolean playing = current.optBoolean("playing");
        if (playing && !playback.setPlaying(false)) {
            target.peer.changeRole("cancel/" + ticket, 1800);
            JSONObject state = playback.local();
            if (sameEntry(current, state) && !state.optBoolean("playing")) playback.setPlaying(true);
            return 503;
        }
        JSONObject paused = playback.local();
        if (!sameEntry(current, paused) || (album != null && !album.matches(playback.albumQueue(paused)))) {
            int cancelled = target.peer.changeRole("cancel/" + ticket, 1800);
            if (paused == null || cancelled != 204) return 503;
            if (playing && !paused.optBoolean("playing") && !playback.setPlaying(true)) return 503;
            return TRACK_CHANGED;
        }
        if (paused.optBoolean("playing")) {
            target.peer.changeRole("cancel/" + ticket, 1800);
            return 503;
        }
        long position = Math.max(0L, Math.min(9999999999L, paused.optLong("position")));
        int result = target.peer.changeRole("commit/" + ticket + "/" + position + "/" + (playing ? "1" : "0"), 7000);
        if (result != 204 && accepted(target, ticket)) result = 204;
        if (result == 204) {
            publish(destination, target);
            return album == null ? 204 : 200;
        }
        int cancelled = target.peer.changeRole("cancel/" + ticket, 3000);
        if (cancelled != 204 && !accepted(target, ticket)) {
            cancelled = target.peer.changeRole("cancel/" + ticket, 3200);
        }
        if (accepted(target, ticket)) {
            publish(destination, target);
            return album == null ? 204 : 200;
        }
        if (cancelled != 204) return 503;
        if (playing && !playback.setPlaying(true)) return 503;
        return 412;
    }

    private static boolean sameEntry(JSONObject old, JSONObject current) {
        return current != null && old.optString("mediaId").equals(current.optString("mediaId"))
                && old.optString("queueEntry").equals(current.optString("queueEntry"));
    }

    private static boolean accepted(RemoteStore.Device target, String ticket) {
        JSONObject state = target.peer.role();
        return state != null && ticket.equals(state.optString("accepted"));
    }

    private void publish(String destination, RemoteStore.Device target) {
        following = destination;
        output = target;
        playback.follow(target);
    }

    private int stage(String ticket, String id, AlbumHandoff album) {
        expireStage();
        if (!transaction.tryLock()) return 409;
        try {
            if (committed.contains(ticket)) return 409;
            if (pending != null) return pending.matches(ticket, id, album) ? 204 : 409;
            JSONObject current = playback.local();
            if (current != null && current.optBoolean("playing")) return 409;
            int status = playback.stage(id, album);
            if (status == 204) pending = new Stage(ticket, id, album);
            else playback.cancelStage();
            return status;
        } finally {
            transaction.unlock();
        }
    }

    private int commit(String ticket, long position, boolean playing) {
        expireStage();
        if (!transaction.tryLock()) return 409;
        try {
            if (committed.contains(ticket)) return 204;
            Stage stage = pending;
            if (stage == null || !ticket.equals(stage.ticket) || ticket.equals(uncertain)) return 409;
            int status = playback.commit(stage.song, position, playing, stage.album);
            if (status == 204) {
                rememberCommit(ticket);
                pending = null;
                localSelected();
            } else if (status == 503) {
                uncertain = ticket;
                // The source must not resume until we can prove our player is paused.
            }
            return status;
        } finally {
            transaction.unlock();
        }
    }

    private int cancel(String ticket) {
        if (!transaction.tryLock()) return 409;
        try {
            if (committed.contains(ticket)) return 409;
            Stage stage = pending;
            if (stage == null || !ticket.equals(stage.ticket)) return 204;
            if (ticket.equals(uncertain) && !playback.setPlaying(false)) return 409;
            uncertain = "";
            pending = null;
            playback.cancelStage();
            return 204;
        } finally {
            transaction.unlock();
        }
    }

    private int take(String ticket, String id, long position, boolean playing) {
        expireStage();
        if (!transaction.tryLock()) return 409;
        try {
            if (committed.contains(ticket)) return 204;
            if (pending != null) return 409;
            JSONObject current = playback.local();
            if (current != null && current.optBoolean("playing")) return 409;
            int status = playback.take(id, position, playing);
            if (status == 204) {
                rememberCommit(ticket);
                localSelected();
            }
            return status;
        } finally {
            transaction.unlock();
        }
    }

    private void rememberCommit(String ticket) {
        accepted = ticket;
        committed.add(ticket);
        if (committed.size() > 32) committed.remove(committed.iterator().next());
    }

    private static boolean validNode(String value) {
        return value != null && value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
}
