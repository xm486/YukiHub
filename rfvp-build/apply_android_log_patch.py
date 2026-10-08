#!/usr/bin/env python3
"""
rfvp Android logcat 日志补丁（YukiHub 定制）。

上游的 Rust 日志（log::error!/warn!/info!）在 Android 上没有接 logcat，
引擎诊断全部不可见。本补丁：
  - 给 crates/rfvp/Cargo.toml 增加 android_logger 依赖
  - 在 rfvp_android_create 入口做 init_once，tag 固定为 "rfvp"，级别 Info

之后 `adb logcat -s rfvp` 即可看到引擎日志（含 load:/save: 错误详情）。

2026-10：诊断期的全部 YHPROBE 探针已移除（黑框问题已定位并整理成上游
issue，见仓库外 upstream_issue_parts_background.md）。如需再次诊断，可从
git 历史（9abc191 / 5672620）找回带探针的版本。

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
android_logger = \"0.14\"""",
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
        log::error!(\"rfvp_android_create: native_window_ptr is null\");
        return std::ptr::null_mut();
    };""",
                """) -> *mut c_void {
    // YukiHub patch: route Rust logs (log::error!/warn!/info!) to logcat under tag \"rfvp\".
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag(\"rfvp\"),
    );
    let Some(win) = NonNull::new(native_window_ptr) else {
        log::error!(\"rfvp_android_create: native_window_ptr is null\");
        return std::ptr::null_mut();
    };""",
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
