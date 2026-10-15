#!/usr/bin/env python3
"""YukiHub 补丁：转场（dissolve）改成「擦掉开始时那一帧」，不再盖黑。

来源
----
移植自上游 xmoezzz/rfvp 提交 `2a9ff58a`（"fix dissolve"，2026-10-15）。

背景
----
上游 10.12 的实现在**所有转场都盖一层黑**，转场因此又生硬又全黑。
上游 10.15 删掉的 `setsumei/compatibility/DISSOLVE.md` 写明了原引擎的做法：

    wipes **the frame shown when it started** away through the mask,
    **revealing the new scene under it** … drawing black there blacks the
    screen out at every transition

也就是说：正确做法不是"盖黑"，而是
**抓取转场开始那一刻的画面 → 按遮罩把它擦掉 → 露出下面的新场景**。

修复（三块）
----
1. `motion_manager/mod.rs`
   - 新增 `dissolve_serial: u32`，`start_dissolve` 时 +1 —— 用来标识
     "这一帧是第几次转场的快照"，避免复用上一轮的旧画面。
   - `start_dissolve` 把 mask 类转场的初始 alpha 设为 **1.0**
     （"一开始整个被上一帧盖住"），而不是 0。
   - `MaskFadeInOut` 的曲线由三角波 `1.0 - (2t-1).abs()` 改成 **`1.0 - t`**
     （单调递减：从"完全盖住"一路擦到"完全露出"）。
2. `rendering/gpu_prim.rs`
   - 新增 `mask_wants_snapshot()` / `set_mask_snapshot()`，向 `App` 暴露
     "这一帧需不需要先把画面回读下来"。
   - mask 纹理改用 **sRGB**（`GpuTexture::new`）：现在贴的是**真实画面的颜色**，
     不再是黑图 —— 这是我们此前选 `new_rgba8_unorm`（线性）的原因。
3. `app.rs`
   - 每帧 `rebuild` 之前：若 `mask_wants_snapshot()` 为真，就用
     `RenderTarget::encode_readback_rgba8` + `map_to_rgba8` 把当前渲染目标
     回读成 RGBA，交给 `set_mask_snapshot`。
     （这两个 API 基线里就有，存档缩略图在用，无需新增。）
4. `rendering/mask_dissolve.rs` 整体替换为上游 10.15 版
   - 底图从「黑图」换成「快照帧」，遮罩按快照分辨率缩放。
   - 还原原引擎的 `MaskFadeIn` 语义：wait=4 是**硬边**擦除
     （`mask + 255 - 255t` 对 255 做 alpha 测试），wait=5 才是软混合。

前置依赖
----
**必须在 `apply_fvp_mask_dissolve_patch.py` 之后执行**（本补丁是它的增量）：
本补丁要复用后者建立的 `DrawTextureKey::Mask`、`mask_frame` / `mask_texture`
字段、`texture_bind_group` 分支与 `build()` 插入点。

上游 10.15 的 `mask_dissolve.rs` 同时引用了 `dissolve_serial` 与 `snapshot`，
且删除了 `DISSOLVE.md`，说明该实现仍在快速迭代；本移植对齐的是 10.15 快照。
"""
import sys
from pathlib import Path

MOTION = "crates/rfvp/src/subsystem/resources/motion_manager/mod.rs"
GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"
APP = "crates/rfvp/src/app.rs"
MASK_RS = "crates/rfvp/src/rendering/mask_dissolve.rs"

BASE_REL = "上游 81a8b685 基线"

# ------------------------------------------------------------ mask_dissolve.rs

