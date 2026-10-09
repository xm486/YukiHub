#!/usr/bin/env python3
"""
rfvp 外部文本替换补丁（YukiHub 定制）。

给引擎加一个「按脚本字节偏移查表替换文本」的能力，用于 Windows 注入式汉化
（SakuraChs.exe + filter.dll + patch.dat 这类）：

  - 游戏脚本 Sakura.hcb 保持原样，一个字节都不改
  - 替换发生在 Parser::read_cstring 里，按字符串在脚本中的字节偏移查表
  - 因此 hcb 里所有 jmp/jz 的 u32 绝对地址天然不变，零风险
  - 替换文本长度不受限制（不像等长重写需要裁剪/放弃）

查表数据来自游戏根目录（FVP_BASE_PATH）下的 patch.dat：
    16 字节头 + zlib 流；解压后为记录流 [u8 0][u32 offA][u8 len][前导NUL + GBK文本]
没有 patch.dat 的游戏不受任何影响（表为空 -> 行为与上游完全一致）。

用法（在 rfvp 仓库根目录）：
    python3 apply_text_patch.py

全部使用锚文本匹配并严格校验命中次数，任何锚点匹配失败都会以非零码退出。
可重复执行（幂等）。
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

NEW_FILE_REL = "crates/rfvp/src/text_patch.rs"
NEW_FILE_BODY = r'''//! YukiHub patch: external text replacement for Windows-injection Chinese
//! patches (e.g. `SakuraChs.exe` + `filter.dll` + `patch.dat`).
//!
//! The game script (`Sakura.hcb`) is left untouched. Replacement strings are
//! looked up by their byte offset inside the script, so every `jmp`/`jz`
//! absolute address still points at the same instruction and the file size
//! never changes. Unlike in-place rewriting there is no length limit at all,
//! and nothing has to be clipped or skipped.

use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

static TABLE: OnceLock<Mutex<Option<HashMap<u32, String>>>> = OnceLock::new();
static LOAD_TRIED: AtomicBool = AtomicBool::new(false);

fn slot() -> &'static Mutex<Option<HashMap<u32, String>>> {
    TABLE.get_or_init(|| Mutex::new(None))
}

/// Look up a replacement string for a script byte offset.
///
/// Returns `None` when no table is loaded (the common case for games without a
/// Windows-style patch) or when this offset has no replacement, in which case
/// the caller falls back to the normal NLS decoding path.
pub fn lookup(offset: u32) -> Option<String> {
    if !LOAD_TRIED.load(Ordering::Relaxed) {
        ensure_loaded();
    }
    let guard = slot().lock().ok()?;
    guard.as_ref()?.get(&offset).cloned()
}

/// Load the best replacement table found in the current game root
/// (`FVP_BASE_PATH`).
///
/// Several `.dat` files may live next to the script (a Windows patch, its
/// backup, an unrelated patch, ...). Instead of guessing a file name we parse
/// every candidate and score it against the string offsets the script actually
/// uses, then keep the table that covers the most strings. A table that covers
/// less than 20% of them is discarded, so a foreign patch can never hijack the
/// text. Any failure is silent: the engine then behaves exactly like upstream.
///
/// Only the first attempt is performed.
pub fn ensure_loaded() {
    if LOAD_TRIED.swap(true, Ordering::Relaxed) {
        return;
    }
    let base = match std::env::var("FVP_BASE_PATH") {
        Ok(v) if !v.is_empty() => v,
        _ => return,
    };
    let dir = Path::new(&base);
    let offsets = match script_offsets(dir) {
        Some(o) if !o.is_empty() => o,
        _ => return,
    };

    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let is_dat = path
                .extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| e.eq_ignore_ascii_case("dat"));
            if is_dat {
                candidates.push(path);
            }
        }
    }
    candidates.sort();

    let mut best: Option<(usize, HashMap<u32, String>, String)> = None;
    for path in candidates {
        let data = match std::fs::read(&path) {
            Ok(d) => d,
            Err(_) => continue,
        };
        let map = match parse_patch_dat(&data) {
            Some(m) => m,
            None => continue,
        };
        let hits = map.keys().filter(|k| offsets.contains(k)).count();
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        log::info!(
            "YukiHub text patch: candidate {} -> {} entries, {}/{} script strings covered",
            name,
            map.len(),
            hits,
            offsets.len()
        );
        if best.as_ref().map_or(true, |(h, _, _)| hits > *h) {
            best = Some((hits, map, name));
        }
    }

    let (hits, map, name) = match best {
        Some(v) => v,
        None => return,
    };
    if hits * 5 < offsets.len() {
        log::info!(
            "YukiHub text patch: best candidate {} covers only {}/{} strings, ignored",
            name,
            hits,
            offsets.len()
        );
        return;
    }
    log::info!(
        "YukiHub text patch: using {} ({} entries, {}/{} script strings covered)",
        name,
        map.len(),
        hits,
        offsets.len()
    );
    if let Ok(mut guard) = slot().lock() {
        *guard = Some(map);
    }
}

/// Collect the byte offset of every string literal (`0x0E <len> <body>`) in the
/// game script. The offset points at the first body byte, which is exactly what
/// Windows-style `patch.dat` tables reference.
fn script_offsets(dir: &Path) -> Option<HashSet<u32>> {
    let script = find_script(dir)?;
    let buffer = std::fs::read(script).ok()?;
    let mut out = HashSet::new();
    let mut i = 0usize;
    while i + 2 <= buffer.len() {
        if buffer[i] == 0x0e {
            let len = buffer[i + 1] as usize;
            if len >= 2 && i + 2 + len <= buffer.len() {
                out.insert((i + 2) as u32);
                i += 2 + len;
                continue;
            }
        }
        i += 1;
    }
    Some(out)
}

/// Locate the script with the same rule rfvp uses: prefer `*.bch` (patched
/// script), otherwise `*.hcb`, taking the first match in directory order.
fn find_script(dir: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(dir).ok()?;
    let mut bch: Option<PathBuf> = None;
    let mut hcb: Option<PathBuf> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        match path.extension().and_then(|e| e.to_str()) {
            Some(e) if e.eq_ignore_ascii_case("bch") => {
                if bch.is_none() {
                    bch = Some(path);
                }
            }
            Some(e) if e.eq_ignore_ascii_case("hcb") => {
                if hcb.is_none() {
                    hcb = Some(path);
                }
            }
            _ => {}
        }
    }
    bch.or(hcb)
}

fn parse_patch_dat(data: &[u8]) -> Option<HashMap<u32, String>> {
    // Windows patch layout: 16-byte header followed by a zlib stream.
    // If inflation fails, fall back to treating the file as a raw record stream.
    let mut inflated: Vec<u8> = Vec::new();
    if data.len() > 16 {
        let mut decoder = flate2::read::ZlibDecoder::new(&data[16..]);
        if decoder.read_to_end(&mut inflated).is_err() {
            inflated.clear();
        }
    }
    let raw: &[u8] = if inflated.is_empty() { data } else { &inflated };

    let mut out: HashMap<u32, String> = HashMap::new();
    let mut i = 0usize;
    while i + 6 <= raw.len() {
        if raw[i] != 0 {
            i += 1;
            continue;
        }
        let offset = u32::from_le_bytes([raw[i + 1], raw[i + 2], raw[i + 3], raw[i + 4]]);
        let len = raw[i + 5] as usize;
        if len == 0 || i + 6 + len > raw.len() {
            i += 1;
            continue;
        }
        let body = &raw[i + 6..i + 6 + len];
        // Records carry a leading NUL before the actual text.
        let start = match body.iter().position(|b| *b != 0) {
            Some(p) => p,
            None => {
                i += 6 + len;
                continue;
            }
        };
        let (text, _, _) = encoding_rs::GBK.decode(&body[start..]);
        if !text.is_empty() {
            out.insert(offset, text.into_owned());
        }
        i += 6 + len;
    }

    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}
'''

# (相对路径, [(旧文本, 新文本, 期望命中次数), ...])
EDITS = [
    (
        "crates/rfvp/src/lib.rs",
        [
            (
                """pub mod script;
