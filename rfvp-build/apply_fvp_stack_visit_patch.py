#!/usr/bin/env python3
"""YukiHub 补丁：prim 树遍历的 visit 缓冲改用栈数组（省堆分配）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `cbe9f1fa`（"perf(render): use bounded stack
traversal scratch"，2026-10-16，PR #54）。

背景
----
`GpuPrimRenderer::build()` 每帧要遍历两棵 prim 树（root 0 + 可选 overlay
root），每次遍历都 `vec![0u8; 4096]` 分配一块堆内存，用完就丢。

每帧 2 次分配 × 60fps = 120 次/秒的 4 KiB 堆分配/释放，纯属浪费。

修复
----
`vec![0u8; 4096]` → `[0u8; 4096]`（栈上数组）。

上游的措辞是 "bounded"：遍历深度上限由 `visit` 长度约束（4096 项），
换成栈数组后这个上界不变，只是不再走堆。

行为影响
--------
纯性能改动，渲染结果完全不变。
"""
import sys
from pathlib import Path

GPU_PRIM = "crates/rfvp/src/rendering/gpu_prim.rs"

MARKER = "YHFVP-STACKVISIT-1.0"

# 两处调用点：root 0 与 overlay root。
# 注意：两处的 `let mut visit = vec![0u8; 4096];` 前缀相同，必须带上后续行
# （`self.collect_tree(`）才能唯一定位，否则 count == 2。
EDITS = [
    (
        "A/root 0",
        """            let mut visit = vec![0u8; 4096];
            self.collect_tree(""",
        """            let mut visit = [0u8; 4096];
            self.collect_tree(""",
    ),
    (
        "B/overlay root",
        """            let mut visit = vec![0u8; 4096]; // do NOT reuse visit; allow redraw
            self.collect_tree(""",
        """            let mut visit = [0u8; 4096]; // do NOT reuse visit; allow redraw
            self.collect_tree(""",
    ),
]

DOC_OLD = """    /// Build geometry and draw items from the current primitive tree.
    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {"""

DOC_NEW = """    /// Build geometry and draw items from the current primitive tree.
    /// Each tree traversal uses its own bounded 4096-byte stack visitation buffer.
    pub fn build(&mut self, resources: &GpuCommonResources, motion: &MotionManager) {"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_STACK_VISIT_MARKER: [u8; 20] = *b"YHFVP-STACKVISIT-1.0";
"""


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

    n = text.count(DOC_OLD)
    if n != 1:
        print(f"[FAIL] {GPU_PRIM}: 锚点 build() 文档注释命中 {n} 次（期望 1）")
        return 1
    text = text.replace(DOC_OLD, DOC_NEW, 1)

    # CI 标记追加到文件末尾（grep .so 可验证）。
    text = text.rstrip("\n") + "\n\n" + MARKER_RUST
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {GPU_PRIM}: 2 处 visit 改栈数组 + 文档 + CI 标记")
    return 0


def main() -> int:
    p = Path(GPU_PRIM)
    if not p.exists():
        # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
        print(f"[FAIL] {GPU_PRIM} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1
    return patch(p)


if __name__ == "__main__":
    sys.exit(main())