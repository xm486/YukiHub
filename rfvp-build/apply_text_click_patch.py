#!/usr/bin/env python3
"""YukiHub 补丁：实现「点击补全文字」（PC 原版行为）。

问题
----
Gal 的点字（逐字出现）动画进行中点击，PC 原版行为是：
  第一次点击 -> 立刻把这一句显示完（点击被吞掉）
  第二次点击 -> 才推进到下一句

rfvp 现状：引擎只在 Ctrl / ControlPulse 时才 `force_reveal_all_non_suspended`
（见 text_manager.rs），**鼠标点击完全没有接进来**。所以点字动画中点一下，
脚本会顺着往下走 -> 直接过对话。

修复
----
在 Android 触摸入口（`App::host_touch_android`）里，遇到「按下」时：
  - 如果还有文字槽在逐字显示中 -> 立刻补全（force reveal），并**直接 return**
    （这次触摸不下发，脚本看不到，因此不会顺势推进对话）；
  - 否则按原逻辑下发。

为什么直接 return 就够（不需要额外"吞掉抬起"）：
`InputManager::notify_mouse_up` 只有在 `new_input_state` 里对应位为 1 时才
会产生 up 边沿；按下被跳过 -> 该位从未置 1 -> 抬起天然不产生边沿。实测脚本
也不用 `InputGetUp`（YHPROBE 探测 0 次）。

附带探针（诊断期，验证后应整体删除本补丁）
------------------------------------------
  YHPROBE TextWaitArm / TextWaitDone  -> 脚本是否真的被阻塞在点字等待上
  YHPROBE TextForceReveal             -> Ctrl/ControlPulse 路径是否被触发
  YHPROBE TextSpeed                   -> 脚本设置的显示速度
  YHPROBE ControlPulse                -> 脚本是否主动发 ControlPulse
  YHPROBE ClickCompletesText          -> 本次点击是否走了"补全文字"分支
"""
import sys
from pathlib import Path

TEXT_MANAGER = "crates/rfvp/src/subsystem/resources/text_manager.rs"
APP_RS = "crates/rfvp/src/app.rs"
INPUT_RS = "crates/rfvp/src/subsystem/components/syscalls/input.rs"
TEXT_RS = "crates/rfvp/src/subsystem/components/syscalls/text.rs"