MASK_DISSOLVE_RS = '''//! Shared 8-bit mask dissolve. Source alpha is retained; only the GPU copy changes.
//!
//! A mask dissolve (`Dissolve` with a mask, wait=4/5) wipes the frame shown when it started away
//! through the mask, revealing the scene under it; the renderer hands that frame over with
//! `set_snapshot`. Only `MaskFadeOut` still fades the mask to black.

/// YukiHub patch marker - CI greps the built .so for this string to prove the patch landed.
#[no_mangle]
pub static YHFVP_DISSOLVE_SNAPSHOT_MARKER: [u8; 26] = *b"YHFVP-DISSOLVESNAPSHOT-1.0";

use crate::subsystem::resources::motion_manager::{DissolveType, MotionManager};
use alloc::{string::String, vec::Vec};
use image::{DynamicImage, GenericImageView, RgbaImage};

#[derive(Debug, Default)]
pub(crate) struct MaskDissolveFrame {
    /// Mask generation and path, and the serial of the snapshot the image was built from.
    source: Option<(u64, String, Option<u32>)>,
    alpha: Vec<u8>,
    phase: u16,
    snapshot: Option<(u32, RgbaImage)>,
    pub generation: u64,
    pub image: Option<DynamicImage>,
}

fn wipes(motion: &MotionManager) -> bool {
    matches!(
        motion.get_dissolve_type(),
        DissolveType::MaskFadeIn | DissolveType::MaskFadeInOut
    )
}

impl MaskDissolveFrame {
    /// Whether the renderer must capture the frame it last showed (before drawing the next one).
    pub fn wants_snapshot(&self, motion: &MotionManager) -> bool {
        wipes(motion)
            && self
                .snapshot
                .as_ref()
                .map_or(true, |(serial, _)| *serial != motion.get_dissolve_serial())
    }

    /// The last frame shown, in the render target's own color encoding.
    pub fn set_snapshot(&mut self, motion: &MotionManager, frame: RgbaImage) {
        self.snapshot = Some((motion.get_dissolve_serial(), frame));
    }

    pub fn prepare(&mut self, motion: &MotionManager) {
        let active = matches!(
            motion.get_dissolve_type(),
            DissolveType::MaskFadeIn | DissolveType::MaskFadeInOut | DissolveType::MaskFadeOut
        );
        let graph = motion.get_dissolve_mask_graph();
        let texture = graph.get_texture().as_ref().filter(|_| active);
        let snapshot = if wipes(motion) {
            // Without the previous frame there is nothing to wipe: the new scene shows at once.
            match &self.snapshot {
                Some((serial, frame)) if *serial == motion.get_dissolve_serial() => {
                    Some((*serial, frame))
                }
                _ => None,
            }
        } else {
            None
        };
        let Some(texture) = texture.filter(|_| snapshot.is_some() || !wipes(motion)) else {
            self.source = None;
            self.alpha = Vec::new();
            self.image = None;
            if !active {
                self.snapshot = None;
            }
            return;
        };
        let alpha = motion.get_dissolve_alpha().clamp(0.0, 1.0);
        // Original engine: wait=4 (sub_425130) alpha-tests mask + 255 - 255t against 255, a
        // hard-edged wipe; wait=5 (sub_424C10) blends mask + 255 - 510t. The top bit marks hard.
        let hard = motion.get_dissolve_type() == DissolveType::MaskFadeIn;
        let phase = if hard {
            0x8000 | ((1.0 - alpha) * 255.0) as u16
        } else {
            (alpha * 510.0 + 0.5) as u16
        };
        let serial = snapshot.map(|(serial, _)| serial);
        let same = self.source.as_ref().map_or(false, |(generation, path, s)| {
            *generation == graph.generation && path == &graph.texture_path && *s == serial
        });
        if same && self.phase == phase {
            return;
        }
        if !same {
            let (mw, mh) = texture.dimensions();
            let Some((_, _, _, _, alpha)) = graph.export_alpha_mask() else {
                self.image = None;
                return;
            };
            match snapshot {
                Some((_, frame)) => {
                    // The frame's resolution, the mask scaled to it.
                    let (w, h) = (frame.width(), frame.height());
                    let mut scaled = Vec::with_capacity((w * h) as usize);
                    for y in 0..h {
                        let row = (y as u64 * mh as u64 / h.max(1) as u64) as usize * mw as usize;
                        for x in 0..w {
                            let col = (x as u64 * mw as u64 / w.max(1) as u64) as usize;
                            scaled.push(alpha.get(row + col).copied().unwrap_or(0));
                        }
                    }
                    self.alpha = scaled;
                    self.image = Some(DynamicImage::ImageRgba8(frame.clone()));
                }
                None => {
                    self.alpha = alpha;
                    self.image = Some(DynamicImage::ImageRgba8(RgbaImage::new(mw, mh)));
                }
            }
            self.source = Some((graph.generation, graph.texture_path.clone(), serial));
        }
        let mut lut = [0u8; 256];
        for (alpha, value) in lut.iter_mut().enumerate() {
            *value = if hard {
                if alpha as u16 >= phase & 0xff { 255 } else { 0 }
            } else {
                (alpha as i32 + i32::from(phase) - 255).clamp(0, 255) as u8
            };
        }
        let rgba = self.image.as_mut().unwrap().as_mut_rgba8().unwrap();
        for (pixel, alpha) in rgba.as_mut().chunks_exact_mut(4).zip(&self.alpha) {
            pixel[3] = lut[*alpha as usize];
        }
        self.phase = phase;
        self.generation = self.generation.wrapping_add(1);
    }
}
'''

