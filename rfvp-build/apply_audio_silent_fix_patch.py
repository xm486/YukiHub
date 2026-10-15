#!/usr/bin/env python3
"""YukiHub 补丁：`SoundSilentOn` / `AudioSilentOn` 不应真的静音（修语音被吞）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `be92e540`（"fix audio"，2026-10-15）中
`audio_player/channels.rs` + 两个 player 的语义修正。上游把 SE/BGM 的公共
槽位逻辑重构成了 `channels.rs`，我们**不跟那个重构**，只把语义改对。

背景
----
上游新版 `channels.rs` 原文（直接点了我们这款游戏的名）：

    /// `AudioSilentOn` / `SoundSilentOn`. It does not mute the slot: scripts call it on a slot
    /// while its sound plays (Sakura, on the voice slot at every line), and that sound is heard
    /// in the original engine.
    pub fn silent_on(&mut self, _slot: i32, _tween: Tween) {}

而我们基线（10.09）是**真的静音**：
    pub fn silent_on(&mut self, slot: i32, tween: Tween) {
        self.se_muted[slot] = true;
        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_volume(0.0, tween);      // <- 音量拉到 0
        }
    }
并且 `se_muted` 还参与后续所有音量计算：
    let actual_volume = if self.se_muted[slot] { 0.0 } else { ... };

危害
----
《樱花，萌放。》每句台词都会对语音槽调 `SoundSilentOn`。在基线实现下，
**语音会被永久静音**（槽一旦 muted 就一直为真，直到读档重置）。
症状就是「对话语音不对劲 / 像是没声音或只剩背景音」。

附注（本轮实测 · 2026-10-15）
----
曾注入一条临时探针（`YHPROBE SilentOn`）确认脚本的调用事实，日志结果：

    YHPROBE SilentOn slot=3     <- 11 次 / 3.5 分钟，**全部来自 bgm_player**
    （se_player 一次都没有）

即：脚本只对 **BGM 槽 3** 调它，**从不碰语音槽** —— 这解释了
「对话语音一直正常」的现象，也说明本补丁改的是 BGM 那边。
探针**已移除**（CI 现在会在产物里发现 YHPROBE 时报错）。

> 以后若需重新诊断：把 `PROBE_ON` 置为 `True` 即可（见下方常量），
> 或参考 git 历史里的旧版本。

改动
----
`se_player.rs` / `bgm_player.rs` 各三处：
  1. `silent_on` -> 空操作（对齐原引擎 10.15）
  2. 4 处音量计算的 `if muted { 0.0 } else { ... }` -> 直接用有效音量
  3. `apply_snapshot_v1` 恢复存档时忽略 `muted`（否则旧存档会把静音带回来）
"""
import re
import sys
from pathlib import Path

SE = "crates/rfvp/src/audio_player/se_player.rs"
BGM = "crates/rfvp/src/audio_player/bgm_player.rs"

# 音量计算：把 `if muted { 0.0 } else { X };` 收成 `X;`
#
# 注意 rustfmt 的缩进：`0.0` 与 `self.effective_...` 比 `if` / `} else {` / `};`
# **多缩进 4 空格**，而整体缩进又随所在函数/循环变化（8 或 16）。
# 所以用命名组 `i` 抓住起始缩进，其余行都以它为基准。
VOL_TMPL = (
    r"(?P<i>[ \t]*)let actual_volume = if self\.{field}\[slot\] \{{\n"
    r"(?P=i) {{4}}0\.0\n"
    r"(?P=i)\}} else \{{\n"
    r"(?P=i) {{4}}self\.effective_volume_for_slot\(slot\)\n"
    r"(?P=i)\}};"
)
VOL_NEW = r"\g<i>let actual_volume = self.effective_volume_for_slot(slot);"

