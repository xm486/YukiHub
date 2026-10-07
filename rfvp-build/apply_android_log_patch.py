#!/usr/bin/env python3
"""
rfvp Android logcat 日志补丁（YukiHub 定制）。

上游的 Rust 日志（log::error!/warn!/info!）在 Android 上没有接 logcat，
读档失败、贴图异常等引擎诊断全部不可见。本补丁：
  - 给 crates/rfvp/Cargo.toml 增加 android_logger 依赖
  - 在 rfvp_android_create 入口做 init_once，tag 固定为 "rfvp"，级别 Info

之后 `adb logcat -s rfvp` 即可看到引擎日志（含 load:/save: 错误详情）。

用法（在 rfvp 仓库根目录）：
    python3 apply_android_log_patch.py

锚文本严格校验 + 幂等，风格与 apply_text_scale_patch.py 一致。
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# (相对路径, [(旧文本, 新文本, 期望命中次数), ...])
EDITS = [
    (
        "crates/rfvp/Cargo.toml",
        [
            # 1) 依赖（android 上构建才会编译到；CI 只出 aarch64-linux-android 产物）
            (
                """[dependencies]""",
                """[dependencies]
# YukiHub patch: route Rust logs to Android logcat.
android_logger = "0.14\"""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/android_host.rs",
        [
            # 2) 引擎创建入口安装 logger（init_once 天然幂等）
            (
                """) -> *mut c_void {
    let Some(win) = NonNull::new(native_window_ptr) else {
        log::error!("rfvp_android_create: native_window_ptr is null");
        return std::ptr::null_mut();
    };""",
                """) -> *mut c_void {
    // YukiHub patch: route Rust logs (log::error!/warn!/info!) to logcat under tag "rfvp".
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("rfvp"),
    );
    let Some(win) = NonNull::new(native_window_ptr) else {
        log::error!("rfvp_android_create: native_window_ptr is null");
        return std::ptr::null_mut();
    };""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/subsystem/components/syscalls/input.rs",
        [
            # 3) 探针：脚本读到"按下"边沿时记录（含线程 id / 光标位置）
            (
                """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    Ok(Variant::Int(
        game_data.inputs_manager.get_input_down() as i32
    ))
}""",
                """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    let bits = game_data.inputs_manager.get_input_down();
    // YukiHub patch: probe: log click edges exactly as the script sees them.
    if bits != 0 {
        log::info!(
            "YHPROBE get_down bits={:#x} cursor=({},{}) in={} tid={}",
            bits,
            game_data.inputs_manager.get_cursor_x(),
            game_data.inputs_manager.get_cursor_y(),
            game_data.inputs_manager.get_cursor_in(),
            game_data.get_last_current_thread()
        );
    }
    Ok(Variant::Int(bits as i32))
}""",
                1,
            ),
            # 4) 探针：事件队列是消费型，记录谁取走了什么
            (
                """pub fn input_get_event(game_data: &mut GameData) -> Result<Variant> {
    if let Some(event) = game_data.inputs_manager.get_event() {
        let mut table = Table::new();""",
                """pub fn input_get_event(game_data: &mut GameData) -> Result<Variant> {
    if let Some(event) = game_data.inputs_manager.get_event() {
        // YukiHub patch: probe: the press-item queue is consuming; log the taker.
        log::info!(
            "YHPROBE get_event key={} at=({},{}) tid={}",
            event.get_keycode(),
            event.get_x(),
            event.get_y(),
            game_data.get_last_current_thread()
        );
        let mut table = Table::new();""",
                1,
            ),
            # 5) 探针：脚本用 InputSetClick 切换"入队的是按下还是抬起"
            (
                """    if let Variant::Int(v) = clicked {
        if *v == 0 || *v == 1 {
            game_data.inputs_manager.set_click(*v as u32);
        }
    }""",
                """    if let Variant::Int(v) = clicked {
        if *v == 0 || *v == 1 {
            // YukiHub patch: probe: the script toggles which mouse edge gets enqueued.
            log::info!(
                "YHPROBE set_click mode={} tid={}",
                v,
                game_data.get_last_current_thread()
            );
            game_data.inputs_manager.set_click(*v as u32);
        }
    }""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/subsystem/components/syscalls/graph.rs",
        [
            # 6) 探针：点击进行中时记录每次图元命中测试及其结果
            (
                """    let hit = game_data.motion_manager.prim_hit(
        id,
        flag_non_nil,
        game_data.inputs_manager.get_cursor_in(),
        game_data.inputs_manager.get_cursor_x(),
        game_data.inputs_manager.get_cursor_y(),
    );

    Ok(if hit { Variant::True } else { Variant::Nil })""",
                """    let cin = game_data.inputs_manager.get_cursor_in();
    let cx = game_data.inputs_manager.get_cursor_x();
    let cy = game_data.inputs_manager.get_cursor_y();
    let click_active = game_data.inputs_manager.get_input_down() != 0
        || game_data.inputs_manager.get_input_up() != 0;
    let hit = game_data.motion_manager.prim_hit(id, flag_non_nil, cin, cx, cy);
    // YukiHub patch: probe: log hit tests while a click is in flight.
    if click_active {
        log::info!(
            "YHPROBE prim_hit id={} flag={} cursor=({},{}) in={} -> {} tid={}",
            id,
            flag_non_nil,
            cx,
            cy,
            cin,
            hit,
            game_data.get_last_current_thread()
        );
    }

    Ok(if hit { Variant::True } else { Variant::Nil })""",
                1,
            ),
        ],
    ),
]


def main() -> int:
    failed = False
    for rel, edits in EDITS:
        path = ROOT / rel
        if not path.exists():
            print(f"[MISS] 找不到文件: {rel}（请在 rfvp 仓库根目录运行）")
            failed = True
            continue
        text = path.read_text(encoding="utf-8")
        for old, new, expected in edits:
            tag = new.strip().splitlines()[0][:52]
            if new in text:
                # 新文本必然包含旧文本，new 已存在 = 补丁已应用
                print(f"[SKIP] 已打过补丁: {tag}")
                continue
            count = text.count(old)
            if count != expected:
                print(f"[FAIL] 锚点命中 {count} 次（期望 {expected}）: {tag}")
                failed = True
                continue
            text = text.replace(old, new, expected)
            print(f"[OK]   {rel}: {tag}")
        if not failed:
            if "YukiHub patch" not in text:
                print(f"[FAIL] 写回校验失败（无标记）: {rel}")
                failed = True
            else:
                path.write_text(text, encoding="utf-8")
    if failed:
        print("补丁应用失败，未修改的文件保持原样。请确认仓库版本与补丁匹配。")
        return 1
    print("全部锚点应用成功 ✓（可重复执行，幂等）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
