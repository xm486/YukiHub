#!/usr/bin/env python3
"""YukiHub 补丁：Sprt 纹理 id -1 = 绘制「上一帧」（设置界面背景变透明）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `cc8ac77`（2026-10-19），
`docs/adr/0002-sprt-texture-minus-one.md`。

问题
----
`PrimSetSprt` 接受 -2 起的纹理 id。我们画了 -2（电影帧），但 **-1 被直接跳过**。

`はっぴぃ☆マーガレット!` 的**设置界面**用 `Sprt -1`（prim 291，alpha 200）当背景，
原版效果是「把刚才那帧画面铺在设置界面下面，半透明」，我们这边啥都没画。

根因（上游 IDA）
----------------
- Sprt 绘制 `0x416b02`：-1 调 `sub_407440`，-2 调 `sub_407b10`（电影）。
- `sub_407440` 绑定的是渲染器纹理 `+0x1e0`，那是 `0x40a6b0` 创建的三个屏幕尺寸
  渲染目标之一。
- `Present`（`sub_4083B0`）每帧顺序是：`EndScene` → `Present` → `StretchRect`
  把后备缓冲拷进那个纹理。主循环 `sub_4199F0` 在画完 prim 树之后调用它。
  **所以那个纹理里永远是「上一次 present 的那帧」**，包含转场和 overlay。
- 几何：四边形是屏幕尺寸（attr bit 1 时用 prim 的 W/H）；UV 永远覆盖整帧
  （prim 的 U/V 不参与）；旋转/缩放/pivot(attr bit 2)/V3D(attr bit 4) 与普通
  sprite 相同，默认 pivot 是 (0,0)。颜色是白色 × prim 的 alpha，走 prim 的混合模式。
- `PrimHit` 对 -1 会读到 graph 表外，没有意义 —— **不注册命中代理**。

修复
----
1. `render_target.rs`：补 `logical_size()` 和 `encode_copy_to()`（上游新增的两个小方法）。
2. `gpu_prim.rs`：
   - `DrawTextureKey` 加 `PrevFrame`
   - 结构体加 `prev_frame: Option<RenderTarget>` 与 `wants_prev_frame: bool`
   - `build()` 开头清 `wants_prev_frame`
   - 新增 `capture_prev_frame()`：需要时才拷上一帧
   - Sprt 分支加 `texture_id == -1` 的处理
   - `texture_bind_group` 加 `PrevFrame` 分支
3. `app.rs`：在 `build()` 之后、开渲染 pass 之前调用 `capture_prev_frame`。

时序说明
--------
- 拷贝发生在**该 sprite 首次出现的那一帧**（在画新内容之前拷走旧内容）。
- `wants_prev_frame` 由上一帧的 `build()` 置位，所以第一帧不会画它
  （和上游「一帧延迟」的表述一致）。

为什么只改 GPU 路径
------------------
上游同时改了 `prim_commands.rs`（host-command）和 `soft_render/renderer.rs`。
本仓库的 Android 构建**只走 `GpuPrimRenderer`**，那两个文件我们与上游 10.18
已有差异且不参与构建，**不移植**（见 ENGINE_PATCHES §5）。

行为影响
--------
- 设置界面背景显示为「刚才那帧画面的半透明拷贝」，与原版一致。
- 没有可见 Sprt -1 的帧：`wants_prev_frame` 为 false，**零额外开销**
  （`prev_frame` 会被置 None 释放）。
- 有可见 Sprt -1 的帧：多一次全屏 `copy_texture_to_texture`。
"""
import sys
from pathlib import Path

RENDER_TARGET = "crates/rfvp/src/rfvp_render/render_target.rs"
GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"
APP_RS = "crates/rfvp/src/app.rs"

MARKER = "YHFVP-PREVFRAME-1.0"

# ------------------------------------------- render_target.rs: 两个新方法
RT_OLD = """    pub fn backing_size(&self) -> (u32, u32) {"""

RT_NEW = """    pub fn logical_size(&self) -> (u32, u32) {
        self.logical_size
    }

    /// YukiHub: encode a copy of this target's pixels into `dst`, which has the same backing
    /// size. Used for Sprt texture id -1 (the frame last shown, ADR-0002).
    pub fn encode_copy_to(&self, encoder: &mut wgpu::CommandEncoder, dst: &RenderTarget) {
        encoder.copy_texture_to_texture(
            self.texture.as_image_copy(),
            dst.texture.as_image_copy(),
            wgpu::Extent3d {
                width: self.size.0,
                height: self.size.1,
                depth_or_array_layers: 1,
            },
        );
    }

    pub fn backing_size(&self) -> (u32, u32) {"""

