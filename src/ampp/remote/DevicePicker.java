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
        boolean[] canJoin = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] += i == current ? (source == null ? " · 本机模式" : " · 当前播放设备") : " · 检测中…";
        }
        AlertDialog.Builder builder = Dialogs.builder(context);
        ArrayAdapter<String> rows = new ArrayAdapter<String>(builder.getContext(),
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return available[position]; }
        };
        builder
                .setTitle(owner == null ? "播放设备" : "播放设备 · 在 " + owner + " 上播放")
                .setSingleChoiceItems(rows, current, (sheet, index) -> {
                    if (index != checked && !selecting && !closed && available[index]) {
                        sheet.dismiss();
                        RemoteStore.Device device = index == 0 ? null : paired.get(index - 1);
                        if (canJoin[index]) {
                            Context app = context.getApplicationContext();
                            probe.attach(device, message -> toast(app, message));
                        } else {
                            switchTo(context, device);
                        }
                    }
                })
                .setNegativeButton("取消", null);
        if (source != null) {
            builder.setNeutralButton("退出遥控", (sheet, unused) -> returnToLocal(context));
        }
        AlertDialog dialog = Dialogs.show(builder);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            if (closed || !dialog.isShowing()) return;
            worker.execute(() -> {
            for (int i = 0; i < names.length && !Thread.currentThread().isInterrupted(); i++) {
                int index = i;
                RemoteStore.Device candidate = i == 0 ? null : paired.get(i - 1);
                Choice status = i == checked ? null : checkChoice(source, candidate);
                String currentStatus = candidate == null ? "本机模式"
                        : "当前播放设备 · " + candidate.peer.connectionLabel();
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    labels[index] = names[index] + " · " + (status == null ? currentStatus : status.label);
                    available[index] = status != null && status.enabled;
                    canJoin[index] = status == Choice.JOIN;
                    rows.notifyDataSetChanged();
                });
            }
            main.post(() -> {
                if (!closed && dialog.isShowing()) main.postDelayed(refresh[0], 3000);
            });
            });
        };
        dialog.setOnDismissListener(ignored -> main.removeCallbacks(refresh[0]));
        refresh[0].run();
    }

    private enum Choice {
        READY(true, "可接管"), LEGACY(true, "可尝试接管"), LOCAL(true, "退出遥控"),
        JOIN(true, "正在播放 · 可接入遥控"),
        OFFLINE(false, "离线或未开启接收"),
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
        if (target == null && !receiving.getAsBoolean()) return Choice.LOCAL;
        JSONObject targetRole = target == null ? null : target.peer.role();
        if (target != null && targetRole == null) return Choice.OFFLINE;
        if (targetRole != null && targetRole.optBoolean("busy")) return Choice.BUSY;
        if (targetRole != null && targetRole.optBoolean("playing")) return source == null ? Choice.JOIN : Choice.BUSY;
        String targetId = target == null ? node : targetRole.optString("node");
        if (source != null) {
            JSONObject sourceRole = source.peer.role();
            if (sourceRole == null) return target == null ? Choice.LOCAL : Choice.UNKNOWN;
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
            returnToLocal(context);
            return;
        }
        if (source == null && destination == null) return;
        selecting = true;
        Context app = context.getApplicationContext();
        toast(app, "正在交接播放…");
        worker.execute(() -> {
            int status = 503;
            try {
                JSONObject role = destination == null ? null : destination.peer.role();
                if (destination == null || role != null) {
                    String target = destination == null ? node : role.optString("node");
                    if (source == null) {
                        status = roles.move(target);
                    } else if (destination == null && source.peer.role() == null) {
                        status = 0;
                    } else {
                        status = source.peer.changeRole("move/" + target, 46000);
                    }
                }
            } finally {
                int result = status;
                main.post(() -> {
                    selecting = false;
                    if (closed) return;
                    if (source != null && destination == null && result == 0) {
                        if (probe.remoteDevice() == source) returnToLocal(app);
                        else if (probe.remoteDevice() == null) toast(app, "已返回本机播放。");
                    } else {
                        toast(app, message(result, destination));
                    }
                });
            }
        });
    }

    private void returnToLocal(Context context) {
        Context app = context.getApplicationContext();
        probe.detach(message -> toast(app, message));
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
