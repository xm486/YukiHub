#!/usr/bin/env python3
"""
rfvp 全局文字缩放补丁（YukiHub 定制）。

给引擎加一个 host 可下发的全局字号系数 rfvp_android_set_text_scale(handle, f32)：
  - 只放大文字的逻辑排版尺寸（字号/行距/换行随动），UI 布局、文本面尺寸不变
  - 缩放系数由 YukiHub 通过 JNI 在引擎创建后下发，档位 1.0/1.25/1.5/1.75/2.0
  - gaiji（外字位图）槽位键不缩放，避免位图字被拉糊

用法（在 rfvp 仓库根目录）：
    python3 apply_text_scale_patch.py

脚本全部使用锚文本匹配并严格校验命中次数，任何锚点匹配失败都会以非零码退出，
不会出现"看起来成功其实没打上"的情况。可重复执行（幂等）。
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# (相对路径, [(旧文本, 新文本, 期望命中次数), ...])
EDITS = [
    (
        "crates/rfvp/src/subsystem/resources/text_manager.rs",
        [
            # 1) TextItem 结构体字段
            (
                """    text_font_idx1: i32,
    text_font_idx2: i32,
    text_size1: u8,
    text_size2: u8,
    outline_size1: u8,""",
                """    text_font_idx1: i32,
    text_font_idx2: i32,
    text_size1: u8,
    text_size2: u8,
    // YukiHub patch: host-driven global text scale (1.0 = script-authored size).
    text_scale: f32,
    outline_size1: u8,""",
                1,
            ),
            # 2) TextItem::set_text_scale（插在 set_text_size2 之后）
            (
                """    pub fn set_text_size1(&mut self, size: u8) {
        self.text_size1 = size;
        self.mark_layout_dirty();
    }

    pub fn set_text_size2(&mut self, size: u8) {
        self.text_size2 = size;
        self.mark_layout_dirty();
    }""",
                """    pub fn set_text_size1(&mut self, size: u8) {
        self.text_size1 = size;
        self.mark_layout_dirty();
    }

    pub fn set_text_size2(&mut self, size: u8) {
        self.text_size2 = size;
        self.mark_layout_dirty();
    }

    /// YukiHub patch: host-driven global text scale for this text object.
    /// Scales the logical glyph metrics (size / line gap / wrap), so the text
    /// occupies more of the same-size surface; positions and surface dims are
    /// untouched. Gaiji slot keys stay unscaled on purpose (bitmap fonts).
    pub fn set_text_scale(&mut self, scale: f32) {
        let normalized = if scale.is_finite() && scale > 0.0 {
            scale.clamp(0.5, 4.0)
        } else {
            1.0
        };
        if (self.text_scale - normalized).abs() <= f32::EPSILON {
            return;
        }
        self.text_scale = normalized;
        self.mark_layout_dirty();
    }""",
                1,
            ),
            # 3) 布局块：main_size / ruby_size 乘上系数（先于一切派生量）
            (
                """        let main_size = if self.text_size1 == 0 {
            16.0
        } else {
            self.text_size1 as f32
        };
        let ruby_size = if self.text_size2 == 0 {
            (main_size * 0.6).max(8.0)
        } else {
            self.text_size2 as f32
        };
        let main_draw_size = main_size * render_scale;""",
                """        let main_size = if self.text_size1 == 0 {
            16.0
        } else {
            self.text_size1 as f32
        };
        let ruby_size = if self.text_size2 == 0 {
            (main_size * 0.6).max(8.0)
        } else {
            self.text_size2 as f32
        };
        // YukiHub patch: apply host text scale before every derived quantity
        // (draw sizes, advance measurement, line heights, wrap limits).
        let main_size = main_size * self.text_scale;
        let ruby_size = ruby_size * self.text_scale;
        let main_draw_size = main_size * render_scale;""",
                1,
            ),
            # 4) TextManager 结构体字段
            (
                """    device_render_scale: f32,
    render_scale: f32,
    hidpi_enabled: bool,
}""",
                """    device_render_scale: f32,
    render_scale: f32,
    hidpi_enabled: bool,
    // YukiHub patch: host-driven global text scale.
    text_scale: f32,
}""",
                1,
            ),
            # 4b) TextManager::new 初始化
            (
                """            device_render_scale: 1.0,
            render_scale: 1.0,
            hidpi_enabled: true,
        }""",
                """            device_render_scale: 1.0,
            render_scale: 1.0,
            hidpi_enabled: true,
            text_scale: 1.0,
        }""",
                1,
            ),
            # 5) TextItem::new 初始化（单行锚点 + 全部替换：upstream 可能在
            #    text_size1/text_size2 之间插入过新字段，两行相邻锚点会脱靶）
            (
                """            text_size2: 0,""",
                """            text_size2: 0,
            text_scale: 1.0,""",
                -1,
            ),
            # 6) TextManager::set_text_scale（遍历下发到所有文本对象）
            (
                """    pub fn set_hidpi_enabled(&mut self, enabled: bool) {
        if self.hidpi_enabled == enabled {
            return;
        }
        self.hidpi_enabled = enabled;
        self.apply_effective_render_scale();
    }

    pub fn hidpi_enabled(&self) -> bool {
        self.hidpi_enabled
    }""",
                """    pub fn set_hidpi_enabled(&mut self, enabled: bool) {
        if self.hidpi_enabled == enabled {
            return;
        }
        self.hidpi_enabled = enabled;
        self.apply_effective_render_scale();
    }

    pub fn hidpi_enabled(&self) -> bool {
        self.hidpi_enabled
    }

    /// YukiHub patch: host-driven global text scale applied to every text object.
    pub fn set_text_scale(&mut self, scale: f32) {
        let normalized = if scale.is_finite() && scale > 0.0 {
            scale.clamp(0.5, 4.0)
        } else {
            1.0
        };
        if (self.text_scale - normalized).abs() <= f32::EPSILON {
            return;
        }
        self.text_scale = normalized;
        for item in self.items.iter_mut() {
            item.set_text_scale(normalized);
        }
    }""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/app.rs",
        [
            # App::set_text_scale（照 set_text_hidpi_enabled 的模式）
            (
                """    pub fn set_text_hidpi_enabled(&mut self, enabled: bool) {
        let mut gd = gd_write(&self.game_data);
        gd.motion_manager
            .text_manager
            .set_hidpi_enabled(enabled);
    }""",
                """    pub fn set_text_hidpi_enabled(&mut self, enabled: bool) {
        let mut gd = gd_write(&self.game_data);
        gd.motion_manager
            .text_manager
            .set_hidpi_enabled(enabled);
    }

    /// YukiHub patch: host-driven global text scale.
    pub fn set_text_scale(&mut self, scale: f32) {
        let mut gd = gd_write(&self.game_data);
        gd.motion_manager
            .text_manager
            .set_text_scale(scale);
    }""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/android_host.rs",
        [
            # 新增 C ABI 导出（不加也兼容旧宿主：桥按可选符号解析）
            (
                """/// Enable or disable HiDPI text backing surfaces without changing the Android create ABI.
#[no_mangle]
pub unsafe extern "C" fn rfvp_android_set_text_hidpi(handle: *mut c_void, enabled: i32) {
    if handle.is_null() {
        return;
    }
    let app: &mut App = &mut *(handle as *mut App);
    app.set_text_hidpi_enabled(enabled != 0);
}""",
                """/// Enable or disable HiDPI text backing surfaces without changing the Android create ABI.
#[no_mangle]
pub unsafe extern "C" fn rfvp_android_set_text_hidpi(handle: *mut c_void, enabled: i32) {
    if handle.is_null() {
        return;
    }
    let app: &mut App = &mut *(handle as *mut App);
    app.set_text_hidpi_enabled(enabled != 0);
}

/// YukiHub patch: set the global text scale factor for all text rendering.
/// `scale` = 1.0 keeps the script-authored size; 1.5 renders text 50% larger.
/// Invalid / non-positive values fall back to 1.0 inside the engine.
#[no_mangle]
pub unsafe extern "C" fn rfvp_android_set_text_scale(handle: *mut c_void, scale: f32) {
    if handle.is_null() {
        return;
    }
    let app: &mut App = &mut *(handle as *mut App);
    app.set_text_scale(scale);
}""",
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
            if expected == -1:
                # -1 = 至少命中 1 次，全部替换（用于可能多处出现的初始化器）
                if count < 1:
                    print(f"[FAIL] 锚点命中 {count} 次（期望至少 1 次）: {tag}")
                    failed = True
                    continue
                text = text.replace(old, new)
                print(f"[OK]   {rel}: {tag}（{count} 处）")
                continue
            if count != expected:
                print(f"[FAIL] 锚点命中 {count} 次（期望 {expected}）: {tag}")
                failed = True
                continue
            text = text.replace(old, new, expected)
            print(f"[OK]   {rel}: {tag}")
        if not failed:
            # 写回校验：插入的标记必须存在
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
