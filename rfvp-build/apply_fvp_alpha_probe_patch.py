#!/usr/bin/env python3
"""YukiHub 诊断探针：对话框背景不透明 —— 查 prim 树的 alpha 从哪来。

要回答的问题
------------
用户报告：对话框背景是**不透明白色**，原版（wine）是半透明；
且「设置里的对话框颜色」不生效。上游也有同样现象。

代码事实（已核实）
------------------
1. 对话框背景 = `PrimTypeTile`，颜色取 `color_manager.get_entry(tile_id)`：
       alpha = 图元自身 alpha/255 * 调色板 alpha/255
2. `prim_set_tile()` 按 IDA 逆向把 **alpha 默认设为 255**（原引擎行为，不是 bug），
   之后靠脚本调 `PrimSetAlpha` 改。
3. `collect_tree()` **只累积位置**（parent_x/parent_y），**不累积 alpha**：
       let draw_alpha = base_prim.get_alpha() as f32 / 255.0;
   `PrimTypeGroup` 更是直接 `// No direct draw.` —— Group 的 alpha 被完全忽略。
4. 三个渲染器（gpu_prim / prim_commands / soft_render）全都如此，上游 10.18 也一样。

待验证的假设
------------
A. 游戏把 alpha 设在**父 Group** 上，子 Tile 不继承 -> 需要补 parent_alpha 累积；
B. 游戏根本没调 PrimSetAlpha -> 是别的机制缺失，改累积没用。

探针设计
--------
只挂一个点（`gpu_prim.rs::collect_tree`），只在「自己 alpha != 255 或任一祖先 alpha != 255」
时才打印，避免刷屏。输出三样东西：
  - 当前 prim 的 self alpha（self_a）
  - 从根到父的**累积** alpha（anc_a，乘出来的）
  - 从根到父**最大**的单个 alpha（anc_min，用来判断「是不是某一层设了透明度」）
  - type / tile 色号 / 调色板 alpha（tile_a，只对 Tile 有意义）

判读方式
--------
- 若大量 `self_a=255` 且 `anc_a<255`  -> **假设 A 成立**，补 parent_alpha 累积即可。
- 若 `anc_a` 也全是 255，但 Tile 画出来仍不透明 -> 假设 B，另找机制。
- 若 `self_a<255` 已经生效但仍白 -> 说明问题不在 alpha，而在颜色来源（tile 色号/ColorSet）。

用法
----
    python3 apply_fvp_alpha_probe_patch.py        # 打上
    adb logcat -s rfvp | grep YHPROBE

关闭：把本文件里的 `PROBE_ON` 改成 False 重新构建，或直接不执行本脚本。
"""
import sys
from pathlib import Path

GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"

MARKER = "YHPROBE ALPHA"

# 幂等哨兵（写在生成代码里）
SENTINEL = "alpha_probe"

# --- 1) 签名：加一个 parent_alpha 参数 ---
SIG_OLD = """        prim_id: i16,
        parent_x: f32,
        parent_y: f32,
        visit: &mut [u8],
        depth: usize,
    ) {"""

SIG_NEW = """        prim_id: i16,
        parent_x: f32,
        parent_y: f32,
        // YukiHub 探针：从根到父累积下来的 alpha（0.0~1.0），仅用于诊断。
        parent_alpha: f32,
        visit: &mut [u8],
        depth: usize,
    ) {"""

# --- 2) 探针本体：插在 draw_alpha 计算之后 ---
PROBE_OLD = """        let draw_alpha = base_prim.get_alpha() as f32 / 255.0;

        let mut draw_id = prim_id;"""

