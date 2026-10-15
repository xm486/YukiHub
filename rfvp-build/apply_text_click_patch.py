#!/usr/bin/env python3
"""YukiHub 补丁：「点击补全点字动画」（正式版 · 已移除诊断探针）。

背景
----
目标（PC 原版行为）：点字动画进行中点击 → 先把这一句显示完（这次点击吞掉）；
已显示完时点击 → 推进下一句。

已确认的引擎事实
----------------
  - 点字是引擎逐列 reveal：TextItem.visible_chars / total_chars
  - text_print 按 should_block_on_print() 决定是否阻塞脚本线程：
        thread_text_wait(tid)  ->  VM 把该线程置 CONTEXT_STATUS_TEXT(8)
        collect_completed_sync_print_waiters() -> thread_text_resume(tid)
        全库只有 Ctrl / ControlPulse 会 force_reveal_all_non_suspended()
        => 鼠标点击完全没有接进"补全文字"（本补丁补上）
  - should_use_sync_print_wait(): skip_mode!=0 -> 不阻塞；
        ctrl/pulse -> 不阻塞；否则 effective_reveal_speed_units(g0) > 0 才阻塞

实测失败记录（重要）
--------------------
  v1（判据 !reveal_is_complete()）：太宽。set_speed(0) 把 next_wait_index 归零，
     有 wait_points 的静态槽永远"未完成" -> 设置页按钮全哑（误吞 45 次）。
  v2（判据 sync_wait_active && vis<tot）：仍然坏。日志显示
     ClickCompletesText slots=[31]，且 TextWaitArm 大多是 thread=15
     （非主线程的 UI 文字槽）—— 把 UI 线程的 reveal 也当成对话点字，
     结果点击被吞：设置页点不动、对话也过不去。
  v3（诊断插桩版）：只加探针不改行为，一次实机日志定死了槽位/线程/阻塞判据。
  v4（本版）：按实测判据实现，并**移除全部 YHPROBE 诊断探针**。

本版策略（实测确定的判据）
--------------------------
  按下这一刻：
    incomplete = 任何槽 visible<total（含设置页）      -> 需要补全
    nb         = 正在逐字出现 且 skip_mode!=0          -> 脚本会看到这次点击，必须吞
                 （对话消息槽 slot 0：sk=3；设置页描述槽 slot 31：sk=0，永不吞）
  => 先判断要不要吞，再补全（顺序关键：补全会让判据失效）。

保留的功能函数（text_manager.rs）
--------------------------------
  force_reveal_slots()              把指定槽补全（核心动作）
  incomplete_slot_ids()             列出"按引擎判据未完成"的槽
  nonblocking_revealing_slot_ids()  列出"在逐字出现且不阻塞脚本"的槽

另有一处**真实崩溃修复**（vm_runner.rs）：
  ThreadRequest::TextResume 只对真的处于 CONTEXT_STATUS_TEXT 的线程生效。
  实测 slot 的 sync_wait_active 会残留，而该线程上下文可能已被
  thread_exit / thread_start(0) 重建（pc=0）。强行置 RUNNING 会让它从地址 0
  取指 -> "unknown opcode: 0x2b @ 0x000000" -> SIGABRT。
"""
import sys
from pathlib import Path

TEXT_MANAGER = "crates/rfvp/src/subsystem/resources/text_manager.rs"
APP_RS = "crates/rfvp/src/app.rs"
INPUT_RS = "crates/rfvp/src/subsystem/components/syscalls/input.rs"
TEXT_RS = "crates/rfvp/src/subsystem/components/syscalls/text.rs"

ONE = "one"   # 锚点必须恰好命中 1 次
ALL = "all"   # 锚点命中 >=1 次，全部替换

