#!/usr/bin/env python3
"""
rfvp Movie 播放补丁（YukiHub 定制）。

背景：Android/Qualcomm 上 ByteBuffer 解码链路打通后，OP 能出画面，但：

  1) 视频只占屏幕左上角
     MP4 路径把原始帧（1280x720）直接上传成纹理。movie sprite 没有 rect 属性
     （ensure_layer 里只设了 pos/size/uv，没设 attr bit0），而渲染器在无 rect 时
     按「纹理像素尺寸」绘制（见 rendering/gpu_prim.rs 的 Sprite 分支 else 分支），
     于是 1280x720 的画面落在 1920x1080 逻辑画布的左上角。
     WMV 路径没这个问题，因为它提前把帧缩放到屏幕尺寸再上传。
     -> 本补丁给 MP4 路径补上同样的预缩放。

  2) OP 没声音
     -> 给音频解码 / 启动路径补日志，便于定位（symphonia 解 AAC + Kira 播放）。

用法（在 rfvp 仓库根目录）：
    python3 apply_movie_patch.py

锚文本严格校验 + 幂等，风格与 apply_android_log_patch.py 一致。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# (相对路径, [(旧文本, 新文本, 期望命中次数), ...])
EDITS = [
    (
        "crates/rfvp/src/subsystem/resources/videoplayer.rs",
        [
            # 1) VideoPlayerManager 记住逻辑屏幕尺寸
            (
                """#[derive(Debug, Default)]
pub struct VideoPlayerManager {
    playing: bool,
    modal: bool,
    playback: Option<Playback>,
}""",
                """#[derive(Debug, Default)]
