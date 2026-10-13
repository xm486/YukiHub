#!/usr/bin/env python3
"""YukiHub 补丁：`PrimSetBlend(id, 1)` 的 prim 走加法混合（对齐原引擎语义）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `ffc73809`（"fix blend"，2026-10-09），
**只取 Android/wgpu 需要的部分**（上游同时改的 `sprite_wasm.rs` 与
`soft_render/` 我们走不到，不移植）。

背景
----
原引擎里 `PrimSetBlend(id, 1)` 表示 **加法混合**：
SRCBLEND = SRCALPHA、DESTBLEND = ONE（而不是常规的 SRCALPHA / INVSRCALPHA）。

rfvp 此前对所有 prim 一律用 `wgpu::BlendState::ALPHA_BLENDING`，
于是「本该叠加发光」的图层被当成普通半透明贴图画出来 —— 典型症状是
FVP HD 里 `<BG>_parts1/2` 这类接近纯色的叠加层，会在背景上压出一条
**硬边暗带**（issue #38 的现象之一）。

修复
----
1. `sprite.rs`：多建一条 additive 混合管线，`draw()` 转发到新的 `draw_blended()`；
2. `gpu_prim.rs`：`DrawItem` 带上 `additive` 标志，4 个 `emit_sprite_vertices`
   调用点从 `DrawPrim::get_blend()` 取值。

行为影响
--------
只有脚本显式设过 `PrimSetBlend(id, 1)` 的 prim 会改变混合方式，
其余 prim 仍走原来的 alpha 混合 —— 属于"把错误行为改回原引擎行为"。
"""
import re
import sys
from pathlib import Path

SPRITE = "crates/rfvp/src/rfvp_render/pipelines/sprite.rs"
GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"

MARKER = "YukiHub additive blend patch"

# ---------------------------------------------------------------- sprite.rs

SPRITE_A_OLD = """use crate::rfvp_render::vertices::{PosColTexVertex, VertexSource};
use crate::rfvp_render::{BindGroupLayouts, TextureBindGroup};

pub struct SpritePipeline {
    pipeline: wgpu::RenderPipeline,
}
"""

SPRITE_A_NEW = """use crate::rfvp_render::vertices::{PosColTexVertex, VertexSource};
use crate::rfvp_render::{BindGroupLayouts, TextureBindGroup};

/// Additive blending as used by the original engine for blend=1 prims:
/// SRCBLEND = SRCALPHA, DESTBLEND = ONE.
fn additive_blend_state() -> wgpu::BlendState {
    wgpu::BlendState {
        color: wgpu::BlendComponent {
            src_factor: wgpu::BlendFactor::SrcAlpha,
            dst_factor: wgpu::BlendFactor::One,
            operation: wgpu::BlendOperation::Add,
        },
        // Additive layers must not change the destination coverage.
        alpha: wgpu::BlendComponent {
            src_factor: wgpu::BlendFactor::Zero,
            dst_factor: wgpu::BlendFactor::One,
            operation: wgpu::BlendOperation::Add,
        },
    }
}

pub struct SpritePipeline {
    pipeline: wgpu::RenderPipeline,
    pipeline_additive: wgpu::RenderPipeline,
}
"""

SPRITE_B_OLD = """        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("rfvp_render.sprite_pipeline"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: "vs_main",
                buffers: &[vertex_layout],
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: "fs_main",
                targets: &[Some(wgpu::ColorTargetState {
                    format: target_format,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleList,
                strip_index_format: None,
                front_face: wgpu::FrontFace::Ccw,
                cull_mode: None,
                polygon_mode: wgpu::PolygonMode::Fill,
                unclipped_depth: false,
                conservative: false,
            },
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
        });

        Self { pipeline }
"""

SPRITE_B_NEW = """        let make_pipeline = |blend: wgpu::BlendState| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("rfvp_render.sprite_pipeline"),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: "vs_main",
                    buffers: &[vertex_layout.clone()],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &shader,
                    entry_point: "fs_main",
                    targets: &[Some(wgpu::ColorTargetState {
                        format: target_format,
                        blend: Some(blend),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: wgpu::PrimitiveState {
                    topology: wgpu::PrimitiveTopology::TriangleList,
                    strip_index_format: None,
                    front_face: wgpu::FrontFace::Ccw,
                    cull_mode: None,
                    polygon_mode: wgpu::PolygonMode::Fill,
                    unclipped_depth: false,
                    conservative: false,
                },
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview: None,
            })
        };
        let pipeline = make_pipeline(wgpu::BlendState::ALPHA_BLENDING);
        let pipeline_additive = make_pipeline(additive_blend_state());

        Self {
            pipeline,
            pipeline_additive,
        }
"""

SPRITE_C_OLD = """        transform: Mat4,
    ) {
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, tex.raw(), &[]);
"""

