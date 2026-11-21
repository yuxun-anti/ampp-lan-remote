package ampp.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONException;
import org.json.JSONObject;

final class ControlPanel implements AutoCloseable {
    private final Context context;
    private final RemoteStore store;
    private final LanServer.Player local;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private final Button target;
    private final Button previous;
    private final Button toggle;
    private final Button next;
    private final SeekBar progress;
    private final TextView position;
    private final TextView title;
    private final TextView details;
    private final TextView status;
    private final Button forget;
    private final List<RemoteStore.Device> devices;
    private AlertDialog dialog;
    private Future<?> poll;
    private RemoteStore.Device selected;
    private JSONObject state;
    private JSONObject seekStart;
    private boolean dragging;
    private boolean pending;
    private boolean closed;
    private int generation;
    private String commandNotice = "";

    ControlPanel(Context context, LinearLayout layout, RemoteStore store, LanServer.Player local) {
        this.context = context;
        this.store = store;
        this.local = local;
        devices = store.devices();
        for (RemoteStore.Device item : devices) {
            if (item.peer.authority.equals(store.selected())) selected = item;
        }

        target = new Button(context);
        layout.addView(target);
        target.setOnClickListener(view -> chooseDevice());

        LinearLayout edit = row(context, layout);
        Button add = action(edit, "添加设备");
        add.setOnClickListener(view -> addDevice());
        forget = action(edit, "移除设备");
        forget.setOnClickListener(view -> forgetDevice());

        title = label(layout, 23);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        details = label(layout, 15);

        progress = new SeekBar(context);
        progress.setMax(1000);
        progress.setContentDescription("播放进度");
        layout.addView(progress);
        position = label(layout, 13);

        LinearLayout playback = row(context, layout);
        previous = action(playback, "上一首");
        toggle = action(playback, "播放");
        next = action(playback, "下一首");
        status = label(layout, 13);
        status.setMinLines(2);

        previous.setOnClickListener(view -> command("previous", -1));
        toggle.setOnClickListener(view -> command(playing() ? "pause" : "play", -1));
        next.setOnClickListener(view -> command("next", -1));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                seekStart = state;
            }

            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || state == null) return;
                long duration = state.optLong("duration", 0);
                long desired = (long) (duration * (value / 1000.0));
                position.setText(time(desired) + " / " + time(duration));
                if (!dragging && progress.isEnabled()) command("seek", desired);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                JSONObject before = seekStart;
                seekStart = null;
                if (state == null || before == null || !progress.isEnabled()) return;
                long duration = state.optLong("duration", 0);
                if (duration == before.optLong("duration", 0)
                        && state.optString("title").equals(before.optString("title"))) {
                    command("seek", (long) (duration * (bar.getProgress() / 1000.0)));
                } else {
                    render(state, "曲目已切换");
                }
            }
        });

        showSelected();
    }

    private void chooseDevice() {
        String[] items = new String[devices.size() + 1];
        items[0] = "本机";
        for (int i = 0; i < devices.size(); i++) {
            RemoteStore.Device item = devices.get(i);
            items[i + 1] = item.name + " · " + item.peer.connectionLabel() + " · " + item.peer.authority;
        }
        dialog = Dialogs.show(Dialogs.builder(context)
                .setTitle("选择要控制的设备")
                .setSingleChoiceItems(items, devices.indexOf(selected) + 1, (sheet, which) -> {
                    RemoteStore.Device device = which == 0 ? null : devices.get(which - 1);
                    if (save(devices, device)) {
                        sheet.dismiss();
                        showSelected();
                    }
                })
                .setNegativeButton("取消", null));
    }

    private void addDevice() {
        AlertDialog.Builder builder = Dialogs.builder(context);
        Context dialogContext = builder.getContext();
        LinearLayout inputs = new LinearLayout(dialogContext);
        inputs.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        inputs.setPadding(padding, padding / 2, padding, 0);
        EditText name = new EditText(dialogContext);
        name.setHint("设备名称（例如 S9 Ultra）");
        name.setSingleLine();
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
        inputs.addView(name);
        EditText address = new EditText(dialogContext);
        address.setHint("粘贴完整配对地址");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine();
        address.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        inputs.addView(address);

        dialog = Dialogs.show(builder
                .setTitle("添加播放设备")
                .setView(inputs)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null));
        AlertDialog pairing = dialog;
        pairing.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            LanPeer peer;
            try {
                peer = store.pairing(LanPeer.parse(address.getText().toString()));
            } catch (IOException invalid) {
                address.setError("请输入接收设备显示的完整地址，包含端口和 # 后的配对码");
                return;
            }
            String label = name.getText().toString().trim();
            RemoteStore.Device joined = new RemoteStore.Device(label.isEmpty() ? peer.authority : label, peer);
            List<RemoteStore.Device> saved = new ArrayList<>(devices);
            int existing = -1;
            for (int i = 0; i < saved.size(); i++) {
                RemoteStore.Device device = saved.get(i);
                if (device.peer.authority.equals(peer.authority)) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                saved.set(existing, joined);
            } else if (saved.size() < 24) {
                saved.add(joined);
            } else {
                address.setError("设备列表已满，请先移除一台");
                return;
            }
            if (save(saved, joined)) {
                pairing.dismiss();
                showSelected();
            } else {
                address.setError("保存失败");
            }
        });
    }

    private void forgetDevice() {
        if (selected == null) return;
        RemoteStore.Device device = selected;
        dialog = Dialogs.show(Dialogs.builder(context)
                .setTitle("移除「" + device.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (sheet, which) -> {
                    List<RemoteStore.Device> saved = new ArrayList<>(devices);
                    saved.remove(device);
                    if (save(saved, null)) showSelected();
                }));
    }

    private boolean save(List<RemoteStore.Device> saved, RemoteStore.Device device) {
        try {
            store.devices(saved, device == null ? "" : device.peer.authority);
        } catch (IOException error) {
            status.setText("设备列表保存失败");
            return false;
        }
        if (saved != devices) {
            devices.clear();
            devices.addAll(saved);
        }
        selected = device;
        return true;
    }

    private void showSelected() {
        if (poll != null) poll.cancel(false);
        generation++;
        pending = false;
        dragging = false;
        seekStart = null;
        commandNotice = "";
        String name = selected == null ? "本机" : selected.name;
        target.setText("正在控制 · " + name + " ▾");
        forget.setEnabled(selected != null);
        render(null, "正在连接" + name + "…");
        planRefresh(0);
    }

    private void planRefresh(long delay) {
        ui.removeCallbacks(refresh);
        if (!closed) ui.postDelayed(refresh, delay);
    }

    private void refresh() {
        LanServer.Player player = activePlayer();
        int current = generation;
        poll = worker.submit(() -> {
            JSONObject snapshot = null;
            String message;
            try {
                snapshot = new JSONObject(player.state());
                message = snapshot.optBoolean("ready") ? "已连接" : "请在所选设备上开始播放";
            } catch (LanPeer.Unavailable unavailable) {
                message = unavailable.status == 401
                        ? "配对码已失效。请用新地址再次添加设备"
                        : "设备离线。请确认网络连接和接收开关";
            } catch (JSONException | RuntimeException unavailable) {
                message = "暂时无法读取播放状态";
            }
            final JSONObject value = snapshot;
            final String label = message;
            ui.post(() -> {
                if (closed || current != generation) return;
                render(value, commandNotice.isEmpty() ? label : commandNotice);
                commandNotice = "";
                planRefresh(1200);
            });
        });
    }

    private LanServer.Player activePlayer() {
        return selected == null ? local : selected.peer;
    }

    private void command(String action, long at) {
        if (pending || state == null || !state.optBoolean("ready")) return;
        pending = true;
        if (poll != null) poll.cancel(false);
        ui.removeCallbacks(refresh);
        LanServer.Player player = activePlayer();
        int current = ++generation;
        worker.execute(() -> {
            int reply;
            try {
                reply = player.command(action, at);
            } catch (RuntimeException error) {
                reply = 0;
            }
            final int result = reply;
            ui.post(() -> {
                if (closed || current != generation) return;
                pending = false;
                if (result != 204) {
                    commandNotice = result == 401 ? "配对码已失效"
                            : result == 409 ? "当前播放状态暂不支持此操作" : "设备未接受此操作";
                }
                planRefresh(180);
            });
        });
    }

    private void render(JSONObject snapshot, String message) {
        state = snapshot;
        boolean ready = snapshot != null && snapshot.optBoolean("ready");
        long actions = ready ? snapshot.optLong("actions", 0) : 0;
        long at = ready ? snapshot.optLong("position", -1) : -1;
        long duration = ready ? snapshot.optLong("duration", -1) : -1;
        String song = ready ? snapshot.optString("title", "") : "";
        title.setText(!ready ? "等待播放" : song.isEmpty() ? "正在播放的曲目" : song);
        String artist = ready ? snapshot.optString("artist", "") : "";
        String album = ready ? snapshot.optString("album", "") : "";
        String subtitle = artist.isEmpty() || album.isEmpty() ? artist + album : artist + " · " + album;
        details.setText(subtitle);
        details.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        toggle.setText(playing() ? "暂停" : "播放");
        toggle.setEnabled((actions & (PlaybackState.ACTION_PLAY_PAUSE
                | (playing() ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0);
        previous.setEnabled((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        next.setEnabled((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        progress.setEnabled(duration > 0 && at >= 0 && (actions & PlaybackState.ACTION_SEEK_TO) != 0);
        if (!dragging || !progress.isEnabled()) {
            progress.setProgress(duration <= 0 || at <= 0 ? 0
                    : (int) (1000 * Math.min(1.0, at / (double) duration)));
            position.setText(time(at) + " / " + time(duration));
        }
        status.setText(message);
    }

    private boolean playing() {
        return state != null && state.optBoolean("playing");
    }

    private static String time(long milliseconds) {
        if (milliseconds < 0) return "–:––";
        long seconds = milliseconds / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static LinearLayout row(Context context, LinearLayout layout) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private TextView label(LinearLayout layout, int size) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        layout.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button action(LinearLayout row, String label) {
        Button button = new Button(context);
        button.setText(label);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    @Override public void close() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
    }
}