# ------------------------------------------------ gpu_prim.rs: 枚举加一项
GPU_ENUM_OLD = """use crate::{
    rfvp_render::{
        pipelines::sprite::SpritePipeline,
        vertices::{PosColTexVertex, VertexSource},
        GpuCommonResources, GpuTexture, TextureBindGroup, VertexBuffer,
    },"""

GPU_ENUM_NEW = """use crate::{
    rfvp_render::{
        pipelines::sprite::SpritePipeline,
        vertices::{PosColTexVertex, VertexSource},
        GpuCommonResources, GpuTexture, RenderTarget, TextureBindGroup, VertexBuffer,
    },"""

# ------------------------------------------------ gpu_prim.rs: 枚举加 PrevFrame
GPU_KEY_OLD = """#[derive(Clone, Copy, Debug)]
enum DrawTextureKey {
    Graph(u16),
    White,
    Mask,
}"""

GPU_KEY_NEW = """#[derive(Clone, Copy, Debug)]
enum DrawTextureKey {
    Graph(u16),
    White,
    Mask,
    /// YukiHub: Sprt texture id -1 — the frame last shown (see `capture_prev_frame`).
    PrevFrame,
}"""

# ------------------------------------------------ gpu_prim.rs: 结构体字段
# 注意：`mask_dissolve` 补丁会在 `white` 之后插入 mask_frame/mask_texture，
# 所以本锚点要覆盖「已打 mask 补丁」和「未打」两种形态。
GPU_STRUCT_OLD = """    white: GpuTexture,
    mask_frame: super::mask_dissolve::MaskDissolveFrame,
    mask_texture: Option<GraphGpuEntry>,

    // Debug HUD: prim tile preview (opt-in via env)"""

GPU_STRUCT_NEW = """    white: GpuTexture,
    mask_frame: super::mask_dissolve::MaskDissolveFrame,
    mask_texture: Option<GraphGpuEntry>,
    /// YukiHub: the frame last shown, for Sprt texture id -1 (ADR-0002).
    prev_frame: Option<RenderTarget>,
    /// Whether the draw list built last samples `prev_frame`.
    wants_prev_frame: bool,

    // Debug HUD: prim tile preview (opt-in via env)"""

# ------------------------------------------------ gpu_prim.rs: 初始化
GPU_INIT_OLD = """            mask_frame: Default::default(),
            mask_texture: None,

            debug_tiles: Vec::new(),"""

GPU_INIT_NEW = """            mask_frame: Default::default(),
            mask_texture: None,
            prev_frame: None,
            wants_prev_frame: false,

            debug_tiles: Vec::new(),"""

# ------------------------------------------------ gpu_prim.rs: build 开头
GPU_BUILD_OLD = """    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {
        self.vertices.clear();
        self.draws.clear();
"""

GPU_BUILD_NEW = """    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {
        self.vertices.clear();
        self.draws.clear();
        // YukiHub: recomputed below when a visible Sprt -1 is collected.
        self.wants_prev_frame = false;
"""

# ------------------------------------------------ gpu_prim.rs: capture_prev_frame
# 注意：`stack_visit` 补丁会在 build() 的文档注释里加一行
# 「Each tree traversal uses its own bounded 4096-byte stack visitation buffer.」，
# 所以锚点只取 `pub fn build(` 本身，不包含前面的注释。
GPU_CAPTURE_OLD = """    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {"""

GPU_CAPTURE_NEW = """    /// YukiHub: copy the frame last shown out of `target` (before the next one is drawn into
    /// it) when the draw list built last has a Sprt with texture id -1.
    ///
    /// The original engine copies its back buffer into that texture after every present, so the
    /// texture always holds the previous presented frame (ADR-0002).
    pub fn capture_prev_frame(
        &mut self,
        resources: &GpuCommonResources,
        encoder: &mut wgpu::CommandEncoder,
        target: &RenderTarget,
    ) {
        if !self.wants_prev_frame {
            self.prev_frame = None;
            return;
        }
        let stale = self.prev_frame.as_ref().map_or(true, |f| {
            f.backing_size() != target.backing_size() || f.logical_size() != target.logical_size()
        });
        if stale {
            self.prev_frame = Some(RenderTarget::new(
                resources,
                target.logical_size(),
                target.backing_size(),
                Some("rfvp prev frame"),
            ));
        }
        target.encode_copy_to(encoder, self.prev_frame.as_ref().expect("prev frame allocated"));
    }

    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {"""

# ------------------------------------------------ gpu_prim.rs: bind group
GPU_BIND_OLD = """            DrawTextureKey::Graph(id) => self
                .graph_cache
                .get(&id)
                .expect("graph texture must be uploaded before use")
                .texture
                .bind_group(),
        }
    }"""

