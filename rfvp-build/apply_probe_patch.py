#!/usr/bin/env python3
"""YukiHub 临时诊断补丁（YHPROBE）：定位「点按钮时对话也过一句」（点击穿透）。

要回答的问题
------------
一次点击在脚本层到底发生了什么？具体要拿到：

  1. `PrimHit`（脚本判定「光标是否在按钮上」）返回什么、在哪些帧被调用；
  2. `InputGetDown`（脚本判定「推进对话」）在哪些帧返回非 0；
  3. `InputGetEvent`（按钮常靠事件队列判定）取到了什么 keycode/坐标；
  4. `InputSetClick`（脚本切换「鼠标事件是否入队」的模式）何时被调用。

把 1~4 按时间顺序排出来，就能确定穿透属于哪种：
  A. 同一帧内 PrimHit 与 InputGetDown 同时成立 -> 脚本两个分支都跑了；
  B. down 边沿在按钮判定之前就已被消费 -> 事件队列模式问题；
  C. down 根本不在按钮所在帧到达 -> 时序问题。

用法（CI）
---------
与其它 apply_*.py 一样：在 rfvp 仓库根目录执行。
诊断完成后应当**整体删除本补丁与 workflow 中的调用**（探针会刷日志）。
"""
import sys
from pathlib import Path

# ─────────────────────────────────────────────────────────────────────────────
# 1) input.rs：InputGetDown / InputGetEvent / InputSetClick 探针
# ─────────────────────────────────────────────────────────────────────────────
INPUT_RS = "crates/rfvp/src/subsystem/components/syscalls/input.rs"

