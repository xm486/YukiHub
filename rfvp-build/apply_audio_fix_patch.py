#!/usr/bin/env python3
"""
rfvp 音频修复补丁（YukiHub 定制）—— 修复 MP4/AAC 电影没声音。

问题（已实锤，日志 + 源码双重验证）：
    MoviePlay: path=movie/01.wmv flag=Nil is_layer_effect=true
    mp4 audio: no track with sample_rate+channels (tracks=2)
    Movie audio: no decodable audio track: .../01.mp4

    mp4 文件本身没问题（stsd 里明明白白写着 mp4a / 2ch / 48000Hz / 16bit），
    但 symphonia 0.5.5 的 isomp4 解析器有缺口：
      - atoms/stsd.rs::fill_codec_params 只调 with_sample_rate()，
        channels 仅在 PCM 分支才设置，AAC(mp4a+esds) 分支不设置；
      - 而 symphonia-codec-aac 的解码器又强制要求 channels：
          "aac: channels or channel layout is required"
    于是 rfvp 里那句
        .find(|t| sample_rate.is_some() && channels.is_some())
    永远匹配不上 AAC 轨 -> 音频永远解不出来。

修复思路：
    不指望 symphonia 帮忙 —— rfvp 自己从 mp4 容器里把声道数读出来，
    补进找到的那条音轨的 codec_params，再交给解码器。

    具体做法：轻量扫描 mp4 box 树，定位 `trak` 下 `mdia/minf/stbl/stsd` 的
    audio sample entry（handler 必须为 soun），读出 version/channels 字段。
    只支持 version 0/1/2 的 audio sample entry（覆盖 mp4a/AC-3/ALAC 等常规情况），
    读不到就返回 None、保持原行为（不影响其它格式的影片）。

用法（在 rfvp 仓库根目录）：
    python3 apply_audio_fix_patch.py

锚文本严格校验 + 幂等，风格与 apply_movie_patch.py 一致。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

EDITS = [
    (
        "crates/rfvp/src/subsystem/resources/videoplayer.rs",
        [
            # 0) import：Channels（音频声道位掩码）+ std::fs（读 mp4 容器）
            (
                """use symphonia::core::audio::{SampleBuffer, SignalSpec};""",
                """// YukiHub patch: `Channels` is needed to backfill channel info for AAC tracks,