# ---------------------------------------------------------- motion_manager

MOTION_FIELD_OLD = """    dissolve_alpha: f32,
    dissolve2: Dissolve2State,
"""

MOTION_FIELD_NEW = """    dissolve_alpha: f32,
    /// Counts started dissolves: a mask dissolve wipes away the frame shown when it started.
    dissolve_serial: u32,
    dissolve2: Dissolve2State,
"""

MOTION_INIT_OLD = """            dissolve_alpha: 0.0,
            dissolve2: Dissolve2State::new(),
"""

MOTION_INIT_NEW = """            dissolve_alpha: 0.0,
            dissolve_serial: 0,
            dissolve2: Dissolve2State::new(),
"""

MOTION_START_OLD = """    pub fn start_dissolve(&mut self, duration_ms: u32, typ: DissolveType) {
        self.dissolve_duration_ms = duration_ms.max(1);
        self.dissolve_elapsed_ms = 0;
        self.dissolve_type = typ;
        self.dissolve_alpha = 0.0;
    }
"""

MOTION_START_NEW = """    pub fn start_dissolve(&mut self, duration_ms: u32, typ: DissolveType) {
        self.dissolve_duration_ms = duration_ms.max(1);
        self.dissolve_elapsed_ms = 0;
        self.dissolve_type = typ;
        // A mask dissolve starts covered by the previous frame.
        self.dissolve_alpha = match typ {
            DissolveType::MaskFadeIn | DissolveType::MaskFadeInOut => 1.0,
            _ => 0.0,
        };
        self.dissolve_serial = self.dissolve_serial.wrapping_add(1);
    }

    pub fn get_dissolve_serial(&self) -> u32 {
        self.dissolve_serial
    }
"""

MOTION_CURVE_OLD = """            DissolveType::MaskFadeInOut => 1.0 - (2.0 * t - 1.0).abs(),
"""

MOTION_CURVE_NEW = """            // Both mask dissolves (wait=4/5) wipe the frame shown when they started away.
            DissolveType::MaskFadeInOut => 1.0 - t,
"""

# ------------------------------------------------------------- gpu_prim.rs

GPU_METHOD_OLD = """    pub fn remove_graph_cache(&mut self, graph_id: u16) {
"""

GPU_METHOD_NEW = """    /// Whether a mask dissolve needs the frame last shown (`set_mask_snapshot`) before `rebuild`.
    pub fn mask_wants_snapshot(&self, motion: &MotionManager) -> bool {
        self.mask_frame.wants_snapshot(motion)
    }

    pub fn set_mask_snapshot(&mut self, motion: &MotionManager, frame: image::RgbaImage) {
        self.mask_frame.set_snapshot(motion, frame);
    }

    pub fn remove_graph_cache(&mut self, graph_id: u16) {
"""

GPU_SRGB_OLD = """                    texture: GpuTexture::new_rgba8_unorm(resources, image, Some("dissolve_mask")),
"""

GPU_SRGB_NEW = """                    // sRGB: the colors are a captured frame's (or black).
                    texture: GpuTexture::new(resources, image, Some("dissolve_mask")),
"""

# ------------------------------------------------------------------ app.rs

APP_ANCHOR_OLD = """            let gd = gd_read(&self.game_data);

            // Build primitive draw list and upload any modified GraphBuffs to the GPU.
            self.prim_renderer
"""

