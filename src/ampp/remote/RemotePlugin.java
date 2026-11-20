package ampp.remote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import dev.amenhancer.plugin.api.AmppPlugin;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginSettingsSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RemotePlugin extends AmppPlugin {
    private Map<String, byte[]> assets;
    private AndroidPlayer player;
    private LanServer server;
    private RemoteStore store;
    private NativeSessionProbe probe;
    private RoleHandoff roles;
    private DevicePicker picker;
    private PlayerDeviceButton playerButtons;

    @Override public void onLoad(PluginContext context) throws IOException {
        store = new RemoteStore(context.getDataDirectory());
        String node = store.node();
        player = new AndroidPlayer(context.getApplication(), node);
        assets = new LinkedHashMap<>();
        assets.put("/", readAsset(context, "remote.html"));
        assets.put("/remote.css", readAsset(context, "remote.css"));
        assets.put("/remote.js", readAsset(context, "remote.js"));
        if (context.getHostVersionCode() == 1614 && "com.apple.android.music".equals(context.getHostPackageName())) {
            try {
                probe = new NativeSessionProbe(context, store, node);
                player.excludeWhen(probe::connected);
                player.nativeState(probe::sampleLocal);
                player.nativeTransport(probe::transportLocal);
                player.nativeSelection(probe::playLocalCatalogSong);
                player.nativeAlbumSelection(probe::playLocalAlbumSong);
                roles = new RoleHandoff(node, store::devices, probe);
                probe.roles(roles);
                player.transferGate(roles::busy);
                probe.startAutomatic();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                context.log("AM 1614 native session profile unavailable", error);
            }
        }
        if (roles != null) {
            picker = new DevicePicker(store, node, roles, probe, () -> activeUrl() != null);
            try {
                playerButtons = new PlayerDeviceButton(context, picker::show, probe::remoteName);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                context.log("Native playback device button unavailable", unavailable);
            }
        }
        restoreReceiver(context);
        context.onClose(this::onStop);
    }

    private void restoreReceiver(PluginContext context) {
        String savedAddress = store.receiverAddress();
        if (savedAddress.isEmpty()) return;
        try {
            for (Inet4Address address : localAddresses()) {
                if (savedAddress.equals(address.getHostAddress())) {
                    start(address, true);
                    return;
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            context.log("Saved LAN receiver could not be restored", unavailable);
        }
    }

    @Override public void onStop() {
        stopReceiving();
        if (playerButtons != null) playerButtons.close();
        if (picker != null) picker.close();
        if (probe != null) probe.close();
        if (player != null) player.closeArtwork();
    }

    private synchronized void stopReceiving() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override public PluginSettingsSession createSettings(Context context) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);
        ControlPanel controls = new ControlPanel(context, layout, store, player);

        Button reception = new Button(context);
        reception.setText("本机接收与配对地址");
        layout.addView(reception);
        LinearLayout receiver = new LinearLayout(context);
        receiver.setOrientation(LinearLayout.VERTICAL);
        receiver.setVisibility(View.GONE);
        layout.addView(receiver);
        reception.setOnClickListener(view -> receiver.setVisibility(
                receiver.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        TextView instructions = new TextView(context);
        instructions.setTextSize(16);
        instructions.setText("开启接收后，把完整配对地址填到同一局域网内另一台设备的 AM++「添加设备」。请在可信网络使用。");
        receiver.addView(instructions);

        Spinner network = new Spinner(context);
        receiver.addView(network);
        Button toggle = new Button(context);
        receiver.addView(toggle);
        TextView address = new TextView(context);
        address.setTextIsSelectable(true);
        address.setTextSize(14);
        receiver.addView(address);
        Button copy = new Button(context);
        copy.setText("复制配对地址");
        receiver.addView(copy);
        Button reset = new Button(context);
        reset.setText("更换配对码");
        receiver.addView(reset);
        TextView status = new TextView(context);
        receiver.addView(status);
        List<Inet4Address> choices = new ArrayList<>();

        TextView trial = new TextView(context);
        trial.setText(probe == null
                ? "原生会话需要兼容的 Apple Music 7.0.0（1614）。"
                : "对方开启局域网接收后，打开 Apple Music 即可自动接入唯一正在播放的已配对设备。");
        layout.addView(trial);
        Switch automatic = new Switch(context);
        automatic.setText("自动接入已配对设备");
        automatic.setChecked(store.autoJoin());
        automatic.setEnabled(probe != null);
        layout.addView(automatic);
        Button join = new Button(context);
        join.setText("接入所选设备的原生会话");
        join.setEnabled(probe != null);
        layout.addView(join);
        Button leave = new Button(context);
        leave.setText("退出远端会话");
        leave.setEnabled(probe != null);
        layout.addView(leave);
        if (probe != null) {
            if (probe.connected()) trial.setText(probe.report());
            automatic.setOnClickListener(view -> {
                try {
                    store.autoJoin(automatic.isChecked());
                    probe.automaticChanged();
                    trial.setText(automatic.isChecked() ? "已开启自动接入。" : "已关闭自动接入。");
                } catch (IOException error) {
                    automatic.setChecked(store.autoJoin());
                    trial.setText("保存自动接入设置失败。");
                }
            });
            join.setOnClickListener(view -> {
                RemoteStore.Device endpoint = null;
                for (RemoteStore.Device device : store.devices()) {
                    if (device.peer.authority.equals(store.selected())) endpoint = device;
                }
                if (endpoint == null) {
                    trial.setText("请先选中已配对的设备。");
                    return;
                }
                trial.setText("正在读取设备的播放状态…");
                probe.attach(endpoint, trial::setText);
            });
            leave.setOnClickListener(view -> probe.detach(trial::setText));
        }

        Runnable refresh = () -> {
            try {
                choices.clear();
                choices.addAll(localAddresses());
                List<String> labels = new ArrayList<>();
                for (Inet4Address candidate : choices) labels.add(candidate.getHostAddress());
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        context, android.R.layout.simple_spinner_item, labels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                network.setAdapter(adapter);
                if (choices.isEmpty()) status.setText("未找到局域网 IPv4 地址。连上 Wi-Fi 后重新打开设置页。");
            } catch (IOException | RuntimeException error) {
                status.setText("读取局域网地址失败。");
            }
        };
        Runnable render = () -> {
            String url = activeUrl();
            network.setVisibility(url == null ? View.VISIBLE : View.GONE);
            address.setVisibility(url == null ? View.GONE : View.VISIBLE);
            copy.setVisibility(url == null ? View.GONE : View.VISIBLE);
            reset.setVisibility(url == null ? View.GONE : View.VISIBLE);
            toggle.setText(url == null ? "开启局域网接收" : "停止局域网接收");
            address.setText(url);
        };
        refresh.run();
        render.run();

        toggle.setOnClickListener(view -> {
            status.setText("");
            if (activeUrl() != null) {
                try {
                    store.receiverAddress("");
                    stopReceiving();
                    refresh.run();
                } catch (IOException error) {
                    status.setText("保存关闭设置失败。");
                }
            } else {
                int selected = network.getSelectedItemPosition();
                if (selected < 0 || selected >= choices.size()) {
                    refresh.run();
                    return;
                }
                try {
                    start(choices.get(selected), false);
                    status.setText("接收已开启。仅要遥控其他设备时，可以关闭本机接收。");
                } catch (IOException | RuntimeException error) {
                    status.setText("开启失败。请确认 Wi-Fi 已连接，再重新打开设置页。");
                }
            }
            render.run();
        });
        copy.setOnClickListener(view -> {
            String url = activeUrl();
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (url != null && clipboard != null) {
                ClipData clip = ClipData.newPlainText("AM++ 局域网遥控", url);
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
                clipboard.setPrimaryClip(clip);
            }
        });

        reset.setOnClickListener(view -> Dialogs.show(Dialogs.builder(context)
                .setTitle("更换配对码？")
                .setMessage("其他设备上保存的旧地址会失效。")
                .setNegativeButton("取消", null)
                .setPositiveButton("更换", (dialog, which) -> {
                    try {
                        store.receivers(store.port(), LanServer.newToken(), "");
                        stopReceiving();
                        status.setText("已更换配对码。重新开启接收后可复制新地址。");
                        refresh.run();
                        render.run();
                    } catch (IOException error) {
                        status.setText("配对码保存失败。");
                    }
                })));

        return new PluginSettingsSession() {
            @Override public View getView() { return scroll; }
            @Override public void close() {
                controls.close();
                automatic.setOnClickListener(null);
                join.setOnClickListener(null);
                leave.setOnClickListener(null);
                reception.setOnClickListener(null);
                reset.setOnClickListener(null);
                toggle.setOnClickListener(null);
                copy.setOnClickListener(null);
            }
        };
    }

    private synchronized void start(Inet4Address bind, boolean restoring) throws IOException {
        if (activeUrl() != null) return;
        String token = store.token();
        if (!token.matches("[A-Za-z0-9_-]{32}")) token = LanServer.newToken();
        LanServer started;
        try {
            started = new LanServer(bind, store.port(), token, player, assets, roles);
        } catch (java.net.BindException busy) {
            if (restoring || store.port() == 0) throw busy;
            started = new LanServer(bind, 0, token, player, assets, roles);
        }
        try {
            store.receivers(started.port(), token, bind.getHostAddress());
            server = started;
        } catch (IOException failure) {
            started.close();
            throw failure;
        }
    }

    private synchronized String activeUrl() {
        return server == null ? null : server.url();
    }

    private static List<Inet4Address> localAddresses() throws IOException {
        List<Inet4Address> result = new ArrayList<>();
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        interfaces.sort(Comparator.comparing(item -> !item.getName().startsWith("wlan")));
        for (NetworkInterface network : interfaces) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    Inet4Address candidate = (Inet4Address) address;
                    if (!result.contains(candidate)) result.add(candidate);
                }
            }
        }
        return result;
    }

    private static byte[] readAsset(PluginContext context, String asset) throws IOException {
        try (InputStream in = context.openAsset(asset); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
