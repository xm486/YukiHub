#!/usr/bin/env python3
"""YukiHub 补丁：「点击补全点字动画」—— 诊断插桩版（不改行为）。

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
        => 鼠标点击完全没有接进"补全文字"
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

本版策略：**先把行为退回安全状态**（不吞任何触摸），只加诊断探针，
用一次实机日志把下面这些定死，再做精确修复：
  1. 对话 message 用哪个槽（TextWaitArm/Done 带 slot id）
  2. 它为什么（不）阻塞：TextPrint 决策 spd/skip/g0/ctrl/pulse/block
  3. 点击那一帧各槽完整状态：vis/tot/wp/nxt/pwm/psw/spd/swa/skip/rect
  4. 设置页预览文字用哪个槽、哪个线程

探针清单
--------
  YHPROBE TextPrint id=.. loaded=.. spd=.. skip=.. g0=.. ctrl=.. pulse=.. block=..
  YHPROBE TextSkip id=.. skip=..
  YHPROBE TextSpeed id=.. speed=..
  YHPROBE TextWaitArm slot=.. thread=..
  YHPROBE TextWaitDone slot=.. thread=..
  YHPROBE TextForceReveal
  YHPROBE ControlPulse
  YHPROBE click armed=[..] incomplete=[..]
  YHPROBE slotdump click id=.. su=.. vis=../.. wp=.. nxt=.. pwm=.. psw=.. spd=.. swa=.. skip=.. rect=..
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

    /// YukiHub 诊断：列出「脚本真的被阻塞、且逐字显示未完成」的文字槽。
    pub fn sync_print_wait_revealing_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.sync_wait_active
                && !t.is_suspended
                && t.total_chars > 0
                && t.visible_chars < t.total_chars
            {
                out.push(i);
            }
        }
        out
    }

    /// YukiHub 诊断：无条件 dump 单个槽位（不过滤 loaded / reveal_complete）。
    pub fn log_slot_state(&self, idx: usize, tag: &str) {
        if idx >= self.items.len() {
            return;
        }
        let t = &self.items[idx];
        log::info!(
            "YHPROBE SLOT {} i={} ld={} su={} sk={} sp={} v={}/{} swa={} th={} pwm={} nxt={} wp={}",
            tag,
            idx,
            t.loaded as u8,
            t.is_suspended as u8,
            t.skip_mode,
            t.speed,
            t.visible_chars,
            t.total_chars,
            t.sync_wait_active as u8,
            t.sync_wait_thread.map(|v| v as i64).unwrap_or(-1),
            t.pending_wait_ms,
            t.next_wait_index,
            t.wait_points.len()
        );
    }

    /// YukiHub 诊断：返回“正在逐字出现”的槽位掩码（bit i = slot i）。
    pub fn revealing_mask(&self) -> u32 {
        let mut cur: u32 = 0;
        for (i, t) in self.items.iter().enumerate() {
            if i < 32
                && t.loaded
                && !t.is_suspended
                && t.total_chars > 0
                && t.visible_chars < t.total_chars
            {
                cur |= 1u32 << i;
            }
        }
        cur
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

    /// YukiHub: 列出“视觉上还在逐字出现”的槽位（visible_chars < total_chars）。
    /// 不要求脚本处于阻塞态 —— 实测对话的 reveal 可能很快（armed 已空），
    /// 但玩家点击时画面上确实还在逐字出现，这时就应该补全。
    pub fn revealing_slot_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.loaded
                && !t.is_suspended
                && t.total_chars > 0
                && t.visible_chars < t.total_chars
            {
                out.push(i);
            }
        }
        out
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

    /// YukiHub 诊断：所有「已加载、未暂停、但按引擎判据未完成」的槽位。
    pub fn incomplete_slot_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.loaded && !t.is_suspended && !t.reveal_is_complete() {
                out.push(i);
            }
        }
        out
    }

    /// YukiHub 诊断：dump 每个「未完成或正在等待」的文字槽完整状态。
    pub fn log_slot_dump(&self, tag: &str) {
        for (i, t) in self.items.iter().enumerate() {
            if !t.loaded {
                continue;
            }
            if t.reveal_is_complete() && !t.sync_wait_active {
                continue;
            }
            log::info!(
                "YHPROBE slotdump {} id={} su={} vis={} tot={} wp={} nxt={} pwm={} psw={} spd={} swa={} skip={} rect={}x{} {}x{}",
                tag,
                i,
                t.is_suspended as u8,
                t.visible_chars,
                t.total_chars,
                t.wait_points.len(),
                t.next_wait_index,
                t.pending_wait_ms,
                t.pending_special_wait as u8,
                t.speed,
                t.sync_wait_active as u8,
                t.skip_mode,
                t.offset_x,
                t.offset_y,
                t.w,
                t.h
            );
        }
    }

    /// YukiHub 诊断：打印 text_print 的阻塞判定细节。
    pub fn log_block_decision(&self, id: i32, global0: i32, ctrl: bool, pulse: bool) {
        if !(0..32).contains(&id) {
            return;
        }
        let t = &self.items[id as usize];
        log::info!(
            "YHPROBE TextPrint id={} loaded={} spd={} skip={} g0={} ctrl={} pulse={} block={}",
            id,
            t.loaded as u8,
            t.speed,
            t.skip_mode,
            global0,
            ctrl as u8,
            pulse as u8,
            self.should_block_on_print(id, global0, ctrl, pulse) as u8
        );
    }

    /// Tick reveal-by-time for all text slots.""",
        ONE,
    ),
    (
        """    pub fn arm_sync_print_wait(&mut self, id: i32, thread_id: u32) {
        if !(0..32).contains(&id) {
            return;
        }
        self.items[id as usize].arm_sync_print_wait(thread_id);
    }""",
        """    pub fn arm_sync_print_wait(&mut self, id: i32, thread_id: u32) {
        if !(0..32).contains(&id) {
            return;
        }
        // YHPROBE: 哪个槽把哪个线程卡住了（关键：区分对话线程 / UI 线程）
        log::info!("YHPROBE TextWaitArm slot={} thread={}", id, thread_id);
        self.items[id as usize].arm_sync_print_wait(thread_id);
    }""",
        ONE,
    ),
    (
        """    pub fn collect_completed_sync_print_waiters(&mut self) -> Vec<u32> {
        let mut out = Vec::new();
        for t in self.items.iter_mut() {
            if t.sync_wait_active && t.reveal_is_complete() {
                if let Some(id) = t.sync_wait_thread.take() {
                    out.push(id);
                }
                t.sync_wait_active = false;
            }
        }
        out
    }""",
        """    pub fn collect_completed_sync_print_waiters(&mut self) -> Vec<u32> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter_mut().enumerate() {
            if t.sync_wait_active && t.reveal_is_complete() {
                if let Some(id) = t.sync_wait_thread.take() {
                    // YHPROBE: 哪个槽显示完了、唤醒了哪个线程
                    log::info!("YHPROBE TextWaitDone slot={} thread={}", i, id);
                    out.push(id);
                }
                t.sync_wait_active = false;
            }
        }
        out
    }""",
        ONE,
    ),
    (
        """    pub fn force_reveal_all_non_suspended(&mut self) {
        for t in self.items.iter_mut() {""",
        """    pub fn force_reveal_all_non_suspended(&mut self) {
        // YHPROBE: 走了 Ctrl/ControlPulse 的"立即全显"路径
        log::info!("YHPROBE TextForceReveal (ctrl/pulse)");
        for t in self.items.iter_mut() {""",
        ONE,
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 2) app.rs：只加诊断 dump，**不吞任何触摸**（行为退回 v4）
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
                log::info!(
                    "YHPROBE click complete+SWALLOW incomplete={:?} nb={:?}",
                    incomplete,
                    nb
                );
            } else if !incomplete.is_empty() {
                log::info!("YHPROBE click complete only incomplete={:?}", incomplete);
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
                    } else {
                        log::warn!(
                            "YHPROBE TextResume ignored (tid={} bits={} not TEXT)",
                            id,
                            st.bits()
                        );
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
                        } else {
                            log::warn!(
                                "YHPROBE TextResume ignored (tid={} bits={} not TEXT)",
                                id,
                                st.bits()
                            );
                        }
                    }""",
        ONE,
    ),
]

MOTION_MANAGER = "crates/rfvp/src/subsystem/resources/motion_manager/mod.rs"

# 每帧追踪“哪些槽正在逐字出现”（唯一入口，update_text_reveal）
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
        }
        // YHPROBE: 逐帧追踪“哪些槽正在逐字出现”，只在集合变化时打日志。
        {
            use std::sync::atomic::{AtomicU32, Ordering};
            static YH_LAST_MASK: AtomicU32 = AtomicU32::new(0);
            let cur = self.text_manager.revealing_mask();
            let last = YH_LAST_MASK.load(Ordering::Relaxed);
            if cur != last {
                log::info!("YHPROBE REVEALMASK {:#010x} -> {:#010x}", last, cur);
                YH_LAST_MASK.store(cur, Ordering::Relaxed);
            }
        }""",
        ONE,
    ),
]

