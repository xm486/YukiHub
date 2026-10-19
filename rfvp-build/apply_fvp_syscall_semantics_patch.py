#!/usr/bin/env python3
"""YukiHub 补丁：三个小 syscall 语义修正（PrimSetZ / TimerSuspend / PrimSetRS）。

来源
----
移植自上游 xmoezzz/rfvp 提交 `cc8ac77`（2026-10-19），对应
`docs/adr/0003-prim-scale-clamp.md` 与 `docs/adr/0004-true-type-tag.md`。

原引擎 VM 的类型标签是：0=Nil、1=True、2=Int、3=Float、4/5=String。
全量扫 143 个 syscall 处理器，只有三处拿标签 1/3 做比较：
`FloatToInt`(3)、`PrimSetZ`(1)、`TimerSuspend`(1)。我们有两处判错了。

一、`PrimSetZ` 判 `True`，不是 `Float`
---------------------------------------
原引擎（Marguerite `0x415650` / Sakura `0x43bff0`）：
- Int：钳到 [100, 10000]，存值，设 attr bit 0x04
- **标签 1（True）：保留 z，设 attr bit 0x04**
- 其它：清除 attr bit 0x04
- 无论如何：设 dirty bit 0x40

我们把「True 分支」写成了 `z.as_float().is_some()`，于是 **True 反而清掉了 bit**。

二、`TimerSuspend` 判 `True`，不是「真值」
------------------------------------------
原引擎存的是 `type != 1`，计时器 tick（`0x412430`）在该标志置位时**跳过所有计时器**。
所以**只有 True 会恢复计时器**，nil / Int / 其它值一律**暂停**。

我们写的是 `!on.canbe_true()`，于是传 **Int 时反而恢复了计时器**。

> 与 `apply_fvp_timer_stop_patch.py` 的关系：那个补丁改的是 `tick()`（到点即停），
> 本补丁改的是 `timer_suspend()`，**两者不冲突**，可叠加。

三、`PrimSetRS` / `PrimSetRS2` 缩放钳到 [100, 10000]
----------------------------------------------------
原引擎（`PrimSetRS` `0x4153e0`；`PrimSetRS2` Sakura `0x43bb80`）：
- Int 缩放：**钳制**到 [100, 10000]
- 非 Int：**保持不变**（用当前值）

我们的实现是「超出 0..=10000 就重置成 1000，0..=99 原样接受」：
- `PrimSetRS2(id, nil, 0, 0)` 本来该画成 10%，我们把它缩成 0（看不见）
- 超范围的缩放会突然跳回 100%

行为影响
--------
- 缩放不再「归零/跳回」，改为按原引擎钳制。
- `TimerSuspend` 传 Int 时暂停计时器（原为恢复）。
- `PrimSetZ` 传 `True` 时保留 z 并启用 Z（原为清掉）。

本地三个作品（Marguerite / AngelWish / WA_funta）里没人给 `PrimSetZ` 传字面量
`true`/`float`，也没人调 `TimerSuspend`，所以这两条对现有作品无可见变化；
`PrimSetRS2(..., 0, 0)` 在 WA_funta 里有用到，那条会生效。
"""
import sys
from pathlib import Path

GRAPH_RS = "crates/rfvp/src/subsystem/components/syscalls/graph.rs"
TIMER_RS = "crates/rfvp/src/subsystem/components/syscalls/timer.rs"

MARKER = "YHFVP-SYSCALL-SEMANTICS-1.0"

# ------------------------------------------------ graph.rs: PrimSetZ
Z_OLD = """    // - If z is Float: do NOT change prim.z, but still enable attr bit 0x04."""
Z_NEW = """    // - If z is True: do NOT change prim.z, but still enable attr bit 0x04."""

Z_CODE_OLD = """    if z.as_float().is_some() {"""
Z_CODE_NEW = """    if matches!(z, Variant::True) {"""

# ------------------------------------------- graph.rs: PrimSetRS 钳制
RS_OLD = """    let scale = match scale.as_int() {
        Some(s) => s,
        None => cur_fx,
    };

    let scale = if !(0..=10000).contains(&scale) {
        1000
    } else {
        scale
    };"""

RS_NEW = """    // YukiHub: the original clamps an int scale to [100, 10000] (it never resets it) and
    // leaves the scale alone otherwise. ADR-0003.
    let scale = match scale.as_int() {
        Some(s) => s.clamp(100, 10000),
        None => cur_fx,
    };"""

# ------------------------------------------ graph.rs: PrimSetRS2 钳制
RS2_OLD = """    let scale_x = match scale_x.as_int() {
        Some(s) => s,
        None => cur_fx,
    };

    let scale_y = match scale_y.as_int() {
        Some(s) => s,
        None => cur_fy,
    };

    let scale_x = if !(0..=10000).contains(&scale_x) {
        1000
    } else {
        scale_x
    };
    let scale_y = if !(0..=10000).contains(&scale_y) {
        1000
    } else {
        scale_y
    };"""

RS2_NEW = """    // YukiHub: the original clamps each int scale to [100, 10000] (it never resets them)
    // and leaves the scale alone otherwise. ADR-0003.
    let scale_x = match scale_x.as_int() {
        Some(s) => s.clamp(100, 10000),
        None => cur_fx,
    };
    let scale_y = match scale_y.as_int() {
        Some(s) => s.clamp(100, 10000),
        None => cur_fy,
    };"""

# -------------------------------------------- timer.rs: TimerSuspend
TS_OLD = """    // IDA (original engine): boolean args are evaluated as (Type != 0).
    game_data.timer_manager.set_suspend(!on.canbe_true());"""

TS_NEW = """    // YukiHub: the original stores (Type != True): only True resumes the timers, anything else
    // (nil, ints, ...) suspends them. ADR-0004.
    game_data
        .timer_manager
        .set_suspend(!matches!(on, Variant::True));"""

MARKER_RUST = """/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_SYSCALL_SEMANTICS_MARKER: [u8; 27] = *b"YHFVP-SYSCALL-SEMANTICS-1.0";
"""

EDITS_GRAPH = [
    ("PrimSetZ 注释", Z_OLD, Z_NEW),
    ("PrimSetZ 判 True", Z_CODE_OLD, Z_CODE_NEW),
    ("PrimSetRS 钳制", RS_OLD, RS_NEW),
    ("PrimSetRS2 钳制", RS2_OLD, RS2_NEW),
]

EDITS_TIMER = [
    ("TimerSuspend 判 True", TS_OLD, TS_NEW),
]


def apply_edits(rel: str, sentinel: str, edits, add_marker: bool) -> int:
    """`sentinel` 是该文件专属的幂等标记。

    不能只用全局 MARKER：MARKER 只写在 graph.rs 末尾，timer.rs 用它判断会永远
    「未应用」，第二次运行就会重复插入（`TS_NEW` 不含 `TS_OLD`，会插两遍）。
    """
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
    rc = apply_edits(GRAPH_RS, "matches!(z, Variant::True)", EDITS_GRAPH, add_marker=True)
    if rc != 0:
        return rc
    return apply_edits(
        TIMER_RS, "matches!(on, Variant::True)", EDITS_TIMER, add_marker=False
    )


if __name__ == "__main__":
    sys.exit(main())