#!/usr/bin/env python3
"""YukiHub 补丁：修 wmv-decoder 的 WMA 指数解码负数下标 panic。

背景
----
`na_wmv_player`（crate 名 wmv-decoder）的 WMA 解码在 `decode_exp_vlc` 里照搬了
FFmpeg 的逻辑：

    last_exp += code - 60;
    if (last_exp + 60) >= POW_TAB.len() { return Err(...); }   // 只挡上界
    let v = ptab[last_exp as usize];

FFmpeg 原版是 `if ((unsigned)last_exp >= 96)` —— **无符号比较**，负数会被一并拦下。
Rust 移植时只写了上界检查，于是 `last_exp` 变成负数时
`ptab[last_exp as usize]` 会拿 ~usize::MAX 去索引，直接 panic。

危害
----
`decode_wmv_audio_to_wav_bytes` 是**同步**调用（在游戏逻辑线程上）。这个 panic 会杀掉
该线程 → 永久白屏、OP 永远跳不过去（logcat 里看不到 panic 文本，因为 Rust panic 走
stderr，android_logger 不捕获）。

修复
----
把上界检查补成「负数 or 越界」，行为对齐 FFmpeg：越界时返回 Err 而不是 panic。
这样即使将来某条路径仍去解 WMV 音频，最坏也只是"没声音"，绝不会再卡死游戏。
"""
import re
import sys
from pathlib import Path

TARGET = "crates/na_wmv_player/src/wma/decoder.rs"

OLD = """            last_exp += code - 60;
            if (last_exp as i32 + 60) as usize >= tables::POW_TAB.len() {
                return Err(DecoderError::InvalidData(format!(
                    "Exponent out of range: {last_exp}"
                )));
            }
            let v = ptab[last_exp as usize];"""

NEW = """            last_exp += code - 60;
            // YukiHub fix: FFmpeg guards this with `if ((unsigned)last_exp >= 96)`, i.e. an
            // unsigned compare that also rejects NEGATIVE exponents. The Rust port only
            // checked the upper bound, so a negative `last_exp` slipped through and
            // `ptab[last_exp as usize]` indexed with ~usize::MAX -> panic. That panic killed
            // the caller thread (for WMV the game's logic thread) -> permanent white screen.
            if last_exp < 0 || last_exp as usize >= ptab.len() {
                return Err(DecoderError::InvalidData(format!(
                    "Exponent out of range: {last_exp}"
                )));
            }
            let v = ptab[last_exp as usize];"""


def main() -> int:
    path = Path(TARGET)
    if not path.exists():
        print(f"[SKIP] {TARGET} 不存在（可能不是 WMV 构建）")
        return 0

    text = path.read_text(encoding="utf-8")

    if NEW in text:
        print(f"[OK]   {TARGET}: 已应用（幂等）")
        return 0

    count = text.count(OLD)
    if count != 1:
        print(f"[FAIL] {TARGET}: 锚点命中 {count} 次（期望 1）")
        return 1

    text = text.replace(OLD, NEW, 1)
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {TARGET}: 负数下标防护已应用")
    return 0


if __name__ == "__main__":
    sys.exit(main())