PROBE_NEW = """        let draw_alpha = base_prim.get_alpha() as f32 / 255.0;

        // ---- YukiHub alpha_probe: 诊断「对话框背景不透明」----
        // 只在「自己不是满不透明」或「祖先有透明度」时打印，避免每帧刷屏。
        {
            let self_a = base_prim.get_alpha();
            if self_a != 255 || parent_alpha < 1.0 {
                let probe_type = base_prim.get_type();
                let probe_tile = base_prim.get_tile();
                let tile_a = if matches!(probe_type, PrimType::PrimTypeTile) {
                    color_manager.get_entry(probe_tile as u8).get_a()
                } else {
                    255
                };
                log::info!(
                    "YHPROBE ALPHA prim={} depth={} self_a={} parent_a={:.3} eff_a={:.3} tile={} tile_a={} type={:?}",
                    prim_id,
                    depth,
                    self_a,
                    parent_alpha,
                    parent_alpha * (self_a as f32 / 255.0),
                    probe_tile,
                    tile_a,
                    probe_type,
                );
            }
        }

        let mut draw_id = prim_id;"""

# --- 3) 递归：把累积 alpha 传下去 ---
RECURSE_OLD = """        let next_parent_x = parent_x + draw_x;
        let next_parent_y = parent_y + draw_y;
        for cid in children {
            self.collect_tree("""

RECURSE_NEW = """        let next_parent_x = parent_x + draw_x;
        let next_parent_y = parent_y + draw_y;
        // YukiHub alpha_probe: 把累积 alpha 传给子节点（诊断用，不参与绘制）。
        let next_parent_alpha = parent_alpha * draw_alpha;
        for cid in children {
            self.collect_tree("""

RECURSE_CALL_OLD = """                cid,
                next_parent_x,
                next_parent_y,
                visit,
                depth + 1,
            );"""

RECURSE_CALL_NEW = """                cid,
                next_parent_x,
                next_parent_y,
                next_parent_alpha,
                visit,
                depth + 1,
            );"""

# --- 4) 两个入口调用点：根节点 parent_alpha = 1.0 ---
ROOT_OLD = """                0, // root
                0.0,
                0.0,
                &mut visit,
                0,
            );"""

ROOT_NEW = """                0, // root
                0.0,
                0.0,
                1.0, // YukiHub alpha_probe: 根节点不透明
                &mut visit,
                0,
            );"""

OVERLAY_OLD = """                root,
                0.0,
                0.0,
                &mut visit,
                0,
            );"""

OVERLAY_NEW = """                root,
                0.0,
                0.0,
                1.0, // YukiHub alpha_probe: 根节点不透明
                &mut visit,
                0,
            );"""

# --- 5) CI 标记 ---
MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_ALPHAPROBE_MARKER: [u8; 20] = *b"YHFVP-ALPHAPROBE-1.0";
"""

EDITS = [
    ("签名加 parent_alpha", SIG_OLD, SIG_NEW),
    ("探针本体", PROBE_OLD, PROBE_NEW),
    ("递归传递", RECURSE_OLD, RECURSE_NEW),
    ("递归调用参数", RECURSE_CALL_OLD, RECURSE_CALL_NEW),
    ("root 入口", ROOT_OLD, ROOT_NEW),
    ("overlay 入口", OVERLAY_OLD, OVERLAY_NEW),
]


def patch(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if MARKER in text:
        print(f"[OK]   {GPU_PRIM}: 已应用（幂等）")
        return 0

    for name, old, new in EDITS:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {GPU_PRIM}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    text = text.rstrip("\n") + "\n\n" + MARKER_RUST
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {GPU_PRIM}: alpha 探针已注入（{len(EDITS)} 处）")
    print()
    print("下一步：")
    print("  adb logcat -c && adb logcat -s rfvp > /tmp/log.txt")
    print("  进游戏到有对话框的对话处，翻几页")
    print("  grep 'YHPROBE ALPHA' /tmp/log.txt | head -40")
    return 0


def main() -> int:
    p = Path(GPU_PRIM)
    if not p.exists():
        print(f"[FAIL] {GPU_PRIM} 不存在 —— 上游可能改了目录结构，探针无法应用")
        return 1
    return patch(p)


if __name__ == "__main__":
    sys.exit(main())