SPRITE_C_NEW = """        transform: Mat4,
    ) {
        self.draw_blended(pass, src, tex, transform, false);
    }

    /// Draw with either normal alpha blending or additive blending (`PrimSetBlend(id, 1)`).
    pub fn draw_blended<'a>(
        &'a self,
        pass: &mut wgpu::RenderPass<'a>,
        src: VertexSource<'a>,
        tex: &'a TextureBindGroup,
        transform: Mat4,
        additive: bool,
    ) {
        pass.set_pipeline(if additive {
            &self.pipeline_additive
        } else {
            &self.pipeline
        });
        pass.set_bind_group(0, tex.raw(), &[]);
"""

# -------------------------------------------------------------- gpu_prim.rs

GP_A_OLD = """struct DrawItem {
    tex: DrawTextureKey,
    vertex_range: std::ops::Range<u32>,
}
"""

GP_A_NEW = """struct DrawItem {
    tex: DrawTextureKey,
    /// `PrimSetBlend(id, 1)`: SRCALPHA/ONE instead of SRCALPHA/INVSRCALPHA.
    additive: bool,
    vertex_range: std::ops::Range<u32>,
}
"""

GP_B_OLD = """        color: Vec4,
        tex: DrawTextureKey,
    ) {
        let base = self.vertices.len() as u32;
"""

GP_B_NEW = """        color: Vec4,
        tex: DrawTextureKey,
        additive: bool,
    ) {
        let base = self.vertices.len() as u32;
"""

GP_C_OLD = """        self.draws.push(DrawItem {
            tex,
            vertex_range: base..base + 6,
        });
"""

GP_C_NEW = """        self.draws.push(DrawItem {
            tex,
            additive,
            vertex_range: base..base + 6,
        });
"""

GP_D_OLD = """            let src = self.vb.vertex_source_slice(item.vertex_range.clone());
            sprite_pipeline.draw(render_pass, src, self.texture_bind_group(item.tex), proj);
"""

GP_D_NEW = """            let src = self.vb.vertex_source_slice(item.vertex_range.clone());
            sprite_pipeline.draw_blended(
                render_pass,
                src,
                self.texture_bind_group(item.tex),
                proj,
                item.additive,
            );
"""

# `emit_sprite_vertices(...)` 调用点：参数表末尾是 `DrawTextureKey::Xxx,` 换行 + 缩进 + `);`
# 3 处 Graph + 1 处 White 取自 `DrawPrim::get_blend()`；
# 若 mask dissolve 补丁已应用，会多出 1 处 Mask（合成层固定用普通 alpha，传字面量 false）。
CALL_RE = re.compile(
    r"(?P<indent>\n[ \t]*)DrawTextureKey::(Graph\(graph_id\)|White|Mask),\n(?P<close>[ \t]*)\);"
)


def patch_sprite(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if "pipeline_additive" in text:
        print(f"[OK]   {SPRITE}: 已应用（幂等）")
        return 0

    for name, old, new in (
        ("A/字段+混合状态", SPRITE_A_OLD, SPRITE_A_NEW),
        ("B/双管线", SPRITE_B_OLD, SPRITE_B_NEW),
        ("C/draw_blended", SPRITE_C_OLD, SPRITE_C_NEW),
    ):
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {SPRITE}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {SPRITE}: additive 管线已应用（3 处）")
    return 0


def patch_gpu_prim(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if "additive: bool," in text:
        print(f"[OK]   {GPU_PRIM}: 已应用（幂等）")
        return 0

    for name, old, new in (
        ("A/DrawItem", GP_A_OLD, GP_A_NEW),
        ("B/emit_sprite_vertices 签名", GP_B_OLD, GP_B_NEW),
        ("C/draws.push", GP_C_OLD, GP_C_NEW),
        ("D/draw_items_with_proj", GP_D_OLD, GP_D_NEW),
    ):
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {GPU_PRIM}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    # 调用点：3 处 Graph + 1 处 White 取 prim 自己的 blend 标志；
    # 若 mask dissolve 补丁已应用，再多 1 处 Mask（合成层固定普通 alpha）。
    mask_done = "mask_frame" in text
    expected = 5 if mask_done else 4
    hits = CALL_RE.findall(text)
    if len(hits) != expected:
        print(
            f"[FAIL] {GPU_PRIM}: emit_sprite_vertices 调用点命中 {len(hits)} 处"
            f"（期望 {expected}；mask dissolve 已应用={mask_done}）"
        )
        return 1

    def repl(m: "re.Match[str]") -> str:
        indent = m.group("indent")
        key = m.group(2)
        arg = "false" if key == "Mask" else "draw_prim.get_blend()"
        return f"{indent}DrawTextureKey::{key},{indent}{arg},\n{m.group('close')});"

    text = CALL_RE.sub(repl, text)
    if mask_done:
        print(f"[OK]   {GPU_PRIM}: 已顺带补上 mask dissolve 调用点的 additive=false")

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {GPU_PRIM}: additive 字段 + {len(hits)} 处调用点已应用")
    return 0


def main() -> int:
    for rel in (SPRITE, GPU_PRIM):
        if not Path(rel).exists():
            # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
            print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
            return 1

    rc = patch_sprite(Path(SPRITE))
    if rc != 0:
        return rc
    return patch_gpu_prim(Path(GPU_PRIM))


if __name__ == "__main__":
    sys.exit(main())