# ─────────────────────────────────────────────────────────────────────────────
# 1) text_manager.rs
# ─────────────────────────────────────────────────────────────────────────────
TM_EDITS = [
    (
        """        out
    }

    /// Tick reveal-by-time for all text slots.""",
        """        out
    }

    /// YukiHub: 只把指定槽位的逐字显示补全（“点击补全文字”的核心动作）。
    pub fn force_reveal_slots(&mut self, ids: &[usize]) {
        for &i in ids {
            if i >= self.items.len() {
                continue;
            }
            let t = &mut self.items[i];
            let target = t.total_chars;
            t.visible_chars = target;
            t.pending_wait_ms = 0;
            t.pending_special_wait = false;
            t.reveal_carry = 0;
            t.next_wait_index = t.wait_points.len();
            if !t.layout_dirty {
                t.apply_reveal_delta_to_current_target();
            }
            t.dirty = true;
        }
    }

    /// YukiHub: 所有「已加载、未暂停、但按引擎判据未完成」的槽位。
    ///
    /// 判据沿用引擎自己的 reveal_is_complete()，而不是简单地 vis<tot ——
    /// 后者会把 set_speed(0) 归零 wait_points 的静态槽也算进来（v1 的坑）。
    pub fn incomplete_slot_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.loaded && !t.is_suspended && !t.reveal_is_complete() {
                out.push(i);
            }
        }
        out
    }

    /// YukiHub: “视觉上还在逐字出现、且不会阻塞脚本”的槽位（skip_mode != 0）。
    ///
    /// should_use_sync_print_wait() 里 skip_mode != 0 直接返回 false => 这类槽
    /// 永远不会把脚本卡在文字等待上。实测对话消息槽 skip=3，而设置页描述槽 skip=0。
    /// 因此“补全+吞点击”只应对这类槽做：不阻塞 => 脚本会看到这次按下并顺势推进。
    pub fn nonblocking_revealing_slot_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.loaded
                && !t.is_suspended
                && t.skip_mode != 0
                && t.total_chars > 0
                && t.visible_chars < t.total_chars
            {
                out.push(i);
            }
        }
        out
    }

    /// Tick reveal-by-time for all text slots.""",
        ONE,
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 2) app.rs：「点击补全点字动画」的实际实现
# ─────────────────────────────────────────────────────────────────────────────
APP_EDITS = [
    (
        """    pub fn host_touch_android(&mut self, phase: i32, x_px: f64, y_px: f64) {
        use crate::subsystem::resources::input_manager::KeyCode;
""",
        """    pub fn host_touch_android(&mut self, phase: i32, x_px: f64, y_px: f64) {
        use crate::subsystem::resources::input_manager::KeyCode;

        // YukiHub: 点字动画进行中点击 = “先把这一句显示完”（PC 原版行为）。
        //
        // 顺序很重要：**先判断要不要吞点击，再补全**。
        // 上一版先补全、后判断 -> 补全后 visible==total，判据已失效 -> 从不吞。
        //
        // 判据（按下这一刻的状态）：
        //   incomplete   = 有任何槽 visible<total（含设置页）-> 需要补全
        //   nb_to_swallow= 正在逐字出现 且 skip_mode!=0 -> 脚本会看到这次点击，必须吞
        //     （对话消息槽 slot 0：sk=3；设置页描述槽 slot 31：sk=0，永不吞）
        //   实测数据：slot 0 sp=-1 sk=3；REVEALMASK 0x1 表示 slot 0 正在逐字显示。
        if phase == 0 {
            let mut gd = gd_write(&self.game_data);
            let incomplete = gd.motion_manager.text_manager.incomplete_slot_ids();
            let nb = gd
                .motion_manager
                .text_manager
                .nonblocking_revealing_slot_ids();
            if !incomplete.is_empty() {
                gd.motion_manager.text_manager.force_reveal_slots(&incomplete);
            }
            if !nb.is_empty() {
                gd.inputs_manager.suppress_next_mouse_click();
            }
        }
""",
        ONE,
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 3) input.rs：ControlPulse 探针
# ─────────────────────────────────────────────────────────────────────────────
VM_RUNNER = "crates/rfvp/src/vm_runner.rs"

# 崩溃修复：thread_text_resume 只能作用于“真的在等文字”的线程。
# 实测：slot 的 sync_wait_active 会是残留值，而该线程的上下文可能已被
# thread_exit/thread_start(0) 重建（pc=0）。这时强行置 RUNNING，会让它
# 从地址 0 取指 -> "unknown opcode: 0x2b @ 0x000000" -> SIGABRT。
VM_EDITS = [
    (
        """                ThreadRequest::TextResume(id) => {
                    let mut st = self.tm.get_context_status(id);
                    st.remove(ThreadState::CONTEXT_STATUS_TEXT);
                    st.insert(ThreadState::CONTEXT_STATUS_RUNNING);
                    self.tm.set_context_status(id, st);
                }""",
        """                ThreadRequest::TextResume(id) => {
                    // YukiHub: 只有确实处于 CONTEXT_STATUS_TEXT 的线程才允许恢复。
                    // 否则会复活已销毁/已重置的上下文（pc=0）-> unknown opcode -> SIGABRT。
                    let st = self.tm.get_context_status(id);
                    if st.contains(ThreadState::CONTEXT_STATUS_TEXT) {
                        let mut st2 = st.clone();
                        st2.remove(ThreadState::CONTEXT_STATUS_TEXT);
                        st2.insert(ThreadState::CONTEXT_STATUS_RUNNING);
                        self.tm.set_context_status(id, st2);
                    }
                }""",
        ONE,
    ),
    (
        """                    ThreadRequest::TextResume(id) => {
                        let mut st = self.tm.get_context_status(id);
                        st.remove(ThreadState::CONTEXT_STATUS_TEXT);
                        st.insert(ThreadState::CONTEXT_STATUS_RUNNING);
                        self.tm.set_context_status(id, st);
                    }""",
        """                    ThreadRequest::TextResume(id) => {
                        // YukiHub: 同上，只在真的 TEXT 状态时才恢复。
                        let st = self.tm.get_context_status(id);
                        if st.contains(ThreadState::CONTEXT_STATUS_TEXT) {
                            let mut st2 = st.clone();
                            st2.remove(ThreadState::CONTEXT_STATUS_TEXT);
                            st2.insert(ThreadState::CONTEXT_STATUS_RUNNING);
                            self.tm.set_context_status(id, st2);
                        }
                    }""",
        ONE,
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 以下三组仅为诊断探针，已随 YHPROBE 一起移除（保留空列表与注释，便于回溯）
# ─────────────────────────────────────────────────────────────────────────────
#
# MM_EDITS（motion_manager.rs）：曾逐帧打 REVEALMASK（哪些槽在逐字出现）。
#   依赖 text_manager.revealing_mask()，该函数已随诊断一起删除。
# INPUT_EDITS（syscalls/input.rs）：曾在脚本下发 ControlPulse 时打点。
# TEXT_EDITS（syscalls/text.rs）：曾打 TextSpeed / TextSkip / TextPrint 决策。
#
# 若将来需要重新诊断，从 git 历史取回本文件即可（提交 6af86dc 之前）。
MM_EDITS = [
    (
        """        if elapsed < 0 {
            self.text_manager.force_reveal_all_non_suspended();
        } else {
            self.text_manager
                .tick(elapsed as u32, global_speed_var0, release_special_wait);
        }""",
        """        if elapsed < 0 {
            self.text_manager.force_reveal_all_non_suspended();
        } else {
            self.text_manager
                .tick(elapsed as u32, global_speed_var0, release_special_wait);
        }""",
        ONE,
    ),
]

INPUT_EDITS = [
    (
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    game_data.inputs_manager.set_control_pulse();""",
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    game_data.inputs_manager.set_control_pulse();""",
        ONE,
    ),
]

TEXT_EDITS = [
    (
        """    if let Variant::Int(v) = speed {
        if (-1..=300000).contains(v) {
            game_data.motion_manager.text_manager.set_text_speed(id, *v);
        }
    }""",
        """    if let Variant::Int(v) = speed {
        if (-1..=300000).contains(v) {
            game_data.motion_manager.text_manager.set_text_speed(id, *v);
        }
    }""",
        ONE,
    ),
    (
        """    if let Variant::Int(v) = skip {
        if (0..=3).contains(v) {
            game_data
                .motion_manager
                .text_manager
                .set_text_skip(id, *v as u8);
        }
    }""",
        """    if let Variant::Int(v) = skip {
        if (0..=3).contains(v) {
            game_data
                .motion_manager
                .text_manager
                .set_text_skip(id, *v as u8);
        }
    }""",
        ONE,
    ),
    (
        """            let ctrl_down = (game_data.inputs_manager.get_input_state()
                & (1u32 << (KeyCode::Ctrl as u32)))
                != 0;
            let pulse = game_data.inputs_manager.peek_control_pulse();
            let global0 = GLOBAL.lock().unwrap().get_int_var(0);
            if game_data
                .motion_manager
                .text_manager
                .should_block_on_print(id, global0, ctrl_down, pulse)
            {""",
        """            let ctrl_down = (game_data.inputs_manager.get_input_state()
                & (1u32 << (KeyCode::Ctrl as u32)))
                != 0;
            let pulse = game_data.inputs_manager.peek_control_pulse();
            let global0 = GLOBAL.lock().unwrap().get_int_var(0);
            if game_data
                .motion_manager
                .text_manager
                .should_block_on_print(id, global0, ctrl_down, pulse)
            {""",
        ALL,
    ),
]


def apply_edits(path: str, edits, label: str) -> int:
    p = Path(path)
    if not p.exists():
        print(f"[FAIL] {path} 不存在 —— 上游目录结构可能已变")
        return 1

    text = p.read_text(encoding="utf-8")
    changed = 0

    for i, (old, new, mode) in enumerate(edits, 1):
        if old == new:
            print(f"[OK]   {path} #{i}: 无需改动（占位）")
            continue
        count = text.count(old)
        if count == 0:
            if new and new in text:
                print(f"[OK]   {path} #{i}: 已应用（幂等）")
                continue
            print(f"[FAIL] {path} #{i}: 锚点未命中 —— 上游源码已变动")
            return 1
        if mode == ONE and count != 1:
            print(f"[FAIL] {path} #{i}: 锚点命中 {count} 次（期望 1）")
            return 1
        text = text.replace(old, new)
        changed += count
        print(f"[OK]   {path} #{i}: 已应用（{count} 处）")

    if changed:
        p.write_text(text, encoding="utf-8")
    print(f"[OK]   {label} 完成（共 {changed} 处）")
    return 0


def main() -> int:
    for path, edits, label in (
        (VM_RUNNER, VM_EDITS, "vm_runner.rs"),
        (TEXT_MANAGER, TM_EDITS, "text_manager.rs"),
        (APP_RS, APP_EDITS, "app.rs"),
    ):
        rc = apply_edits(path, edits, label)
        if rc != 0:
            return rc
    return 0


if __name__ == "__main__":
    sys.exit(main())