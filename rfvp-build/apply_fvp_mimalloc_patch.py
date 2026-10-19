#!/usr/bin/env python3
"""YukiHub 补丁：用 mimalloc 作为全局分配器（对齐上游 10.19）。

来源
----
上游 xmoezzz/rfvp 10.19 把 `mimalloc` 加进了 default features：

    # crates/rfvp/Cargo.toml
    default = [..., "logging", "mimalloc"]
    mimalloc = ["dep:mimalloc"]
    [target.'cfg(all(not(target_arch = "wasm32"), not(target_os = "uefi")))'.dependencies]
    mimalloc = { version = "0.1", default-features = false, optional = true }

    // crates/rfvp/src/lib.rs
    /// The allocator of the desktop and mobile builds. The system mallocs keep freed large
    /// blocks resident for reuse (on macOS about 100 MiB once a game had decoded its pictures
    /// and read back a frame for a save); mimalloc gives freed memory back to the system.
    #[cfg(all(feature = "mimalloc", not(feature = "no_std"),
              not(target_arch = "wasm32"), not(target_os = "uefi")))]
    #[global_allocator]
    static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

为什么要它
----------
系统 malloc 对**大块**释放后倾向于「留着复用」，不还给 OS。游戏读了一大堆图片、
又为存档缩略图回读了一整帧之后，那块内存就一直占着（上游在 macOS 上量到约 100 MiB）。
mimalloc 会把空闲页真正还给系统 —— 对 Android 低端机（内存紧张）有实际意义。

我们对齐上游，同样**放进 default**，因为：
1. 与上游行为一致，减少「我们和上游不一样」的排查成本；
2. 我们的 Android 构建正好命中 cfg（不是 wasm32、不是 uefi），会真正启用。

风险（务必知道）
----------------
`mimalloc` 依赖 `libmimalloc-sys`，**里面有 C 代码**，交叉编译要给 NDK 的
clang 当 CC/AR。CI 里 NDK r26d + `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`
已经配好，正常情况下 cc crate 能自动找到。

**但「编译通过」不等于「没问题」**：
全局分配器是所有内存分配的地基，出问题往往是运行时随机崩溃或长跑才暴露。
所以本补丁只负责「接线正确、能编过」，功能是否可靠必须靠真机长跑验证
（见 ENGINE_PATCHES §3.18 的验证清单）。

行为影响
--------
- 所有堆分配改走 mimalloc，逻辑不变。
- no_std / wasm32 / uefi 构建**不受影响**（cfg 排除了）。
- 二进制会变大一些（静态链接 mimalloc）。
"""
import sys
from pathlib import Path

CARGO = "crates/rfvp/Cargo.toml"
LIB = "crates/rfvp/src/lib.rs"

MARKER = "YHFVP-MIMALLOC-1.0"

# ------------------------------------------------------------- Cargo.toml
# 1) default features 加 mimalloc
CARGO_DEFAULT_OLD = (
    'default = ["runtime-core-deps", "gpu-render", "audio", "mp4", "native-video", '
    '"random", "uuid-support", "image-formats", "bevy-utils", "fontdue-compat", '
    '"cursor-ani", "zlib-flate2", "logging"]'
)
CARGO_DEFAULT_NEW = (
    'default = ["runtime-core-deps", "gpu-render", "audio", "mp4", "native-video", '
    '"random", "uuid-support", "image-formats", "bevy-utils", "fontdue-compat", '
    '"cursor-ani", "zlib-flate2", "logging", "mimalloc"]'
)

# 2) 定义 feature（插在 default 那行之后）
CARGO_FEATURE_OLD = CARGO_DEFAULT_NEW
CARGO_FEATURE_NEW = CARGO_DEFAULT_NEW + """
# YukiHub: mimalloc as the global allocator (desktop and mobile; never with `no_std`).
# Aligned with upstream 10.19. See src/lib.rs.
mimalloc = ["dep:mimalloc"]"""

# 3) 依赖声明（放进 wasm32/uefi 都排除的那个 target 块，与上游一致）
CARGO_DEPS_OLD = """[target.'cfg(all(not(target_arch = "wasm32"), not(target_os = "uefi")))'.dependencies]
rodio = { version = "0.17.3", optional = true }"""
CARGO_DEPS_NEW = """[target.'cfg(all(not(target_arch = "wasm32"), not(target_os = "uefi")))'.dependencies]
# YukiHub: the allocator of desktop and mobile builds (feature `mimalloc`): see src/lib.rs.
mimalloc = { version = "0.1", default-features = false, optional = true }
rodio = { version = "0.17.3", optional = true }"""

# ------------------------------------------------------------------- lib.rs
# 锚点选在 `compile_error!` 之后、`pub mod host_api;` 之前 —— 上游 10.19 的
# global_allocator 也正好插在这个位置（`ring_log` 与 `alloc_error_handler` 是
# 上游后来才有的，基线里没有，不能用它们当锚点）。
LIB_OLD = """compile_error!("feature `no_std` is an independent core-library build and must not be combined with runtime/backend features");

pub mod host_api;"""

LIB_NEW = """compile_error!("feature `no_std` is an independent core-library build and must not be combined with runtime/backend features");

/// YukiHub: the allocator of the desktop and mobile builds. The system mallocs keep freed
/// large blocks resident for reuse (upstream measured about 100 MiB on macOS once a game had
/// decoded its pictures and read back a frame for a save); mimalloc gives freed memory back
/// to the system. Aligned with upstream 10.19.
#[cfg(all(feature = "mimalloc", not(feature = "no_std"), not(target_arch = "wasm32"), not(target_os = "uefi")))]
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

pub mod host_api;"""

MARKER_RUST = """
/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_MIMALLOC_MARKER: [u8; 18] = *b"YHFVP-MIMALLOC-1.0";
"""


def apply(path_rel: str, sentinel: str, edits, add_marker: bool) -> int:
    """`sentinel` 是该文件专属的幂等标记。

    不能共用全局 MARKER：MARKER 只写在 lib.rs 末尾，Cargo.toml 用它判断会永远
    「未应用」，第二次运行就会重复插入 feature/依赖。
    """
    path = Path(path_rel)
    if not path.exists():
        print(f"[FAIL] {path_rel} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1

    text = path.read_text(encoding="utf-8")
    if sentinel in text:
        print(f"[OK]   {path_rel}: 已应用（幂等）")
        return 0

    for name, old, new in edits:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {path_rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1
        text = text.replace(old, new, 1)

    if add_marker:
        text = text.rstrip("\n") + "\n" + MARKER_RUST

    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {path_rel}: {len(edits)} 处已应用")
    return 0


def main() -> int:
    # 顺序：default 行先含 mimalloc，再定义 feature，最后声明依赖。
    rc = apply(CARGO, "mimalloc = [\"dep:mimalloc\"]", [
        ("default 加 mimalloc", CARGO_DEFAULT_OLD, CARGO_DEFAULT_NEW),
        ("定义 mimalloc feature", CARGO_FEATURE_OLD, CARGO_FEATURE_NEW),
        ("声明 mimalloc 依赖", CARGO_DEPS_OLD, CARGO_DEPS_NEW),
    ], add_marker=False)
    if rc != 0:
        return rc
    return apply(
        LIB,
        "#[global_allocator]",
        [("global_allocator", LIB_OLD, LIB_NEW)],
        add_marker=True,
    )


if __name__ == "__main__":
    sys.exit(main())