GPU_BIND_NEW = """            DrawTextureKey::Graph(id) => self
                .graph_cache
                .get(&id)
                .expect("graph texture must be uploaded before use")
                .texture
                .bind_group(),
            DrawTextureKey::PrevFrame => self
                .prev_frame
                .as_ref()
                .expect("prev frame captured")
                .bind_group(),
        }
    }"""

# ------------------------------------------------ gpu_prim.rs: Sprt -1 分支
GPU_SPRT_OLD = """            PrimType::PrimTypeSprt => {
                let tex_id = draw_prim.get_texture_id();
                let graph_id = if tex_id >= 0 {"""

GPU_SPRT_NEW = """            PrimType::PrimTypeSprt if draw_prim.get_texture_id() == -1 => {
                // YukiHub: the frame last shown, stretched over the screen (or the prim's W/H
                // with attr bit 1). The whole frame is always sampled; U/V are not used.
                let attr = draw_prim.get_attr();
                let (mut w, mut h) = (self.virtual_size.0 as f32, self.virtual_size.1 as f32);
                if (attr & 1) != 0 {
                    w = draw_prim.get_w() as f32;
                    h = draw_prim.get_h() as f32;
                }
                if w > 0.0 && h > 0.0 && draw_alpha > 0.0 {
                    let (pivot_x, pivot_y) = if (attr & 2) != 0 {
                        (draw_prim.get_opx() as f32, draw_prim.get_opy() as f32)
                    } else {
                        (0.0, 0.0)
                    };
                    let model = self.build_draw_model(
                        &draw_prim, parent_x, parent_y, draw_x, draw_y, 0.0, 0.0, pivot_x,
                        pivot_y, v3d_x, v3d_y, v3d_z,
                    );
                    self.wants_prev_frame = true;
                    self.emit_sprite_vertices(
                        model,
                        w,
                        h,
                        vec2(0.0, 0.0),
                        vec2(1.0, 1.0),
                        vec4(1.0, 1.0, 1.0, draw_alpha),
                        DrawTextureKey::PrevFrame,
                        draw_prim.get_blend(),
                    );
                }
            }

            PrimType::PrimTypeSprt => {
                let tex_id = draw_prim.get_texture_id();
                let graph_id = if tex_id >= 0 {"""

# ------------------------------------------------ app.rs: 调用 capture_prev_frame
APP_OLD = """                .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                    label: Some("rfvp frame encoder"),
                });"""

APP_NEW = """                .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                    label: Some("rfvp frame encoder"),
                });
        // YukiHub: Sprt texture id -1 draws the frame last shown, which is still in the
        // render target at this point (ADR-0002).
        self.prim_renderer
            .capture_prev_frame(&self.resources, &mut encoder, &self.render_target);"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_PREV_FRAME_MARKER: [u8; 19] = *b"YHFVP-PREVFRAME-1.0";
"""


def apply_edits(rel: str, sentinel: str, edits, add_marker: bool) -> int:
    path = Path(rel)
    if not path.exists():
        print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1

    text = path.read_text(encoding="utf-8")
    if sentinel in text:
        print(f"[OK]   {rel}: 已应用（幂等）")
        return 0

    for name, old, new in edits:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    if add_marker:
        text = text.rstrip("\n") + "\n\n" + MARKER_RUST

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {rel}: {len(edits)} 处已应用")
    return 0


def main() -> int:
    rc = apply_edits(RENDER_TARGET, "fn encode_copy_to", [("logical_size+encode_copy_to", RT_OLD, RT_NEW)], False)
    if rc != 0:
        return rc

    rc = apply_edits(
        GPU_PRIM,
        "DrawTextureKey::PrevFrame",
        [
            ("import RenderTarget", GPU_ENUM_OLD, GPU_ENUM_NEW),
            ("DrawTextureKey 加 PrevFrame", GPU_KEY_OLD, GPU_KEY_NEW),
            ("结构体字段", GPU_STRUCT_OLD, GPU_STRUCT_NEW),
            ("初始化", GPU_INIT_OLD, GPU_INIT_NEW),
            ("capture_prev_frame", GPU_CAPTURE_OLD, GPU_CAPTURE_NEW),
            ("build 清标志", GPU_BUILD_OLD, GPU_BUILD_NEW),
            ("bind group 分支", GPU_BIND_OLD, GPU_BIND_NEW),
            ("Sprt -1 分支", GPU_SPRT_OLD, GPU_SPRT_NEW),
        ],
        True,
    )
    if rc != 0:
        return rc

    return apply_edits(APP_RS, "capture_prev_frame(&self.resources", [("调用点", APP_OLD, APP_NEW)], False)


if __name__ == "__main__":
    sys.exit(main())