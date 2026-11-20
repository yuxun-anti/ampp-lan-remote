package ampp.remote;

import java.util.List;
import org.json.JSONObject;

final class PairedPlayback {
    final RemoteStore.Device device;
    final JSONObject state;

    private PairedPlayback(RemoteStore.Device device, JSONObject state) {
        this.device = device;
        this.state = state;
    }

    static PairedPlayback playing(List<RemoteStore.Device> devices, String localNode) {
        PairedPlayback found = null;
        for (RemoteStore.Device device : devices) {
            if (Thread.currentThread().isInterrupted()) return null;
            JSONObject state = read(device);
            if (!followable(state)) continue;
            String node = state.optString("node");
            if (node.isEmpty() || node.equals(localNode)) continue;
            if (found == null) {
                found = new PairedPlayback(device, state);
            } else if (!node.equals(found.state.optString("node"))) {
                return null;
            } else if (found.state.has("following") && !state.has("following")) {
                found = new PairedPlayback(device, state);
            }
        }
        return found;
    }

    static boolean hasTrack(JSONObject state) {
        return state != null && !state.optString("title").isEmpty();
    }

    static boolean followable(JSONObject state) {
        return hasTrack(state) && (state.optBoolean("playing") || !state.optString("following").isEmpty());
    }

    static JSONObject read(RemoteStore.Device device) {
        JSONObject state = readRaw(device);
        return state != null && state.optBoolean("ready") ? state : null;
    }

    static JSONObject readRaw(RemoteStore.Device device) {
        try {
            return new JSONObject(device.peer.state());
        } catch (Exception unavailable) {
            return null;
        }
    }
}
