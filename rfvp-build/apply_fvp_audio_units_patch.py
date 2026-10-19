#!/usr/bin/env python3
"""YukiHub 补丁：修音频的两个单位 bug —— 音量（线性→分贝）与 pan（0..1→-1..1）。

来源
----
对照上游 xmoezzz/rfvp 10.19 的音频重写：
- `crates/rfvp/src/rfvp_audio/kira.rs` 的 `decibels()`
- `crates/rfvp/src/audio_player/channels.rs` 的 `params()`
- `crates/rfvp/src/host_api/audio.rs` 里 `AudioParams::volume` 的注释
  （「Linear gain: 0 is silent, 1 is the sound as recorded.」）

问题一：线性音量被当成分贝传给 kira（爆音根因）
------------------------------------------------
kira 的 `.volume()` 收的是 **Decibels**：

    /// Represents a change in volume.
    /// Setting the volume of a sound to -60dB or lower makes it silent.
    pub struct Decibels(pub f32);

    impl From<f32> for Decibels {
        fn from(value: f32) -> Self { Self(value) }   // 裸转换
    }

    pub fn as_amplitude(self) -> f32 {
        if self <= Self::SILENCE { return 0.0; }
        10.0f32.powf(self.0 / 20.0)                    // dB -> 振幅
    }

我们一直把线性值（0..1）直接传进去，于是：

    | 我们传 | kira 实际增益 | 应为 |
    |--------|---------------|------|
    | 0.0    | 1.000         | 0.0  ← 想静音反而满音量 |
    | 0.5    | 1.059         | 0.5  |
    | 0.65   | 1.078         | 0.65 |
    | 1.0    | 1.122         | 1.0  |

后果：
1. **音量滑条 0%..100% 只让响度变化 12%**，几乎失效；
2. `volume = 0.0`（想静音）→ **满音量播放**；
3. 所有声音恒定 > 1.0 倍，**多路 SE 叠加必硬削波**。

`はっぴぃ☆マーガレット!` 在 19:54:14~16 的 2 秒内连播 8 次 SE，
多路 1.078~1.122 倍叠上去，就是用户听到的「噪音」。
其它作品 SE 稀疏、音量滑条默认 100%，单个 1.122 倍听不太出来，所以只有
这一个游戏明显。

问题二：pan 没做 0..1 -> -1..1 映射（全部偏右）
----------------------------------------------
kira 的 Panning：

    /// Valid panning values range from `-1.0` to `1.0`.
    /// A value of `0.0` will cause a sound to be played at an equal volume from both speakers.
    pub struct Panning(pub f32);

脚本的 pan 是 0..1（上游注释：Scripts keep pan from 0 to 1），
上游做了 `pan * 2.0 - 1.0`。我们直接 `Panning::from(pan)`，
默认值 0.5 就变成「偏右 50%」——所有音效都偏右。

修复
----
1. `bgm_player.rs` / `se_player.rs` 各加两个文件内辅助函数 `decibels()` / `panning()`；
2. 8 处 `.volume(x)` / `.set_volume(x, tween)` 改为 `.volume(decibels(x))` 等；
3. 4 处 `Panning::from(pan)` 改为 `panning(pan)`。

注意
----
改完音量会**明显变小**——因为之前一直是 1.078~1.122 倍（偏响）。
这是把音量还原到正确值，不是退化。
"""
import sys
from pathlib import Path

BGM = "crates/rfvp/src/audio_player/bgm_player.rs"
SE = "crates/rfvp/src/audio_player/se_player.rs"

MARKER = "YHFVP-AUDIO-UNITS-1.0"

HELPERS = '''/// YukiHub: kira takes volume in **decibels**; the engine's volume is linear (0 = silent,
/// 1 = the sound as recorded). Passing the linear value straight through made 0.0 play at
/// full volume and 1.0 at 1.122×, which clipped as soon as two sounds mixed.
///
/// Upstream does the same conversion in `rfvp_audio/kira.rs` (`decibels`).
fn decibels(volume: f32) -> kira::Decibels {
    if volume <= 0.0 {
        kira::Decibels::SILENCE
    } else {
        kira::Decibels(20.0 * volume.log10())
    }
}

/// YukiHub: scripts keep pan in 0..1 (0 = left, 0.5 = center, 1 = right), while kira's
/// `Panning` is -1..1. Passing 0.5 straight through put every sound 50% to the right.
///
/// Upstream does the same mapping in `audio_player/channels.rs` (`params`).
fn panning(pan: f64) -> kira::Panning {
    kira::Panning::from((pan as f32) * 2.0 - 1.0)
}

'''

MARKER_RUST = '''
/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_AUDIO_UNITS_MARKER: [u8; 21] = *b"YHFVP-AUDIO-UNITS-1.0";
'''

# ---------------------------------------------------------------- BGM edits
BGM_ANCHOR = """pub const BGM_SLOT_COUNT: usize = 4;"""

