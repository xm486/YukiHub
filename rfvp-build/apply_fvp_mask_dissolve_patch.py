#!/usr/bin/env python3
"""YukiHub 补丁：实现 Mask Dissolve（遮罩渐变），修 FVP HD "parts" 背景暗带。

来源
----
移植自上游 xmoezzz/rfvp 提交 `e95f275a`
（"render: enable Mask Dissolve across platforms with PS1 Bayer coverage (#44)"，
2026-10-12），**只取 Android/wgpu 需要的部分**。

上游对 issue #38（FVP HD `BGxxx_parts1/2` 硬边暗带）的回复：
    "I guess the incorrect dissolve motion caused it. It should be fixed in e95f275"

背景
----
`MaskFadeIn` / `MaskFadeInOut` / `MaskFadeOut` 这类 dissolve 用的是
**8-bit 遮罩图**（Type 3 / Single8Bit），原引擎会把它当作"按遮罩形状逐渐
变黑"的过渡来合成。

rfvp 此前**在所有平台上都没实现这条管线**（旧版 app.rs 原话：
"Until a dedicated mask dissolve pipeline is implemented, do not draw a solid
overlay here"），于是这些遮罩图被当成普通贴图画出来 —— 在 FVP HD 的星空
背景上就是那条硬边暗带。

修复
----
在 root=0 场景树之后、dissolve2/HUD 之前，插入一层全屏 mask 合成：

    clamp(source_alpha + 2 * progress - 1, 0, 1)

进度 0 = 透明，进度 1 = 全黑，中途保留原遮罩形状。

上游改动里 **PS1 / 控制台专用的部分我们一律不移植**（`platform/ps1/*`、
`host_api/*` 的 portable 渲染器、`texture.rs`/`graph_buff.rs` 里的
`#[cfg(feature = "psx")]` 分支、`soft_render/`）—— Android 走
`GpuPrimRenderer`（wgpu），碰不到那些路径。

依赖
----
本补丁用到的 API 在旧版基线（上游 81a8b685）里**全都已经存在**：
`GraphBuff::export_alpha_mask()` / `texture_path` / `generation`、
`MotionManager::get_dissolve_*()`、`GpuTexture::new_rgba8_unorm` /
`update_rgba8`。所以是"纯插入"，不改动任何既有结构。

与加法混合补丁的关系
--------------------
两个补丁**顺序无关**。若 `apply_fvp_additive_blend_patch.py` 已应用，
本补丁会自动给 mask 的 `emit_sprite_vertices` 调用补上 `additive=false`。
"""
import sys
from pathlib import Path

MOD_RS = "crates/rfvp/src/rendering/mod.rs"
GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"
MOTION = "crates/rfvp/src/subsystem/resources/motion_manager/mod.rs"
MASK_RS = "crates/rfvp/src/rendering/mask_dissolve.rs"

