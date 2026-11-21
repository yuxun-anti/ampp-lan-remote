package ampp.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** NSD only supplies candidate endpoints; the existing credential authenticates every move. */
final class LanDiscovery implements AutoCloseable {
    private static final String TYPE = "_amppremote._tcp.";
    private final NsdManager nsd;
    private final RemoteStore store;
    private final String node;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor verifier = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.DiscardPolicy());
    private final Map<String, NsdServiceInfo> services = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.RegistrationListener registration;
    private boolean resolving;
    private volatile boolean closed;
    private volatile String status = "正在启动局域网发现…";
    private int advertisedPort;
    private String advertisedToken = "";
    private final Runnable refresh = this::refresh;

    LanDiscovery(Context context, RemoteStore store, String node) {
        nsd = context.getSystemService(NsdManager.class);
        this.store = store;
        this.node = node;
        main.post(refresh);
    }

    String status() { return status; }

    void rescan() {
        main.post(() -> {
            if (closed) return;
            queue.clear();
            queue.addAll(services.values());
            resolveNext();
            if (discovery == null) startDiscovery();
        });
    }

    private void refresh() {
        if (closed) return;
        if (nsd == null) {
            status = "系统未提供局域网发现，可继续使用手动配对地址。";
            return;
        }
        if (discovery == null) startDiscovery();
        if (registration == null && advertisedPort > 0) register();
        if (queue.isEmpty()) queue.addAll(services.values());
        resolveNext();
        main.postDelayed(refresh, 10000);
    }

    private void startDiscovery() {
        if (nsd == null || closed) return;
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {
                main.post(() -> { if (!closed) status = "局域网发现已开启 · 自动查找已配对设备"; });
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                main.post(() -> {
                    if (closed || !TYPE.equals(service.getServiceType()) || services.size() >= 32) return;
                    services.put(service.getServiceName(), service);
                    queue.add(service);
                    resolveNext();
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                main.post(() -> {
                    services.remove(service.getServiceName());
                    queue.removeIf(item -> item.getServiceName().equals(service.getServiceName()));
                });
            }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) {
                main.post(() -> {
                    if (discovery == this) discovery = null;
                    if (!closed) status = "局域网发现暂不可用，稍后自动重试。手动地址仍可使用。";
                });
            }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };
        discovery = listener;
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException unavailable) {
            discovery = null;
            status = "局域网发现暂不可用，稍后自动重试。手动地址仍可使用。";
        }
    }

    // Android versions before API 34 permit only one outstanding resolve per app.
    private void resolveNext() {
        if (closed || nsd == null || resolving || queue.isEmpty()) return;
        NsdServiceInfo service = queue.remove();
        resolving = true;
        try {
            nsd.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int error) { finished(null); }
                @Override public void onServiceResolved(NsdServiceInfo info) { finished(info); }
                private void finished(NsdServiceInfo info) {
                    main.post(() -> {
                        resolving = false;
                        if (closed) return;
                        if (info != null) verifier.execute(() -> verify(info));
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException unavailable) {
            resolving = false;
        }
    }

    private static String attribute(NsdServiceInfo info, String key) {
        byte[] value = info.getAttributes().get(key);
        return value == null || value.length > 128 ? "" : new String(value, StandardCharsets.US_ASCII);
    }

    private void verify(NsdServiceInfo info) {
        if (closed || !(info.getHost() instanceof Inet4Address) || !info.getHost().isSiteLocalAddress()) return;
        String identity = attribute(info, "node");
        if (node.equals(identity) || !"1".equals(attribute(info, "v"))) return;
        String fingerprint = attribute(info, "pair");
        for (RemoteStore.Device device : store.devices()) {
            LanPeer paired = device.peer;
            if (closed || !paired.fingerprint().equals(fingerprint)) continue;
            if (!paired.node.isEmpty() && !paired.node.equals(identity)) continue;
            try {
                LanPeer candidate = LanPeer.parse("http://" + info.getHost().getHostAddress() + ":"
                        + info.getPort() + "/#" + paired.credential());
                if (DiscoveryIdentity.accepts(paired.node, identity, candidate.authenticatedIdentity()) && !closed) {
                    store.discovered(paired, candidate, identity);
                }
            } catch (IOException | RuntimeException ignored) {
                // Advertisements never overwrite a working endpoint unless authentication succeeds.
            }
        }
    }

    void advertise(int port, String token) {
        main.post(() -> {
            if (closed || nsd == null) return;
            unregister();
            advertisedPort = port;
            advertisedToken = token;
            if (port > 0) register();
        });
    }

    private void register() {
        if (closed || nsd == null || advertisedPort <= 0) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("AMPP-" + node);
        info.setServiceType(TYPE);
        info.setPort(advertisedPort);
        info.setAttribute("v", "1");
        info.setAttribute("node", node);
        info.setAttribute("pair", DiscoveryIdentity.fingerprint(advertisedToken));
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo service) {
                main.post(() -> {
                    if (closed || registration != this) {
                        try { nsd.unregisterService(this); } catch (RuntimeException ignored) { }
                    }
                });
            }
            @Override public void onRegistrationFailed(NsdServiceInfo service, int error) {
                main.post(() -> { if (registration == this) registration = null; });
            }
            @Override public void onServiceUnregistered(NsdServiceInfo service) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) { }
        };
        registration = listener;
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException unavailable) {
            registration = null;
        }
    }

    private void unregister() {
        NsdManager.RegistrationListener previous = registration;
        registration = null;
        if (previous != null) {
            try { nsd.unregisterService(previous); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        closed = true;
        verifier.shutdownNow();
        main.post(() -> {
            main.removeCallbacks(refresh);
            unregister();
            if (discovery != null) {
                try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
                discovery = null;
            }
            queue.clear();
            services.clear();
        });
    }
}
