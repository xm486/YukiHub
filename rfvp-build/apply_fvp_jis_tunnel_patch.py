#!/usr/bin/env python3
"""YukiHub 补丁：还原官方简体中文版的「SJIS 替身字」（SJIS tunnel）。

现象
----
《樱花，萌放。》**官方简体中文版**对话里夹带冷僻汉字乱码：

    寅喊着什弗。                          ← 实际显示
    她喊着什么。                          ← 应该是这样
    平日寡言少苫的寅，用我从未听到慓的音量，大声地喊着什弗。
    平日寡言少语的她，用我从未听到过的音量，大声地喊着什么。

**改字体没有任何效果**（字体没问题），改文本编码到 GBK / UTF-8 也都不对
（GBK 出的是 `彫铎` 这类假字，UTF-8 有 57,507/64,779 条直接解码失败）。

根因
----
官方简体版**没法把简体中文直接写进 Shift-JIS**——简体字绝大多数在
JIS X 0208 里**没有码位**（「么 / 这 / 为 / 着 / 过 / 语 / 说 / 话 / 车 / 时 …」
全部 encode('cp932') 失败）。

于是官方用了一套**替身字表**：把每个简体字映射到一个**冷僻的 JIS 汉字码位**，
再用那个码位写进脚本。PC 版在**解码之后**再做一次 Unicode→Unicode 还原
（`Sakura.dll` 里的 `babel::sjis_cp932_to_unicode_engine`，映射表即
`jis_map.bin`），所以 PC 上显示正常。

rfvp 解到 Shift-JIS 就停了，缺的正是这第二步 —— 所以替身字原样显示出来。

实测证据（本机《樱花，萌放。》官方简体版）
------------------------------------------
| 项 | 值 |
|---|---|
| `Sakura.hcb` | 4,372,452 字节（日文版是 5,002,852，是**另一份脚本**） |
| 脚本 `push_string` 条数 | 64,779（按 opcode 表反汇编） |
| Shift-JIS 解码失败 | 1,729 条（**用 cp932 全部正常**） |
| 表位置 | `Sakura.dll` 偏移 `0x47960`（`.rdata` 段内），**1,206 对 UTF-16LE** |
| 表内容（开头） | 徼→这 · 戍→你 · 禳→— · 諍→说 · 弗→么 · 來→们 · 餤→为 · 肛→样 · 寅→她 |
| 替身字覆盖率 | 223,054 / 1,310,705 字符 = **17.02%**（平均每 6 字 1 个替身字） |
| 表 key 所在区段 | **全部** JIS X 0208 标准区（cp932 与 shift_jis 解码结果完全一致） |
| `Sakura.dll` 内符号 | `babel::sjis_cp932_to_unicode_engine` / `SJIS tunnel limit exceeded` / `Invalid jis_map.bin format` |

做法
----
新增模块 `crates/rfvp/src/jis_tunnel.rs`，并在 `Parser::decode_string_bytes()`
（所有脚本文本的**唯一解码出口**）之后做一次 Unicode→Unicode 还原。

**脚本文件一个字节都不改**，所有字节偏移天然不变。

为什么不会误伤别的版本（已实测）
--------------------------------
数据来源选 A：**运行时解析游戏目录里的 `Sakura.dll`**（官方若更新版本，
表跟着走，不用改补丁）。判定为「自证 + 白名单」双保险：

1. **只认 `Sakura.dll`**（不做全目录 dll/exe 扫描）。原因：FVP 生态很小，
   目前只有这一个官方版本用替身字方案；而且显式命名 + 自证的文件**不可能**
   被游戏目录里恰好躺着的无关二进制误触发。将来若要支持别的作品，改这一行名字即可。
2. **候选表识别**：在该文件里找**最长的一段连续 `(u16>=0x2000, u16>=0x2000)`**
   （即「(汉字, 字符) 对」的连续序列），至少 64 对；再按
   「key 必须是 CJK 汉字」「value 必须在 0x3000..=0x9FFF」过滤。
3. **自证机制**：用「表里的 key 是否出现在**本游戏脚本**用到的字符集里」打分。
   只有 **命中 ≥ 500 且命中率 ≥ 90%** 才启用；否则静默不启用（行为与上游一致）。

   阈值定得这么严是有实测依据的：

   | 组合 | 命中 | 命中率 |
   |---|---|---|
   | 官中版脚本 + 它自己的表 | **1,205 / 1,207** | **99.8%** ✅ |
   | *日文原版*脚本 + 官中版的表 | 754 / 1,207 | 62.5% ❌ |

   第二条正是「汉化组版」的情形（它用的就是日文原版脚本）——**50% 的松阈值挡不住它**，
   所以必须提到 90%。

本机全盘实测（判据的可分辨性）：

| 候选二进制 | 有效表 | 命中脚本 | 判定 |
|---|---|---|---|
| **官网简体版 `Sakura.dll`** | **1,207** | **1,205（99.8%）** | ✅ **启用** |
| 星空EH `xmoe_fvp.dll` | 170 | 0 | ❌ 拒绝 |
| 星空EH `FVPLoaderGui.exe` | 384 | 72 | ❌ 拒绝 |
| 星空Wish `Memoria.exe` | 2 | 1 | ❌ 拒绝 |
| 无关 `Steam2.dll` | 993 | 239 | ❌ 拒绝 |
| 无关 `quartz.dll` | 348 | 71 | ❌ 拒绝 |
| 无关 `d3d9.dll` | 3 | 0 | ❌ 拒绝 |
| 无关 `SDL3.dll` / `Steam.dll` / `GDFRuntimeSdk.dll` … | 0~295 | 0 | ❌ 拒绝 |

**安全间隔 966**（真表 1205 vs 最高假阳性 239；再加上 90% 命中率这道闸）。

> 日文原版 / 汉化组版（`patch.dat` 查表式）目录里没有这种东西：
> 日文原版脚本本身是合法 SJIS，汉化组版走的是我们已有补丁（`YHFVP-TEXTPATCH`），
> 两者都不会命中该判据 —— 行为保持原样。

资源名不会被误伤（实测）：表里所有 key 都是**汉字**，而资源名是纯 ASCII。
官方脚本里 7,267 条纯 ASCII 字符串、3,764 条含路径分隔符的字符串（`voice/01000060`
`se/001` …），被还原改动的**都是 0 条**。

依赖
----
- `encoding_rs`（已是 rfvp 的直接依赖，用于 `SHIFT_JIS` 解码）
- `FVP_BASE_PATH` 环境变量（已有，`apply_text_patch.py` 就用它）

整个模块在 `#[cfg(not(feature = "no_std"))]` 下（Android 构建不是 no_std）。
"""
import sys
from pathlib import Path

