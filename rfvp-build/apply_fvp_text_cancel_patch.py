#!/usr/bin/env python3
"""YukiHub 补丁：线程结束/重启时清掉遗留的 TextWait / TextResume 请求。

来源
----
移植自上游 xmoezzz/rfvp 提交 `513b3955`（"fix(vm): cancel stale text completion
requests"，2026-10-16）。

背景
----
`ThreadWrapper` 的请求队列是全局的，脚本线程销毁后，队列里可能还留着指向
**已消失线程** 的 `TextWait(id)` / `TextResume(id)`。这些请求被消费时：

- `TextResume(id)` 会给一个已经不存在（pc=0）的上下文解除 TEXT 阻塞，
  下一次调度就跳到 0 地址执行 → unknown opcode → SIGABRT。
- `TextWait(id)` 会把一个已销毁的上下文重新标成 TEXT，把它永远挂住。

我们此前（`apply_text_click_patch.py`）在**消费端**加了守卫：只有确实处于
`CONTEXT_STATUS_TEXT` 的线程才允许恢复。那是"止血"。

上游 `513b3955` 是**根治**：在请求产生的源头——线程退出/重启时——就把
该线程的过期请求清掉。

修复
----
1. `thread_wrapper.rs`：新增 `cancel_text_requests(id)`，从队列里剔除
   该线程的 `TextWait` / `TextResume`（`id == 0` 时全部保留，与上游一致）。
2. `text_manager.rs`：新增 `cancel_sync_print_waits(thread_id)`，清掉
   指向该线程的 `sync_wait_thread`（`thread_id == 0` 时全清）。
3. `vm_runner.rs`：在 3 个位置调用它们
   - `get_contexct_should_exit(tid)` 成立、`thread_exit(Some(tid))` 之前；
   - 消费 `ThreadRequest::Start(id, addr)` 时（重启线程，先清旧的）；
   - 消费 `ThreadRequest::Exit(id)` 时（`target = id.unwrap_or(tid)`）。

与既有补丁的关系
----------------
`vm_runner.rs` 已被 `apply_text_click_patch.py` 改过（`TextResume` 的两处
`if st.contains(CONTEXT_STATUS_TEXT)` 守卫）。本补丁的锚点**避开了那两段**，
只落在 `thread_exit` / `Start` / `Exit` 三处，两者可以叠加。

行为影响
--------
清掉的是"本来就该失效"的请求，正常流程（线程活着、文字打完）不受影响。
"""
import sys
from pathlib import Path

THREAD_WRAPPER = "crates/rfvp/src/subsystem/resources/thread_wrapper.rs"
TEXT_MANAGER = "crates/rfvp/src/subsystem/resources/text_manager.rs"
VM_RUNNER = "crates/rfvp/src/vm_runner.rs"

MARKER = "YHFVP-TEXTCANCEL-1.0"

# ------------------------------------------------------- thread_wrapper.rs

TW_OLD = """    pub fn thread_exit(&mut self, id: Option<u32>) {
        self.requests.push_back(ThreadRequest::Exit(id));
    }"""

TW_NEW = """    /// YukiHub: 丢弃指向 `id` 的过期文本请求。
    ///
    /// `id == 0` 表示**主线程**（`ThreadManager::start_main` -> `thread_start(0, ..)`），
    /// 重启主线程意味着整局脚本重来，因此此时**清空全部**文本请求。
    /// 非 0 时只剔除指向该线程的 `TextWait` / `TextResume`。
    ///
    /// 线程销毁/重启后，队列里可能还留着指向已消失上下文的 `TextWait` /
    /// `TextResume`；消费它们会把 pc=0 的上下文重新唤醒（unknown opcode）。
    pub(crate) fn cancel_text_requests(&mut self, id: u32) {
        self.requests.retain(|request| match request {
            ThreadRequest::TextWait(target) | ThreadRequest::TextResume(target) => {
                id != 0 && *target != id
            }
            _ => true,
        });
    }

    pub fn thread_exit(&mut self, id: Option<u32>) {
        self.requests.push_back(ThreadRequest::Exit(id));
    }"""

# -------------------------------------------------------- text_manager.rs

TM_OLD = """    pub fn collect_completed_sync_print_waiters(&mut self) -> Vec<u32> {"""