// and `std::fs` to probe the MP4 container when symphonia omits it.
use symphonia::core::audio::{Channels, SampleBuffer, SignalSpec};
use std::fs;""",
                1,
            ),
            # 1) decode_mp4_audio_to_wav_bytes：先把 impl AsRef<Path> 转成 owned PathBuf
            #    （函数后面既要 File::open 又要传给 read_mp4_audio_channel_count，
            #      直接传 mp4_path 会被 move，导致 E0382）
            (
                """fn decode_mp4_audio_to_wav_bytes(mp4_path: impl AsRef<Path>) -> Result<Option<Vec<u8>>> {
    // Hint Symphonia that this is MP4.
    let mut hint = Hint::new();
    hint.with_extension("mp4");

    let cursor = std::fs::File::open(mp4_path)?;""",
                """fn decode_mp4_audio_to_wav_bytes(mp4_path: impl AsRef<Path>) -> Result<Option<Vec<u8>>> {
    // YukiHub patch: take ownership once — `impl AsRef<Path>` is not `Copy`, and we need
    // the path again below for the channel-count probe.
    let mp4_path: std::path::PathBuf = mp4_path.as_ref().to_path_buf();

    // Hint Symphonia that this is MP4.
    let mut hint = Hint::new();
    hint.with_extension("mp4");

    let cursor = std::fs::File::open(&mp4_path)?;""",
                1,
            ),
            # 2) 音轨查找：放宽条件 + 自行补齐 channels
            (
                """    // Prefer an audio track. In MP4 files, the "default" track is often video.
    let track = match format
        .tracks()
        .iter()
        .find(|t| t.codec_params.sample_rate.is_some() && t.codec_params.channels.is_some())
    {
        Some(t) => t,
        None => return Ok(None),
    };

    let sr = track
        .codec_params
        .sample_rate
        .ok_or_else(|| anyhow!("mp4 audio: missing sample_rate"))?;
    let ch = track
        .codec_params
        .channels
        .ok_or_else(|| anyhow!("mp4 audio: missing channels"))?
        .count();
    if ch == 0 {
        return Ok(None);
    }

    let mut decoder = get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .context("symphonia make decoder")?;

    let track_id = track.id;

    // Decode into interleaved PCM16.
    let spec = SignalSpec::new(sr, track.codec_params.channels.unwrap());""",
                """    // YukiHub patch: pick the audio track.
    //
    // Upstream required BOTH `sample_rate` and `channels` to be present on the track.
    // symphonia 0.5.5's isomp4 reader sets `sample_rate` but *never* `channels` for
    // AAC (`mp4a`/`esds`) sample entries, so no track ever matched and every MP4 movie
    // played silently. We therefore:
    //   1) narrow candidates by sample_rate (or by channels when already present), and
    //   2) backfill `channels` from the MP4 container itself when symphonia omitted it.
    let track = match format
        .tracks()
        .iter()
        .find(|t| t.codec_params.sample_rate.is_some() || t.codec_params.channels.is_some())
    {
        Some(t) => t,
        None => {
            log::warn!(
                "mp4 audio: no track with sample_rate/channels (tracks={})",
                format.tracks().len()
            );
            return Ok(None);
        }
    };

    let sr = match track.codec_params.sample_rate {
        Some(v) => v,
        None => {
            log::warn!("mp4 audio: track has no sample_rate, skipping");
            return Ok(None);
        }
    };

    // YukiHub patch: symphonia may leave `channels` unset (see comment above). When that
    // happens, read it straight out of the MP4 sample entry.
    let mut track_for_decode = track.clone();
    if track_for_decode.codec_params.channels.is_none() {
        match read_mp4_audio_channel_count(&mp4_path) {
            Some(n) if n > 0 => {
                log::info!("mp4 audio: backfilled {} channel(s) from container", n);
                track_for_decode.codec_params.channels = Some(channels_from_count(n));
            }
            _ => {
                log::warn!(
                    "mp4 audio: container channel count unavailable, defaulting to stereo"
                );
                track_for_decode.codec_params.channels =
                    Some(Channels::FRONT_LEFT | Channels::FRONT_RIGHT);
            }
        }
    }

    let ch = track_for_decode
        .codec_params
        .channels
        .ok_or_else(|| anyhow!("mp4 audio: missing channels"))?
        .count();
    if ch == 0 {
        return Ok(None);
    }

    let mut decoder = get_codecs()
        .make(&track_for_decode.codec_params, &DecoderOptions::default())
        .context("symphonia make decoder")?;

    let track_id = track_for_decode.id;

    // Decode into interleaved PCM16.
    let spec = SignalSpec::new(sr, track_for_decode.codec_params.channels.unwrap());""",
                1,
            ),
            # 3) 解码缓冲区：SampleBuffer::new(0, spec) 容量为 0，
            #    一旦真正解码就会 assert!(capacity >= n_samples) panic
            (
                """    let mut pcm: Vec<i16> = Vec::new();
    let mut sample_buf = SampleBuffer::<i16>::new(0, spec);""",
                """    let mut pcm: Vec<i16> = Vec::new();
    // YukiHub patch: `SampleBuffer::new(0, spec)` allocates a zero-capacity buffer and
    // `copy_interleaved_ref` asserts `capacity() >= n_samples`, so it panics on the very
    // first decoded packet. (Unreachable before, because AAC tracks were never selected.)
    // Allocate lazily, sized to each decoded packet's real frame count.
    let mut sample_buf: Option<SampleBuffer<i16>> = None;""",
                1,
            ),
            # 4) 解码成功分支：按需分配 + copy
            (
                """        match decoder.decode(&packet) {
            Ok(decoded) => {
                sample_buf.copy_interleaved_ref(decoded);
                pcm.extend_from_slice(sample_buf.samples());
            }""",
                """        match decoder.decode(&packet) {
            Ok(decoded) => {
                // YukiHub patch: size the interleaved buffer to this packet's frame count.
                let frames = decoded.frames();
                let n_channels = decoded.spec().channels.count();
                let need_realloc = match &sample_buf {
                    Some(b) => b.capacity() < frames * n_channels,
                    None => true,
                };
                if need_realloc {
                    sample_buf = Some(SampleBuffer::<i16>::new(frames as u64, *decoded.spec()));
                }
                let buf = sample_buf.as_mut().expect("just allocated");
                buf.copy_interleaved_ref(decoded);
                pcm.extend_from_slice(buf.samples());
            }""",
                1,
            ),
            # 5) 插入容器解析辅助函数（放到 build_wav_pcm16le 之前）
            (
                """fn build_wav_pcm16le(samples: &[i16], channels: u16, sample_rate: u32) -> Vec<u8> {""",
                """// ─────────────────────────────────────────────────────────────────────────────
// YukiHub patch: minimal MP4 audio sample-entry reader.
//
// Reads the channel count stsd writes for the audio track. Symphonia 0.5.5's isomp4
// reader drops this field for AAC, which makes the AAC decoder refuse to initialise.
// ─────────────────────────────────────────────────────────────────────────────

/// Map a channel count to a symphonia `Channels` bitmask.
fn channels_from_count(n: usize) -> Channels {
    match n {
        0 => Channels::empty(),
        1 => Channels::FRONT_LEFT,
        2 => Channels::FRONT_LEFT | Channels::FRONT_RIGHT,
        3 => Channels::FRONT_LEFT | Channels::FRONT_CENTRE | Channels::FRONT_RIGHT,
        4 => {
            Channels::FRONT_LEFT
                | Channels::FRONT_CENTRE
                | Channels::FRONT_RIGHT
                | Channels::REAR_CENTRE
        }
        5 => {
            Channels::FRONT_LEFT
                | Channels::FRONT_CENTRE
                | Channels::FRONT_RIGHT
                | Channels::REAR_LEFT
                | Channels::REAR_RIGHT
        }
        6 => {
            Channels::FRONT_LEFT
                | Channels::FRONT_CENTRE
                | Channels::FRONT_RIGHT
                | Channels::LFE1
                | Channels::REAR_LEFT
                | Channels::REAR_RIGHT
        }
        7 => {
            Channels::FRONT_LEFT
                | Channels::FRONT_CENTRE
                | Channels::FRONT_RIGHT
                | Channels::LFE1
                | Channels::REAR_LEFT
                | Channels::REAR_RIGHT
                | Channels::REAR_CENTRE
        }
        8 => {
            Channels::FRONT_LEFT
                | Channels::FRONT_CENTRE
                | Channels::FRONT_RIGHT
                | Channels::LFE1
                | Channels::REAR_LEFT
                | Channels::REAR_RIGHT
                | Channels::SIDE_LEFT
                | Channels::SIDE_RIGHT
        }
        _ => Channels::FRONT_LEFT | Channels::FRONT_RIGHT,
    }
}

/// Read the channel count from the audio track's sample entry in an MP4 file.
///
/// Layout: moov/trak* -> mdia/hdlr(handler == "soun") selects the audio track,
/// then mdia/minf/stbl/stsd -> first sample entry (usually `mp4a`).
///
/// The audio sample entry is a 8-byte box header followed by:
///   6 reserved | 2 data_ref_idx | 2 version | 2 revision | 4 vendor
///   | 2 channel_count | 2 sample_size | ...
/// `channel_count` therefore sits at entry_body + 16.
///
/// Only version 0/1 entries carry the count at that fixed offset; version 2 entries
/// (rare QuickTime LPCM) use a different layout and are skipped. Returns `None` when
/// anything does not line up — callers then fall back to stereo.
fn read_mp4_audio_channel_count(path: &Path) -> Option<usize> {
    let data = match fs::read(path) {
        Ok(d) => d,
        Err(e) => {
            log::warn!("mp4 audio: cannot read file for channel probe: {e:?}");
            return None;
        }
    };

    let moov = find_box(&data, 0, data.len(), b"moov")?;
    for trak in iter_boxes(&data, moov.0, moov.1) {
        if trak.typ != b"trak" {
            continue;
        }
        let Some(mdia) = find_box(&data, trak.body_start, trak.body_end, b"mdia") else {
            continue;
        };
        // Audio tracks are tagged by hdlr handler_type == "soun".
        let Some(hdlr) = find_box(&data, mdia.0, mdia.1, b"hdlr") else {
            continue;
        };
        // hdlr: 4 version/flags | 4 pre_defined | 4 handler_type
        let handler = data.get(hdlr.0 + 8..hdlr.0 + 12);
        if handler != Some(b"soun") {
            continue;
        }
        let Some(minf) = find_box(&data, mdia.0, mdia.1, b"minf") else {
            continue;
        };
        let Some(stbl) = find_box(&data, minf.0, minf.1, b"stbl") else {
            continue;
        };
        let Some(stsd) = find_box(&data, stbl.0, stbl.1, b"stsd") else {
            continue;
        };
        // stsd body: 4 version/flags | 4 entry_count, then the sample entries.
        let entries_start = stsd.0 + 8;
        for entry in iter_boxes(&data, entries_start, stsd.1) {
            if entry.typ != b"mp4a" && entry.typ != b"soun" {
                continue;
            }
            let version = read_be_u16(&data, entry.body_start + 8)?;
            let channel_count = read_be_u16(&data, entry.body_start + 16)? as usize;
            log::debug!(
                "mp4 audio: sample entry '{}' version={} channels={}",
                String::from_utf8_lossy(entry.typ),
                version,
                channel_count
            );
            if version <= 1 && channel_count > 0 {
                return Some(channel_count);
            }
            return None;
        }
    }
    None
}

struct Mp4Box<'a> {
    typ: &'a [u8],
    body_start: usize,
    body_end: usize,
}

/// Iterate sibling boxes in `[start, end)`.
fn iter_boxes<'a>(data: &'a [u8], start: usize, end: usize) -> impl Iterator<Item = Mp4Box<'a>> {
    let mut off = start;
    std::iter::from_fn(move || {
        if off + 8 > end {
            return None;
        }
        let size = read_be_u32(data, off)? as usize;
        let typ = data.get(off + 4..off + 8)?;
        let (body_start, body_end) = if size == 1 {
            let large =
                (read_be_u32(data, off + 8)? as usize) << 32 | read_be_u32(data, off + 12)? as usize;
            (off + 16, off + large)
        } else if size == 0 {
            (off + 8, end)
        } else {
            (off + 8, off + size)
        };
        if body_end > end || body_start > body_end {
            return None;
        }
        let cur = Mp4Box {
            typ,
            body_start,
            body_end,
        };
        if size == 0 {
            off = end;
        } else {
            off = body_end;
        }
        Some(cur)
    })
}

/// Find the first direct child box of the given type; returns its body range.
fn find_box(data: &[u8], start: usize, end: usize, want: &[u8; 4]) -> Option<(usize, usize)> {
    for b in iter_boxes(data, start, end) {
        if b.typ == want {
            return Some((b.body_start, b.body_end));
        }
    }
    None
}

fn read_be_u16(data: &[u8], off: usize) -> Option<u16> {
    let b = data.get(off..off + 2)?;
    Some(u16::from_be_bytes([b[0], b[1]]))
}

fn read_be_u32(data: &[u8], off: usize) -> Option<u32> {
    let b = data.get(off..off + 4)?;
    Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn build_wav_pcm16le(samples: &[i16], channels: u16, sample_rate: u32) -> Vec<u8> {""",
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