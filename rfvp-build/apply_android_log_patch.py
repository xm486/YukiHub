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
            game_data.get_current_thread()
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
                game_data.get_current_thread()
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
    // YukiHub patch: probe only (log hit tests while a click is in flight).
    if click_active {
        log::info!(
            "YHPROBE prim_hit id={} flag={} cursor=({},{}) in={} -> {} tid={}",
            id,
            flag_non_nil,
            cx,
            cy,
            cin,
            hit,
            game_data.get_current_thread()
        );
    }

    Ok(if hit { Variant::True } else { Variant::Nil })""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/subsystem/components/syscalls/input.rs",
        [
            # 8) 保留只读探针（撤销消费式读取）
            (
                """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    Ok(Variant::Int(
        game_data.inputs_manager.get_input_down() as i32
    ))
}""",
                """pub fn input_get_down(game_data: &GameData) -> Result<Variant> {
    let bits = game_data.inputs_manager.get_input_down();
    if bits != 0 {
        log::info!(
            "YHPROBE get_down bits={:#x} cursor=({},{}) in={} tid={}",
            bits,
            game_data.inputs_manager.get_cursor_x(),
            game_data.inputs_manager.get_cursor_y(),
            game_data.inputs_manager.get_cursor_in(),
            game_data.get_current_thread()
        );
    }
    Ok(Variant::Int(bits as i32))
}""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/vm_runner.rs",
        [
            # 9) 探针：读档路径 + 读档前后活跃线程状态（定位「读档后卡在菜单」）
            (
                """            #[cfg(not(feature = "no_std"))]
            {
                let path = SaveItem::resolve_save_path_for_read(slot);""",
                """            #[cfg(not(feature = "no_std"))]
            {
                let path = SaveItem::resolve_save_path_for_read(slot);
                // YukiHub patch: probe — which file is being read?
                match fs::metadata(&path) {
                    Ok(md) => log::info!(
                        "YHPROBE load path={} len={}",
                        path.display(),
                        md.len()
                    ),
                    Err(e) => log::warn!(
                        "YHPROBE load path={} unreadable: {:#}",
                        path.display(),
                        e
                    ),
                }""",
                1,
            ),
            (
                """        if let Some(slot) = game.save_manager.take_load_request() {""",
                """        if let Some(slot) = game.save_manager.take_load_request() {
            // YukiHub patch: probe — dump active contexts before restoring.
            {
                let mut s = String::new();
                for tid in 0..self.tm.total_contexts() {
                    let st = self.tm.get_context_status(tid as u32);
                    if st != ThreadState::CONTEXT_STATUS_NONE {
                        s.push_str(&format!("{}:{:#x} ", tid, st.bits()));
                    }
                }
                log::info!("YHPROBE load slot={} BEFORE active=[{}]", slot, s);
            }""",
                1,
            ),
            # 10) 探针：恢复完成后再次打印（对比菜单线程是否仍活跃）
            (
                """            // Do not advance contexts in the same tick; resume on the next frame.
            #[cfg(not(feature = "no_std"))]
            if debug_ui::enabled() {""",
                """            // YukiHub patch: probe — dump active contexts after restoring.
            {
                let mut s = String::new();
                for tid in 0..self.tm.total_contexts() {
                    let st = self.tm.get_context_status(tid as u32);
                    if st != ThreadState::CONTEXT_STATUS_NONE {
                        s.push_str(&format!("{}:{:#x} ", tid, st.bits()));
                    }
                }
                log::info!("YHPROBE load slot={} AFTER active=[{}]", slot, s);
            }

            // Do not advance contexts in the same tick; resume on the next frame.
            #[cfg(not(feature = "no_std"))]
            if debug_ui::enabled() {""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/rendering/prim_commands.rs",
        [
            # 11) 探针：打印纯色矩形（Tile）图元的矩形与 RGBA
            #     半透明黑色矩形（黑框 bug）最可能就是这类图元
            (
                """                let rgba = vec4(
                    color.get_r() as f32 / 255.0,
                    color.get_g() as f32 / 255.0,
                    color.get_b() as f32 / 255.0,
                    draw_alpha * (color.get_a() as f32 / 255.0),
                );""",
                """                let rgba = vec4(
                    color.get_r() as f32 / 255.0,
                    color.get_g() as f32 / 255.0,
                    color.get_b() as f32 / 255.0,
                    draw_alpha * (color.get_a() as f32 / 255.0),
                );
                // YukiHub patch: probe — log solid-colour Tile prims (black-box hunt).
                {
                    use core::sync::atomic::{AtomicU32, Ordering};
                    static TILE_BUDGET: AtomicU32 = AtomicU32::new(600);
                    let n = TILE_BUDGET.fetch_sub(1, Ordering::Relaxed);
                    if n > 0 && (n % 8) == 0 && rgba.w > 0.001 {
                        log::info!(
                            "YHPROBE tile id={} xy=({},{}) wh=({},{}) rgba=({:.3},{:.3},{:.3},{:.3})",
                            draw_id,
                            parent_x + draw_x,
                            parent_y + draw_y,
                            w,
                            h,
                            rgba.x,
                            rgba.y,
                            rgba.z,
                            rgba.w
                        );
                    }
                }""",
                1,
            ),
            # 12) 探针：打印「面积大的贴图图元」（背景层），用于定位黑框
            (
                """    let p3 = model.transform_point3(vec3(dst_w, 0.0, 0.0));
    let texture = match texture {""",
                """    let p3 = model.transform_point3(vec3(dst_w, 0.0, 0.0));
    // YukiHub patch: probe — log big textured quads (background layers).
    {
        use core::sync::atomic::{AtomicU32, Ordering};
        static BIG_BUDGET: AtomicU32 = AtomicU32::new(1200);
        let n = BIG_BUDGET.fetch_sub(1, Ordering::Relaxed);
        let tex_key = match texture {
            DrawTextureKey::Graph(g) => g as i64,
            DrawTextureKey::White => -1i64,
        };
        if n > 0 && (n % 24) == 0 && dst_w * dst_h > 100000.0 {
            let min_x = p0.x.min(p1.x).min(p2.x).min(p3.x);
            let min_y = p0.y.min(p1.y).min(p2.y).min(p3.y);
            let max_x = p0.x.max(p1.x).max(p2.x).max(p3.x);
            let max_y = p0.y.max(p1.y).max(p2.y).max(p3.y);
            log::info!(
                "YHPROBE big prim={} rect=({:.0},{:.0},{:.0},{:.0}) dst=({:.0},{:.0}) uv=({:.3},{:.3})-({:.3},{:.3}) rgba=({:.3},{:.3},{:.3},{:.3}) tex={}",
                prim_id,
                min_x,
                min_y,
                max_x - min_x,
                max_y - min_y,
                dst_w,
                dst_h,
                uv0.x,
                uv0.y,
                uv1.x,
                uv1.y,
                color.x,
                color.y,
                color.z,
                color.w,
                tex_key
            );
        }
    }
    let texture = match texture {""",
                1,
            ),
        ],
    ),
    (
        "crates/rfvp/src/rendering/gpu_prim.rs",
        [
            # 11b) 探针：纯色矩形（Tile）。注意：Android 实际走的是本文件
            #      （GpuPrimRenderer，被 app.rs 引用），而 prim_commands.rs 里那套
            #      collect_tree/emit_sprite 没有任何调用者，属死代码会被链接器丢弃，
            #      因此探针必须放在这里才进得了二进制。
            (
                """                if w > 0.0 && h > 0.0 {
                    let color = vec4(
                        c.get_r() as f32 / 255.0,
                        c.get_g() as f32 / 255.0,
                        c.get_b() as f32 / 255.0,
                        draw_alpha * (c.get_a() as f32 / 255.0),
                    );""",
                """                if w > 0.0 && h > 0.0 {
                    let color = vec4(
                        c.get_r() as f32 / 255.0,
                        c.get_g() as f32 / 255.0,
                        c.get_b() as f32 / 255.0,
                        draw_alpha * (c.get_a() as f32 / 255.0),
                    );
                    // YukiHub patch: probe — solid-colour Tile prims (black-box hunt).
                    {
                        use core::sync::atomic::{AtomicU32, Ordering};
                        static TILE_BUDGET: AtomicU32 = AtomicU32::new(600);
                        let n = TILE_BUDGET.fetch_sub(1, Ordering::Relaxed);
                        if n > 0 && (n % 8) == 0 && color.w > 0.001 {
                            log::info!(
                                "YHPROBE tile id={} xy=({},{}) wh=({},{}) rgba=({:.3},{:.3},{:.3},{:.3})",
                                draw_id,
                                parent_x + draw_x,
                                parent_y + draw_y,
                                w,
                                h,
                                color.x,
                                color.y,
                                color.z,
                                color.w
                            );
                        }
                    }""",
                1,
            ),
            # 12b) 探针：大贴图图元（背景层），放在真正被链接的顶点发射函数里
            (
                """        let base = self.vertices.len() as u32;

        // Two triangles (0,1,2) (2,1,3)
        let p0 = model.transform_point3(vec3(0.0, dst_h, 0.0));""",
                """        let base = self.vertices.len() as u32;

        // Two triangles (0,1,2) (2,1,3)
        let p0 = model.transform_point3(vec3(0.0, dst_h, 0.0));
        // YukiHub patch: probe — big textured quads (background layers).
        {
            use core::sync::atomic::{AtomicU32, Ordering};
            static BIG_BUDGET: AtomicU32 = AtomicU32::new(1200);
            let n = BIG_BUDGET.fetch_sub(1, Ordering::Relaxed);
            let tex_key = match tex {
                DrawTextureKey::Graph(g) => g as i64,
                DrawTextureKey::White => -1i64,
            };
            if n > 0 && (n % 24) == 0 && dst_w * dst_h > 100000.0 {
                log::info!(
                    "YHPROBE big dst=({:.0},{:.0}) uv=({:.3},{:.3})-({:.3},{:.3}) rgba=({:.3},{:.3},{:.3},{:.3}) tex={}",
                    dst_w,
                    dst_h,
                    uv0.x,
                    uv0.y,
                    uv1.x,
                    uv1.y,
                    color.x,
                    color.y,
                    color.z,
                    color.w,
                    tex_key
                );
            }
        }""",
                1,
            ),
            # 12c) 探针：只记录屏幕矩形覆盖「黑框区域（x 700~1100）」的图元，
            #      带屏幕坐标，便于直接对上截图里的竖直暗带
            (
                """        tex: DrawTextureKey,
    ) {
        let base = self.vertices.len() as u32;""",
                """        tex: DrawTextureKey,
    ) {
        // YukiHub patch: probe — quads covering the black-strip area (x 700..1100).
        {
            use core::sync::atomic::{AtomicU32, Ordering};
            static HIT_BUDGET: AtomicU32 = AtomicU32::new(400);
            let n = HIT_BUDGET.fetch_sub(1, Ordering::Relaxed);
            let q0 = model.transform_point3(vec3(0.0, dst_h, 0.0));
            let q1 = model.transform_point3(vec3(0.0, 0.0, 0.0));
            let q2 = model.transform_point3(vec3(dst_w, dst_h, 0.0));
            let q3 = model.transform_point3(vec3(dst_w, 0.0, 0.0));
            let min_x = q0.x.min(q1.x).min(q2.x).min(q3.x);
            let max_x = q0.x.max(q1.x).max(q2.x).max(q3.x);
            let min_y = q0.y.min(q1.y).min(q2.y).min(q3.y);
            let max_y = q0.y.max(q1.y).max(q2.y).max(q3.y);
            let tex_key = match tex {
                DrawTextureKey::Graph(g) => g as i64,
                DrawTextureKey::White => -1i64,
            };
            if n > 0
                && (n % 8) == 0
                && max_x > 700.0
                && min_x < 1100.0
                && max_y > 100.0
                && min_y < 900.0
            {
                log::info!(
                    "YHPROBE hit rect=({:.0},{:.0})-({:.0},{:.0}) dst=({:.0},{:.0}) uv=({:.3},{:.3})-({:.3},{:.3}) rgba=({:.3},{:.3},{:.3},{:.3}) tex={}",
                    min_x,
                    min_y,
                    max_x,
                    max_y,
                    dst_w,
                    dst_h,
                    uv0.x,
                    uv0.y,
                    uv1.x,
                    uv1.y,
                    color.x,
                    color.y,
                    color.z,
                    color.w,
                    tex_key
                );
            }
        }
        let base = self.vertices.len() as u32;""",
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