#[cfg(all(not(feature = "no_std"), feature = "soft-render-desktop"))]
pub mod soft_host;""",
                """pub mod script;
// YukiHub patch: external text replacement table (patch.dat).
#[cfg(not(feature = "no_std"))]
pub mod text_patch;
#[cfg(all(not(feature = "no_std"), feature = "soft-render-desktop"))]
pub mod soft_host;""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/script/parser.rs",
        [
            (
                """    pub fn read_cstring(&self, offset: usize, len: usize) -> Result<String> {
        #[cfg(feature = "old_school")]
        {""",
                """    pub fn read_cstring(&self, offset: usize, len: usize) -> Result<String> {
        // YukiHub patch: consult the external replacement table first.
        // Hit = a Windows-injection Chinese patch covers this script offset;
        // miss = normal NLS decoding, i.e. upstream behaviour.
        #[cfg(not(feature = "no_std"))]
        if let Some(replaced) = crate::text_patch::lookup(offset as u32) {
            return Ok(replaced);
        }

        #[cfg(feature = "old_school")]
        {""",
                1,
            ),
        ],
    ),
]


def main() -> int:
    failed = False

    # 1) 新文件
    new_path = ROOT / NEW_FILE_REL
    if new_path.exists():
        existing = new_path.read_text(encoding="utf-8")
        if "YukiHub patch: external text replacement" in existing:
            print(f"[SKIP] 已存在: {NEW_FILE_REL}")
        else:
            print(f"[FAIL] {NEW_FILE_REL} 已存在但内容不是本补丁，拒绝覆盖")
            failed = True
    else:
        new_path.parent.mkdir(parents=True, exist_ok=True)
        new_path.write_text(NEW_FILE_BODY, encoding="utf-8")
        print(f"[OK]   新建: {NEW_FILE_REL}")

    # 2) 锚点替换
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
                print(f"[SKIP] 已打过补丁: {rel}: {tag}")
                continue
            count = text.count(old)
            if count != expected:
                print(f"[FAIL] 锚点命中 {count} 次（期望 {expected}）: {rel}: {tag}")
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