# 诊断开关：置 True 会在 silent_on 里加一行 YHPROBE（缩进 8 空格，插在函数体首行）。
# ⚠️ 开着它 CI 的「无探针」校验会失败 —— 只在本机诊断时临时打开。
PROBE_ON = False
PROBE = '        log::info!("YHPROBE SilentOn slot={}", slot);\n' if PROBE_ON else ""


def silent_on_block(prefix: str, field: str) -> "tuple[str, str]":
    old = (
        f"    /// Permanently silence a slot. This mirrors the engine's `{prefix}SilentOn` semantics.\n"
        f"    pub fn silent_on(&mut self, slot: i32, tween: Tween) {{\n"
        f"        let slot = slot as usize;\n"
        f"        self.{field}[slot] = true;\n"
        f"\n"
        f"        if let Some(handle) = self.{field.replace('_muted', '_slots')}[slot].as_mut() {{\n"
        f"            handle.set_volume(0.0, tween);\n"
        f"        }}\n"
        f"    }}\n"
    )
    new = (
        f"    /// `{prefix}SilentOn` does **not** mute in the original engine: scripts call it on a\n"
        f"    /// slot while its sound plays (Sakura, on the voice slot at every line) and that sound\n"
        f"    /// is still heard. YukiHub: keep the call, drop the mute.\n"
        f"    pub fn silent_on(&mut self, slot: i32, tween: Tween) {{\n"
        f"{PROBE}"
        f"        let _ = (slot, tween);\n"
        f"    }}\n"
    )
    return old, new


def restore_anchor(field: str, snap_field: str) -> "tuple[str, str]":
    old = f"            self.{field}[slot] = s.{snap_field};\n"
    new = (
        f"            // `muted` from an older save is ignored: it would re-silence the slot.\n"
        f"            self.{field}[slot] = false;\n"
    )
    return old, new


def patch(path: Path, prefix: str, field: str, slots_field: str) -> int:
    text = path.read_text(encoding="utf-8")

    # 幂等判断：真实标志是「silent_on 已变成空操作」。
    # ⚠️ 不能用 YHPROBE 判断 —— 探针已默认关闭（PROBE_ON=False），
    #    用它会永远判为「未应用」，导致每次构建都重跑（并在锚点已消失时误报 FAIL）。
    already = (
        "does **not** mute in the original engine" in text
        and f"self.{field}[slot] = s.muted;\n" not in text
    )
    if already:
        print(f"[OK]   {path.name}: 已应用（幂等）")
        return 0

    # 1. silent_on -> 空操作
    old, new = silent_on_block(prefix, field)
    n = text.count(old)
    if n != 1:
        print(f"[FAIL] {path.name}: silent_on 锚点命中 {n} 次（期望 1）—— 上游源码已变动")
        return 1
    text = text.replace(old, new, 1)

    # 2. 音量计算去掉 muted 分支
    vol_re = re.compile(VOL_TMPL.format(field=field))
    hits = vol_re.findall(text)
    if len(hits) != 4:
        print(f"[FAIL] {path.name}: 音量计算锚点命中 {len(hits)} 处（期望 4）")
        return 1
    text = vol_re.sub(VOL_NEW, text)

    # 3. 恢复存档时忽略 muted
    old, new = restore_anchor(field, "muted")
    n = text.count(old)
    if n != 1:
        print(f"[FAIL] {path.name}: apply_snapshot 锚点命中 {n} 次（期望 1）")
        return 1
    text = text.replace(old, new, 1)

    path.write_text(text, encoding="utf-8")
    probe_note = "（含诊断探针）" if PROBE_ON else ""
    print(f"[OK]   {path.name}: silent_on 改空操作 + 4 处音量 + 存档恢复已应用{probe_note}")
    return 0


def main() -> int:
    for rel in (SE, BGM):
        if not Path(rel).exists():
            # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
            print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
            return 1

    rc = patch(Path(SE), "Sound", "se_muted", "se_slots")
    if rc != 0:
        return rc
    return patch(Path(BGM), "Audio", "bgm_muted", "bgm_slots")


if __name__ == "__main__":
    sys.exit(main())