BGM_EDITS = [
    ("插入辅助函数", BGM_ANCHOR, HELPERS + BGM_ANCHOR),
    # play(): 非 wasm 分支
    (
        "play 非 wasm：pan + volume",
        """            let pan = Panning::from(pan as f32);
            let mut bgm = Self::streaming_data_for_source(source, vfs)?
                .panning(pan)
                .volume(actual_volume)
                .fade_in_tween(fade_in);""",
        """            let mut bgm = Self::streaming_data_for_source(source, vfs)?
                .panning(panning(pan))
                .volume(decibels(actual_volume))
                .fade_in_tween(fade_in);""",
    ),
    # play(): wasm 分支
    (
        "play wasm：pan + volume",
        """            let loop_region = repeat.then_some(Region::default());
            let pan = Panning::from(pan as f32);
            let settings = StaticSoundSettings::new()
                .panning(pan)
                .volume(actual_volume)
                .fade_in_tween(fade_in)""",
        """            let loop_region = repeat.then_some(Region::default());
            let settings = StaticSoundSettings::new()
                .panning(panning(pan))
                .volume(decibels(actual_volume))
                .fade_in_tween(fade_in)""",
    ),
    # set_volume
    (
        "set_volume",
        """        if let Some(handle) = self.bgm_slots[slot].as_mut() {
            handle.set_volume(actual_volume, tween);
        } else {""",
        """        if let Some(handle) = self.bgm_slots[slot].as_mut() {
            handle.set_volume(decibels(actual_volume), tween);
        } else {""",
    ),
    # set_type
    (
        "set_type",
        """        if let Some(handle) = self.bgm_slots[slot].as_mut() {
            handle.set_volume(actual_volume, Tween::default());
        }""",
        """        if let Some(handle) = self.bgm_slots[slot].as_mut() {
            handle.set_volume(decibels(actual_volume), Tween::default());
        }""",
    ),
    # set_type_volume
    (
        "set_type_volume",
        """                let actual_volume = self.effective_volume_for_slot(slot);
                if let Some(handle) = self.bgm_slots[slot].as_mut() {
                    handle.set_volume(actual_volume, tween);
                }""",
        """                let actual_volume = self.effective_volume_for_slot(slot);
                if let Some(handle) = self.bgm_slots[slot].as_mut() {
                    handle.set_volume(decibels(actual_volume), tween);
                }""",
    ),
]

# ----------------------------------------------------------------- SE edits
SE_ANCHOR = """pub const SE_SLOT_COUNT: usize = 256;"""

SE_EDITS = [
    ("插入辅助函数", SE_ANCHOR, HELPERS + SE_ANCHOR),
    # play()
    (
        "play：pan + volume",
        """        let loop_region = repeat.then_some(Region::default());
        let pan = kira::Panning::from(pan as f32);
        let settings = StaticSoundSettings::new()
            .panning(pan)
            .volume(actual_volume)""",
        """        let loop_region = repeat.then_some(Region::default());
        let settings = StaticSoundSettings::new()
            .panning(panning(pan))
            .volume(decibels(actual_volume))""",
    ),
    # set_volume
    (
        "set_volume",
        """        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_volume(actual_volume, tween);
        } else {""",
        """        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_volume(decibels(actual_volume), tween);
        } else {""",
    ),
    # set_type_volume
    (
        "set_type_volume",
        """                let actual_volume = self.effective_volume_for_slot(slot);
                if let Some(handle) = self.se_slots[slot].as_mut() {
                    handle.set_volume(actual_volume, tween);
                }""",
        """                let actual_volume = self.effective_volume_for_slot(slot);
                if let Some(handle) = self.se_slots[slot].as_mut() {
                    handle.set_volume(decibels(actual_volume), tween);
                }""",
    ),
    # set_panning
    (
        "set_panning",
        """        let pan = kira::Panning::from(pan as f32);
        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_panning(pan, tween);""",
        """        let pan = panning(pan);
        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_panning(pan, tween);""",
    ),
    # set_type
    (
        "set_type",
        """        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_volume(actual_volume, Tween::default());
        }""",
        """        if let Some(handle) = self.se_slots[slot].as_mut() {
            handle.set_volume(decibels(actual_volume), Tween::default());
        }""",
    ),
]


def apply_edits(rel: str, edits, add_marker: bool) -> int:
    path = Path(rel)
    if not path.exists():
        print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1

    text = path.read_text(encoding="utf-8")
    if "fn decibels(volume: f32)" in text:
        print(f"[OK]   {rel}: 已应用（幂等）")
        return 0

    for name, old, new in edits:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    if add_marker:
        text = text.rstrip("\n") + "\n" + MARKER_RUST

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {rel}: {len(edits)} 处已应用")
    return 0


def main() -> int:
    rc = apply_edits(BGM, BGM_EDITS, add_marker=True)
    if rc != 0:
        return rc
    return apply_edits(SE, SE_EDITS, add_marker=False)


if __name__ == "__main__":
    sys.exit(main())