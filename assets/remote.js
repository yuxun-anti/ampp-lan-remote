"use strict";

const byId = id => document.getElementById(id);
const title = byId("title");
const details = byId("details");
const progress = byId("progress");
const elapsed = byId("elapsed");
const duration = byId("duration");
const previous = byId("previous");
const next = byId("next");
const toggle = byId("toggle");
const status = byId("status");
const token = location.hash.slice(1);
const validToken = /^[A-Za-z0-9_-]{32}$/.test(token);
let state = null;
let dragging = false;
let sending = false;
let generation = 0;
let timer;

function time(ms) {
  if (ms < 0) return "–:––";
  const seconds = Math.floor(ms / 1000);
  return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
}

function render(record, message = "") {
  state = record;
  const ready = Boolean(record?.ready);
  const flags = ready ? record.actions : 0;
  const playing = ready && record.playing;
  title.textContent = ready ? record.title || "正在播放的曲目" : "等待播放";
  details.textContent = ready ? [record.artist, record.album].filter(Boolean).join(" · ") : "";
  toggle.textContent = playing ? "暂停" : "播放";
  toggle.disabled = !(flags & (512 | (playing ? 2 : 4)));
  previous.disabled = !(flags & 16);
  next.disabled = !(flags & 32);
  progress.disabled = !(flags & 256) || record.duration <= 0 || record.position < 0;
  if (!dragging || progress.disabled) {
    dragging = false;
    progress.max = ready && record.duration > 0 ? record.duration : 1;
    progress.value = ready && record.position >= 0 ? record.position : 0;
    elapsed.textContent = time(ready ? record.position : -1);
  }
  duration.textContent = time(ready && record.duration > 0 ? record.duration : -1);
  status.textContent = message || (ready ? "已连接" : "请在接收设备上打开 Apple Music 并开始播放。");
}

async function request(path, method) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3500);
  try {
    const reply = await fetch(path, {
      method, headers: {Authorization: "Bearer " + token}, signal: controller.signal
    });
    if (reply.status === 401) throw new Error("地址已失效。请复制接收设备上的新地址。");
    if (reply.status === 409) throw new Error("当前曲目或播放状态暂不支持此操作。");
    if (!reply.ok) throw new Error("接收设备暂时无法处理请求。");
    return method === "GET" ? await reply.json() : null;
  } catch (error) {
    if (error instanceof TypeError || error.name === "AbortError") {
      throw new Error("连接中断。请确认两台设备在同一 Wi-Fi 下，且接收仍在运行。");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function schedule(delay, notice = "") {
  clearTimeout(timer);
  const current = ++generation;
  timer = setTimeout(async () => {
    try {
      const record = await request("/api/state", "GET");
      if (current === generation) render(record, notice);
    } catch (error) {
      if (current === generation) render(null, error.message);
    } finally {
      if (current === generation) schedule(1500);
    }
  }, delay);
}

async function command(name) {
  if (sending || !state?.ready) return;
  sending = true;
  clearTimeout(timer);
  generation++;
  let notice = "";
  try {
    await request("/api/" + name, "POST");
  } catch (error) {
    notice = error.message;
  } finally {
    sending = false;
    schedule(220, notice);
  }
}

toggle.onclick = () => command(state?.playing ? "pause" : "play");
previous.onclick = () => command("previous");
next.onclick = () => command("next");
progress.oninput = () => {
  dragging = true;
  elapsed.textContent = time(Number(progress.value));
};
progress.onchange = () => {
  dragging = false;
  command("seek/" + progress.value);
};
progress.onpointercancel = () => { dragging = false; };
progress.onblur = () => { dragging = false; };

if (validToken) schedule(0);
else render(null, "请打开包含配对码的完整遥控地址。");