# ─────────────────────────────────────────────────────────────────────────────
# 1) text_manager.rs：新增 any_revealing / force_reveal_all_non_suspended_checked
# ─────────────────────────────────────────────────────────────────────────────
TM_EDITS = [
    (
        """        out
    }

    /// Tick reveal-by-time for all text slots.""",
        """        out
    }

    /// YukiHub: 列出「正在阻塞脚本、且逐字显示尚未完成」的文字槽。
    ///
    /// 只有这种槽才需要"点击补全文字并吞掉点击"：
    ///   - `sync_wait_active`：脚本正被 `thread_text_wait` 卡住（text_print 的等待）
    ///   - `visible_chars < total_chars`：这一句确实还没显示完
    ///
    /// 为什么不能简单用 `!reveal_is_complete()`：`set_speed(0)`（立即显示）会把
    /// `next_wait_index` 归零，若该槽有 wait_points，`reveal_is_complete()` 会
    /// **永久为 false**。设置页那类"静态显示"的文字槽正好命中这一点，导致判据
    /// 恒真 -> 每次点击都被吞掉 -> 设置页按钮全部失效（实测 45 次误吞）。
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

    /// YukiHub: 诊断用 —— 所有「已加载且未暂停、但按引擎判据未完成」的槽位。
    /// 只用于日志，不参与决策（用于确认静态槽被误判的情况）。
    pub fn incomplete_slot_ids(&self) -> Vec<usize> {
        let mut out = Vec::new();
        for (i, t) in self.items.iter().enumerate() {
            if t.loaded && !t.is_suspended && !t.reveal_is_complete() {
                out.push(i);
            }
        }
        out
    }

    /// YukiHub: 立即补全所有未暂停文字槽的逐字显示；返回是否真的补全了某个槽。
    /// 语义与 `force_reveal_all_non_suspended` 相同，只是额外返回"有没有做事"。
    pub fn force_reveal_all_non_suspended_checked(&mut self) -> bool {
        let mut changed = false;
        for t in self.items.iter_mut() {
            if !t.loaded || t.is_suspended {
                continue;
            }
            let target = t.total_chars;
            if t.visible_chars != target || t.pending_wait_ms != 0 || t.pending_special_wait {
                t.visible_chars = target;
                t.pending_wait_ms = 0;
                t.pending_special_wait = false;
                t.reveal_carry = 0;
                t.next_wait_index = t.wait_points.len();
                if !t.layout_dirty {
                    t.apply_reveal_delta_to_current_target();
                }
                t.dirty = true;
                changed = true;
            }
        }
        changed
    }

    /// Tick reveal-by-time for all text slots.""",
    ),
    # 探针：脚本阻塞在点字等待上
    (
        """    fn arm_sync_print_wait(&mut self, thread_id: u32) {
        self.sync_wait_thread = Some(thread_id);
        self.sync_wait_active = true;
    }""",
        """    fn arm_sync_print_wait(&mut self, thread_id: u32) {
        // YHPROBE: 脚本被阻塞在"等文字显示完"上
        log::info!("YHPROBE TextWaitArm thread={}", thread_id);
        self.sync_wait_thread = Some(thread_id);
        self.sync_wait_active = true;
    }""",
    ),
    # 探针：文字显示完、脚本被唤醒
    (
        """            if t.sync_wait_active && t.reveal_is_complete() {
                if let Some(id) = t.sync_wait_thread.take() {
                    out.push(id);
                }
                t.sync_wait_active = false;
            }""",
        """            if t.sync_wait_active && t.reveal_is_complete() {
                if let Some(id) = t.sync_wait_thread.take() {
                    // YHPROBE: 文字已显示完，唤醒阻塞中的脚本线程
                    log::info!("YHPROBE TextWaitDone thread={}", id);
                    out.push(id);
                }
                t.sync_wait_active = false;
            }""",
    ),
    # 探针：Ctrl/ControlPulse 触发的强制补全
    (
        """    pub fn force_reveal_all_non_suspended(&mut self) {
        for t in self.items.iter_mut() {""",
        """    pub fn force_reveal_all_non_suspended(&mut self) {
        // YHPROBE: 走了 Ctrl/ControlPulse 的"立即全显"路径
        log::info!("YHPROBE TextForceReveal (ctrl/pulse)");
        for t in self.items.iter_mut() {""",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 2) app.rs：Android 触摸入口 —— 点字动画中的点击改为"补全文字并吞掉"
# ─────────────────────────────────────────────────────────────────────────────
APP_EDITS = [
    (
        """    pub fn host_touch_android(&mut self, phase: i32, x_px: f64, y_px: f64) {
        use crate::subsystem::resources::input_manager::KeyCode;
""",
        """    pub fn host_touch_android(&mut self, phase: i32, x_px: f64, y_px: f64) {
        use crate::subsystem::resources::input_manager::KeyCode;

        // YukiHub: 点字动画进行中的「按下」= 先把这一句显示完（PC 原版行为），
        // 并吞掉这次触摸（不下发），脚本因此看不到这次点击、不会顺势推进对话。
        // 第二次点击时已无正在显示的槽 -> 走原逻辑 -> 正常推进对话。
        if phase == 0 {
            let mut gd = gd_write(&self.game_data);
            // 只有“脚本真被阻塞在等文字显示”的槽才算需要补全；
            // 用 !reveal_is_complete() 会把静态槽（set_speed(0) 后 next_wait_index 归零）
            // 误判为未完成，导致所有点击被吞（设置页按钮全哑）。
            let ids = gd
                .motion_manager
                .text_manager
                .sync_print_wait_revealing_ids();
            if !ids.is_empty() {
                let changed = gd
                    .motion_manager
                    .text_manager
                    .force_reveal_all_non_suspended_checked();
                if changed {
                    log::info!("YHPROBE ClickCompletesText slots={:?}", ids);
                    return;
                }
            }
            // 诊断：有没有“按引擎判据未完成、但并不阻塞脚本”的槽（静态槽误判证据）
            let inc = gd.motion_manager.text_manager.incomplete_slot_ids();
            if !inc.is_empty() {
                log::info!("YHPROBE incomplete-but-no-wait slots={:?}", inc);
            }
        }
""",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 3) input.rs：ControlPulse 探针
# ─────────────────────────────────────────────────────────────────────────────
INPUT_EDITS = [
    (
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    game_data.inputs_manager.set_control_pulse();""",
        """pub fn control_pulse(game_data: &mut GameData) -> Result<Variant> {
    // YHPROBE: 脚本主动下发 ControlPulse（用于快速跳过文字显示）
    log::info!("YHPROBE ControlPulse");
    game_data.inputs_manager.set_control_pulse();""",
    ),
]

# ─────────────────────────────────────────────────────────────────────────────
# 4) text.rs：TextSpeed 探针
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
        print(f"[OK]   {path} #{i}: 已应用")

    if changed:
        p.write_text(text, encoding="utf-8")
    print(f"[OK]   {label} 完成（新增 {changed} 处）")
    return 0


def main() -> int:
    for path, edits, label in (
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