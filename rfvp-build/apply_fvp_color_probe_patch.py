#!/usr/bin/env python3
"""YukiHub 诊断探针 v2：只盯 `ColorSet`，查「对话框背景颜色/透明度不生效」。

v1 的教训
---------
v1 把 `parent_alpha` 打进了 `collect_tree`，结果：
  - 每帧几十条，17k 条探针把 logcat 配额打爆（**丢弃 60 万行**，关键日志全没了）；
  - 而且**证实了假设 A 是错的**：顶层 Group（depth=1，7 个）alpha 全是 0，
    若真补 parent_alpha 累积，14609 个图元会全变 alpha=0（画面消失）。
  -> 所以「累积父 alpha」这条路**放弃**，本补丁 v2 完全不动 collect_tree。

v1 仍然有价值的发现
------------------
绘制 Tile 时 alpha = self_a/255 * tile_a/255，其中 tile_a 来自
`color_manager.get_entry(tile_id)`，**这条链路是通的**（日志里 tile_a 出现过
78 / 128 / 8，说明调色板 alpha 确实被读出来并参与绘制）。

那问题只可能在**上游没把颜色写进去**（`ColorSet` 没调 / 调了但值不对）。
所以 v2 只在 `color_set()` 里打一行，回答三个问题：
  1. 游戏到底调没调 ColorSet？
  2. 调了哪些色号、设的 RGBA 是多少？
  3. 有没有「设置了但和绘制时读到的对不上」的情况？

输出量
------
`ColorSet` 每次改颜色只调一次，一局游戏通常几十次量级 —— 不会刷屏。

用法
----
    python3 apply_fvp_color_probe_patch.py
    adb logcat -c && adb logcat -s rfvp > /tmp/log.txt
    # 进游戏，翻几页对话，并去设置里改一次对话框颜色
    grep 'YHPROBE COLOR' /tmp/log.txt
"""
import sys
from pathlib import Path

COLOR_RS = "crates/rfvp/src/subsystem/components/syscalls/color.rs"

MARKER = "YHPROBE COLOR"

# 在 color_set 的四个 set_* 之后、返回之前插一条日志。
# 锚点取「四个 if let 之后 + Ok(Variant::Nil)」这段，唯一。
ANCHOR_OLD = """    if let Variant::Int(a) = a {
        color.set_a(*a as u8);
    }

    Ok(Variant::Nil)
}"""

ANCHOR_NEW = """    if let Variant::Int(a) = a {
        color.set_a(*a as u8);
    }

    // ---- YukiHub color_probe: 记录脚本对调色板的每一次写入 ----
    // 用来回答：游戏有没有调 ColorSet、改了哪条色号、设的 RGBA 是多少。
    // 只读，不改变任何行为。
    {
        let cur = game_data.motion_manager.color_manager.get_entry(id);
        log::info!(
            "YHPROBE COLOR set id={} r={} g={} b={} a={}",
            id,
            cur.get_r(),
            cur.get_g(),
            cur.get_b(),
            cur.get_a(),
        );
    }

    Ok(Variant::Nil)
}"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_COLORPROBE_MARKER: [u8; 20] = *b"YHFVP-COLORPROBE-1.0";
"""


def patch(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if MARKER in text:
        print(f"[OK]   {COLOR_RS}: 已应用（幂等）")
        return 0

    n = text.count(ANCHOR_OLD)
    if n != 1:
        print(f"[FAIL] {COLOR_RS}: 锚点命中 {n} 次（期望 1）—— 上游源码已变动")
        return 1

    text = text.replace(ANCHOR_OLD, ANCHOR_NEW, 1)
    text = text.rstrip("\n") + "\n\n" + MARKER_RUST
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {COLOR_RS}: ColorSet 探针已注入")
    print()
    print("下一步：")
    print("  adb logcat -c && adb logcat -s rfvp > /tmp/log.txt")
    print("  进游戏翻几页对话 + 去设置里改一次对话框颜色")
    print("  grep 'YHPROBE COLOR' /tmp/log.txt")
    return 0


def main() -> int:
    p = Path(COLOR_RS)
    if not p.exists():
        print(f"[FAIL] {COLOR_RS} 不存在 —— 上游可能改了目录结构，探针无法应用")
        return 1
    return patch(p)


if __name__ == "__main__":
    sys.exit(main())