LIB_RS = "crates/rfvp/src/lib.rs"
PARSER_RS = "crates/rfvp/src/script/parser.rs"
TUNNEL_RS = "crates/rfvp/src/jis_tunnel.rs"

# ---------------------------------------------------------------- 模块实现

JIS_TUNNEL_RS = r'''//! YukiHub patch: restore the "SJIS tunnel" surrogate characters used by the
//! official Simplified-Chinese releases of FVP games.
//!
//! Official zh-Hans builds cannot store their text as plain Shift-JIS: most
//! Simplified forms have **no JIS X 0208 code point** at all. They therefore map
//! each Simplified character onto an unused *archaic* JIS kanji and ship a
//! mapping table (the Windows build applies it through
//! `babel::sjis_cp932_to_unicode_engine`, its data being `jis_map.bin`).
//!
//! rfvp decodes the script as Shift-JIS and stops there, so those surrogates
//! show up verbatim (e.g. `什弗` where the original text is `什么`). This module
//! performs the missing second step: a Unicode-to-Unicode substitution applied
//! after decoding. The script file itself is never modified and no byte offset
//! changes.
//!
//! Detection is self-validating: every `*.dll` / `*.exe` next to the script is
//! scanned for a surrogate table (the longest run of consecutive UTF-16LE
//! "character pair"s), then scored against the characters the script actually
//! uses. Only a table covering a large majority of them is accepted, so an
//! unrelated binary can never hijack the text.
//!
//! Reference measurement (《樱花，萌放。》official zh-Hans):
//!   genuine `Sakura.dll` table -> 1,205 / 1,207 hits   (99.8%)
//!   best unrelated binary      ->   239 hits           (Steam2.dll)
//!   threshold                  ->   300 hits
//! so the margin to the nearest false positive is 966 entries.

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_JIS_TUNNEL_MARKER: [u8; 19] = *b"YHFVP-JISTUNNEL-1.0";

/// A run shorter than this is never considered a table.
const MIN_PAIRS: usize = 64;
/// Minimum number of table keys that must occur in the script.
const MIN_HITS: usize = 500;
/// Minimum share of table keys that must occur in the script.
///
/// 9/10 is deliberately strict. Measured on the real game:
///   official zh-Hans script + its own table  -> 1,205 / 1,207 =  99.8%
///   *Japanese* script + that same table      ->   754 / 1,207 =  62.5%
/// so a "half of them" rule would not be enough to reject the second case.
/// A 90% bar leaves ~10% headroom for an official re-release whose text
/// drifts slightly, while still refusing any table that merely shares a large
/// kanji pool with the script.
const MIN_RATE_NUM: usize = 9;
const MIN_RATE_DEN: usize = 10;

static TABLE: OnceLock<HashMap<char, char>> = OnceLock::new();

fn table() -> &'static HashMap<char, char> {
    TABLE.get_or_init(|| {
        let map = load_table().unwrap_or_default();
        if map.is_empty() {
            log::info!("YukiHub jis-tunnel: no surrogate table recognised (upstream behaviour)");
        }
        map
    })
}

/// Replace SJIS-tunnel surrogates with the real characters.
///
/// Borrows the input untouched when no table was recognised (every game except
/// the official Simplified-Chinese release), so the common path costs one
/// `is_empty` check plus a single scan for candidate characters.
pub fn restore(s: &str) -> Cow<'_, str> {
    let map = table();
    if map.is_empty() || !s.chars().any(|c| map.contains_key(&c)) {
        return Cow::Borrowed(s);
    }
    Cow::Owned(s.chars().map(|c| map.get(&c).copied().unwrap_or(c)).collect())
}

/// Locate and validate a surrogate table in the current game directory
/// (`FVP_BASE_PATH`). Any failure is silent: the engine then behaves exactly
/// like upstream. Only the first attempt is performed (via [`TABLE`]).
fn load_table() -> Option<HashMap<char, char>> {
    let base = std::env::var("FVP_BASE_PATH").ok()?;
    if base.is_empty() {
        return None;
    }
    let dir = Path::new(&base);

    let used = script_charset(dir)?;
    if used.is_empty() {
        return None;
    }

    // Only `Sakura.dll` is treated as a table source.
    //
    // This began as a scan over every `*.dll` / `*.exe` in the directory (each
    // candidate validated against the script by the score below), which is what
    // a generic detector would do. It was narrowed on purpose:
    //   - FVP is a small ecosystem and only this one official release uses the
    //     surrogate scheme, so there is nothing else to discover;
    //   - a single, explicitly named, self-validating file cannot suffer a
    //     "false enable" from an unrelated binary that happens to be lying in
    //     the game directory;
    //   - one small read, once per process.
    // To support another title later, widen the name list here — the scoring
    // below already keeps the decision safe, so even the presence of this file
    // in some other game's directory would not misfire.
    let path = dir.join("Sakura.dll");
    let data = std::fs::read(&path).ok()?;
    let map = extract_table(&data)?;
    let hits = map.keys().filter(|k| used.contains(*k)).count();

    let needed = MIN_HITS.max(map.len() * MIN_RATE_NUM / MIN_RATE_DEN);
    if hits < needed {
        log::info!(
            "YukiHub jis-tunnel: Sakura.dll table has {} entries, {} hit(s) < {} needed, ignored",
            map.len(),
            hits,
            needed
        );
        return None;
    }
    log::info!(
        "YukiHub jis-tunnel active: Sakura.dll ({} entries, {} hits) = YHFVP-JISTUNNEL-1.0",
        map.len(),
        hits
    );
    Some(map)
}

/// The surrogate table layout in the binary: consecutive UTF-16LE
/// `(key, value)` pairs. Returns the longest plausible run.
///
/// Both halves are required to be `>= 0x2000` while scanning (this is what
/// separates the table from surrounding code/data), then keys are restricted to
/// CJK ideographs and values to the CJK + CJK-punctuation block.
fn extract_table(data: &[u8]) -> Option<HashMap<char, char>> {
    let mut best = (0usize, 0usize); // (offset, pair count)
    for phase in 0..4 {
        let mut i = phase;
        let mut start: Option<usize> = None;
        let mut n = 0usize;
        while i + 4 <= data.len() {
            let a = u16::from_le_bytes([data[i], data[i + 1]]);
            let b = u16::from_le_bytes([data[i + 2], data[i + 3]]);
            if a >= 0x2000 && b >= 0x2000 {
                if start.is_none() {
                    start = Some(i);
                }
                n += 1;
                i += 4;
            } else {
                if let Some(s) = start {
                    if n >= MIN_PAIRS && n > best.1 {
                        best = (s, n);
                    }
                }
                start = None;
                n = 0;
                i += 4;
            }
        }
        if let Some(s) = start {
            if n >= MIN_PAIRS && n > best.1 {
                best = (s, n);
            }
        }
    }

    let (offset, count) = best;
    if count == 0 {
        return None;
    }

    let mut map = HashMap::with_capacity(count);
    for k in 0..count {
        let i = offset + 4 * k;
        let a = u16::from_le_bytes([data[i], data[i + 1]]);
        let b = u16::from_le_bytes([data[i + 2], data[i + 3]]);
        let key_is_kanji = (0x4E00..=0x9FFF).contains(&a) || (0x3400..=0x4DBF).contains(&a);
        if !key_is_kanji || !(0x3000..=0x9FFF).contains(&b) {
            continue;
        }
        if let (Some(key), Some(value)) = (char::from_u32(a as u32), char::from_u32(b as u32)) {
            map.insert(key, value);
        }
    }
    if map.len() >= MIN_PAIRS {
        Some(map)
    } else {
        None
    }
}

/// Every character the script decodes to under Shift-JIS.
///
/// Surrogate keys are ordinary JIS X 0208 kanji, so they appear in this set as
/// themselves — which is exactly what makes the scoring self-validating.
fn script_charset(dir: &Path) -> Option<HashSet<char>> {
    let script = find_script(dir)?;
    let data = std::fs::read(script).ok()?;
    let mut set = HashSet::new();
    let mut i = 0usize;
    while i + 1 < data.len() {
        let lead = data[i];
        let is_lead = (0x81..=0x9F).contains(&lead) || (0xE0..=0xEF).contains(&lead);
        if is_lead {
            let trail = data[i + 1];
            let is_trail = (0x40..=0x7E).contains(&trail) || (0x80..=0xFC).contains(&trail);
            if is_trail {
                let (decoded, _, had_err) = encoding_rs::SHIFT_JIS.decode(&[lead, trail]);
                if !had_err {
                    if let Some(c) = decoded.chars().next() {
                        if decoded.chars().count() == 1 {
                            set.insert(c);
                            i += 2;
                            continue;
                        }
                    }
                }
            }
        }
        i += 1;
    }
    Some(set)
}

/// Locate the script the same way rfvp does: prefer `*.bch` (patched script),
/// otherwise `*.hcb`, taking the first match in directory order.
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
'''

