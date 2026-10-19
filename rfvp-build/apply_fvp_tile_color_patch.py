#!/usr/bin/env python3
"""YukiHub 补丁：Tile 色号 -1 = 全局「窗口颜色」（修对话框背景不透明）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `cc8ac77`（2026-10-19），对应
`docs/adr/0001-tile-color-minus-one.md`，closes issue **#55**。

问题
----
`はっぴぃ☆マーガレット!` 的对话框/名字框被画成**不透明白色**，原版是**半透明粉**；
设置里的「窗口颜色」R/G/B/A 滑块**拖了没反应**。

根因（上游 IDA 结论 + 我们探针实测一致）
---------------------------------------
脚本创建这两个框用的是 `PrimSetTile(513, -1, ...)` / `PrimSetTile(514, -1, ...)`
—— 字节码真的推的是 `-1`（`push_i8 0xFF`），**-1 是刻意的**。

| Tile id | 原引擎行为 |
|---|---|
| **-1** | **不查调色板**，每帧读全局 `G[5..8]` 作为 R/G/B/A（取低字节） |
| 其它 | 查调色板 `[base + id*4 + 0xabd74]` |

而 `G[5..8]`（游戏有 1272 个非易失全局，所以是 `G[1277..1280]`）**正是配置界面
「窗口颜色」滑块编辑的那四个全局**。引擎每帧读，所以拖滑块立刻生效。

我们此前把 -1 当成调色板槽 255（`tile as u8`），于是：
1. Tile -1 画的是槽 255 的颜色；
2. 游戏初始化执行 `ColorSet(-1, 174,118,118,170)` 时，我们的 `ColorSet`
   先 `id as u8` 把 -1 截成 255，**写进了槽 255**；
3. 紧接着 `ColorSet(255, 255,255,255,255)` 又把它覆盖成**不透明白**。

→ 最终对话框就是不透明白色。

修复
----
1. `color_manager.rs`：新增 `tile_color(tile)` —— `-1` 时读 `get_int_var(5..8)`，
   其它走调色板。GPU / host-command / soft 三个渲染器统一走它。
2. `gpu_prim.rs`：Tile 分支 `get_entry(color_id as u8)` → `tile_color(...)`。
   （我们只走 GPU 路径；`prim_commands.rs` / `soft_render` 在本仓库不参与 Android 构建，
   且它们与上游 10.18 已有差异，不移植，见 §5。）

依赖
----
`crate::script::global::get_int_var(u16) -> i32` —— **我们基线里早就有**（`script/global.rs`），
无需新增任何 API。

行为影响
--------
- Tile -1 不再占用调色板槽 255，`ColorSet(-1, ...)` 的误写问题也一并消失
  （另一半修复见 `apply_fvp_colorset_signed_patch.py`）。
- 对话框取玩家选的窗口颜色，且随滑块实时变化。
- 其它 Tile id 行为完全不变。
"""
import sys
from pathlib import Path

COLOR_MANAGER = "crates/rfvp/src/subsystem/resources/color_manager.rs"
GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"

MARKER = "YHFVP-TILECOLOR-1.0"

# ---------------------------------------------- color_manager.rs: 加 import
CM_IMPORT_OLD = """use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ColorItem {"""

CM_IMPORT_NEW = """use serde::{Deserialize, Serialize};

use crate::script::global::get_int_var;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ColorItem {"""

# ------------------------------ color_manager.rs: 加 tile_color 方法
CM_METHOD_OLD = """    pub fn get_entry(&self, id: u8) -> &ColorItem {
        &self.colors[id as usize]
    }
}"""

CM_METHOD_NEW = """    pub fn get_entry(&self, id: u8) -> &ColorItem {
        &self.colors[id as usize]
    }

    /// YukiHub: the colour a Tile primitive draws with.
    ///
    /// Tile id `-1` bypasses the palette: the original engine reads R, G, B, A from the
    /// volatile globals 5..8 every frame (the message-window colour the config screens edit),
    /// keeping the low byte of each. Any other id is a palette lookup.
    ///
    /// See upstream ADR-0001 (`docs/adr/0001-tile-color-minus-one.md`, issue #55).
    pub fn tile_color(&self, tile: i16) -> ColorItem {
        if tile == -1 {
            let channel = |i| get_int_var(i) as u8;
            return ColorItem {
                r: channel(5),
                g: channel(6),
                b: channel(7),
                a: channel(8),
            };
        }
        self.colors[tile as u8 as usize].clone()
    }
}"""

# ------------------------------------------- gpu_prim.rs: Tile 分支取色
GPU_OLD = """            PrimType::PrimTypeTile => {
                let w = draw_prim.get_w() as f32;
                let h = draw_prim.get_h() as f32;
                let color_id = draw_prim.get_tile();
                let c = color_manager.get_entry(color_id as u8);"""

GPU_NEW = """            PrimType::PrimTypeTile => {
                let w = draw_prim.get_w() as f32;
                let h = draw_prim.get_h() as f32;
                // YukiHub: tile id -1 reads the window colour from globals (ADR-0001).
                let c = color_manager.tile_color(draw_prim.get_tile());"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_TILE_COLOR_MARKER: [u8; 19] = *b"YHFVP-TILECOLOR-1.0";
"""

EDITS_CM = [
    ("import get_int_var", CM_IMPORT_OLD, CM_IMPORT_NEW),
    ("tile_color 方法", CM_METHOD_OLD, CM_METHOD_NEW),
]

EDITS_GPU = [
    ("Tile 分支取色", GPU_OLD, GPU_NEW),
]


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
    rc = apply_edits(COLOR_MANAGER, "fn tile_color", EDITS_CM, add_marker=True)
    if rc != 0:
        return rc
    return apply_edits(GPU_PRIM, "tile_color(draw_prim.get_tile())", EDITS_GPU, add_marker=False)


if __name__ == "__main__":
    sys.exit(main())