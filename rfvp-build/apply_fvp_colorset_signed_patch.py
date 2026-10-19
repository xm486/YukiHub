#!/usr/bin/env python3
"""YukiHub 补丁：`ColorSet` 用有符号 id 检查，`-1` 不再写坏调色板槽 255。

来源
----
移植自上游 xmoezzz/rfvp 提交 `cc8ac77`（2026-10-19），
`docs/adr/0001-tile-color-minus-one.md`，closes issue **#55**。

问题
----
原引擎 `ColorSet`（`0x4132c0`）做的是**有符号** `0 <= id < 256` 检查，
所以 `ColorSet(-1, ...)` **什么都不做**。

我们的实现是：

    let id = match id { Variant::Int(id) => *id, ... };
    let id = id as u8;              // ← 这里 -1 被截成 255
    if !(0..=255).contains(&id) { ... }   // ← 检查的是 u8，永远成立

于是 `-1` 被当成 `255` 写进去了。

对 `はっぴぃ☆マーガレット!` 的连锁后果：

    ColorSet(-1,  174,118,118,170);   // 本想设半透明粉 → 实际写进槽 255
    ColorSet(255, 255,255,255,255);   // 紧接着把槽 255 覆盖成不透明白

而 Tile -1 又被我们错当成槽 255（见 `apply_fvp_tile_color_patch.py`），
两个 bug 叠在一起 → 对话框成了不透明白色。

修复
----
把 `as u8` 的截断去掉，改成对**有符号 i32** 做范围检查。
`-1` 与任何负数/超范围值都会被静默忽略（与原引擎一致）。

配合 `apply_fvp_tile_color_patch.py`（Tile -1 读全局窗口色），
两个补丁合起来才完整 —— 建议按顺序都应用。

行为影响
--------
- `ColorSet(-1, ...)` 不再误写槽 255。
- `ColorSet` 对 0..=255 的正常调用行为完全不变。
"""
import sys
from pathlib import Path

COLOR_RS = "crates/rfvp/src/subsystem/components/syscalls/color.rs"

MARKER = "YHFVP-COLORSET-SIGNED-1.0"

OLD = """    let id = id as u8; // compiler optimization

    if !(0..=255).contains(&id) {
        log::error!("id must be in range 0..256");
        return Ok(Variant::Nil);
    }"""

NEW = """    // YukiHub: signed check, as in the original. `ColorSet(-1, ...)` is ignored rather than
    // writing palette slot 255 — Tile id -1 is not a palette slot; it reads the window colour
    // from globals (see ColorManager::tile_color / ADR-0001, issue #55).
    if !(0..=255).contains(&id) {
        log::error!("id must be in range 0..256");
        return Ok(Variant::Nil);
    }
    let id = id as u8;"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_COLORSET_SIGNED_MARKER: [u8; 25] = *b"YHFVP-COLORSET-SIGNED-1.0";
"""


def patch(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if MARKER in text:
        print(f"[OK]   {COLOR_RS}: 已应用（幂等）")
        return 0

    n = text.count(OLD)
    if n != 1:
        print(f"[FAIL] {COLOR_RS}: 锚点命中 {n} 次（期望 1）—— 上游源码已变动")
        return 1

    text = text.replace(OLD, NEW, 1)
    text = text.rstrip("\n") + "\n\n" + MARKER_RUST
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {COLOR_RS}: ColorSet 改为有符号检查")
    return 0


def main() -> int:
    p = Path(COLOR_RS)
    if not p.exists():
        print(f"[FAIL] {COLOR_RS} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1
    return patch(p)


if __name__ == "__main__":
    sys.exit(main())