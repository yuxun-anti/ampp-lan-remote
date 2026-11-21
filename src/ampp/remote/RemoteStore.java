package ampp.remote;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RemoteStore {
    private final AtomicFile file;
    private volatile JSONObject values;
    private final java.util.Map<String, LanPeer> peers = new java.util.HashMap<>();

    RemoteStore(File directory) {
        file = new AtomicFile(new File(directory, "devices.json"));
        try {
            values = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException error) {
            values = new JSONObject();
        }
    }

    int port() { return values.optInt("port", 0); }
    String token() { return values.optString("token", ""); }
    String selected() { return values.optString("selected", ""); }
    boolean autoJoin() { return values.optBoolean("autoJoin", true); }
    String receiverAddress() { return values.optString("receiverAddress", ""); }

    synchronized void autoJoin(boolean enabled) throws IOException {
        set("autoJoin", enabled);
    }

    synchronized void receiverAddress(String address) throws IOException {
        set("receiverAddress", address);
    }

    private void set(String key, Object value) throws IOException {
        try {
            JSONObject updated = copy();
            updated.put(key, value);
            write(updated);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    synchronized List<Device> devices() {
        List<Device> result = new ArrayList<>();
        JSONArray array = values.optJSONArray("devices");
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            try {
                LanPeer peer = LanPeer.parse(item.getString("address"));
                LanPeer shared = peers.get(peer.credential());
                if (shared == null) {
                    peer.node = item.optString("node");
                    peers.put(peer.credential(), peer);
                } else peer = shared;
                result.add(new Device(item.getString("name"), peer));
            } catch (IOException | JSONException invalid) {
                // Ignore a malformed saved entry.
            }
        }
        return result;
    }

    synchronized void receivers(int port, String token, String address) throws IOException {
        try {
            JSONObject updated = copy();
            updated.put("port", port);
            updated.put("token", token);
            updated.put("receiverAddress", address);
            write(updated);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    synchronized void devices(List<Device> devices, String selected) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (Device device : devices) {
                JSONObject item = new JSONObject();
                item.put("name", device.name);
                item.put("address", device.peer.address);
                item.put("node", device.peer.node);
                array.put(item);
            }
            JSONObject updated = copy();
            updated.put("devices", array);
            updated.put("selected", selected);
            write(updated);
            java.util.Set<String> retained = new java.util.HashSet<>();
            for (Device device : devices) {
                retained.add(device.peer.credential());
                if (!peers.containsKey(device.peer.credential())) peers.put(device.peer.credential(), device.peer);
            }
            peers.keySet().retainAll(retained);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    synchronized LanPeer pairing(LanPeer candidate) {
        LanPeer current = peers.get(candidate.credential());
        if (current == null) {
            peers.put(candidate.credential(), candidate);
            return candidate;
        }
        if (!current.address.equals(candidate.address)) current.relocate(candidate, current.node);
        return current;
    }

    synchronized boolean discovered(LanPeer paired, LanPeer candidate, String node) throws IOException {
        List<Device> devices = devices();
        LanPeer current = null;
        for (Device device : devices) {
            if (device.peer.credential().equals(paired.credential())) current = device.peer;
        }
        if (current == null || !DiscoveryIdentity.accepts(current.node, node, node)) return false;
        if (current.address.equals(candidate.address) && current.node.equals(node)) return true;
        String selection = selected();
        boolean selected = current.authority.equals(selection);
        String oldAddress = current.address;
        String oldNode = current.node;
        current.relocate(candidate, node);
        try {
            devices(devices, selected ? current.authority : selection);
        } catch (IOException error) {
            current.relocate(LanPeer.parse(oldAddress), oldNode);
            throw error;
        }
        return true;
    }

    synchronized String node() throws IOException {
        String node = values.optString("node");
        if (!node.isEmpty()) return node;
        node = java.util.UUID.randomUUID().toString();
        try {
            JSONObject updated = copy();
            updated.put("node", node);
            write(updated);
        } catch (JSONException error) {
            throw new IOException(error);
        }
        return node;
    }

    private JSONObject copy() throws JSONException {
        return new JSONObject(values.toString());
    }

    private void write(JSONObject updated) throws IOException {
        FileOutputStream out = file.startWrite();
        try {
            out.write(updated.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            file.failWrite(out);
            throw failure;
        }
        file.finishWrite(out);
        values = updated;
    }

    static final class Device {
        final String name;
        final LanPeer peer;
        Device(String name, LanPeer peer) {
            this.name = name;
            this.peer = peer;
        }
    }
}