INPUT_EDITS = [
    # 1.1 InputGetDown
    (
        """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    Ok(Variant::Int(
        game_data.inputs_manager.get_input_down() as i32
    ))
}""",
        """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    let v = game_data.inputs_manager.get_input_down();
    // YHPROBE: 脚本查询「本帧按下边沿」时打点（穿透诊断）
    if v != 0 {
        log::info!(
            "YHPROBE InputGetDown -> 0x{:X} cursor_in={} cursor=({}, {})",
            v,
            if game_data.inputs_manager.get_cursor_in() { 1 } else { 0 },
            game_data.inputs_manager.get_cursor_x(),
            game_data.inputs_manager.get_cursor_y()
        );
    }
    Ok(Variant::Int(v as i32))
}""",
    ),
    # 1.2 InputGetUp（关键补充：确认脚本是否用 up 推进对话）
    (
        """pub fn input_get_up(game_data: &GameData) -> Result<Variant> {
    Ok(Variant::Int(game_data.inputs_manager.get_input_up() as i32))
}""",
        """pub fn input_get_up(game_data: &GameData) -> Result<Variant> {
    let v = game_data.inputs_manager.get_input_up();
    // YHPROBE: 脚本查询「本帧抬起边沿」时打点（穿透诊断关键补充）
    if v != 0 {
        log::info!(
            "YHPROBE InputGetUp -> 0x{:X} cursor_in={} cursor=({}, {})",
            v,
            if game_data.inputs_manager.get_cursor_in() { 1 } else { 0 },
            game_data.inputs_manager.get_cursor_x(),
            game_data.inputs_manager.get_cursor_y()
        );
    }
    Ok(Variant::Int(v as i32))
}""",
    ),
    # 1.3 InputGetEvent
    (
        """pub fn input_get_event(game_data: &mut GameData) -> Result<Variant> {
    if let Some(event) = game_data.inputs_manager.get_event() {
        let mut table = Table::new();""",
        """pub fn input_get_event(game_data: &mut GameData) -> Result<Variant> {
    if let Some(event) = game_data.inputs_manager.get_event() {
        // YHPROBE: 脚本取走一个输入事件时打点（穿透诊断）
        log::info!(
            "YHPROBE InputGetEvent keycode={} x={} y={} in_screen={}",
            event.get_keycode(),
            event.get_x(),
            event.get_y(),
            if event.get_in_screen() { 1 } else { 0 }
        );
        let mut table = Table::new();""",
    ),
    # 1.3 InputSetClick
    (
        """    if let Variant::Int(v) = clicked {
        if *v == 0 || *v == 1 {
            game_data.inputs_manager.set_click(*v as u32);
        }
    }""",
        """    if let Variant::Int(v) = clicked {
        if *v == 0 || *v == 1 {
            // YHPROBE: 脚本切换「鼠标事件是否入队」的模式（穿透诊断）
            log::info!(
                "YHPROBE InputSetClick {} cursor_in={} cursor=({}, {})",
                v,
                if game_data.inputs_manager.get_cursor_in() { 1 } else { 0 },
                game_data.inputs_manager.get_cursor_x(),
                game_data.inputs_manager.get_cursor_y()
            );
            game_data.inputs_manager.set_click(*v as u32);
        }
    }""",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 2) graph.rs：PrimHit 探针
# ─────────────────────────────────────────────────────────────────────────────
GRAPH_RS = "crates/rfvp/src/subsystem/components/syscalls/graph.rs"

GRAPH_EDITS = [
    (
        """    let hit = game_data.motion_manager.prim_hit(
        id,
        flag_non_nil,
        game_data.inputs_manager.get_cursor_in(),
        game_data.inputs_manager.get_cursor_x(),
        game_data.inputs_manager.get_cursor_y(),
    );

    Ok(if hit { Variant::True } else { Variant::Nil })""",
        """    let hit = game_data.motion_manager.prim_hit(
        id,
        flag_non_nil,
        game_data.inputs_manager.get_cursor_in(),
        game_data.inputs_manager.get_cursor_x(),
        game_data.inputs_manager.get_cursor_y(),
    );

    // YHPROBE: 脚本用 PrimHit 判定「光标是否落在按钮上」（穿透诊断关键证据）
    log::info!(
        "YHPROBE PrimHit id={} -> {} cursor_in={} cursor=({}, {})",
        id,
        if hit { 1 } else { 0 },
        if game_data.inputs_manager.get_cursor_in() { 1 } else { 0 },
        game_data.inputs_manager.get_cursor_x(),
        game_data.inputs_manager.get_cursor_y()
    );

    Ok(if hit { Variant::True } else { Variant::Nil })""",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 3) app.rs：每帧输入快照（有输入时才打）
# ─────────────────────────────────────────────────────────────────────────────
APP_RS = "crates/rfvp/src/app.rs"

APP_EDITS = [
    (
        """            // Movie update must run even when the VM/scheduler is halted for modal playback.
            let mut video_tick_failed = false;""",
        """            // YHPROBE: 每帧输入快照（仅在有输入时），用于穿透诊断
            {
                let im = &gd.inputs_manager;
                let down = im.get_input_down();
                let up = im.get_input_up();
                let st = im.get_input_state();
                if down != 0 || up != 0 || st != 0 {
                    log::info!(
                        "YHPROBE frame={} state=0x{:X} down=0x{:X} up=0x{:X} cursor_in={} cursor=({}, {})",
                        self.debug_frame_no,
                        st,
                        down,
                        up,
                        if im.get_cursor_in() { 1 } else { 0 },
                        im.get_cursor_x(),
                        im.get_cursor_y()
                    );
                }
            }
            // Movie update must run even when the VM/scheduler is halted for modal playback.
            let mut video_tick_failed = false;""",
    ),
]


def apply_edits(path: str, edits, label: str) -> int:
    p = Path(path)
    if not p.exists():
        print(f"[FAIL] {path} 不存在 —— 上游目录结构可能已变")
        return 1

    text = p.read_text(encoding="utf-8")
    changed = 0

    for i, (old, new) in enumerate(edits, 1):
        if new in text:
            print(f"[OK]   {path} #{i}: 已应用（幂等）")
            continue
        count = text.count(old)
        if count != 1:
            print(f"[FAIL] {path} #{i}: 锚点命中 {count} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)
        changed += 1
        print(f"[OK]   {path} #{i}: 探针已插入")

    if changed:
        p.write_text(text, encoding="utf-8")
    print(f"[OK]   {label} 完成（新增 {changed} 处）")
    return 0


def main() -> int:
    rc = apply_edits(INPUT_RS, INPUT_EDITS, "input.rs")
    if rc != 0:
        return rc
    rc = apply_edits(GRAPH_RS, GRAPH_EDITS, "graph.rs")
    if rc != 0:
        return rc
    return apply_edits(APP_RS, APP_EDITS, "app.rs")


if __name__ == "__main__":
    sys.exit(main())