TM_NEW = """    /// YukiHub: 清掉指向 `thread_id` 的逐字同步等待（`thread_id == 0` 时全清）。
    ///
    /// 对应 `thread_wrapper::cancel_text_requests`：线程没了，等它打完字的
    /// 记录也不该留着，否则槽位会一直挂着 `sync_wait_active`。
    pub(crate) fn cancel_sync_print_waits(&mut self, thread_id: u32) {
        for text in &mut self.items {
            if thread_id == 0 || text.sync_wait_thread == Some(thread_id) {
                text.clear_sync_print_wait();
            }
        }
    }

    pub fn collect_completed_sync_print_waiters(&mut self) -> Vec<u32> {"""

# ------------------------------------------------------------ vm_runner.rs

VM_A_OLD = """            if self.tm.get_contexct_should_exit(tid) {
                self.tm.thread_exit(Some(tid));
                break;
            }"""

VM_A_NEW = """            if self.tm.get_contexct_should_exit(tid) {
                // YukiHub: 线程结束前清掉它遗留的文本请求，避免它们复活 pc=0 的上下文。
                game.thread_wrapper.cancel_text_requests(tid);
                game.motion_manager.text_manager.cancel_sync_print_waits(tid);
                self.tm.thread_exit(Some(tid));
                break;
            }"""

VM_B_OLD = """                    ThreadRequest::Start(id, addr) => self.tm.thread_start(id, addr),"""

VM_B_NEW = """                    ThreadRequest::Start(id, addr) => {
                        // YukiHub: 重启同一 id 前先清掉上一轮的文本请求。
                        game.thread_wrapper.cancel_text_requests(id);
                        game.motion_manager.text_manager.cancel_sync_print_waits(id);
                        self.tm.thread_start(id, addr);
                    }"""

VM_C_OLD = """                    ThreadRequest::Exit(id) => {
                        self.tm.thread_exit(id);
                        must_yield = true;
                    }"""

VM_C_NEW = """                    ThreadRequest::Exit(id) => {
                        // YukiHub: 退出线程时清掉它遗留的文本请求。
                        let target = id.unwrap_or(tid);
                        game.thread_wrapper.cancel_text_requests(target);
                        game.motion_manager.text_manager.cancel_sync_print_waits(target);
                        self.tm.thread_exit(id);
                        must_yield = true;
                    }"""


def apply_edits(rel: str, sentinel: str, edits) -> int:
    """应用一组替换。

    `sentinel` 是该文件专属的幂等标记（新增函数名）——**不能**用全局 MARKER，
    因为 MARKER 只落在 `thread_wrapper.rs`，其余两个文件用它判断会永远"未应用"，
    导致重复运行时重复插入（`TM_NEW` 里还包含 `TM_OLD`，会插两遍）。
    """
    path = Path(rel)
    if not path.exists():
        # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
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

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {rel}: {len(edits)} 处已应用")
    return 0


def patch_thread_wrapper() -> int:
    return apply_edits(
        THREAD_WRAPPER, "fn cancel_text_requests", [("cancel_text_requests", TW_OLD, TW_NEW)]
    )


def patch_text_manager() -> int:
    return apply_edits(
        TEXT_MANAGER,
        "fn cancel_sync_print_waits",
        [("cancel_sync_print_waits", TM_OLD, TM_NEW)],
    )


def patch_vm_runner() -> int:
    return apply_edits(
        VM_RUNNER,
        "cancel_text_requests(tid)",
        [
            ("A/线程退出", VM_A_OLD, VM_A_NEW),
            ("B/线程重启", VM_B_OLD, VM_B_NEW),
            ("C/Exit 请求", VM_C_OLD, VM_C_NEW),
        ],
    )


def main() -> int:
    for fn in (patch_thread_wrapper, patch_text_manager, patch_vm_runner):
        rc = fn()
        if rc != 0:
            return rc

    # CI 标记：单独落在 thread_wrapper.rs 里（grep .so 可验证）。
    path = Path(THREAD_WRAPPER)
    text = path.read_text(encoding="utf-8")
    if "YHFVP_TEXT_CANCEL_MARKER" not in text:
        text = text.rstrip("\n") + (
            "\n\n/// YukiHub patch marker — CI greps the built .so for this string.\n"
            "#[no_mangle]\n"
            'pub static YHFVP_TEXT_CANCEL_MARKER: [u8; 20] = *b"YHFVP-TEXTCANCEL-1.0";\n'
        )
        path.write_text(text, encoding="utf-8")
        print(f"[OK]   {THREAD_WRAPPER}: CI 标记已写入")
    return 0


if __name__ == "__main__":
    sys.exit(main())