# 上游 e95f275a 新增文件，逐字照抄（无 PS1 相关代码）。
# 唯一附加：顶部一个 `#[no_mangle]` 标记串，供 CI 在 .so 里 grep 校验补丁是否真的编进去了
# （与 apply_text_patch.py 的 `YHFVP-TEXTPATCH-1.0` 做法一致）。
MASK_DISSOLVE_RS = '''//! Shared 8-bit mask reveal. Source alpha is retained; only the GPU copy changes.
use crate::subsystem::resources::motion_manager::{DissolveType, MotionManager};
use alloc::{string::String, vec::Vec};
use image::{DynamicImage, GenericImageView, RgbaImage};

/// YukiHub patch marker — CI greps the built .so for this string to prove the patch landed.
#[no_mangle]
pub static YHFVP_MASK_DISSOLVE_MARKER: [u8; 22] = *b"YHFVP-MASKDISSOLVE-1.0";

#[derive(Debug, Default)]
pub(crate) struct MaskDissolveFrame {
    source: Option<(u64, String)>,
    alpha: Vec<u8>,
    phase: u16,
    pub generation: u64,
    pub image: Option<DynamicImage>,
}

impl MaskDissolveFrame {
    pub fn prepare(&mut self, motion: &MotionManager) {
        let active = matches!(
            motion.get_dissolve_type(),
            DissolveType::MaskFadeIn | DissolveType::MaskFadeInOut | DissolveType::MaskFadeOut
        );
        let graph = motion.get_dissolve_mask_graph();
        let Some(texture) = graph.get_texture().as_ref().filter(|_| active) else {
            self.source = None;
            self.alpha = Vec::new();
            self.image = None;
            return;
        };
        let phase = (motion.get_dissolve_alpha().clamp(0.0, 1.0) * 510.0 + 0.5) as u16;
        let same = self.source.as_ref().map_or(false, |(generation, path)| {
            *generation == graph.generation && path == &graph.texture_path
        });
        if same && self.phase == phase {
            return;
        }
        if !same {
            let (w, h) = texture.dimensions();
            let Some((_, _, _, _, alpha)) = graph.export_alpha_mask() else {
                self.image = None;
                return;
            };
            self.alpha = alpha;
            self.image = Some(DynamicImage::ImageRgba8(RgbaImage::new(w, h)));
            self.source = Some((graph.generation, graph.texture_path.clone()));
        }
        let mut lut = [0u8; 256];
        for (alpha, value) in lut.iter_mut().enumerate() {
            *value = (alpha as i32 + i32::from(phase) - 255).clamp(0, 255) as u8;
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

# --------------------------------------------------------------- mod.rs

MOD_OLD = """#[cfg(all(not(feature = "no_std"), feature = "gpu-render"))]
pub(crate) mod gpu_prim;
#[cfg(feature = "no_std")]
pub(crate) mod prim_commands;
"""

MOD_NEW = """#[cfg(all(not(feature = "no_std"), feature = "gpu-render"))]
pub(crate) mod gpu_prim;
#[cfg(all(not(feature = "no_std"), feature = "gpu-render"))]
pub(crate) mod mask_dissolve;
#[cfg(feature = "no_std")]
pub(crate) mod prim_commands;
"""

# ------------------------------------------------------------ gpu_prim.rs

GPU_KEY_OLD = """enum DrawTextureKey {
    Graph(u16),
    White,
}
"""

GPU_KEY_NEW = """enum DrawTextureKey {
    Graph(u16),
    White,
    Mask,
}
"""

GPU_FIELD_OLD = """    graph_cache: HashMap<u16, GraphGpuEntry>,
    white: GpuTexture,
"""

GPU_FIELD_NEW = """    graph_cache: HashMap<u16, GraphGpuEntry>,
    white: GpuTexture,
    mask_frame: super::mask_dissolve::MaskDissolveFrame,
    mask_texture: Option<GraphGpuEntry>,
"""

GPU_INIT_OLD = """            graph_cache: HashMap::new(),
            white,

            debug_tiles: Vec::new(),
"""

GPU_INIT_NEW = """            graph_cache: HashMap::new(),
            white,
            mask_frame: Default::default(),
            mask_texture: None,

            debug_tiles: Vec::new(),
"""

GPU_BIND_OLD = """            DrawTextureKey::White => self.white.bind_group(),
            DrawTextureKey::Graph(id) => self
"""

GPU_BIND_NEW = """            DrawTextureKey::White => self.white.bind_group(),
            DrawTextureKey::Mask => self
                .mask_texture
                .as_ref()
                .expect("mask texture uploaded before use")
                .texture
                .bind_group(),
            DrawTextureKey::Graph(id) => self
"""

GPU_BUILD_OLD = """        // Record the draw boundary for root=0 so the caller can insert overlays (e.g. dissolves)
        // between the main scene tree and the overlay/custom root tree.
        self.root0_draw_end = self.draws.len();
"""

# 注意：本模板用 `str.replace()` 填充，因此花括号必须是单个 `{` / `}`（不要写成 `{{`）。
GPU_BUILD_NEW_TMPL = """        // Mask dissolve: composite the 8-bit mask over the finished root=0 scene,
        // before dissolve2 and the HUD/custom root (matches the original engine).
        self.mask_frame.prepare(motion);
        if let Some(image) = &self.mask_frame.image {
            let generation = self.mask_frame.generation;
            let updated = self.mask_texture.as_mut().map_or(false, |entry| {
                if entry.generation == generation {
                    return true;
                }
                if entry.texture.update_rgba8(resources, image) {
                    entry.generation = generation;
                    true
                } else {
                    false
                }
            });
            if !updated {
                self.mask_texture = Some(GraphGpuEntry {
                    generation,
                    texture: GpuTexture::new_rgba8_unorm(resources, image, Some("dissolve_mask")),
                });
            }
            self.emit_sprite_vertices(
                Mat4::IDENTITY,
                self.virtual_size.0 as f32,
                self.virtual_size.1 as f32,
                vec2(0.0, 0.0),
                vec2(1.0, 1.0),
                vec4(1.0, 1.0, 1.0, 1.0),
                DrawTextureKey::Mask{MASK_ARG},
            );
        } else {
            self.mask_texture = None;
        }

        // Record the draw boundary for root=0 so the caller can insert overlays (e.g. dissolves)
        // between the main scene tree and the overlay/custom root tree.
        self.root0_draw_end = self.draws.len();
"""

# ---------------------------------------------------------- motion_manager

MOTION_OLD = """    pub fn set_dissolve_mask_graph(&mut self, graph: GraphBuff) {
        self.dissolve_mask_graph = graph;
    }
"""

MOTION_NEW = """    pub fn set_dissolve_mask_graph(&mut self, graph: GraphBuff) {
        // Bump the generation so the renderer notices a new mask even when the
        // path is unchanged (the mask graph is replaced, not mutated in place).
        let graph = {
            let mut graph = graph;
            graph.generation = self.dissolve_mask_graph.generation.wrapping_add(1);
            graph
        };
        self.dissolve_mask_graph = graph;
    }
"""

# 加法混合补丁是否已应用（决定 mask 调用点要不要带 additive 实参）
ADDITIVE_ANCHOR = """        color: Vec4,
        tex: DrawTextureKey,
        additive: bool,
    ) {
"""


def apply_anchors(text: str, rel: str, pairs) -> "tuple[int, str]":
    for name, old, new in pairs:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1, text
        text = text.replace(old, new, 1)
    return 0, text


def main() -> int:
    for rel in (MOD_RS, GPU_PRIM, MOTION):
        if not Path(rel).exists():
            # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
            print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
            return 1

    mask_path = Path(MASK_RS)
    if mask_path.exists():
        print(f"[OK]   {MASK_RS}: 已存在（幂等）")
    else:
        mask_path.write_text(MASK_DISSOLVE_RS, encoding="utf-8")
        print(f"[OK]   {MASK_RS}: 新增（{len(MASK_DISSOLVE_RS.splitlines())} 行）")

    # mod.rs
    mod_path = Path(MOD_RS)
    mod_text = mod_path.read_text(encoding="utf-8")
    if "mod mask_dissolve;" in mod_text:
        print(f"[OK]   {MOD_RS}: 已应用（幂等）")
    else:
        rc, mod_text = apply_anchors(mod_text, MOD_RS, [("mod 声明", MOD_OLD, MOD_NEW)])
        if rc != 0:
            return rc
        mod_path.write_text(mod_text, encoding="utf-8")
        print(f"[OK]   {MOD_RS}: mask_dissolve 模块已注册")

    # gpu_prim.rs
    gp_path = Path(GPU_PRIM)
    gp_text = gp_path.read_text(encoding="utf-8")
    if "mask_frame" in gp_text:
        print(f"[OK]   {GPU_PRIM}: 已应用（幂等）")
    else:
        has_additive = ADDITIVE_ANCHOR in gp_text
        mask_arg = ", false" if has_additive else ""
        build_new = GPU_BUILD_NEW_TMPL.replace("{MASK_ARG}", mask_arg)
        rc, gp_text = apply_anchors(
            gp_text,
            GPU_PRIM,
            [
                ("DrawTextureKey::Mask", GPU_KEY_OLD, GPU_KEY_NEW),
                ("字段", GPU_FIELD_OLD, GPU_FIELD_NEW),
                ("初始化", GPU_INIT_OLD, GPU_INIT_NEW),
                ("texture_bind_group", GPU_BIND_OLD, GPU_BIND_NEW),
                ("build 插入点", GPU_BUILD_OLD, build_new),
            ],
        )
        if rc != 0:
            return rc
        gp_path.write_text(gp_text, encoding="utf-8")
        extra = "（含 additive=false 实参）" if has_additive else ""
        print(f"[OK]   {GPU_PRIM}: mask 纹理 + 合成插入点已应用{extra}")

    # motion_manager
    mo_path = Path(MOTION)
    mo_text = mo_path.read_text(encoding="utf-8")
    if "wrapping_add(1);" in mo_text and "set_dissolve_mask_graph" in mo_text and \
            mo_text.count("set_dissolve_mask_graph") == 1 and "generation.wrapping_add" in mo_text:
        print(f"[OK]   {MOTION}: 已应用（幂等）")
    else:
        rc, mo_text = apply_anchors(
            mo_text, MOTION, [("set_dissolve_mask_graph", MOTION_OLD, MOTION_NEW)]
        )
        if rc != 0:
            return rc
        mo_path.write_text(mo_text, encoding="utf-8")
        print(f"[OK]   {MOTION}: mask graph generation 递增已应用")

    return 0


if __name__ == "__main__":
    sys.exit(main())