APP_ANCHOR_NEW = """            let gd = gd_read(&self.game_data);

            // A mask dissolve that just started wipes away the frame last shown, which is still
            // in the render target.
            if self.prim_renderer.mask_wants_snapshot(&gd.motion_manager) {
                let mut encoder = self.resources.device.create_command_encoder(
                    &wgpu::CommandEncoderDescriptor { label: Some("rfvp dissolve snapshot") },
                );
                let readback = self
                    .render_target
                    .encode_readback_rgba8(&self.resources.device, &mut encoder);
                self.resources.queue.submit(Some(encoder.finish()));
                let rgba = readback.map_to_rgba8(&self.resources.device);
                if let Some(frame) = image::RgbaImage::from_raw(readback.width, readback.height, rgba) {
                    self.prim_renderer.set_mask_snapshot(&gd.motion_manager, frame);
                }
            }

            // Build primitive draw list and upload any modified GraphBuffs to the GPU.
            self.prim_renderer
"""


def apply_anchors(text: str, rel: str, pairs) -> "tuple[int, str]":
    for name, old, new in pairs:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 源码已变动")
            return 1, text
        text = text.replace(old, new, 1)
    return 0, text


def main() -> int:
    for rel in (MOTION, GPU_PRIM, APP, MASK_RS):
        if not Path(rel).exists():
            # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
            print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
            return 1

    # 前置：apply_fvp_mask_dissolve_patch.py 必须先跑（本补丁是它的增量）
    gp_text = Path(GPU_PRIM).read_text(encoding="utf-8")
    if "mask_frame" not in gp_text:
        print(
            f"[FAIL] {GPU_PRIM}: 未发现 mask_frame —— 请先执行 "
            f"apply_fvp_mask_dissolve_patch.py（本补丁是它的增量）"
        )
        return 1

    # 1. mask_dissolve.rs 整体替换
    mask_path = Path(MASK_RS)
    mask_text = mask_path.read_text(encoding="utf-8")
    if "wants_snapshot" in mask_text:
        print(f"[OK]   {MASK_RS}: 已应用（幂等）")
    else:
        mask_path.write_text(MASK_DISSOLVE_RS, encoding="utf-8")
        print(f"[OK]   {MASK_RS}: 已替换为快照版（{len(MASK_DISSOLVE_RS.splitlines())} 行）")

    # 2. motion_manager
    mo_path = Path(MOTION)
    mo_text = mo_path.read_text(encoding="utf-8")
    if "dissolve_serial" in mo_text:
        print(f"[OK]   {MOTION}: 已应用（幂等）")
    else:
        rc, mo_text = apply_anchors(
            mo_text,
            MOTION,
            [
                ("字段", MOTION_FIELD_OLD, MOTION_FIELD_NEW),
                ("初始化", MOTION_INIT_OLD, MOTION_INIT_NEW),
                ("start_dissolve", MOTION_START_OLD, MOTION_START_NEW),
                ("MaskFadeInOut 曲线", MOTION_CURVE_OLD, MOTION_CURVE_NEW),
            ],
        )
        if rc != 0:
            return rc
        mo_path.write_text(mo_text, encoding="utf-8")
        print(f"[OK]   {MOTION}: dissolve_serial + 初始 alpha + 曲线已应用")

    # 3. gpu_prim.rs
    if "mask_wants_snapshot" in gp_text:
        print(f"[OK]   {GPU_PRIM}: 已应用（幂等）")
    else:
        rc, gp_text = apply_anchors(
            gp_text,
            GPU_PRIM,
            [
                ("snapshot 方法", GPU_METHOD_OLD, GPU_METHOD_NEW),
                ("mask 纹理改 sRGB", GPU_SRGB_OLD, GPU_SRGB_NEW),
            ],
        )
        if rc != 0:
            return rc
        Path(GPU_PRIM).write_text(gp_text, encoding="utf-8")
        print(f"[OK]   {GPU_PRIM}: mask_wants_snapshot/set_mask_snapshot + sRGB 已应用")

    # 4. app.rs
    app_path = Path(APP)
    app_text = app_path.read_text(encoding="utf-8")
    if "dissolve snapshot" in app_text:
        print(f"[OK]   {APP}: 已应用（幂等）")
    else:
        rc, app_text = apply_anchors(
            app_text, APP, [("帧回读", APP_ANCHOR_OLD, APP_ANCHOR_NEW)]
        )
        if rc != 0:
            return rc
        app_path.write_text(app_text, encoding="utf-8")
        print(f"[OK]   {APP}: 转场起始帧回读已应用")

    return 0


if __name__ == "__main__":
    sys.exit(main())