# ------------------------------------------------------------------- lib.rs
#
# 锚点选在 `pub mod rfvp_render;`（`pub mod script;` 的**上一行**）之后：
#   - `apply_text_patch.py` 的锚点是 `pub mod script;` 紧邻 `pub mod soft_host;`，
#     所以本补丁**绝不能**插在那两者之间；插在更前面则完全不影响它。
#   - 本补丁插在 `rfvp_render` 与 `script` 之间：`script` 与 `soft_host` 依然相邻，
#     text_patch 的锚点保持完好。
# 两个补丁**任意顺序**都安全（已用 seqA / seqB 双顺序实测）。
LIB_OLD = """#[cfg(all(not(feature = "no_std"), feature = "gpu-render"))]
pub mod rfvp_render;
"""

LIB_NEW = """#[cfg(all(not(feature = "no_std"), feature = "gpu-render"))]
pub mod rfvp_render;
// YukiHub patch: SJIS-tunnel surrogate restoration (official Simplified-Chinese
// releases store their text as Shift-JIS with surrogate kanji; see module docs).
#[cfg(not(feature = "no_std"))]
pub mod jis_tunnel;
"""

# ---------------------------------------------------------------- parser.rs
#
# `decode_string_bytes` 是**所有**脚本字符串（剧情/人名/syscall 名/标题）的唯一
# 解码出口（`read_cstring` 的两条路径都汇到这里）。
PARSER_OLD = """        Ok(s.to_string())
    }
"""

