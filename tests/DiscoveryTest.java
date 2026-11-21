package ampp.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.FutureTask;

/** Real HTTP and persistence tests; Android NSD callbacks require on-device validation. */
public final class DiscoveryTest {
    public static void main(String[] args) throws Exception {
        String token = LanServer.newToken();
        String node = UUID.randomUUID().toString();
        check(!DiscoveryIdentity.fingerprint(token).contains(token), "Advertisements contain no credential");
        check(!DiscoveryIdentity.fingerprint(token).equals(DiscoveryIdentity.fingerprint(LanServer.newToken())), "Distinct pairings");
        check(!DiscoveryIdentity.accepts(node, UUID.randomUUID().toString(), node), "Advertised identity must match");
        check(!DiscoveryIdentity.accepts(UUID.randomUUID().toString(), node, node), "Never replace a pinned identity");
        check(DiscoveryIdentity.accepts("", node, node), "Upgrade old pairing without resetting it");
        check(!DiscoveryIdentity.proof(token, "a", node, "192.168.1.1:1")
                .equals(DiscoveryIdentity.proof(token, "a", node, "192.168.1.2:1")), "Bind proof to endpoint");

        Inet4Address local = (Inet4Address) InetAddress.getByName("127.0.0.1");
        LanServer.Player player = new LanServer.Player() {
            public String state() { return "{\"ready\":true,\"playing\":false}"; }
            public int command(String command, long at) { return 204; }
        };
        java.io.File directory = Files.createTempDirectory("ampp-discovery-test").toFile();
        RemoteStore store = new RemoteStore(directory);
        LanPeer original;
        try (LanServer old = new LanServer(local, 0, token, player, Collections.emptyMap())) {
            original = loopbackPeer(old.url());
            check(original.state().contains("ready"), "Original endpoint works");
        }
        LanPeer heldBySession = original;
        try (LanServer next = new LanServer(local, 0, token, player, Collections.emptyMap())) {
            next.identity(node);
            LanPeer candidate = loopbackPeer(next.url());
            check(node.equals(candidate.authenticatedIdentity()), "Authenticate new endpoint without bearer token");
            LanPeer wrongCode = loopbackPeer(next.url().split("#")[0] + "#" + LanServer.newToken());
            check(wrongCode.authenticatedIdentity().isEmpty(), "Reject endpoint with a different pairing code");
            original.relocate(candidate, node);
            check(heldBySession.address.equals(candidate.address), "Live session follows changed endpoint");
            check(heldBySession.state().contains("ready"), "Live session reaches new HTTP server");
        }
        LanPeer paired = LanPeer.parse("http://192.168.1.2:12345/#" + token);
        store.devices(Collections.singletonList(new RemoteStore.Device("tablet", paired)), paired.authority);
        check(store.devices().get(0).peer == paired, "Keep the peer used by an existing session");
        LanPeer changed = LanPeer.parse("http://192.168.2.3:23456/#" + token);
        check(store.discovered(paired, changed, node), "Accept authenticated migration");
        check(paired.address.equals(changed.address), "Update held peer address");
        check(store.selected().equals(changed.authority), "Keep selected device after address change");
        check(store.devices().get(0).peer == paired, "Future lookups share migrated peer");
        LanPeer restored = new RemoteStore(directory).devices().get(0).peer;
        check(restored.address.equals(changed.address) && restored.node.equals(node), "Persist address and identity across restart");
        check(!store.discovered(paired, changed, UUID.randomUUID().toString()), "Reject changed pinned node");
        store.devices(Collections.emptyList(), "");
        check(!store.discovered(paired, changed, node), "Discovery must not re-add forgotten devices");

        try (ServerSocket rogue = new ServerSocket(0, 1, local)) {
            FutureTask<String> received = new FutureTask<>(() -> {
                try (Socket socket = rogue.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder request = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                    byte[] body = "{\"node\":\"fake\",\"proof\":\"wrong\"}".getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    return request.toString();
                }
            });
            new Thread(received).start();
            LanPeer fake = loopbackPeer("http://" + local.getHostAddress() + ":" + rogue.getLocalPort() + "/#" + token);
            check(fake.authenticatedIdentity().isEmpty(), "Reject forged proof");
            String request = received.get();
            check(!request.contains(token) && !request.contains("Authorization"), "Never disclose token to a rogue discovery endpoint");
        }
        System.out.println("Discovery authentication, endpoint migration, live session, selection, removal: PASS");
    }

    private static LanPeer loopbackPeer(String url) throws Exception {
        // Production parsing continues to reject loopback. This test-only constructor permits local HTTP tests.
        java.lang.reflect.Constructor<LanPeer> constructor = LanPeer.class.getDeclaredConstructor(
                java.net.URI.class, InetAddress.class);
        constructor.setAccessible(true);
        return constructor.newInstance(java.net.URI.create(url), InetAddress.getByName("127.0.0.1"));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