INPUT_EDITS = [
    (
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    game_data.inputs_manager.set_control_pulse();""",
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    // YHPROBE: 脚本主动下发 ControlPulse（用于快速跳过文字显示）
    log::info!("YHPROBE ControlPulse");
    game_data.inputs_manager.set_control_pulse();""",
        ONE,
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 4) text.rs：TextSpeed / TextSkip / TextPrint 决策 探针
# ─────────────────────────────────────────────────────────────────────────────
TEXT_EDITS = [
    (
        """    if let Variant::Int(v) = speed {
        if (-1..=300000).contains(v) {
            game_data.motion_manager.text_manager.set_text_speed(id, *v);
        }
    }""",
        """    if let Variant::Int(v) = speed {
        if (-1..=300000).contains(v) {
            // YHPROBE: 脚本设置的文字显示速度
            log::info!("YHPROBE TextSpeed id={} speed={}", id, v);
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
            // YHPROBE: skip 模式（非 0 会关闭"打印阻塞脚本"的行为）
            log::info!("YHPROBE TextSkip id={} skip={}", id, v);
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
            // YHPROBE: 打印决策（为什么阻塞 / 为什么不阻塞）
            game_data
                .motion_manager
                .text_manager
                .log_block_decision(id, global0, ctrl_down, pulse);
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
        (MOTION_MANAGER, MM_EDITS, "motion_manager.rs"),
        (TEXT_MANAGER, TM_EDITS, "text_manager.rs"),
        (APP_RS, APP_EDITS, "app.rs"),
        (INPUT_RS, INPUT_EDITS, "input.rs"),
        (TEXT_RS, TEXT_EDITS, "text.rs"),
    ):
        rc = apply_edits(path, edits, label)
        if rc != 0:
            return rc
    return 0


if __name__ == "__main__":
    sys.exit(main())