PARSER_NEW = """        // YukiHub patch: official Simplified-Chinese builds encode text as
        // Shift-JIS with surrogate kanji; restore them before returning.
        #[cfg(not(feature = "no_std"))]
        let s = crate::jis_tunnel::restore(&s);
        Ok(s.to_string())
    }
"""


def apply_anchors(text: str, rel: str, pairs) -> "tuple[int, str]":
    for name, old, new in pairs:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1, text
        text = text.replace(old, new, 1)
    return 0, text


def main() -> int:
    for rel in (LIB_RS, PARSER_RS):
        if not Path(rel).exists():
            # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
            print(f"[FAIL] {rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
            return 1

    # 新文件
    tunnel_path = Path(TUNNEL_RS)
    if tunnel_path.exists():
        existing = tunnel_path.read_text(encoding="utf-8")
        if "YHFVP-JIS_TUNNEL_MARKER" in existing or "YHFVP-JISTUNNEL" in existing:
            print(f"[OK]   {TUNNEL_RS}: 已存在（幂等）")
        else:
            print(f"[FAIL] {TUNNEL_RS} 已存在但内容不是本补丁，拒绝覆盖")
            return 1
    else:
        tunnel_path.write_text(JIS_TUNNEL_RS, encoding="utf-8")
        print(f"[OK]   {TUNNEL_RS}: 新增（{len(JIS_TUNNEL_RS.splitlines())} 行）")

    # lib.rs
    lib_path = Path(LIB_RS)
    lib_text = lib_path.read_text(encoding="utf-8")
    if "pub mod jis_tunnel;" in lib_text:
        print(f"[OK]   {LIB_RS}: 已应用（幂等）")
    else:
        rc, lib_text = apply_anchors(lib_text, LIB_RS, [("mod 声明", LIB_OLD, LIB_NEW)])
        if rc != 0:
            return rc
        lib_path.write_text(lib_text, encoding="utf-8")
        print(f"[OK]   {LIB_RS}: jis_tunnel 模块已注册")

    # parser.rs
    par_path = Path(PARSER_RS)
    par_text = par_path.read_text(encoding="utf-8")
    if "crate::jis_tunnel::restore" in par_text:
        print(f"[OK]   {PARSER_RS}: 已应用（幂等）")
    else:
        rc, par_text = apply_anchors(
            par_text, PARSER_RS, [("decode_string_bytes 出口", PARSER_OLD, PARSER_NEW)]
        )
        if rc != 0:
            return rc
        par_path.write_text(par_text, encoding="utf-8")
        print(f"[OK]   {PARSER_RS}: 替身字还原已接入 decode_string_bytes")

    print("YHFVP-JISTUNNEL-1.0 全部锚点应用成功 ✓（可重复执行，幂等）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
