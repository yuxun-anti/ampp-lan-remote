package ampp.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.app.AlertDialog;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class DevicePicker implements AutoCloseable {
    private final RemoteStore store;
    private final String node;
    private final RoleHandoff roles;
    private final NativeSessionProbe probe;
    private final BooleanSupplier receiving;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean selecting;
    private boolean closed;

    DevicePicker(RemoteStore store, String node, RoleHandoff roles, NativeSessionProbe probe,
            BooleanSupplier receiving) {
        this.store = store;
        this.node = node;
        this.roles = roles;
        this.probe = probe;
        this.receiving = receiving;
    }

    void show(Context context) {
        if (closed) return;
        if (selecting) {
            toast(context, "正在交接播放…");
            return;
        }
        List<RemoteStore.Device> paired = store.devices();
        RemoteStore.Device source = probe.remoteDevice();
        String owner = probe.remoteName();
        String[] labels = new String[paired.size() + 1];
        labels[0] = "本机";
        int current = source == null ? 0 : -1;
        for (int i = 0; i < paired.size(); i++) {
            RemoteStore.Device device = paired.get(i);
            labels[i + 1] = device.name;
            if (source != null && source.peer.authority.equals(device.peer.authority)
                    && device.name.equals(owner)) current = i + 1;
        }
        String[] names = labels.clone();
        final int checked = current;
        boolean[] available = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] += i == current ? " · 当前播放设备" : " · 检测中…";
        }
        AlertDialog.Builder builder = Dialogs.builder(context);
        ArrayAdapter<String> rows = new ArrayAdapter<String>(builder.getContext(),
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return available[position]; }
        };
        AlertDialog dialog = Dialogs.show(builder
                .setTitle(owner == null ? "播放设备" : "播放设备 · 在 " + owner + " 上播放")
                .setSingleChoiceItems(rows, current, (sheet, index) -> {
                    if (index != checked && !selecting && !closed && available[index]) {
                        sheet.dismiss();
                        switchTo(context, index == 0 ? null : paired.get(index - 1));
                    }
                })
                .setNegativeButton("取消", null));
        worker.execute(() -> {
            for (int i = 0; i < names.length && !Thread.currentThread().isInterrupted(); i++) {
                if (i == checked) continue;
                int index = i;
                RemoteStore.Device candidate = i == 0 ? null : paired.get(i - 1);
                Choice status = checkChoice(source, candidate);
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    labels[index] = names[index] + " · " + status.label;
                    available[index] = status.enabled;
                    rows.notifyDataSetChanged();
                });
            }
        });
    }

    private enum Choice {
        READY(true, "可接管"), LEGACY(true, "可尝试接管"), OFFLINE(false, "离线或未开启接收"),
        UNPAIRED(false, "当前播放设备未配对它"), BUSY(false, "正在播放或交接"),
        EMPTY(false, "没有可交接的曲目"), UNKNOWN(false, "暂无法确认");
        final boolean enabled;
        final String label;
        Choice(boolean enabled, String label) {
            this.enabled = enabled;
            this.label = label;
        }
    }

    private Choice checkChoice(RemoteStore.Device source, RemoteStore.Device target) {
        if (target == null && !receiving.getAsBoolean()) return Choice.OFFLINE;
        JSONObject targetRole = target == null ? null : target.peer.role();
        if (target != null && targetRole == null) return Choice.OFFLINE;
        if (targetRole != null && (targetRole.optBoolean("busy") || targetRole.optBoolean("playing"))) return Choice.BUSY;
        String targetId = target == null ? node : targetRole.optString("node");
        if (source != null) {
            JSONObject sourceRole = source.peer.role();
            if (sourceRole == null) return Choice.UNKNOWN;
            if (!sourceRole.has("staging")) return Choice.LEGACY;
        }
        String route = "check/" + targetId;
        int status = source == null ? roles.command(route) : source.peer.changeRole(route, 4200);
        switch (status) {
            case 204: return Choice.READY;
            case 404: return Choice.UNPAIRED;
            case 409: return Choice.BUSY;
            case 422: return Choice.EMPTY;
            default: return Choice.UNKNOWN;
        }
    }

    private void switchTo(Context context, RemoteStore.Device destination) {
        RemoteStore.Device source = probe.remoteDevice();
        if (source != null && destination == null && !receiving.getAsBoolean()) {
            toast(context, "请先在插件设置里开启本机接收，并在当前播放设备上配对本机。");
            return;
        }
        selecting = true;
        Context app = context.getApplicationContext();
        toast(app, "正在交接播放…");
        worker.execute(() -> {
            int status = 503;
            try {
                JSONObject role = destination == null ? null : destination.peer.role();
                if (destination == null || role != null) {
                    String target = destination == null ? node : role.optString("node");
                    status = source == null ? roles.move(target) : source.peer.changeRole("move/" + target, 46000);
                }
            } finally {
                int result = status;
                main.post(() -> {
                    selecting = false;
                    if (!closed) toast(app, message(result, destination));
                });
            }
        });
    }

    private static String message(int status, RemoteStore.Device destination) {
        switch (status) {
            case 204: return "播放已切换到「" + (destination == null ? "本机" : destination.name) + "」。";
            case 404: return "请确认当前播放设备也已配对目标设备，且目标设备已开启局域网接收。";
            case 409: return "目标暂时无法接管。请确认它当前处于遥控或暂停状态。";
            case 412: return "目标设备未接管，已恢复原播放设备。";
            case 422: return "当前曲目或进度无法在目标设备恢复。";
            default: return "未确认播放交接。请检查两台设备的连接和插件版本。";
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