pub struct VideoPlayerManager {
    playing: bool,
    modal: bool,
    playback: Option<Playback>,
    // YukiHub patch: remember the logical screen size so MP4 frames can be pre-scaled
    // exactly like the WMV path. The movie sprite carries no rect attribute, so the
    // renderer draws it at the *texture* pixel size rather than the prim size.
    screen_w: u32,
    screen_h: u32,
}""",
                1,
            ),
            # 2) start() 保存屏幕尺寸
            (
                """        self.playback = Some(playback);

        self.ensure_layer(motion, screen_w as i16, screen_h as i16);""",
                """        self.playback = Some(playback);

        // YukiHub patch: remember the logical screen size for MP4 frame pre-scaling.
        self.screen_w = screen_w;
        self.screen_h = screen_h;

        self.ensure_layer(motion, screen_w as i16, screen_h as i16);""",
                1,
            ),
            # 3) tick() 取出屏幕尺寸（避免与 self.playback 的可变借用冲突）
            (
                """        let Some(pb) = self.playback.as_mut() else {
            self.playing = false;
            self.modal = false;
            return Ok(());
        };""",
                """        let (screen_w, screen_h) = (self.screen_w, self.screen_h);
        let Some(pb) = self.playback.as_mut() else {
            self.playing = false;
            self.modal = false;
            return Ok(());
        };""",
                1,
            ),
            # 4) MP4 帧预缩放到逻辑屏幕尺寸
            (
                """            Playback::Mp4(mp4) => {
                if let Some(frame) = mp4.next_due_frame() {
                    if frame.pts_us != mp4.last_presented_pts {
                        mp4.last_presented_pts = frame.pts_us;
                        motion.load_texture_from_buff(
                            MOVIE_GRAPH_ID,
                            frame.data.into_vec(),
                            frame.width,
                            frame.height,
                        )?;
                        motion.refresh_prims(MOVIE_GRAPH_ID);
                    }
                }""",
                """            Playback::Mp4(mp4) => {
                if let Some(frame) = mp4.next_due_frame() {
                    if frame.pts_us != mp4.last_presented_pts {
                        mp4.last_presented_pts = frame.pts_us;
                        // YukiHub patch: pre-scale to the logical screen size. An unscaled
                        // 1280x720 frame would be drawn at 1280x720 in the top-left corner
                        // of a 1920x1080 canvas (the movie sprite has no rect attribute).
                        let src = frame.data.into_vec();
                        let (tw, th, buf) = if screen_w > 0
                            && screen_h > 0
                            && (frame.width != screen_w || frame.height != screen_h)
                        {
                            let mut out = Vec::new();
                            rgba_scale_nearest(
                                &src, frame.width, frame.height, screen_w, screen_h, &mut out,
                            );
                            (screen_w, screen_h, out)
                        } else {
                            (frame.width, frame.height, src)
                        };
                        motion.load_texture_from_buff(MOVIE_GRAPH_ID, buf, tw, th)?;
                        motion.refresh_prims(MOVIE_GRAPH_ID);
                    }
                }""",
                1,
            ),
            # 5) 音频启动路径日志
            (
                """        let Some(am) = self.audio_manager.as_ref() else {
            return;
        };
        let Some(data) = self.audio_data.take() else {
            return;
        };

        // NOTE: Movie audio is typically non-looping.
        let settings = StaticSoundSettings::new().volume(1.0);
        let handle = am.play(data.with_settings(settings));
        self.audio_handle = Some(handle);""",
                """        let Some(am) = self.audio_manager.as_ref() else {
            log::warn!("Movie audio: no AudioManager, skipping");
            return;
        };
        let Some(data) = self.audio_data.take() else {
            log::warn!("Movie audio: no decoded audio data, skipping");
            return;
        };

        // NOTE: Movie audio is typically non-looping.
        let settings = StaticSoundSettings::new().volume(1.0);
        let handle = am.play(data.with_settings(settings));
        log::info!("Movie audio: playback started");
        self.audio_handle = Some(handle);""",
                1,
            ),
            # 6) 【已移除】MP4 音轨查找日志
            #    原 #6 给 `let track = match format ... None => return Ok(None),` 加了
            #    log::warn 块。但 apply_audio_fix_patch.py 已完整重写该区域（并自带
            #    更详细的日志），两者会争抢同一段代码，导致锚点互相破坏。删掉此处，
            #    音轨查找相关日志统一由 apply_audio_fix_patch.py 提供。
            # 7) MP4 音频解码结果日志
            (
                """    if pcm.is_empty() {
        return Ok(None);
    }

    Ok(Some(build_wav_pcm16le(&pcm, ch as u16, sr)))""",
                """    if pcm.is_empty() {
        log::warn!("mp4 audio: decoded 0 samples");
        return Ok(None);
    }

    log::info!(
        "mp4 audio: decoded {} samples, {} Hz, {} ch",
        pcm.len(),
        sr,
        ch
    );
    Ok(Some(build_wav_pcm16le(&pcm, ch as u16, sr)))""",
                1,
            ),
# 8) MP4 路径：层效果电影也要音频（原版 OP 就是 layer 电影且有声音）
            (
                """            MovieMode::LayerNoAudio => (None, None),
        };

        Ok(Self {
            stream,""",
                """            // YukiHub patch: layer-effect movies must keep their audio. The opening OP
            // is played as `Movie(path, nil)` (layer mode) in the original engine and it
            // *does* have sound; decoding audio here is harmless for effect clips without
            // an audio track (decode_* returns Ok(None) in that case).
            MovieMode::LayerNoAudio => {
                let am = audio_manager;
                let data = match am.as_ref() {
                    None => {
                        log::warn!("Movie audio decode skipped: no AudioManager");
                        None
                    }
                    Some(_) => match decode_mp4_audio_to_wav_bytes(&mp4_path) {
                        Ok(Some(wav_bytes)) => {
                            let cursor = std::io::Cursor::new(wav_bytes);
                            Some(
                                StaticSoundData::from_cursor(cursor)
                                    .context("kira StaticSoundData::from_cursor")?,
                            )
                        }
                        Ok(None) => {
                            log::warn!(
                                "Movie audio: no decodable audio track: {}",
                                mp4_path.as_ref().display()
                            );
                            None
                        }
                        Err(e) => {
                            // Audio is best-effort; keep video playing even if audio fails.
                            log::warn!("Movie audio decode failed: {e:?}");
                            None
                        }
                    },
                };
                (data, am)
            }
        };

        Ok(Self {
            stream,""",
                1,
            ),
            # 9) WMV 路径：同上
            (
                """            MovieMode::LayerNoAudio => (None, None),
        };

        let stop_flag = Arc::new(AtomicBool::new(false));
        let (tx, rx) = crossbeam_channel::bounded::<WmvRgbaFrame>(2);""",
                """            // YukiHub patch: layer-effect movies keep their audio (see the MP4 branch).
            MovieMode::LayerNoAudio => {
                let am = audio_manager;
                let data = match am.as_ref() {
                    None => {
                        log::warn!("WMV movie audio decode skipped: no AudioManager");
                        None
                    }
                    Some(_) => match decode_wmv_audio_to_wav_bytes(&wmv_path) {
                        Ok(Some(wav_bytes)) => {
                            let cursor = std::io::Cursor::new(wav_bytes);
                            Some(
                                StaticSoundData::from_cursor(cursor)
                                    .context("kira StaticSoundData::from_cursor")?,
                            )
                        }
                        Ok(None) => {
                            log::warn!(
                                "WMV movie audio: no decodable audio track: {}",
                                wmv_path.display()
                            );
                            None
                        }
                        Err(e) => {
                            log::warn!("WMV movie audio decode failed: {e:?}");
                            None
                        }
                    },
                };
                (data, am)
            }
        };

        let stop_flag = Arc::new(AtomicBool::new(false));
        let (tx, rx) = crossbeam_channel::bounded::<WmvRgbaFrame>(2);""",
                1,
            ),
            # 10) MPEG 路径：同上
            (
                """            MovieMode::LayerNoAudio => (None, None),
        };

        let stop_flag = Arc::new(AtomicBool::new(false));
        let (tx, rx) = crossbeam_channel::bounded::<MpegRgbaFrame>(2);""",
                """            // YukiHub patch: layer-effect movies keep their audio (see the MP4 branch).
            MovieMode::LayerNoAudio => {
                let am = audio_manager;
                let data = match am.as_ref() {
                    None => {
                        log::warn!("MPEG movie audio decode skipped: no AudioManager");
                        None
                    }
                    Some(_) => match decode_mpeg_audio_to_wav_bytes(&mpeg_path) {
                        Ok(Some(wav_bytes)) => {
                            let cursor = std::io::Cursor::new(wav_bytes);
                            Some(
                                StaticSoundData::from_cursor(cursor)
                                    .context("kira StaticSoundData::from_cursor")?,
                            )
                        }
                        Ok(None) => {
                            log::warn!(
                                "MPEG movie audio: no decodable audio track: {}",
                                mpeg_path.display()
                            );
                            None
                        }
                        Err(e) => {
                            log::warn!("MPEG movie audio decode failed: {e:?}");
                            None
                        }
                    },
                };
                (data, am)
            }
        };

        let stop_flag = Arc::new(AtomicBool::new(false));
        let (tx, rx) = crossbeam_channel::bounded::<MpegRgbaFrame>(2);""",
                1,
            ),
                ],
    ),
    (
        "crates/rfvp/src/subsystem/components/syscalls/movie.rs",
        [
# 8) movie.rs：层效果电影也传 AudioManager（真正让音频进播放链）
            (
                """    let audio_manager = if matches!(mode, MovieMode::ModalWithAudio) {
        Some(game_data.audio_manager())
    } else {
        None
    };""",
                """    // YukiHub patch: always hand the AudioManager to the movie player.
    // The original engine plays sound for layer-effect movies too (the opening OP is
    // `Movie(path, nil)`), so gating this on ModalWithAudio silenced every OP.
    let audio_manager = Some(game_data.audio_manager());""",
                1,
            ),
            # 9) movie.rs：候选解析改为「存在性优先」，避免为不存在的文件白白等待
            (
                """    if is_native {
        let mp4 = replace_ext(path, "mp4");
        if mp4 != path {
            return vec![path.to_string(), mp4];
        }
        return vec![path.to_string()];
    }""",
                """    if is_native {
        let mp4 = replace_ext(path, "mp4");
        if mp4 != path {
            // YukiHub patch: existence-first. Probing a file that does not exist first
            // (e.g. a `.wmv` the user replaced with `.mp4`) costs a long black screen
            // before the engine finally falls back to the real file.
            // NOTE: PathBuilder::join takes `self` by value, so we must not reuse a
            // single builder for two joins.
            let has_orig = app_base_path().join(path).get_path().exists();
            let has_mp4 = app_base_path().join(&mp4).get_path().exists();
            if has_mp4 && !has_orig {
                return vec![mp4];
            }
            return vec![path.to_string(), mp4];
        }
        return vec![path.to_string()];
    }""",
                1,
            ),
            # 10) movie.rs：计时日志 —— 把「白屏多久、花在哪」变成数据
            (
                """    let mut started = false;
    for cand in candidates {
        let cand = normalize_vfs_path(&cand);""",
                """    let mut started = false;
    // YukiHub patch: time the whole startup so long black screens can be attributed.
    let yh_t0 = std::time::Instant::now();
    for cand in candidates {
        let cand = normalize_vfs_path(&cand);""",
                1,
            ),
            (
                """                log::debug!("Movie: resolve failed for {} (orig {}): {e:?}", cand, path);
                continue;""",
                """                log::info!(
                    "Movie: resolve failed for {} (orig {}) after {:?}: {e:?}",
                    cand,
                    path,
                    yh_t0.elapsed()
                );
                continue;""",
                1,
            ),
            (
                """            Ok(()) => {
                started = true;
                break;
            }""",
                """            Ok(()) => {
                // YukiHub patch: startup timing (resolve + decoder init + first frame).
                log::info!(
                    "Movie: started {} after {:?}",
                    real_path.display(),
                    yh_t0.elapsed()
                );
                started = true;
                break;
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
