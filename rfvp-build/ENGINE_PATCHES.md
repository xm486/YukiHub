# YukiHub × rfvp 引擎补丁总览（长期记录）

> 本文档记录 YukiHub 对 rfvp 引擎（Rust 实现，`xmoezzz/rfvp`）做的全部定制补丁，
> 包含**根因分析、证据链、以及踩过的坑**，供后续维护/回归排查使用。
>
> 最后更新：本文件提交时（见 git log）。

---

## 1. 构建方式

| 项 | 说明 |
|---|---|
| 构建入口 | `.github/workflows/build-librfvp.yml`（手动触发 `workflow_dispatch`） |
| 补丁方式 | `rfvp-build/*.py` 脚本，**锚文本精确匹配 + 严格校验 + 幂等**；锚点不匹配即非零退出（构建失败，绝不静默漏打） |
| 目标产物 | `librfvp.so`（`aarch64-linux-android`，`cargo build --release --lib -p rfvp`） |
| 替换位置 | `app/src/main/jniLibs/arm64-v8a/librfvp.so`（**APK 需重编**） |
| 本地编译 | 本机 cargo 1.75 无法编译 edition2024 目标，**必须走 CI** |

### 补丁执行顺序（workflow 中）

```
apply_text_scale_patch.py      # 1. 全局字号系数
apply_text_patch.py            # 2. 外部文本替换表（patch.dat 查表）· YHFVP-TEXTPATCH-1.0
apply_android_log_patch.py     # 3. android logger + 压制 wgpu 噪音
apply_movie_patch.py           # 4. 电影播放（音频/层效果）
apply_audio_fix_patch.py       # 5. MP4 声道回填 + SampleBuffer 按需分配
apply_wmv_decoder_patch.py     # 6. WMA 解码器负数下标防护
apply_text_click_patch.py      # 7. 点击补全文字 + vm_runner resume 保护（**已无探针**）
apply_fvp_additive_blend_patch.py  # 8. PrimSetBlend(id,1) 走加法混合（上游 ffc73809）
apply_fvp_mask_dissolve_patch.py   # 9. Mask dissolve 遮罩渐变（上游 e95f275a）· YHFVP-MASKDISSOLVE-1.0
apply_fvp_dissolve_snapshot_patch.py # 10. 转场改为「擦掉起始帧」（上游 2a9ff58a）· YHFVP-DISSOLVESNAPSHOT-1.0
apply_fvp_jis_tunnel_patch.py      # 11. 官中版 SJIS 替身字还原 · YHFVP-JISTUNNEL-1.0
apply_audio_silent_fix_patch.py    # 12. SoundSilentOn/AudioSilentOn 不再真静音（上游 be92e540）
apply_fvp_timer_stop_patch.py      # 13. timer 到点即停（上游 a1091393，issue #49）· YHFVP-TIMERSTOP-1.0
apply_fvp_text_cancel_patch.py     # 14. 清掉遗留 TextWait/TextResume（上游 513b3955）· YHFVP-TEXTCANCEL-1.0
apply_fvp_stack_visit_patch.py     # 15. visit 缓冲改栈数组（上游 cbe9f1fa）· YHFVP-STACKVISIT-1.0
apply_fvp_tile_color_patch.py      # 16. Tile -1 = 全局窗口色（上游 cc8ac77 / ADR-0001，issue #55）· YHFVP-TILECOLOR-1.0
apply_fvp_colorset_signed_patch.py # 17. ColorSet 有符号检查（同 ADR-0001）· YHFVP-COLORSET-SIGNED-1.0
apply_fvp_syscall_semantics_patch.py # 18. PrimSetZ/TimerSuspend/PrimSetRS 语义（ADR-0003/0004）· YHFVP-SYSCALL-SEMANTICS-1.0
apply_fvp_prev_frame_patch.py      # 19. Sprt -1 = 画上一帧（上游 ADR-0002）· YHFVP-PREVFRAME-1.0
apply_fvp_audio_units_patch.py     # 20. 音频两个单位 bug：音量线性→dB、pan 0..1→-1..1 · YHFVP-AUDIO-UNITS-1.0
# 之后独立 git apply：
video-sys-cpu-read.patch       # 21. video-sys 解码器 ByteBuffer 模式
```
> 8 与 9 是**同一批移植**（FVP 渲染修复），**顺序无关**：两个脚本都会自适应
> 对方是否已应用（10 会检测 `emit_sprite_vertices` 是否已有 `additive` 形参）。

> 11 与 2（`apply_text_patch`）都会改 `lib.rs`，但**锚点刻意错开**（11 插在
> `pub mod rfvp_render;` 之后，2 锚定 `pub mod script;` + `pub mod soft_host;`），
> 因此**任意顺序都对**（双顺序已实测）。

> ⚠️ 顺序有依赖：`apply_movie_patch` 与 `apply_audio_fix_patch` 会争抢
> `videoplayer.rs` 同一段代码，必须保持现有顺序（见提交 `a013595`）。

---

## 2. 补丁清单与职责

| 补丁 | 目标文件 | 解决的问题 |
|---|---|---|
| `apply_text_scale_patch.py` | `text_manager.rs` / `app.rs` / `android_host.rs` | 全局字号系数（1.0~2.0x），新增 C ABI `rfvp_android_set_text_scale` |
| `apply_text_patch.py` | `text_patch.rs`(新) / `lib.rs` / `script/parser.rs` | **外部文本替换表**：按脚本字节偏移查表替换（Windows 注入式汉化的安卓化），标识 `YHFVP-TEXTPATCH-1.0` |
| `apply_android_log_patch.py` | `main.rs` 等 | 接入 android_logger；压制 wgpu 日志（避免 logcat 配额把诊断日志挤掉） |
| `apply_movie_patch.py` | `videoplayer.rs` | MP4 层效果电影保留音频；**WMV/MPEG 恢复原版 `(None, None)`**（不做同步音频解码） |
| `apply_audio_fix_patch.py` | `videoplayer.rs` | ① 自研 MP4 box 解析器回填 `channels`；② `SampleBuffer` 按实际帧数惰性分配 |
| `apply_wmv_decoder_patch.py` | `na_wmv_player/src/wma/decoder.rs` | WMA 指数解码负数下标 panic → 返回 `Err` |
| `apply_text_click_patch.py` | `app.rs` / `text_manager.rs` / `vm_runner.rs` | **点击补全点字动画** + `TextResume` 状态保护（**已无探针**，见 §4） |
| `apply_fvp_additive_blend_patch.py` | `sprite.rs` / `gpu_prim.rs` | `PrimSetBlend(id,1)` 改走加法混合，对齐原引擎（见 3.9） |
| `apply_fvp_mask_dissolve_patch.py` | `mask_dissolve.rs`(新) / `rendering/mod.rs` / `gpu_prim.rs` / `motion_manager` | 实现 Mask dissolve 遮罩渐变，修 FVP HD parts 背景暗带（见 3.9） |
| `apply_fvp_dissolve_snapshot_patch.py` | `mask_dissolve.rs` / `gpu_prim.rs` / `motion_manager` / `app.rs` | 转场改为**擦掉开始时那一帧**（不再盖黑），修转场生硬（见 3.12）· **须紧跟上一个补丁** |
| `apply_fvp_jis_tunnel_patch.py` | `jis_tunnel.rs`(新) / `lib.rs` / `text_manager.rs` / `history.rs` / `save_manager.rs` | **SJIS 替身字还原**（Unicode→Unicode）。表来源双认：`Sakura.dll`（樱花萌放官中版）/ `uif_config.json`（UIF 系汉化，如 HappyMarguerite_CHS）。**全部挂在文字路径**（对话/backlog/存档），不碰资源名，标识 `YHFVP-JISTUNNEL-1.0`（见 3.10） |
| `apply_audio_silent_fix_patch.py` | `audio_player/se_player.rs` / `bgm_player.rs` | `SoundSilentOn`/`AudioSilentOn` **不再真的静音**（对齐上游 10.15，见 3.11） |
| `apply_fvp_timer_stop_patch.py` | `timer_manager.rs` | timer 到点即停、`TimerGet(id,nil)` 归零，修老 FVP **按钮点不动**（见 3.13）· `YHFVP-TIMERSTOP-1.0` |
| `apply_fvp_text_cancel_patch.py` | `thread_wrapper.rs` / `text_manager.rs` / `vm_runner.rs` | 线程结束/重启时清掉遗留 `TextWait`/`TextResume`，是 `TextResume` 守卫的**根治版**（见 3.14）· `YHFVP-TEXTCANCEL-1.0` |
| `apply_fvp_stack_visit_patch.py` | `gpu_prim.rs` | prim 树遍历的 visit 缓冲由堆 `vec!` 改栈数组，每帧少 2 次 4KiB 分配（见 3.15）· `YHFVP-STACKVISIT-1.0` |
| `apply_fvp_tile_color_patch.py` | `color_manager.rs` / `gpu_prim.rs` | **Tile 色号 -1 = 全局窗口色**，修对话框背景不透明 + 设置颜色不生效（见 3.16）· `YHFVP-TILECOLOR-1.0` |
| `apply_fvp_colorset_signed_patch.py` | `color.rs` | `ColorSet` 改有符号检查，`-1` 不再写坏槽 255（见 3.16）· `YHFVP-COLORSET-SIGNED-1.0` |
| `apply_fvp_syscall_semantics_patch.py` | `graph.rs` / `timer.rs` | `PrimSetZ`/`TimerSuspend` 判 `True`；`PrimSetRS/RS2` 缩放钳到 [100,10000]（见 3.16）· `YHFVP-SYSCALL-SEMANTICS-1.0` |
| `apply_fvp_prev_frame_patch.py` | `render_target.rs` / `gpu_prim.rs` / `app.rs` | **Sprt 纹理 -1 = 绘制上一帧**，修设置界面背景（见 3.16）· `YHFVP-PREVFRAME-1.0` |
| `apply_fvp_audio_units_patch.py` | `bgm_player.rs` / `se_player.rs` | **音频两个单位 bug**：线性音量当 dB 传 kira、pan 没映射到 -1..1（见 3.17）· `YHFVP-AUDIO-UNITS-1.0` |
| `video-sys-cpu-read.patch` | `video-sys` crate | 解码器输出改 ByteBuffer 模式（修 Qualcomm UBWC 白屏） |

> **APK 侧（不涉及 so）**：输入映射 v4、设置滑条保存、**虚拟按键/虚拟鼠标**（见 3.8）。
> 这些改动只需重编 APK，不需要跑 so 构建 workflow。

---

## 3. 各问题的根因与修复（按发现顺序）

### 3.1 视频白屏卡死（OP 无法播放）

**现象**：OP 播放时全白、无法跳过、游戏卡死。

**根因**：上游用 `AImageReader` 渲染解码输出再 `AImage_getPlaneData` 读回 CPU。
但部分 Qualcomm/Oplus 设备无视 `YUV_420_888` 请求，输出 **UBWC 私有格式**
（`0x7fa30c06`）→ CPU 锁帧失败 → 永久白屏。

**修复**（`video-sys-cpu-read.patch`）：改用 **ByteBuffer 模式** ——
`AMediaCodec_configure(surface=null)` + `COLOR_FormatYUV420Flexible`，
直接 `getOutputBuffer()` 拿数据。

**验证日志**：`mediacodec ByteBuffer layout: 1280x720 stride=1280 planar=false`

---

### 3.2 MP4 电影没有声音

**现象**：画面正常，但无音频。

**根因（两层）**：

1. **symphonia 0.5.5 的 isomp4 读取器不填 `channels`** → 下游按 0 声道处理。
2. `SampleBuffer::new(0, spec)` → **容量 0** → 真正解码时
   `copy_interleaved_ref` 触发 `assert!(capacity)` → **panic**，脚本线程等在音频上 → 白屏。

**修复**（`apply_audio_fix_patch.py`）：

- 自研最小 MP4 box 解析器，从容器回填声道数；
- `SampleBuffer` 改为**按 packet 实际帧数惰性分配**。

**验证日志**：
```
mp4 audio: sample entry 'mp4a' version=0 channels=2
mp4 audio: backfilled 2 channel(s) from container
mp4 audio: decoded 10741760 samples, 48000 Hz, 2 ch
```

**本地真跑验证**：`DONE packets=5245 samples=10741760 elapsed=2.14s`（与设备一致）。

---

### 3.3 未转换视频（.wmv）永久白屏

**现象**：玩家没转换视频、直接用原版 `.wmv` 时卡死在白屏，且**跳不过去**。
（此前只是白屏一会儿就能过。）

**根因（两层，主因见 3.3.1）**：

1. **主因：WMA 解码性能灾难**。上一轮把 WMV/MPEG 的 `LayerNoAudio` 分支也改成
   "照常解码音频"，而 `decode_wmv_audio_to_wav_bytes` 是**主线程同步全量解码**；
   而 `na_wmv_player` 的 WMA 解码器比实时慢 5.3×（`MdctNaive` 非 FFT）
   → 一个 130 秒的 OP 要解约 11 分钟 → 主线程永久阻塞。
   **详细实测数据见 [3.3.1](#331-wmv-没声音结论保持现状不修)。**
2. **次因：`wma/decoder.rs` 负数下标 panic**（`ptab[-3 as usize]`）——
   FFmpeg 用无符号比较 `(unsigned)last_exp >= 96` 拦截负数，Rust 移植只查了上界。
   这会在大文件解码中途崩溃（更早暴露问题）。

**修复**：

- `apply_movie_patch.py`：**WMV/MPEG 恢复原版 `(None, None)`**（不做同步音频解码）；
- `apply_wmv_decoder_patch.py`：负数/越界返回 `Err` 而非 panic；
- 补丁改 **fail-fast**（文件缺失 → 退出码 1，不再静默跳过）。

> ⚠️ **重要约定**：WMV 播放逻辑必须**遵循原版**。
> 实测部分游戏的原版 wmv 是可以正常播放的，不要为了 MP4 而改动 WMV 路径。

**验证日志**：`Movie: started` 仅 7ms（原为 15ms+ 同步解码）。

---

### 3.3.1 WMV 没声音（结论：**保持现状，不修**）

> 这一节是**排查记录 + 明确结论**。以后再遇到"WMV 能播画面但没声音"，
> 直接看这里，**不要重新排查**。

#### 现象

- 部分游戏（如《樱花萌放》）OP 是 `.wmv`：**画面能正常播放，但没有声音**。
- 转成 MP4 后一切正常 → 说明不是游戏资源问题。

#### 排查过程（含实测数据）

**① 确认文件真实格式**（解析 ASF 容器头）：

```bash
# 结果（sakramoyu/movie/01.wmv）
视频：biCompression = "WMV2"   1280x720
音频：wFormatTag = 0x0161 (WMAv2)  2ch / 48000Hz / 160kbps
      block_align=853  bits=16  cbSize=10
```

**两种格式 rfvp 都声明支持**（`na_wmv_player/src/api.rs`）：

```rust
// The decoder selects the first audio stream with format tag 0x0160 (WMAv1) or 0x0161 (WMAv2).
if matches!(a.format_tag, 0x0160 | 0x0161) { ... }
```

→ **不是"不支持这种编码"**。

**② 真编译真跑**（把 `na_wmv_player` 单独编译，直接解码该文件）：

```text
# 音频（WMAv2）—— AsfWmaDecoder::next_frame()
opened: sr=48000 ch=2
  frames=500  samples=2048000  elapsed=56.9s    pts=21291   (≈21s 音频)
  frames=1000 samples=4096000  elapsed=112.0s   pts=42624
exit=124   ← 120 秒超时，只解出 ~21 秒音频

# 视频（WMV2）—— AsfWmv2Decoder::next_frame()
VIDEO DONE frames=3909 elapsed=37.7s video_sec=130.4      ← 快 3.5× 实时
```

**结论（关键数据）**：

| 流 | 内容时长 | 解码耗时 | 相对实时 |
|---|---|---|---|
| 视频 WMV2 | 130.4 s | **37.7 s** | 3.5× 快 ✓ |
| 音频 WMAv2 | ~21 s | **112 s** | **5.3× 慢** ✗ |

→ **音频解码比实时慢 5.3 倍**。一个 130 秒的 OP，全量解码需要 **约 11 分钟**
（PC 上；手机上更慢）。

**③ 为什么会卡死白屏**

`decode_wmv_audio_to_wav_bytes()` 是**主线程同步**调用：

```rust
while let Some(fr) = dec.next_frame()? {   // 主线程死等，直到整个文件解完
    ...
}
```

11 分钟主线程阻塞 = 永久白屏 + 跳不过去（这就是 3.3 白屏问题的**真正原因**，
比"负数下标 panic"更根本）。

**④ 性能灾难的根因**

`crates/na_wmv_player/src/wma/mdct.rs` 用的是 **`MdctNaive`（朴素实现，未用 FFT）**。
功能正确，但性能不可接受。

#### 为什么不做（方案评估）

| 方案 | 可行性 | 说明 |
|---|---|---|
| A. 直接开 WMV 音频解码 | ❌ | 慢 5.3×，主线程同步 → 卡死 11 分钟 |
| B. 后台线程边解边播 | ⚠️ 且无效 | 需重写解码线程+环形缓冲+时钟同步；但速度仅 **0.19× 实时**，**根本追不上**（OP 播 10 秒才解出 2 秒音频） |
| C. 用系统 MediaCodec 解 WMA | ❌ | Android MediaCodec **不支持 WMA**（专利原因已移除） |
| D. 引入 FFmpeg 软解 | ❌ 重 | ffmpeg-kit 使 APK +30MB，且授权复杂 |
| **E. 保持现状（MP4 方案）** | ✅ **推荐** | 玩家自行转 MP4 即可获得**完整功能** |

**最终决定：维持现状。** 理由：

1. **收益/成本极差**：为"原版 wmv 出声"要么卡死 11 分钟，要么重写整条音频管线（还追不上）。
2. **MP4 方案已是完整解**：转换格式即可获得画面+音频+跳过+防穿透+点字动画全套功能。
3. **"WMV 没声音"属于原版行为**（见下），不是我们引入的 bug。
4. 强行改动会引入新风险：层效果期间脚本仍在运行 → 音频与 BGM 冲突、
   时钟不同步、退出时资源泄漏等。

#### 补充：`MovieMode` 与"层效果"（LayerNoAudio）

```rust
// movie.rs
let is_layer_effect = flag.is_nil();          // 脚本 MoviePlay(path, flag) 的 flag
let mode = if is_layer_effect {
    MovieMode::LayerNoAudio                    // 层效果：视频 only，脚本继续跑
} else {
    MovieMode::ModalWithAudio                  // 模态：视频+音频，脚本暂停
};
```

| | `LayerNoAudio`（层效果） | `ModalWithAudio`（模态） |
|---|---|---|
| 触发 | `MoviePlay(path)`，flag = **Nil** | `MoviePlay(path, 1)`，flag **非 Nil** |
| 画面 | 画在图层上，**脚本继续运行** | 全屏模态，`set_halt(true)` 暂停脚本 |
| 典型用途 | **OP**、演出特效、背景动画 | 强制过场动画 |
| 原版音频 | **不解码**（设计如此，避免与 BGM 打架） | 解码 |

> 注意：原版 OP 恰恰是走 `LayerNoAudio` 的，而 Windows 版 OP **有声音**。
> 因此我们**给 MP4 的层效果分支补上了音频解码**（`apply_movie_patch.py` 第 8 条），
> 但 **WMV 的层效果分支保持原版 `(None, None)`**（原因见上：解码性能不可接受）。

#### 给用户的话

> **WMV 视频想有声音？请自行转成 MP4。**
> 转换后即可获得完整功能；原版 WMV 则保持"能播画面、可跳过、不卡死"。

---

### 3.4 设置文本速度失效 / 退出不保存

**根因**：`ff76e1c`（点击延迟到抬手下发）把 `ACTION_DOWN` 改成发 `move`，
导致设置滑条**丢失"按下边沿"** → 拖不动、退出不保存。

**修复**（`FvpActivity.java`）：触摸映射恢复**直通**（`down/up` 真实下发）。

---

### 3.5 点击穿透（点按钮时对话也过一句）

**现象**：点右下角存档/菜单按钮时，按钮响应的同时**对话也推进一句**。

**排查过程**（多次失败，最终靠数据）：

| 尝试 | 结果 |
|---|---|
| `ACTION_DOWN` 改发 `move`、抬手合成 `down+up` | 解决穿透，但滑条失效 |
| 恢复直通 | 穿透回归 |
| 同帧补 `move` | 无效（引擎本来就先调 `notify_mouse_move`） |
| `down` 延后一帧 | 无效（`move` 与 `down` 被脚本同帧读到） |
| 按帧排队（每帧一个边沿） | 无效 |

**YHPROBE 探针数据揭示的真相**：

- `InputGetEvent` / `InputSetClick` / `InputGetUp` 探测 **0 次**
  → 脚本**不用事件队列、不用抬起**，只用 **`down` 边沿** + **当前光标**；
- `frame=1054` 铁证：光标瞬移 `(1838,28)→(999,867)` 与 `down=0x14`
  被脚本**同一帧**读到；
- 脚本每帧顺序：`InputGetDown`（推进对话）→ `PrimHit`（按钮判定）。

**修复（v4，`37d08be`）**：`FvpActivity` 新增 `downSettleFrames` ——
`ACTION_DOWN` 只发 `move` 并置 `1`；`doFrame` 先把 settle 减到 0
（本帧不发 down，让脚本用新光标跑完一帧），**下一次 doFrame 才发 down**。
→ 脚本此时已知"光标在按钮上"，不再推进对话。

> 版本标记：`onCreate` 输出 `YHINPUT-MAP v4 (down-settle + per-frame edge queue)`，
> 日志里没有这行说明 APK 未更新。

---

### 3.6 点击跳过文字加载（点字动画）—— 本轮重点

**需求（PC 原版行为）**：

- 文字**正在逐字出现**时点击 → **先把这一句显示完**，这次点击被吞掉；
- 文字**已显示完**时点击 → 直接推进下一句。

**现象**：rfvp 里无论字有没有显示完，点一下都直接过对话。

#### 根因链（逐层挖出）

**① 引擎缺少"点击补全"的接线**

```rust
// text_manager.rs —— 全库唯一一处"立即全显"
pub fn update_text_reveal(...) {
    if elapsed < 0 {                       // elapsed<0 只在 Ctrl / ControlPulse 时成立
        self.text_manager.force_reveal_all_non_suspended();
    }
}
```

`text_print` 会按 `should_block_on_print()` 决定是否阻塞脚本线程
（`thread_text_wait` → VM 置 `CONTEXT_STATUS_TEXT(8)`）。
**但鼠标点击完全没有接进"补全文字"的逻辑** —— 只有 Ctrl/ControlPulse 有。

**② 对话槽与设置槽的阻塞行为不同（关键区分）**

```rust
fn should_use_sync_print_wait(&self, ...) -> bool {
    if self.skip_mode != 0 { return false; }   // ← skip 模式不阻塞
    ...
    self.effective_reveal_speed_units(g0) > 0
}
```

| 槽 | 用途 | `skip_mode` | 是否阻塞脚本 | 点击的下场 |
|---|---|---|---|---|
| **0** | **对话消息** | **3** | **不阻塞** | 脚本收到点击 → 顺势推进 → 看起来"补全没生效" |
| 31 | 设置页描述 | 0 | 阻塞 | 点击天然丢失 → 只剩补全（所以"点按钮时生效"） |

**③ 判据必须用"点击那一刻的快照"（顺序 bug）**

最后一版修复前，代码是"**先补全、后判断要不要吞**"：

```rust
// ❌ 错：补全后 visible==total，判据立刻失效 → 永不吞
if !incomplete.is_empty() { force_reveal_slots(&incomplete); }
let nb = nonblocking_revealing_slot_ids();      // ← 此时已为空
if !nb.is_empty() { suppress_next_mouse_click(); }
```

#### 最终实现（`apply_text_click_patch.py`）

```rust
// app.rs :: host_touch_android（phase == 0，即按下）
let incomplete = text_manager.incomplete_slot_ids();            // 有未完成槽（含设置页）
let nb         = text_manager.nonblocking_revealing_slot_ids(); // 正在逐字出现 && skip_mode!=0
if !incomplete.is_empty() { text_manager.force_reveal_slots(&incomplete); }
if !nb.is_empty()         { inputs_manager.suppress_next_mouse_click(); }  // 只有对话槽会进这里
```

- `force_reveal_slots()`：只补全指定槽（`visible=total`、清 pending 等待、刷 delta）；
- `suppress_next_mouse_click()`：吞掉 **down+up（=2）**，即"吃一次完整点击"；
- **不 `return`、不跳过 `notify_mouse_move`** → 光标照常更新；
- 设置页的槽 `skip_mode == 0`，**永远进不了 `nb`** → 设置页/按钮零影响。

**帧内顺序保证"这次点击天然丢失"**（`no_std_core` 源码注释）：

```
帧 N：   vm_runner.tick()        ← 脚本先跑（此刻仍在 CONTEXT_STATUS_TEXT 阻塞中）
   ↑ 我们在这里补全
         scene.update_after_vm() ← 才 tick reveal / 唤醒等待线程
帧 N+1： begin_frame 已清掉 down 边沿 → 被唤醒的脚本也看不到这次点击
```

#### 附带修复：`vm_runner` 的 `TextResume` 崩溃保护

**现象**：点设置页"退出"时闪退（`SIGABRT`）。

**证据链**：
```
YHPROBE click completes text slots=[31]
YHPROBE TextWaitDone slot=31 thread=15
E rfvp::script::context: unknown opcode: 0x2b @ 0x000000, thread: 15
F libc: FORTIFY: pthread_mutex_lock called on a destroyed mutex → SIGABRT
```

**根因**：槽的 `sync_wait_active` 会是**残留值**（该线程上下文可能已被
`thread_exit` / `thread_start(0)` 重建，`pc=0`）。补全 → 完成 → `resume`
→ vm_runner **无条件**把该线程置 `RUNNING` → 它从地址 0 取指 → `unknown opcode`。

**修复**：两处 `ThreadRequest::TextResume` 都加状态保护：

```rust
if st.contains(ThreadState::CONTEXT_STATUS_TEXT) {
    let mut st2 = st.clone();
    st2.remove(ThreadState::CONTEXT_STATUS_TEXT);
    st2.insert(ThreadState::CONTEXT_STATUS_RUNNING);
    self.tm.set_context_status(id, st2);
} else {
    log::warn!("YHPROBE TextResume ignored (tid={} bits={} not TEXT)", id, st.bits());
}
```

> 这也顺手修掉了**引擎自身的一个潜在崩溃点**（自然完成路径同样会踩）。

#### 验证结果（最终日志）

```
1  click complete+SWALLOW incomplete=[0] nb=[0]   ← 对话：补全 + 吞点击 ✓
8  click complete only    incomplete=[31]        ← 设置页：只补全、不吞 ✓
3  TextResume ignored (tid=15 bits=0 not TEXT)    ← 崩溃保护在干活 ✓
0  panic / unknown opcode / SIGABRT / FATAL       ← 无崩溃 ✓
```

---

### 3.7 樱花萌放中文文本（外部替换表）—— 借鉴"摸鱼补丁"的思路

**背景**：《樱花，萌放。》的 PC 汉化是 **Windows 注入式**
（`SakuraChs.exe` + `filter.dll` + `patch.dat`）：`Sakura.hcb` 本身仍是日文，
靠 DLL 在运行时按偏移替换文本。rfvp 不加载 DLL，所以直接玩是日文。

**早期方案（已废弃）**：把替换表离线写回 `Sakura.hcb`（等长重写）。
缺点：① 必须等长 → 3758 句要裁标点、643 句放弃；② 改动了游戏文件。

**现方案（`apply_text_patch.py`）**：**引擎侧查表**。

- 在 `Parser::read_cstring(offset, len)` 开头查表：命中即返回替换文本；
- `Sakura.hcb` **一个字节都不改** → 所有 `jmp`/`jz` 的 u32 绝对地址天然不变 → **零风险**；
- 替换发生在内存里 → **长度不受限**（无需裁剪/放弃）；
- 表来源：游戏根目录 `patch.dat`（16 字节头 + zlib，记录 `[00][u32 offA][u8 len][前导NUL+GBK]`）；
- **自动挑表**：扫描目录所有 `.dat`，按与脚本实际字符串偏移的命中数评分取最高；
  覆盖不足 20% 弃用（防外部补丁劫持）。

**实测（v0.1.8 + HS 全部汉化版）**：57043 条替换 / 61529 个脚本字符串（**91%**），
序章 **100% 中文**；未命中的 4231 条里真日文句子仅 **37 条**（且为同一句
`？蝶ネクタイをしたすまし顔の猫` 的重复，属脚本残留）。

**编码设置**：因未命中的非文本串以 SJIS 呈现，**文本编码应设 SJIS**
（设 GBK 会让那 37 条真日文变乱码；正文因查表命中不受编码影响）。

**与「摸鱼安卓补丁」的区别**（同为查表思路，但互不通用）：

| | 摸鱼 | 我们 |
|---|---|---|
| patch.dat 结构 | `[u32 条数][u32 off,u16 len,文本]` | `[00][u32 offA][u8 len][文本]` |
| 条目 | 57172 / 1.42MB | 57053 / 1.34MB |
| 适配版本 | v0.2.0_0222_fix1 | **v0.1.8 + HS** |
| 对本版本命中率 | 4.6%（会被自动弃用） | 91% |

**日志异常项（本次实测，均非本补丁引入）**：

| 现象 | 结论 |
|---|---|
| `app_name: "偝偔傜丄傕備丅 -as the Night's..."`（标题乱码） | **编码设为 GBK 所致**——标题取自 `Sakura.hcb` 头部，该处未做替换；改回 **SJIS** 即正常显示 `さくら、もゆ。` |
| `Invalid duration` ×535 / `Invalid id` ×318 / `Invalid alpha motion id` ×122 | 上游原有噪音（引擎对非法参数宽容处理），**非本补丁引入** |
| `prim_set_uv: invalid v : Nil` ×419 / `graph_load: invalid id : Nil` ×123 | 同上，脚本传 Nil 时引擎的既有告警 |
| `YHPROBE TextResume ignored (tid=15 bits=0 not TEXT)` ×3 | **`apply_text_click_patch` 的崩溃保护在正常工作**（详见 3.6） |
| `Font scan skipped: no font dir under ...` | 正常：游戏目录无字体目录，改用系统 CJK 回退 + 内置 snow.ttf |

**产物辨识**：`librfvp.so` 内含 `YHFVP-TEXTPATCH-1.0` 字符串，
`grep -a YHFVP-TEXTPATCH librfvp.so` 可确认补丁是否编入（CI 也用它做校验）。

---

### 3.8 FVP 虚拟按键 + 虚拟鼠标（**纯 Java，不动 so**）

> 需求来源：rfvp 官方 Android 改版（`MengNiang/源码-1/rfvp-android-sakura-public`）
> 有常显的 ESC / 鼠标 / Ctrl 三个悬浮按钮 + 虚拟光标。
> 本仓库**不照搬**它的做法，而是复用自家 KRKR/Artemis 那套更完整的 `gamecursor`。

#### 需求（用户确认版）

| # | 需求 | 实现 |
|---|---|---|
| 1 | 开关进「FVP 引擎设置」，**默认关闭** | `FvpLaunchPrefs.virtualKeys/virtualMouse`（按游戏存）|
| 2 | 左侧竖排三键：**ESC / 历史 / Ctrl** | `FvpVirtualKeys.java`（新建）|
| 3 | 鼠标浮标右下角、可拖动、"开了就亮" | 复用 `GameCursorOverlay.ToggleView` |
| 4 | **全量复用** KRKR/Artemis 的 gamecursor（含 Windows 光标包） | `GameCursorManager.attachFvp` |
| 5 | **不抄**改版的触屏改动，保持 v4 直触 | 触屏逻辑一行未动 |

#### 键码语义（关键，决定"历史"用什么键）

`librfvp.so` 的 `app.rs::host_key_android` 用的是 **Windows VK 码**：

```
0x1B Escape  0x0D Enter  0x20 Space  0x25..0x28 方向键  0x11 Control
phase: 0 = down, 1 = up
```

因此「历史记录」用 **`0x26` ArrowUp** —— 对应 PC 上「鼠标上滚轮 = 打开历史」。
**实测确认方向键上确实生效**（用户实机验证）。

#### 三个必要条件（早就齐了，所以纯 Java）

| 组件 | 现状 |
|---|---|
| `librfvp.so` 导出 `rfvp_android_key` | ✅ symbol 表实测存在 |
| C 桥 `rfvp_bridge.c` 的 `keyEvent` | ✅ 已实现（dlsym 取 `key` 符号）|
| `NativeRfvp.keyEvent(long,int,int)` | ✅ 已声明 |

#### 踩过的两个坑（都记下来）

**坑 1：`Activity` 不是 `ViewGroup`，也没有 `getContext()`**

为了拿 `DecorView` 把 host 从 `FrameLayout` 改成 `Activity`，
漏改三处按旧类型用的代码 → javac 报错。修法：
`Activity` 本身就是 `Context`，直接 `new LinearLayout(host)`；
兜底父容器用 `host.findViewById(android.R.id.content)`。

**坑 2：`attachFvp` 里读了一个永远为 false 的开关**（**虚拟鼠标"开了没反应"的真凶**）

```java
// 错误写法
GameCursorConfig cfg = GameCursorConfig.load(host);
if (!cfg.fvpEnabled) return;   // ← 读 SharedPreferences("game_cursor")
```

`KEY_ENABLED_FVP` 全项目**只有定义、没有任何 UI 写入它** → 永远 false → 静默 return。
logcat 里 `YukiGameCursor` **一行都没有**，就是被这行挡的。

正确做法：**是否启用由调用方决定**（`FvpActivity` 读「引擎设置」的开关），
`GameCursorManager` 只负责取外观参数（大小/透明度/灵敏度/光标包）。

> 教训：**开关有两个来源时，一定要确认哪一个是真正被写入的那个。**
> 另一个症状是「FVP 设置里根本没有虚拟鼠标入口」——
> `MainActivity` 的菜单项写死了只给 `KIRIKIRI/ARTEMIS`。

#### 实现要点

| 文件 | 改动 |
|---|---|
| `fvp/FvpVirtualKeys.java` | **新建**：左侧三键，ESC 单击 / 历史 ArrowUp / Ctrl 按住连发 |
| `gamecursor/GameCursorInjector.java` | 加 `TARGET_FVP`（tap 50ms 按下再抬起、hover 走 phase=1）|
| `gamecursor/GameCursorManager.java` | 加 `attachFvp/detachFvp`（普通 Activity 走 `showInDecor`，**无需悬浮窗权限**）|
| `gamecursor/GameCursorConfig.java` | 加 `KEY_ENABLED_FVP` |
| `fvp/FvpActivity.java` | EXTRA 开关、坐标换算 `toSurfaceCoords()`、onPause 释放按键 |
| `MainActivity.java` | 设置 UI 两个 CheckBox + 「虚拟鼠标」菜单入口（含 FVP）|
| `launcher/EmulatorLauncher.java` | 启动 Intent 带上开关 |

**坐标换算**：光标用 `DecorView` 坐标，`rfvp_android_touch` 收 `SurfaceView` 坐标，
开「画面放大」时两者不一致，必须减 `surfaceView.getLocationInWindow` 偏移。

**按键外观（实测迭代 4 轮定稿）**：62×48dp、间距 40dp、左边距 12dp、
常态不透明度 40% / 按下 60%、左侧垂直居中（FVP 手机上有黑边，贴左不挡画面）。

#### 验证日志（实测）

```
FvpActivity: virtual controls: keys=true mouse=true
YukiGameCursor: appearance = win cursor pack, frames=60 (幼刀鼠标方案)
YukiGameCursor: overlay shown via decor mouseMode=false
YukiGameCursor: attached fvp
YukiGameCursor: mouseMode=true
YukiGameCursor: fvp tap (1364,778)      ← 点击真的投给引擎了
FvpVirtualKeys: virtual keys attached (ESC / 历史 / Ctrl)
```

---

### 3.9 FVP HD "parts" 背景硬边暗带（上游贴图 bug）—— 移植上游修复

**现象**：FVP HD 版《Hoshizora no Memoria》开场星空背景上，有一条**硬边暗色竖带**
（1920×1080 虚拟坐标下约 x=712..1274），并且会**有节奏地明暗脉动**。

**排查（已提 upstream issue #38）**：

| 证据 | 内容 |
|---|---|
| 涉及的图 | `graph/BG063_020`(基图) / `BG063_020b` / `BG063_020_parts1` / `BG063_020_parts2` |
| 罪魁 | `parts2` 的 sprite quad `rect=(712,-4)-(1274,939)` —— 正好是暗带位置 |
| alpha 时间线 | 一直脉动 `0.98 → 0.25 → 0.88 → 0.25 …`，从不稳定 |
| 文件特征 | `parts2` 598×1002，压缩后仅 ~12 KB —— 按 8-bit 灰度算压缩比 49:1（合理），按 RGB24 算是 146:1（不合理）→ **它本来就是 8-bit 遮罩图** |
| 排除项 | 不是 Parts syscall（只用于角色）、不是 PrimSetZ、不是全屏 Dissolve |

**上游回复（作者 xmoezzz）**：

> I guess the incorrect dissolve motion caused it. It should be fixed in e95f275

**根因**：`MaskFadeIn / MaskFadeInOut / MaskFadeOut` 这类 dissolve 用的是 8-bit 遮罩图，
原引擎按"遮罩形状逐渐变黑"来合成。而 rfvp 此前**在所有平台上都没实现这条管线**
（旧版 `app.rs` 原话：*"Until a dedicated mask dissolve pipeline is implemented, do not draw
a solid overlay here"*），于是遮罩图被当成普通贴图直接画出来 → 硬边暗带。

**上游修复（两个提交）**：

| 提交 | 内容 |
|---|---|
| `e95f275a` | 实现 Mask Dissolve：root=0 之后、dissolve2/HUD 之前插入全屏合成，`clamp(alpha + 2*progress - 1, 0, 1)` |
| `ffc73809` | `PrimSetBlend(id, 1)` 改走**加法混合**（SRCALPHA/ONE），对齐原引擎 |

**我们的做法：不整体同步上游，只把这两段"插"进 fork 的基线。**

理由：上游 09-24 → 10-12 隔了 **30 个提交**，包含 VFS / 存档 / 音频 / 视频四大模块的
跨平台统一重构（143 个文件有差异），整体同步要重做全部 11 个补丁的锚点，风险远大于收益。

**移植范围（只取 Android/wgpu 路径）**：

| 文件 | 改动 | 说明 |
|---|---|---|
| `rendering/mask_dissolve.rs` | **新增 60 行** | 照抄上游（+1 个 `YHFVP-MASKDISSOLVE-1.0` CI 标记） |
| `rendering/mod.rs` | +2 | 注册模块 |
| `rendering/gpu_prim.rs` | +62 | `DrawTextureKey::Mask`、`mask_frame`/`mask_texture` 字段、`texture_bind_group` 分支、`build()` 插入点、`additive` 字段 + 5 处调用点 |
| `rfvp_render/pipelines/sprite.rs` | +106 −32 | additive 混合管线 + `draw_blended()` |
| `motion_manager/mod.rs` | +7 | `set_dissolve_mask_graph` 递增 `generation` |

**不移植的部分**（Android 碰不到）：`platform/ps1/*`、`host_api/*` 的 portable 渲染器、
`texture.rs` / `graph_buff.rs` 里的 `#[cfg(feature = "psx")]` 分支、`soft_render/`、
`sprite_wasm.rs`。

**为什么能这么省事**：所需 API 在旧版基线里**全都有**（逐个核对过）——
`GraphBuff::export_alpha_mask()`（签名逐字节相同）、`texture_path`、`generation`、
`MotionManager::get_dissolve_*()`、`GpuTexture::new_rgba8_unorm` / `update_rgba8`
（`rfvp_render/texture.rs` 新旧连行号都一样）、`DrawPrim::get_blend()`。
所以是"纯插入"，不动任何既有结构，也**不碰我们其它 9 个补丁**。

**验证（本轮已完成）**：

1. 两个脚本**顺序无关**（A→B / B→A 结果功能等价），且都**幂等**；
2. 4 个被改文件用 `rustc 1.75 --edition 2021` 逐个过语法检查 → **0 语法错误**；
3. `rebuild()` 确认是 `build()` 的薄封装 → mask 插入点**一定会被执行**；
4. mask 在 `root0_draw_end` 之前入队 → 会被 `draw_virtual_root0` 绘制、排在 HUD 之前 ✅

**真机实测（2026-10-13）：暗带消失 ✅ 修复确认。**

**同时观察到的副作用**：场景转场变得可见了 —— 从旧版的「硬切」变成「有过渡但偏生硬」。
这不是回归，是 mask dissolve 首次真正生效的必然结果（旧版压根没画）。
→ **后续已在 3.12 进一步修正**（从"盖黑"改为"擦掉起始帧"）。

**注意**：我们 fork 的基线仍是上游 `81a8b685`，所以 `apply_wmv_decoder_patch.py`（补丁 6）
**继续保留** —— 上游虽然自己修了 WMA 负数下标，但我们没同步。

---

### 3.10 中文化文本乱码（SJIS 替身字）—— 新增还原层（双来源）

> **两个作品，同一个机制**：把简体字塞进「有 JIS 码位的汉字」码位里，
> 解码后再做一次 Unicode→Unicode 还原。差别的只是**表存在哪个文件**：
>
> | 作品 | 表来源 | 表格式 | PC 端怎么还原 |
> |---|---|---|---|
> | 《樱花，萌放。》官中版 | `Sakura.dll` | 连续 UTF-16LE `(key,value)` 对，1,206 对 | 引擎内建（`babel::sjis_cp932_to_unicode_engine` / `jis_map.bin`） |
> | 《HappyMarguerite》漢化版 | `uif_config.json` | JSON `"type":"replace_chars"` 的 `source_chars`/`target_chars`，1,004 对 | **UniversalInjectorFramework** 外部注入，在 `DrawTextA` 上替换 |
>
> 本模块**两种都认**（见 3.10.1）。

**现象**：官中版对话里夹带冷僻汉字（用户截图：`寅喊着什弗。`）：

```
实际显示：  平日寡言少苫的寅，用我从未听到慓的音量，大声地喊着什弗。
应该是：    平日寡言少语的他，用我从未听到过的音量，大声地喊着什么。
```

**HappyMarguerite 漢化版**（第二例，2026-10-15）：

```
实际显示：  我己識戰経記个麼景无数次了。          ← 截图
应该是：    我己经见过这个场景无数次了。
```

**用户已试过但无效**：改字体（无效果，字体没问题）、改文本编码（GBK 出 `彫铎` 假字、
UTF-8 有 57,507/64,779 条直接解码失败）——**都解不了**。

**根因：用「SJIS 替身字」承载简体中文**

简体字绝大多数在 **JIS X 0208 里没有码位**（`么/这/为/着/过/语/说/话/车/时` 全部
`encode('cp932')` 失败）。所以中文化的做法是：把每个简体字映射到一个**有 JIS 码位的
汉字**（繁体字或日文汉字），用那个码位写进脚本；PC 端在**解码之后**再做一次
Unicode→Unicode 还原。

共同的**定义性特征**（这正是能用同一套判据的原因）：

| 特征 | 樱花官中 | HappyMarguerite |
|---|---|---|
| 对数 | 1,206 | 1,004 |
| key 有 JIS 码位 | 1,206 / 1,206 | **1,004 / 1,004** |
| value 有 JIS 码位 | 0 / 1,206 | **0 / 1,004** |
| key / value 各自两两不同 | ✅ 双射 | ✅ 双射 |
| key ∩ value | ∅ | ∅ |

> `value` **一个都没有**码位是定义性的：需要替身，正是因为真字写不进 Shift-JIS。

**证据链（案例 1 · 樱花萌放 官中版）**：

| 证据 | 内容 |
|---|---|
| `Sakura.hcb` | 4,372,452 字节（日文版是 5,002,852 → **是另一份脚本**，官中是直接写中文进去的） |
| 反汇编脚本 | 按 opcode 表提取 **64,779** 条 `push_string`；Shift-JIS 解码失败 1,729 条（**cp932 全部正常**） |
| 高频字后继统计 | 「什」(8F59) 后继 top1 = `95A4`「弗」×3237 → 只可能是「什么」 |
| `Sakura.dll` 符号 | `babel::sjis_cp932_to_unicode_engine` / `SJIS tunnel limit exceeded` / `Invalid jis_map.bin format` |
| **表本体** | `Sakura.dll` 偏移 `0x47960`（`.rdata` 段），**1,206 对 UTF-16LE** |
| 表内容 | `徼→这 · 戍→你 · 禳→— · 諍→说 · 弗→么 · 來→们 · 餤→为 · 肛→样 · 寅→她` |
| 替身字覆盖 | 223,054 / 1,310,705 字符 = **17.02%**（平均每 6 字 1 个） |
| 表 key 区段 | **全部**在 JIS X 0208 标准区（cp932 与 shift_jis 解码结果完全一致 → 我们引擎用的 `encoding_rs::SHIFT_JIS` 能正确解出 key） |

**证据链（案例 2 · HappyMarguerite 漢化版，2026-10-15 新增）**：

| 证据 | 内容 |
|---|---|
| 补丁来源 | jyxjyx1234 的 SakuraLLM 机翻补丁（GitHub `jyxjyx1234/HappyMarguerite_CHS`） |
| 汉化脚本 | `Marguerite.bch` 3,193,758 字节 —— **游戏实际加载的就是它**（`find_script` 优先 `*.bch`） |
| 日文原版 | `patchdata/Marguerite.hcb` 3,570,209 字节，mtime **2008-02-13**（补丁的原始备份） |
| **表本体** | `uif_config.json` → `text_processor.rules[0]`（`"type": "replace_chars"`），两条平行字符串各 **1,004** 字 |
| 表内容 | `這→变 · 説→为 · 記→这 · 識→经 · 戰→见 · 場→她 · 軽→啊 · 読→—` |
| 截图命中 | `識戰経記` 在 `Marguerite.bch` 偏移 **`0x6BAD6`** 逐字节命中 |
| 替身字覆盖 | 140,949 / 699,454 字符 = **20.15%** |
| 引擎怎么还原 | PC 端靠 **UniversalInjectorFramework**（`uif_config.json` 的 `text_processor` 规则）在 `DrawTextA` 上替换；`README.md` 原文：「对于 **sjis 编码不支持的汉字**采用了新的方法进行处理」 |

> ⚠️ `uif_config.json` **是补丁本体的一部分**，不是"旧汉化版残留"：它的 mtime 与
> `Marguerite.bch` / `README.md` 完全一致（2024-04-27 09:18）。
> 同目录的 `.CTFontHook/` 属于**另一份已删除的汉化版**残留，代码完全不读它。

**修复**：新增模块 `crates/rfvp/src/jis_tunnel.rs`，在**四处**注入（幂等）：

| # | 位置 | 方向 | 函数 |
|---|---|---|---|
| 1 | `text_manager.rs::set_text_content()` | 对话文字 → 屏幕 | `restore()` |
| 2 | `history.rs::HistorySet(Name)` / `HistorySet(Content)` | 回首过去 → 屏幕 | `restore()` |
| 3 | `save_manager.rs::decode_bytes()` | 存档 → 屏幕 | `restore()` |
| 4 | `save_manager.rs::enc()` | 屏幕 → 存档 | `to_surrogate()` |

- **1 必须挂在「文字」上，不能挂在「解析」上** —— 见 3.10.1 末尾那条
  「资源名不会被误伤」的实测：挂在 `decode_string_bytes()` 会把
  HappyMarguerite 的立绘文件名（日文汉字）也改掉，导致贴图整片消失。
  `set_text_content` 全 workspace 只有 2 个调用点，都在 `text_print` 里。
- **2 是「脚本另存了一份文字」造成的独立出口**：屏幕上的那行（1）还原了，
  backlog 里那份不会自动跟着变。两个游戏都用 `HistorySet`（各 1 处）。
  这两个分支只拿得到对话文字/角色名，**拿不到资源名**。
- 3/4 是**存档**的 3 个显示字段（`title` / `scene_title` / `script_content`）
  的唯一读/写出口 —— `load_from_file` 与 `load_from_mem` 里 6 个调用点全走
  `decode_bytes()`；全 workspace 只有 `enc()` 一处把文本编码回 Shift-JIS。
  这两个函数**只处理显示文字，不处理资源名**，所以挂在这里是安全的。

> **通用判据（拔链时问自己）**：这个出口上还会流过什么**非文本数据**？
> （资源名、文件名、syscall 名…）只要会流过，就不能挂。

**脚本文件与存档文件都一个字节都不改**：盘上存的仍是替身字，格式与 PC 版完全一致，
存档还能拿到 PC 版上用。

### 3.10.1 判据（自证 + 白名单双保险）

**来源白名单**——只读游戏目录里**两个固定的文件名**，按顺序试：

| # | 文件 | 格式 | 对应作品 |
|---|---|---|---|
| 1 | `Sakura.dll` | 连续 UTF-16LE `(key, value)` 数组 | 樱花萌放 官中版 |
| 2 | `uif_config.json` | JSON `"type":"replace_chars"`，`source_chars` / `target_chars` 两条平行字符串 | UIF 系汉化 / 机翻补丁（HappyMarguerite_CHS） |

不做全目录 dll/exe 扫描：FVP 生态很小，现实中出现的替身字方案就这两种；
显式命名 + 自证的文件**不可能**被无关二进制误触发。要加第三种来源，
在 `load_table` 的数组里加一行名字即可。

**形状校验**（各来源各自的严格条件）：

- `Sakura.dll`：找**最长的一段连续 `(u16>=0x2000, u16>=0x2000)`**（≥64 对），
  再**从头逐条读**，遇到第一条不合法（key 非 CJK 汉字 / value < U+2000）就**停**。
- `uif_config.json`：`type` 必须是 **`replace_chars`**（`replace_str` /
  `replace_raw_bytes` 不带字符表，按字符表解析会弄坏文本）；三个键必须按
  `type` → `source_chars` → `target_chars` 的顺序出现（**顺序检查先于取值检查**，
  所以「`source_chars` 写在 `type` 前面」会被拒绝而不是误解析）；两条字符串
  **等长**且 **≥256 对**。解析器遇到任何反斜杠转义直接判「不是表」
  （实测真实文件里反斜杠数 = 0、三个键各出现 1 次，所以这不是猜的）。

**自证机制**：用「表里 key 是否出现在**本游戏脚本**用到的字符集里」打分。
**命中 ≥ max(500, 表长×9/10)** 才启用，否则**静默不启用**（= 上游行为）。

阈值为什么是 90%（**数据用真 Rust 模块跑出来的**，`FVP_BASE_PATH` +
`find_script` + `extract_table` + `script_charset` 全套真实代码）：

| 游戏 | 脚本（实际加载的） | 表来源 | 命中率 | 判定 |
|---|---|---|---|---|
| 樱花萌放 官中 | `Sakura.hcb` | 它自己的 `Sakura.dll` | **1,206 / 1,206 = 100%** | ✅ 启用 |
| HappyMarguerite 漢化 | `Marguerite.bch` | 它自己的 `uif_config.json` | **1,002 / 1,004 = 99.8%** | ✅ 启用 |
| 星空EH / 星空Wish | `Hoshimemo_HD.hcb` | 上面那张 JSON 表 | 731 / 1,004 = 72.8% | ❌ 拒绝 |
| HappyMarguerite 日文原版 | `patchdata/Marguerite.hcb` | 上面那张 JSON 表 | 711 / 1,004 = 70.8% | ❌ 拒绝 |
| 樱花萌放 日文版 | `Sakura.hcb` | 上面那张 JSON 表 | 520 / 1,004 = 51.8% | ❌ 拒绝 |

- 阈值 = `max(500, 表长 × 9/10)`；1,004 对的表要 **903** 个 key 命中才算过。
- 最近的一次拒绝是 **731（72.8%）**，离阈值还差 172 个 key（17 个百分点）。
- **两个作品永不互相误触发**：樱花脚本对 Marguerite 表只有 51.8%。
- **同目录并排的 `Marguerite.bch` / `patchdata/Marguerite.hcb` 是绝佳对照**：
  同一张 JSON 表，对前者 99.8% 启用、对后者 70.8% 拒绝 —— 这直接证明了
  「必须按引擎**实际加载**的脚本判定」。

> 刻意**没有**用「key 全部有 JIS 码位 / value 全部没有」这类编码属性判据：
> 它依赖具体编码语义（strict `shift_jis` 下 HappyMarguerite 的 value 是 0/1004
> 无码位，但 `cp932` 下有 12 个有码位），写死会变成脆弱判据。命中率自证足够。

**资源名不会被误伤（实测）—— ⚠️ 这条结论只在「注入点选对」时才成立**

表里所有 key 都是**汉字**，而**樱花官中版**的资源名是纯 ASCII（实测 `Sakura.hcb` 里
6,082 个标识符，含非 ASCII 的 **0 个**），所以在樱花身上「还原改不到资源名」
**成立**。当时据此写下「资源名风险 = 0」。

**但这个结论是条件性的，不是普适的。** HappyMarguerite 的资源名是**日文汉字**：

```
CHR_爺_基_道着竹刀L   CHR_撫子   CHR_悠綺   CHR_響   CHR_稲穂_貨２_セーラー服L
```

共 **7 个**含表 key（`爺 U+723A→练`、`撫 U+64AB→垫`、`綺 U+7DBA→凿`、
`響 U+97FF→晚`、`貨 U+8CA8→疯`…）。第一版把 `restore()` 挂在
`script/parser.rs` 的 `decode_string_bytes()`（所有脚本字符串的出口）上，
于是这些资源名被改掉，`graph_load` 全部 `file not found`
（logcat 里能看到引擎打印的是**改后**的名字 `CHR_练_基_道着竹刀L`，
而磁盘上的文件叫 `CHR_爺_基_道着竹刀L`）→ **立绘与贴图整片消失**。

修法：把还原挪到**「文字 → 屏幕」这一步**（`text_manager.rs::set_text_content`
+ `history.rs` 的两个 backlog 入口），而不是「字符串解码」这一步。
这样资源名/文件名**物理上不经过它** —— 这也正是 PC 端 UIF 的做法
（在 `DrawTextA` 这个**绘制** API 上替换，碰不到文件名）。

| 阶段 | 流过资源名？ | 能挂还原吗 |
|---|---|---|
| `script/parser.rs::decode_string_bytes()` | ✅ 会 | ❌ **不要挂这里** |
| `text_manager.rs::set_text_content()` | ❌ 不会 | ✅ 挂这里 |
| `history.rs::HistorySet(Name/Content)` | ❌ 不会 | ✅ 挂这里 |

### 樱花官中版为什么不受这次改动影响（运行时证据）

推理链是闭的，不需要相信任何"启发式"：

```
旧注入点会对【所有】含载体字的字符串无差别 restore
   ↓
若樱花有含载体字的资源名，旧版必然产生 `file not found in pack`
   ↓
★ 实测（用户 2026-10-14 那份 logcat，29,686 行）：
   樱花 `file not found in pack` = 0 次
   （E 级 7,009 条全是上游既有噪音：motion/graph_load invalid id 等）
   ↓
⇒ 樱花没有含载体字的资源名
   ↓
⇒ 「资源名还原不还原」对樱花没有任何影响
   ↓
⇒ 旧注入点 与 新注入点 对樱花【完全等价】  ∎
```

对照组（证明这个推理有分辨力）：

| 作品 | 旧注入点下 `file not found in pack` |
|---|---|
| 樱花官中 | **0 次** |
| HappyMarguerite | **3 条**（`CHR_练_基_道着竹刀L` / `CHR_练_基_道着木刀L` / `CHR_稲穂_货２_セーラー服L`）|

外加一条**结构性**保证：樱花脚本里 155,946 条「纯 ASCII 段」，
含载体字在结构上不可能（ASCII 段只有 ASCII 码位，表 key 全是汉字）。

**端到端还原效果**（樱花官中 64,779 条跑一遍，49,649 条被还原 = 76.6%）：

```
徼肛的颯苫悄然落下。   →  这样的话语悄然落下。
母唳 / 索瑛 / 花崟      →  母亲 / 索尔 / 花飞
什弗 / 寅 / 苫          →  什么 / 她 / 语
```

HappyMarguerite 真实脚本字节（`Marguerite.bch` @`0x6BAD0`，真 Rust 解码）：

```
RAW  我已識戰経記个麼景无数次了。
FIX  我已经见过这个场景无数次了。      ✅
往返  restore → to_surrogate 回到原字节 ✅
```

> ⚠️ **教训 1（CI 曾失败）**：第一版挂在 `E0716`（借用检查）——
> `encoding_rs::SHIFT_JIS.decode(&[lead, trail])` 的临时数组在语句结束即释放，
> 而返回的 `Cow` 还借着它。**`rustc --emit=metadata` 单文件检查抓不到这类错**：
> 缺依赖时报的是 `E0433`，编译器在名字解析阶段就停了，走不到借用检查。
> 正确做法是搭一个只依赖 `encoding_rs` + `log` 的最小 crate 做 `cargo build`
> （已写进补丁脚本的「验证方法」章节）。
>
> ⚠️ **教训 2（方法论）**：这个功能**改了三轮才全绿**，每一轮都是真机实测暴露的
> ——① 表提取漏条目、② 存档写入吐 `&#NNNNN;`、③ 存档读取忘了还原。
> **根因是"同一条数据流有多个出口，我只堵了看得见的那一个"。**
> 现在把三个出口列成表（见 §3.10 的注入点表），以后改这类东西先数出口。
>
> ⚠️ **教训 3（加第二来源时）**：**静态看代码能过、真编译 + 真跑才暴露的两个 bug** ——
> ① `source != target.len()`（应为 `source.len() != target.len()`）；
> ② `std::str::from_utf8(&data[i..i+4])` 取一个 UTF-8 字符，4 字节窗口**几乎总是
> 切在下一个字符中间**，于是整个切片不是合法 UTF-8，解析器对**所有真实文件**
> 都返回 `None`（表现为"表读不出来"，光看代码完全看不出）。
> 结论：**判据类代码必须拿真实文件跑一遍**，不能只做语法/编译检查。

### 3.10.2 迭代中修掉的五个坑（真机实测逐个暴露）

**坑 5：回首过去（backlog）漏还原 —— 「脚本另存了一份文字」**

把注入点从解析路径挪到文字路径后，屏幕上的对话正常了，但**回首过去**里还是载体字。
根因：脚本用 `HistorySet` **另存了一份**这行文字（`history_manager`），
它不走 `text_print`，所以 1 号注入点覆盖不到。

- 两个游戏都用 `HistorySet`（在脚本里各出现 **1 次**）→ **两个游戏都有这个漏点**。
- 旧注入点（解析路径）恰好掩盖了它（那时解析阶段就还原了），所以这是**挪注入点时
  才显现出来**的漏点，不是新引入的 bug。
- 修法：`history.rs` 的 `HistorySet(Name)` / `HistorySet(Content)` 两个分支各加一次
  `restore()`。它们只拿得到对话文字/角色名，**拿不到资源名**。

> **教训**：一条数据被**存了两份**（屏幕上 + backlog 里）时，两个出口都要堵。
> 找出口的方法：搜「这个文字还会被写进哪个容器」。这里就是
> `history_manager.set_content/set_name`。

**坑 4（最隐蔽）：注入点选在「数据」而不是「用途」上 —— 贴图整片消失**

第一版把 `restore()` 挂在 `script/parser.rs::decode_string_bytes()`（**所有**脚本
字符串的出口，包括资源名）。HappyMarguerite 的立绘文件名是**日文汉字**：

```
CHR_練_基_道着竹刀L  CHR_撫子  CHR_爺  CHR_悠綺  CHR_響  CHR_稲穂_貨２_セーラー服L
```

共 **7 个**含表 key，被改成 `CHR_觑_…` / `CHR_垫子` / `CHR_练` / `CHR_悠凿` / `CHR_晚`
→ `graph_load` 全部 `file not found`（logcat：`syscall error: file not found in pack:
CHR_練_基_道着竹刀L`）→ **立绘与贴图整片消失，但文字正常**（文字本来就该被改）。

> 用户症状是「汉化有了，但贴图丢了」—— 这两件事是**同一个错误的两面**。

樱花官中版完全没暴露：它的资源名是**纯 ASCII**（`Sakura.hcb` 里 6,082 个标识符，
含非 ASCII 的 **0 个**）。所以 §3.10.1 里那句「表 key 全是汉字，物理上碰不到资源名」
**只在纯 ASCII 资源名时成立** —— 这是个**条件性结论**，不能当普适保证用。

修法：把注入点从「字符串解码」挪到「**文字 → 屏幕**」——
`text_manager.rs::set_text_content()`（只有 2 个调用点，都在 `text_print` 里）。
对齐 PC 端 UIF：它在 `DrawTextA` 这个**绘制** API 上替换，物理上碰不到文件名。

> 口诀：**替身字还原要挂在「文字」上，不要挂在「数据」上。**


**坑 1：表提取漏掉了「禳 → —」（9,211 处显示成两个粘一起的字）**

第一版的过滤是「跳过非法条目（`continue`）+ value 必须落在 `0x3000..=0x9FFF`」。
而表里第 3 高频条目 `禳 → —` 的 value 是 **U+2014 破折号**，低于 CJK 区 ——
被静默丢弃，于是全脚本 **9,211 处 `禳禳`** 原样显示（用户截图里"粘在一起的两个字"）。

改成「run 内从头逐条读，遇到第一条不合法就 **break**」+ 只过滤 key，
实测提取结果与手工 1,206 条**完全一致**（含 `禳→—`）。

**坑 2：存档标题变成 `&#NNNNN;`（HTML 数字字符引用）**

表里 **全部 1,206 个真字都没有 JIS X 0208 码位**（这正是官方要用替身字的原因）。
`encoding_rs::SHIFT_JIS.encode()` 对无法映射的字符会按 WHATWG 规范输出
**数字字符引用**：

```
encode("纸") -> "&#32440;"     had_unmappable = true
encode("这") -> "&#36825;"
```

引擎读回时**不解析**这个实体（`decode("&#32440;") == "&#32440;"`），
所以存档界面显示字面量。实测该存档字节：

```
&#32440; 8fe3 95fa 9285 9770(?) 8dca 9046 939c &#32440; 95ef 9195 8b4e ...
          放着          用彩  色   糖             包装  起来  的 ...
```

对照脚本原文 `@0xbae7a: ...939c e987 95ef...`（`939c`=糖, `e987`=驥 即「纸」），
证明**文本带着实体被写进了存档**。

修法：全 workspace 只有 `save_manager.rs` 这一处把文本编码回 Shift-JIS，在它前面
插入 `to_surrogate()`。反向映射安全 —— 表是**双射**（1,206 key 两两不同，
1,206 value 也两两不同，重复 0 条）。往返已实测：

```
"上放着用彩色糖纸包装起来的巧克力。"
  -> to_surrogate -> "上放着用彩色糖驥包装起来的巧克力。"
  -> encode       -> 8fe3...939c e987 95ef...8142   ← 与脚本原始字节逐字节一致
  -> decode+restore -> 原句  ✅ 一致
```

并且真实 Rust 运行验证：`禳禳` → `——`、纯 ASCII 资源名不受影响、
存档往返 `&#` 出现次数 = 0。

**坑 3：存档标题读出来还是替身字（「寅却稾稾地…」，应为「她却轻轻地…」）**

前两版只修了 `parser.rs`（脚本出口）与 `save_manager.rs` 的 `enc()`（写入），
漏了存档的**读取**出口 —— `save_manager.rs:105` 的 `decode_bytes()`：

```rust
fn decode_bytes(nls: Nls, bytes: &[u8]) -> String {
    Nls::ShiftJIS => encoding_rs::SHIFT_JIS.decode(bytes).0.to_string(),   // 没还原
```

读出来的标题/场景名/剧本预览又带回了替身字。`load_from_file` 与 `load_from_mem`
里共有 6 个调用点，全走这个函数，所以补它一处即可。

---

### 3.10.3 真机验证

**案例 1 ·《樱花，萌放。》官中版（2026-10-14，全部通过）**

**显示方向**：

| 位置 | 修复前 | 修复后 |
|---|---|---|
| 对话文本 | `寅喊着什弗。` | `她喊着什么。` |
| 破折号（9,211 处） | `禳禳`（两字粘一起） | `——` |
| 存档标题 | `寅却稾稾地握住了我的手。` | `她却轻轻地握住了我的手。` |

**存档方向**（真实 Rust 往返，脚本原文 `@0xb9a63` 的真实字节）：

```
磁盘替身字  "寅却稾稾地握住了我的手。"
旧行为     "寅却稾稾地握住了我的手。"     ❌
新行为     "她却轻轻地握住了我的手。"     ✅
再保存+读回，写出字节含 "&#" = false，往返一致 = true
→ 用户实测：旧存档（带 &#NNNNN;）读出来也正常了 ✅
```

**日志体检**（29,686 行，2 分钟）**：0 panic / 0 FATAL / 0 SIGSEGV / 无进程重启。
7,009 条 `E rfvp` **全部是既有上游噪音**，与本次改动无关：

| 消息 | 次数 | 性质 |
|---|---|---|
| `motion: Invalid id` | 2,236 | 脚本传 Nil，引擎宽容处理（§6 已记录） |
| `graph_load: invalid id : Nil` | 2,126 | 同上 |
| `motion: Invalid alpha motion id` | 2,121 | 同上 |
| `motion: Invalid duration` | 242 | 同上 |
| `history_set: unexpected value ... Nil` | 284 | 同上 |

> 注：`jis-tunnel` 的启动日志（`YukiHub jis-tunnel active: <来源名> (N entries, M hits)`）
> 只在**首次加载表**时打一次；这次日志从游戏启动后开始录，所以没抓到。功能不受影响。

> 附带观察：`YHPROBE PrimHit` 占 19,343 行（**65%**），是触发安卓
> `LOGS OVER PROC QUOTA ... DROPPED`（97 次）的主因。等点击功能稳定、按 §6 撤掉探针后
> 日志会清爽很多。

**案例 2 ·《HappyMarguerite》漢化版（2026-10-15，真机确认 ✅）**

**第一次真机**（注入点在解析路径）→ 文字正常 ✅，但 **立绘与贴图整片消失** ❌
（见坑 4）。

**第二次真机**（注入点挪到文字路径 + 补 backlog，CI 修掉 `Cow`/`String` 后）：

| 检查 | 结果 |
|---|---|
| 真机日志 | `YukiHub jis-tunnel active: uif_config.json (1004 entries, 1002 hits)` ✅ |
| **贴图/立绘** | **恢复** ✅ —— `graph.bin: CHR_…` 报错 **4 条 → 0 条** |
| 对话文字 | 正常 ✅ |
| 回首过去 | 正常 ✅ |
| 资源名 | 7 个原本会被误改的（`CHR_爺_…` / `CHR_撫子` / `CHR_悠綺` / `CHR_響` …）→ **全部原样** ✅ |
| 樱花官中 | **同一份日志里**也跑了：`Sakura.dll (1206 entries, 1206 hits)`，一切照旧 ✅ |

**同一会话内两个游戏先后运行**（logcat 行号即启动顺序）：

```
01:03:25  jis-tunnel active: uif_config.json (1004 entries, 1002 hits)   ← HappyMarguerite
01:06:07  jis-tunnel active: Sakura.dll      (1206 entries, 1206 hits)   ← 樱花萌放
```

**残留的 4 类 `not found` 全部与补丁无关**（逐条证明）：

| 报错 | 次数 | restore 后 | 结论 |
|---|---|---|---|
| `graph.bin: SYS_WINDOW_DW` | 3 | `SYS_WINDOW_DW`（不变） | 脚本自己引用了包里没有的条目 |
| `graph.bin: SYS_WINDOW_UP` | 3 | `SYS_WINDOW_UP`（不变） | 同上 |
| `se_sys.bin: 013` | 1 | `013`（不变） | 同上 |
| `voice2.bin: 000004` | 1 | `000004`（不变） | 同上 |

且这些名字在 `graph.bin` 里**根本不存在**（0 次命中）—— 纯原版行为。
对比：修复前的 `graph.bin: CHR_…` 是 **4 条**，现在 **0 条**。

**日志体检**（19,865 行 / 5.5 分钟）：0 panic / 0 FATAL / 0 SIGSEGV / 0 ANR / 0 OOM。
- E 级全是既有噪音（`Invalid id` 3,600、`graph_load invalid id: Nil` 2,272、
  `Invalid alpha motion id` 1,173、`history_set … Nil` 286 等）。
- `YHPROBE` **0 条**（探针已按 §4 撤除）✅
- 日志配额丢弃 `LOGS OVER PROC QUOTA` **14 次**（撤探针前是 **97 次**，降 85%）✅

**回归总表（28 项全绿）**：语法 6 项、真编译 4 项（模块本体 + **注入代码 stub**）、
功能 5 项、双顺序+幂等 2 项、注入点落位 11 项。
>
> **要加第三个来源**：在 `load_table` 的文件名数组里加一行
> （`extract_table` 已经按 `TableKind` 分发）。判据本身**不绑定作品/文件名**，
> 只看「表 key 是否出现在本引擎实际加载的脚本里」，所以换个游戏天然生效。

---

### 3.11 `SoundSilentOn` / `AudioSilentOn` 不再真的静音（对齐上游 10.15）

**来源**：上游 `be92e540`（"fix audio"，2026-10-15）。上游把 SE/BGM 的公共槽位逻辑
重构成了 `audio_player/channels.rs`；**我们不跟那个重构**（`se_player` / `bgm_player`
从基线到新版分别差 440 / 489 行），只把语义改对。

**上游原文（直接点了我们这款游戏的名）**：

```rust
/// `AudioSilentOn` / `SoundSilentOn`. It does not mute the slot: scripts call it on a slot
/// while its sound plays (Sakura, on the voice slot at every line), and that sound is heard
/// in the original engine.
pub fn silent_on(&mut self, _slot: i32, _tween: Tween) {}
```

**我们基线（10.09）的实现 —— 真的静音**：

```rust
pub fn silent_on(&mut self, slot: i32, tween: Tween) {
    self.se_muted[slot] = true;
    if let Some(handle) = self.se_slots[slot].as_mut() {
        handle.set_volume(0.0, tween);        // <- 音量拉到 0
    }
}
```

而且 `se_muted` 还**参与此后所有音量计算**（`se_player.rs` 4 处）：

```rust
let actual_volume = if self.se_muted[slot] { 0.0 } else { self.effective_volume_for_slot(slot) };
```

**10.15 的做法**：`channels.rs` 里连 `muted` 字段都**彻底删除**了，`silent_on` 是
**空操作**；`restore` 里也注明 *"`muted` is ignored: saves made while SilentOn muted
a slot would bring it back."* —— 即**静音这件事整体被移除**。

**改动**（`se_player.rs` / `bgm_player.rs` 各三处，合计约 12 行）：

| # | 改动 | 目的 |
|---|---|---|
| 1 | `silent_on` → 空操作（**保留一条 `YHPROBE SilentOn` 探针**） | 对齐原引擎 |
| 2 | 4 处 `if muted { 0.0 } else { X }` → 直接用 `X` | `muted` 彻底退出音量计算 |
| 3 | `apply_snapshot_v1` 恢复存档时 `muted` 一律置 `false` | 旧存档不会把静音带回来 |

> 注：我们保留 `se_muted` / `bgm_muted` 字段（不删），只是**不再使用**它 —— 
> 功能上与 10.15 等价，但改动面更小、更易回滚。

**为什么带探针**：用户实测反馈「10.09 多款游戏语音均正常，只是声音偏糊/有底噪」，
与「`silent_on` 会真静音」的代码分析不完全吻合。探针用来**确认脚本到底有没有调
`SilentOn`、调用频率多少** —— 一次构建同时拿到「修复后的表现」和「调用事实」。
确认后可按 §6 移除。

**与其它补丁的关系**：独占 `audio_player/`，与其余补丁**零文件重叠**。

**验证（本轮）**：
- dry-run：8 个锚点全部命中；幂等复跑全绿；
- 全部补丁按 workflow 顺序**串行全绿**，且**二次复跑全部幂等**；
- `rustc 1.75 --edition 2021` 逐个语法检查 → 0 语法错误；括号平衡 ✅。

**待实测**：真机确认语音表现 + 读 `YHPROBE SilentOn` 日志。

---

### 3.12 转场（dissolve）生硬 —— 从「盖黑」改成「擦掉开始时那一帧」

**来源**：上游 `2a9ff58a`（"fix dissolve"，2026-10-15）。

**问题**（2026-10-13 真机对比发现）：

| 版本 | 场景过渡表现 |
|---|---|
| 旧 so（无 mask dissolve） | **硬切**，完全没有过渡 |
| 10.12 版 so（我们此前移植的 mask dissolve） | **有过渡，但偏生硬**（直接黑一下） |
| PC 原版 | 平滑：有的白闪缓缓显现、有的小黑一下转换 |

**根因不是「曲线不够缓」，而是方向错了 —— 我们在"盖黑"而不是"擦画面"。**

上游 10.15 删掉的 `setsumei/compatibility/DISSOLVE.md` 写明了原引擎的做法：

> wipes **the frame shown when it started** away through the mask,
> **revealing the new scene under it** … drawing black there blacks the
> screen out at every transition

即：正确做法 = **抓取转场开始那一刻的画面 → 按遮罩把它擦掉 → 露出下面的新场景**；
而不是「往画面上盖一层逐渐变黑的东西」。

**改动**（4 个文件）：

| 文件 | 改动 |
|---|---|
| `motion_manager/mod.rs` | 新增 `dissolve_serial: u32`（`start_dissolve` 时 +1，标识快照属于哪一次转场）；mask 类转场初始 alpha 改 **1.0**（一开始整个被上一帧盖住）；`MaskFadeInOut` 曲线由三角波 `1.0-(2t-1).abs()` 改 **`1.0-t`** |
| `rendering/gpu_prim.rs` | 新增 `mask_wants_snapshot()` / `set_mask_snapshot()`；mask 纹理改 **sRGB**（`GpuTexture::new`）—— 现在贴的是真实画面颜色，不再是黑图 |
| `app.rs` | `rebuild` 之前：若需要快照，用 `RenderTarget::encode_readback_rgba8` + `map_to_rgba8` 回读当前渲染目标，交给 `set_mask_snapshot` |
| `rendering/mask_dissolve.rs` | 整体替换为快照版：底图从黑图换成快照帧（遮罩按快照分辨率缩放）；还原原引擎 `MaskFadeIn` 的**硬边**擦除语义（wait=4 对 255 做 alpha 测试，wait=5 才软混合） |

**依赖与顺序**：**必须在 `apply_fvp_mask_dissolve_patch.py` 之后**执行
（本补丁是它的增量，复用它建立的 `DrawTextureKey::Mask`、`mask_frame` /
`mask_texture`、`texture_bind_group` 分支与 `build()` 插入点）。
脚本已加前置校验：前置缺失时**直接 fail**（不静默漏打）。

**关键前提已核实**：`encode_readback_rgba8` / `map_to_rgba8` 在基线里**早就存在**
（存档缩略图在用，`app.rs` 有现成调用），无需新增任何 API。

**验证（本轮）**：
- dry-run：4 个锚点全部命中；幂等复跑全绿；
- **反序测试**：先跑本补丁会正确 fail（提示先跑前置）⇒ 不会静默漏打；
- 全部 **12 个补丁按 workflow 顺序串行全绿**，且**二次复跑全部幂等**；
- `rustc 1.75 --edition 2021` 逐个语法检查 → 0 语法错误；
- 括号平衡与基线**完全一致**（`gpu_prim.rs` -3/1、`app.rs` -4/0 均为基线原有，非本次引入）。

**待实测**：真机看转场是否变平滑（应能「擦」出下面的新场景，而非黑一下）。

---

### 3.13 老 FVP 按钮点不动（timer 到点不停）—— 移植上游 `a1091393`

**来源**：上游 `a1091393`（"fix(timer): stop a timer once it reaches its resolution"，
2026-10-18，closes **issue #49**）。issue 正文正是我们提的
`upstream_issue_legacy_button_pairs.md`。

**现象**：老 FVP 作品（如 `はっぴい☆マーガレット!`）里，存档/读档按钮**点不动**，
点击落到了「文字推进」上。

**根因**（上游用 IDA 反推 `sub_412430` / `sub_436EC0`）：

| 引擎 | `tick()` 行为 | `TimerGet(id, nil)` |
|---|---|---|
| 原引擎 | elapsed 超过 resolution 时**夹到 resolution 并关闭 timer** | 走 `enabled == false` 分支 → **0** |
| 基线 rfvp | 只做 `saturating_add`，elapsed 一直涨、timer 一直 enabled | 返回**无限增长的值**，永不为 0 |

脚本用 `TimerGet(1) == 0` 判断「按钮空闲可点」，条件永不成立 → 按钮失效。

**修复**（`timer_manager.rs`，4 行）：

```rust
if item.elapsed > item.resolution {
    item.elapsed = item.resolution;
    item.enabled = false;
}
```

**顺带收益**：resolution 未设置（=0）的 timer 第一帧就停，
规避了 `TimerGet` 里 `elapsed / resolution` 的除零。

**与「点击穿透」的关系（重要，别混）**：
- **穿透**是我们自己补丁修的（`apply_text_click_patch.py`），**上游 10.18 并没有修**；
- 本补丁只负责让**按钮的脚本条件**能成立。两者互补，不是同一件事。

**验证（本轮）**：锚点 1 处命中；幂等复跑全绿；`tick()` 与上游逐字一致（14 行）；
括号平衡与基线一致；标记 `YHFVP-TIMERSTOP-1.0` 长度 19 ✅。

**待实测**：`はっぴい☆マーガレット!` 的存档/读档按钮是否可点。

**真机实测（2026-10-19）：按钮正常了 ✅**

**边界复查（补丁安全性）**：
- `timer_set` 会**拒绝** `resolution <= 0`（报 `resolution should be in range 1..100000` 并返回），
  所以 timer 一旦 enabled，`resolution` 必 > 0 —— **不存在 resolution=0 被第一帧误关的风险**；
- 顺带收益：即便将来出现 resolution=0 的路径，补丁也会让 `enabled` 先变 false，
  从而避开 `TimerGet(id, default)` 里 `elapsed / resolution` 的**除零 panic**（已用模拟验证）。

---

### 3.14 遗留 TextWait / TextResume —— 移植上游 `513b3955`（`TextResume` 守卫的根治版）

**来源**：上游 `513b3955`（"fix(vm): cancel stale text completion requests"，2026-10-16）。

**背景**：`ThreadWrapper` 的请求队列是**全局**的。线程销毁后，队列里可能还留着
指向**已消失线程**的请求：

| 遗留请求 | 被消费时的后果 |
|---|---|
| `TextResume(id)` | 给 pc=0 的上下文解除 TEXT 阻塞 → 下帧跳到 0 地址 → unknown opcode → **SIGABRT** |
| `TextWait(id)` | 把已销毁的上下文重新标成 TEXT → 永远挂住 |

**我们已有的防护**：`apply_text_click_patch.py` 在**消费端**加了守卫 ——
只有 `st.contains(CONTEXT_STATUS_TEXT)` 才允许恢复。那是**止血**。

**上游的解法**：在**源头**（线程退出/重启时）清掉该线程的过期请求 —— 这是**根治**。

**修复**（3 个文件）：

| 文件 | 新增/改动 |
|---|---|
| `thread_wrapper.rs` | `cancel_text_requests(id)`：`retain` 掉该 id 的 `TextWait`/`TextResume`；**`id == 0`（主线程）时清空全部** |
| `text_manager.rs` | `cancel_sync_print_waits(thread_id)`：清 `sync_wait_thread`（`thread_id == 0` 时全清） |
| `vm_runner.rs` | 3 处调用：`should_exit` 成立时 / 消费 `Start` 时 / 消费 `Exit` 时 |

**为什么 `id == 0` 要全清**：`ThreadManager::start_main()` 走的是
`thread_start(0, entry_point)` —— **线程 0 就是主线程**。重启主线程等于整局脚本
从头来，队列里任何残留文本请求都不该跨局存活。

**`Exit(None)` 走的是 `id.unwrap_or(tid)`**：`Exit(None)` 语义是「退出所有线程」，
但我们只清 `tid` 那一条，而不是全清 —— 这是**照上游原样**实现的，没有额外扩大清除范围。

**锚点冲突处理（关键）**：`vm_runner.rs` 已被 `apply_text_click_patch.py` 改过
（两处 `TextResume` 守卫）。本补丁锚点**刻意避开那两段**，只落在
`thread_exit` / `Start` / `Exit` 三处，**两者可叠加**（已按 workflow 顺序实测）。

**幂等注意**：标记 `YHFVP-TEXTCANCEL-1.0` 只落在 `thread_wrapper.rs`，
所以**幂等判断按文件各用各的哨兵**（`fn cancel_text_requests` /
`fn cancel_sync_print_waits` / `cancel_text_requests(tid)`）。
用全局 MARKER 判断会让另两个文件永远"未应用"→ 重复插入。

**验证（本轮）**：3 文件锚点全部 1 处命中；幂等第 2 遍全绿；
`retain` 闭包 + `VecDeque` 单独用 `rustc` 编译并跑通；
4 段代码块与上游逐字一致；括号平衡不变；标记长度 20 ✅。

**待实测**：长流程（多分支/多结局）下是否还有 `unknown opcode` / SIGABRT。

---

### 3.15 prim 树遍历 visit 缓冲改栈数组 —— 移植上游 `cbe9f1fa`

**来源**：上游 `cbe9f1fa`（"perf(render): use bounded stack traversal scratch"，
2026-10-16，PR #54）。

**问题**：`GpuPrimRenderer::build()` 每帧遍历两棵树（root 0 + overlay root），
每次 `vec![0u8; 4096]` 分配 4 KiB 堆内存、用完即丢 → **约 120 次/秒**的堆分配/释放。

**修复**：`vec![0u8; 4096]` → `[0u8; 4096]`（栈数组）。上界不变（仍 4096 项），
只是不再走堆。

**锚点注意**：两处 `let mut visit = vec![0u8; 4096];` 是**前缀关系**
（`vec![0u8; 4096];` 本身是 `vec![0u8; 4096]; // do NOT reuse...` 的子串），
只匹配第一行会 `count == 2`。必须带上后续行 `self.collect_tree(` 才能唯一定位。

**验证（本轮）**：2 处命中且各只改 1 次；幂等复跑全绿；
两行结果与上游逐字相同；栈数组 + `&mut [u8]` 单独 `rustc` 编译跑通；
括号平衡不变；标记长度 20 ✅。

**待实测**：纯性能改动，渲染结果应完全不变（暗带/转场均不受影响）。

---

### 3.16 上游 `cc8ac77` 五连修（Tile -1 / ColorSet / Sprt -1 / 三个 syscall）

**来源**：上游 `cc8ac77`（2026-10-19，附 5 篇 ADR，closes issue **#55**）。

这一提交把 **143 个 syscall 全量扫了一遍**，用 IDA 对齐原引擎，修了 5 件事。
我们的探针数据与它的结论**完全吻合**。

#### ① Tile id -1 = 全局「窗口颜色」（ADR-0001）→ `apply_fvp_tile_color_patch.py`

**这是对话框背景不透明的正解。**

脚本用 `PrimSetTile(513, -1, ...)` / `(514, -1, ...)` 创建对话框，
字节码真的推 `-1`（`push_i8 0xFF`）—— **-1 是刻意的，不是无效值**。

| Tile id | 原引擎行为 |
|---|---|
| **-1** | 不查调色板，每帧读**全局 `G[5..8]`** 作为 R/G/B/A（取低字节） |
| 其它 | 调色板 `[base + id*4 + 0xabd74]` |

`G[5..8]` 就是配置界面「窗口颜色」滑块编辑的四个全局（Marguerite 有 1272 个非易失
全局，所以实际是 `G[1277..1280]`）。引擎每帧读，所以拖滑块立刻生效。

修复：`ColorManager::tile_color(tile)` —— `-1` 时 `get_int_var(5..8)`，其它走调色板。
**依赖 `get_int_var` 我们基线里早就有**，无需新增 API。

#### ② `ColorSet` 有符号检查（同 ADR-0001）→ `apply_fvp_colorset_signed_patch.py`

原引擎 `ColorSet`（`0x4132c0`）做的是**有符号** `0 <= id < 256`，`ColorSet(-1, ...)` 什么都不做。
我们写的是 `let id = id as u8;` 再检查 u8 —— **永远成立**，`-1` 被截成 255 写进去。

连锁后果（这才是对话框变白的完整链路）：

```
ColorSet(-1,  174,118,118,170);   // 本想设半透明粉 → 实际写进槽 255
ColorSet(255, 255,255,255,255);   // 紧接着把槽 255 覆盖成不透明白
```

配合 ①（Tile -1 不再读槽 255），两个 bug 同时消失。

#### ③ Sprt 纹理 id -1 = 绘制「上一帧」（ADR-0002）→ `apply_fvp_prev_frame_patch.py`

`PrimSetSprt` 接受 -2 起。我们画了 -2（电影帧），但 **-1 直接跳过**。
Marguerite 的**设置界面**用 `Sprt -1`（prim 291，alpha 200）当背景，
原版是「把刚才那帧铺在下面半透明」。

原引擎 `Present`（`sub_4083B0`）每帧 `EndScene → Present → StretchRect`
把后备缓冲拷进一个屏幕尺寸纹理，所以那纹理永远是**上一次 present 的那帧**。

修复（只做 GPU 路径，见下面「未移植项」）：
- `render_target.rs`：补 `logical_size()` + `encode_copy_to()`（上游新增的两个小方法）
- `gpu_prim.rs`：`DrawTextureKey::PrevFrame`、`prev_frame`/`wants_prev_frame` 字段、
  `capture_prev_frame()`、Sprt `-1` 分支、bind group 分支
- `app.rs`：`build()` 之后、开渲染 pass 之前调用 `capture_prev_frame`

**无 Sprt -1 的帧零开销**（`wants_prev_frame=false` → `prev_frame` 置 `None` 释放）。

#### ④ `PrimSetZ` / `TimerSuspend` 判 `True`（ADR-0004）→ `apply_fvp_syscall_semantics_patch.py`

原引擎 VM 标签：0=Nil、1=True、2=Int、3=Float、4/5=String。
全量扫 143 个 handler，只有三处比标签 1/3：`FloatToInt`(3)、`PrimSetZ`(1)、`TimerSuspend`(1)。

| syscall | 原引擎 | 我们（修前） |
|---|---|---|
| `PrimSetZ` | 标签 1（True）保留 z 并设 attr 0x04 | 写成了 `z.as_float()`，**True 反而清 bit** |
| `TimerSuspend` | 存 `type != 1`，**只有 True 恢复**计时器 | `!on.canbe_true()`，**传 Int 反而恢复** |

> 与 §3.13 的 `apply_fvp_timer_stop_patch.py` **不冲突**：那个改 `tick()`，这个改 `timer_suspend()`。

#### ⑤ `PrimSetRS` / `PrimSetRS2` 缩放钳到 [100, 10000]（ADR-0003）→ 同上

原引擎：Int 缩放**钳制**到 [100, 10000]，非 Int 保持不变。
我们原来是「超出 0..=10000 就重置成 1000，0..=99 原样接受」：
- `PrimSetRS2(id, nil, 0, 0)` 本该画成 10%，我们缩成 0（看不见）
- 超范围缩放会突然跳回 100%

#### 未移植项（有意）

| 项 | 为什么 |
|---|---|
| `prim_commands.rs` / `no_std_core.rs`（host-command 的 Sprt -1） | Android 构建只走 `GpuPrimRenderer`；这两个文件我们与上游 10.18 已有差异且不参与构建 |
| `soft_render/renderer.rs`（软件渲染器） | 同上 |
| `app.rs` 的 `mem_report()` | 上游新增的 `RFVP_MEM_REPORT` 内存统计，纯诊断，与我们无关 |
| `graph.rs` 的 `#[cfg(feature = "psx")]` 块 | PlayStation 专用 |

#### 验证

- 19 个补丁串行全绿，二次复跑全幂等；
- `tile_color` / `capture_prev_frame` / `encode_copy_to` 与上游 10.19 **逐字一致**
  （忽略空行/注释后逐行相等）；
- 5 个新静态标记长度逐个核对 + 抽出来 rustc 编译通过
  （过程中修掉两处自己算错：`TILECOLOR` 19→实际 19、`COLORSET-SIGNED` 24→25）；
- 括号平衡与基线一致；
- 锚点踩的坑都记在脚本注释里：
  `mask_dissolve` 会先插 `mask_frame`、`stack_visit` 会改 `build()` 文档注释、
  `prim_set_rs` 里空行、`color.rs` 的 `}` 与 `Ok()` 之间空行。

**待实测**：对话框背景是否变半透明粉、设置里拖窗口颜色是否实时生效、设置界面背景是否透出画面。

---

### 3.17 音频两个单位 bug：线性音量当 dB、pan 没映射（爆音根因）

**来源**：对照上游 10.19 的音频重写
（`rfvp_audio/kira.rs` 的 `decibels()`、`audio_player/channels.rs` 的 `params()`）。

用户在 `はっぴぃ☆マーガレット!` 里听到噪音，且**只有这一个游戏明显**。
排查后确认是两个**单位换算缺失**，不是游戏特性。

#### ① 线性音量被当成 dB 传给 kira（**爆音根因**）

kira 的 `.volume()` 收的是 **Decibels**（从 `kira-0.10.8` 源码确认）：

```rust
/// Setting the volume of a sound to -60dB or lower makes it silent.
pub struct Decibels(pub f32);

impl From<f32> for Decibels {
    fn from(value: f32) -> Self { Self(value) }   // 裸转换，不换算
}

pub fn as_amplitude(self) -> f32 {
    if self <= Self::SILENCE { return 0.0; }
    10.0f32.powf(self.0 / 20.0)                    // dB -> 振幅
}
```

**我们一直把线性值（0..1）直接传进去。**

| 我们传 | kira 实际增益 | 应为 | 差异 |
|---|---|---|---|
| `0.0` | **1.0000** | 0.0000 | **想静音反而满音量** |
| `0.1` | 1.0116 | 0.1000 | 10 倍 |
| `0.5` | 1.0593 | 0.5000 | 2.1 倍 |
| `0.65` | 1.0777 | 0.6500 | 1.66 倍 |
| `0.8` | 1.0965 | 0.8000 | 1.37 倍 |
| `1.0` | **1.1220** | 1.0000 | 1.12 倍 |

三个后果：
1. **音量滑条 0%..100% 只让响度变化 12%**，几乎失效；
2. `volume = 0.0`（游戏想静音）→ **满音量播放**；
3. 所有声音恒定 > 1.0 倍，**多路 SE 叠加必硬削波**。

**为什么只有 `はっぴぃ☆マーガレット!` 明显**：
日志里 19:54:14~16 的 **2 秒内连播 8 次 SE**，多槽位并发。
每路 1.078~1.122 倍，两路叠加就超过 1.0 → 削波。
其它作品 SE 稀疏、音量滑条默认 100%，单个 1.122 倍听不太出来。

#### ② pan 没做 0..1 → -1..1 映射（**全部偏右**）

kira 的 `Panning`（同样源码确认）：

```rust
/// Valid panning values range from `-1.0` to `1.0`.
/// A value of `0.0` will cause a sound to be played at an equal volume from both speakers.
pub struct Panning(pub f32);
```

脚本的 pan 是 0..1（上游注释：*Scripts keep pan from 0 to 1*），上游做 `pan * 2.0 - 1.0`。
我们直接 `Panning::from(pan)`，默认值 **0.5 就变成「偏右 50%」**。

| 脚本 pan | 我们传 | 实际效果 | 正确 |
|---|---|---|---|
| 0.00（左） | 0.00 | 居中 | -1.00 左 |
| 0.50（中） | 0.50 | **偏右 50%** | 0.00 居中 |
| 1.00（右） | 1.00 | 右满 | 1.00 右 |

#### 修复

两个文件各加一对文件内辅助函数：

```rust
fn decibels(volume: f32) -> kira::Decibels {
    if volume <= 0.0 { kira::Decibels::SILENCE }
    else { kira::Decibels(20.0 * volume.log10()) }
}

fn panning(pan: f64) -> kira::Panning {
    kira::Panning::from((pan as f32) * 2.0 - 1.0)
}
```

- 9 处 `.volume(x)` / `handle.set_volume(x, …)` → 包 `decibels(...)`
- 6 处 pan → `panning(pan)`

#### 验证

| 项 | 结果 |
|---|---|
| 20 个补丁串行 + 二次幂等 | ✅ 全绿 |
| 裸 `.volume` / `set_volume` 残留 | **0 处** ✅ |
| 标记 `YHFVP-AUDIO-UNITS-1.0`（21 字节） | ✅ 声明/实际一致，rustc 编译通过 |
| 括号平衡 | ✅ 与基线一致 |
| 数值：修复后增益 | `0.0→0.0`, `0.5→0.5`, `0.65→0.65`, `1.0→1.0` ✅ **全部精确** |

#### ⚠️ 须知

1. **改完音量会明显变小** —— 之前一直是 1.078~1.122 倍（偏响），现在才是正确值。
2. 多路叠加**仍可能**削波：kira 主轨道默认 `Decibels::IDENTITY`（0 dB = 1.0 倍），
   **没有限幅器**。4 路满音量叠加 = 4.0，一样会爆。上游同样如此。
   修复只是把「两路就必爆」变成「需要更多路才爆」。

**待实测**：噪音是否消失、音量滑条是否真的能调、是否还有偏右感。

#### 真机实测（2026-10-19，已通过）

用户实测确认：**对话框背景、窗口颜色滑块、设置界面背景全部正常**。

日志体检（13,454 行 / rfvp 12,082 行）：

| 指标 | 结果 |
|---|---|
| panic / SIGABRT / FATAL | **0** ✅ |
| YHPROBE 残留 | **0** ✅ |
| E 级 | 11,267 行，**25 类全部是上游原有噪音或游戏自身缺资源** |

**关键验证 —— 修复确实生效了：**

```
19:32:12.570  E rfvp: color: id must be in range 0..256
```

这一条**正是补丁在起作用**：游戏启动时调用 `ColorSet(-1, ...)`，我们按原引擎语义
**拒绝了它**（而不是像修前那样静默写坏槽 255）。上游 `color.rs` 同一位置也是
`log::error!("id must be in range 0..256")`，**逐字一致**，所以这条日志上游也会打。

**E 级噪音白名单（本次核对，全部与上游源码逐字一致）**：

| 消息 | 次数 | 结论 |
|---|---|---|
| `motion: Invalid duration` | 8,764 | 游戏脚本传非 Int duration（`motion.rs` 6 处同名日志）。**上游逐字一致** |
| `motion: Invalid id` | 1,383 | 同上 |
| `graph_load: invalid id : Nil` | 900 | 游戏脚本传 Nil id（`graph.rs` `graph_load`）。**上游逐字一致** |
| `history_set: unexpected value for set_voice : Nil` | 64 | 游戏脚本行为 |
| `text_print: invalid content type` | 49 | `text.rs` **上游逐字一致** |
| `history_set: unexpected value for set_name : Nil` | 37 | 游戏脚本行为 |
| `motion: Duration must be between N and N` | 19 | 上游原有 |
| `file not found in pack ... voiceN.bin` | 7 | **游戏自身缺语音文件** |
| `Gralloc4 / GraphicBuffer / AHardwareBuffer` | 各 8 | 系统图形分配，与我们无关 |

> 注：`Invalid duration` 这次 8,764 次，比上一次日志的 2,176 次多。
> 原因是**这局玩得久、调用的 motion 更多**（上次 1,849 行、这次 13,454 行），
> 不是回归 —— 该行源码我们与上游 10.19 **逐字一致**。

---

## 4. YHPROBE 探针（**已全部移除**）

> 历史记录。探针在 2026-10-15 随点击补全稳定后**整体撤除**，
> CI 现在会在产物里发现 `YHPROBE` 时报错（§见 build-librfvp.yml）。

### 4.1 移除前的探针清单（备查）

| 探针 | 位置 | 用途 |
|---|---|---|
| `InputGetDown/Up/Event/SetClick` | `syscalls/input.rs` | 脚本实际读取的输入通道 |
| `PrimHit` | `syscalls/graph.rs` | 按钮判定（id + 结果 + 光标）——曾占日志 **93%** |
| `frame=` | `app.rs::next_frame` | 每帧输入快照（有输入才打） |
| `TextPrint id=.. spd=.. skip=.. g0=.. block=..` | `text_manager.rs` | 打印决策：为什么（不）阻塞 |
| `TextWaitArm/Done slot=.. thread=..` | `text_manager.rs` | 哪个槽卡住了哪个线程 |
| `TextSpeed / TextSkip` | `syscalls/text.rs` | 脚本设置的显示速度 / skip 模式 |
| `TextForceReveal` | `text_manager.rs` | 走了 Ctrl/ControlPulse 全显路径 |
| `REVEALMASK 0x........ -> 0x........` | `motion_manager/mod.rs` | **逐帧**哪些槽在逐字出现 |
| `SLOT click / slotdump` | `app.rs` · `text_manager.rs` | 按下瞬间 dump 全部 32 槽 |
| `click complete+SWALLOW / complete only` | `app.rs` | 补全/吞点击的实际决策 |
| `TextResume ignored` | `vm_runner.rs` | 崩溃保护拦截记录 |
| `SilentOn slot=..` | `audio_player/*.rs` | 脚本是否在调 `SoundSilentOn`/`AudioSilentOn` |

**已知会逐字出现的槽**（由 `REVEALMASK` 实测，留作参考）：
`0x1` = slot 0（对话）、`0x20` = slot 5、`0x80000000` = slot 31（设置页）。

### 4.2 移除范围

| 项 | 处置 |
|---|---|
| `apply_probe_patch.py` | 🗑️ **整个删除**（input / graph / app 三处探针） |
| `apply_text_click_patch.py` | 删掉 8 处探针 + 6 个纯诊断函数；保留 3 个功能函数与崩溃修复 |
| `build-librfvp.yml` | 去掉 probe 两步；**探针检查恢复 fail 分支** |
| 音频补丁的 `YHPROBE SilentOn` | 改为 `PROBE_ON = False` 开关（默认关，注释说明如何重开） |

### 4.3 保留的功能（**不是探针**）

| 函数 | 作用 |
|---|---|
| `force_reveal_slots()` | 把指定槽补全（「点击补全文字」的核心动作） |
| `incomplete_slot_ids()` | 列出按引擎判据「未完成」的槽 |
| `nonblocking_revealing_slot_ids()` | 列出「在逐字出现且不阻塞脚本」的槽 |
| `vm_runner.rs` 的 `TextResume` 守卫 | **真实崩溃修复**（见 3.6），与探针无关 |

### 4.4 以后要重新诊断怎么办

1. **点击类**：从 git 历史取回 `apply_text_click_patch.py` 的旧版本
   （`ec78a07` 之前），或按 §4.1 清单手动加回；
2. **音频类**：把 `apply_audio_silent_fix_patch.py` 里的 `PROBE_ON` 置为 `True`；
3. 临时诊断时记得把 CI 的「无探针」校验同步放宽
   （否则构建会红 —— 这正是保护机制在起作用）。
---

## 5. 上游 ADR-0005：故意不复刻的原引擎行为（备查）

> 上游 10.19 的 `docs/adr/0005-behaviors-not-replicated.md`。**这不是我们的 TODO，
> 是一份「不要手贱去对齐」的备忘录。**
>
> 上游把 `Marguerite.exe` 的 **143 个 syscall** 全量 IDA 反汇编后发现：原引擎自己
> 有一批 bug。他们决定**不复刻**，除非有作品真的依赖。原文的理由是
> *"so they are not rediscovered and 'fixed' by accident"* —— 免得后人重新发现
> 一次再"顺手修"一遍。

### 5.1 原引擎的缺陷（**不要复刻**）

| # | 原引擎的 bug | 我们的现状 | 本地作品是否触发 |
|---|---|---|---|
| 1 | **`Dissolve` 矩形：Y 覆盖 X**。7 参数 `Dissolve` 把第 4 个参数（X）和第 5 个（Y）**都写进 X 字段**，Y 永远是 0。Marguerite `0x41355a/0x41356a` 与 Sakura `0x43a0bc/0x43a0cc` **两版都有**，说明是长期 bug | 正常存 X / Y 两个字段 | ❌ 本地没有脚本传矩形给 `Dissolve` |
| 2 | **`TextShadowDist(id, nil)` 把阴影距离设成 id**。非 Int 参数时跳到存储，此时 `eax` 还留着文本槽 id | `dist` 非 Int 时**保持默认 0**（`text.rs:699`） | ❌ 本地没有脚本这么调 |
| 3 | **`PrimHit` 对 Sprt -1/-2 越界读**。拿 `graph[texture id]` 当尺寸，-1/-2 读到表外 | Sprt **-1 不注册命中区**（见 3.16 ADR-0002）；-2 用电影画面尺寸 | — |
| 4 | **Tile id < -1 越界读**。要读到调色板**前面**的内存（上一个脚本线程的上下文）。**但这条路不可达**：`PrimSetTile` 只存 -1..255 | 同样只接受 -1..255（见 3.16），所以永远进不去 | ❌ 不可达 |

### 5.2 单纯的差异（**未采纳，但值得知道**）

| # | 差异 | 原引擎 | 我们 / rfvp | 处置 |
|---|---|---|---|---|
| 5 | **`IntToText` 的宽度** | 宽度是 1..10 的 Int → `%0Nd`；其它（含 **nil**）→ `%d`（不补零） | 非 Int → **返回 nil**；Int → 补零到该宽度 | 📌 见 5.3：**已决定跟上游一样不改** |
| 6 | **`TextFontSet(-1)`** | 接受 -5..字体数-1，并把 **-1 替换成 -2（MS Gothic）** 再查字体名 | `-1` 走 `is_valid_fontface_id` 判断，不成立就**静默忽略**（`text.rs:301`） | 📌 Marguerite 有 `TextFontSet(G[1032])`；未确认会不会是 -1，**未触发则不动** |

### 5.3 第 5 条的实测风险 —— **决定：不动，与上游保持一致**

我们的实现（`crates/rfvp/src/subsystem/components/syscalls/utils.rs:88`）：

```rust
let value = format!("{:0width$}", value, width = width as usize);
```

**`width` 是 `i32`，负宽度 `as usize` 会变成天文数字：**

| `width` | `as usize` 之后 | 现在的结果 |
|---|---|---|
| `0` | `0` | `"42"` 正常 |
| `3` | `3` | `"042"` 正常 |
| `8` | `8` | `"00000042"` 正常 |
| **`-1`** | **`18446744073709551615`** | 尝试分配 **1600 万 TiB** → **OOM 崩** |
| `-100` | `18446744073709551640` | 同上 |

**原引擎不会崩**（`0x413c50`：宽度 1..10 才 `%0Nd`，其它一律 `%d`，含 nil）。

#### ✅ 上游的处理方式：**只记录，不修**

**上游 10.19 的 `int_to_text` 与我们逐字相同**（已 diff 确认），
`utils.rs` 整个文件唯一的差异只是 `call(args: Vec<Variant>)` → `call(args: &[Variant])`
的签名重构，**函数体一行没动**。

ADR-0005 的 Decision 原话：

> rfvp keeps its current behavior for every item below.
> An item is revisited only when a title is found whose scripts depend on it.

Consequences 还写了：

> **An entry moves out of this record into its own ADR when it is implemented.**

→ **条目还留在 ADR-0005 里 = 还没实现**。这是他们的"记录风险、暂不改"机制。

#### ✅ 我们的决定：跟上游一样，**不改**

2026-10-19 评估后决定**保持现状**，理由：

1. **上游自己都没修**，没有理由单方面偏离；
2. **实际游戏集中从未触发** —— 三个作品的日志里 `int_to_text` 报错 **0 次**
   （说明从没传过非 Int 宽度，更没传过负数）；
3. 试过的几种改法都不划算：
   - `max(0)`：只堵负数，`width > 10`（如 99）仍会补 99 个零，
     **既没完全解决，又多了与上游的差异**；
   - 完整对齐原引擎（`1..=10` → `%0Nd`，其它 `%d`）：
     **会改变 `IntToText(x, nil)` 的可见输出**（现在返回 `nil`，改后返回数字串），
     而本地三个作品都在调这个（每个 9~22 次），**风险大于收益**。

**结论：保持与上游 100% 一致，什么都不改。** 万一以后真遇到某个作品踩到，
按 5.4 的第 2 条处理。

### 5.4 这份文档的用法

碰到某个作品的怪现象时，**先来这里查一遍**：

1. 如果是 5.1 的 4 条 → **上游故意不复刻**，别去"修"，那不是 bug；
2. 如果是 5.2 的第 5 条 → 看 5.3，可能是 OOM 风险，考虑修；
3. 如果是第 6 条 → 先确认那个游戏的 `G[1032]` 实际值，再决定要不要对齐 `-1 → -2`。

---

## 6. 遗留事项 / TODO

- [x] ~~**mimalloc 全局分配器（上游 10.19）**~~ → **试过，已否决并回退**（2026-10-19，`revert 910fa6e`）。
      **决定理由**：用户反馈「现在本来玩着就没这个问题」，而 mimalloc 属于**全局分配器替换**，
      风险面（随机崩溃 / TLS / 长跑稳定性）远大于收益，且上游那个「省 100 MiB」是 **macOS**
      系统 malloc 的数据，Android bionic malloc 未必有同样问题。**当前没有内存压力，不做。**
      **如果以后真的遇到内存问题**，可以按下面方式重新引入（已验证与上游逐字一致）：
      - `Cargo.toml`：`default` 加 `"mimalloc"`；新增 `mimalloc = ["dep:mimalloc"]`；
        在 `cfg(all(not(wasm32), not(uefi)))` 块里加
        `mimalloc = { version = "0.1", default-features = false, optional = true }`
      - `lib.rs`：在 `compile_error!(...)` 与 `pub mod host_api;` 之间插入
        `#[cfg(all(feature = "mimalloc", not(feature = "no_std"), not(target_arch = "wasm32"), not(target_os = "uefi")))]`
        `#[global_allocator]`
        `static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;`
      - 标记：`YHFVP-MIMALLOC-1.0`（18 字节）
      - **注意**：编译通过 ≠ 没问题。真机必须验证：启动不崩 / 长跑 30~60 分钟 /
        内存 PSS 前后对比 / 切场景不退化 / 存档缩略图正常 / 低端机 + 主力机。
      - 完整补丁脚本在 git 历史里：`git show 910fa6e:rfvp-build/apply_fvp_mimalloc_patch.py`


- [x] ~~**移除诊断探针**~~ → **已完成**（2026-10-15，见 §4）。
      删除 `apply_probe_patch.py`、清理 `apply_text_click_patch.py` 里 8 处探针
      与 6 个诊断函数、音频探针改为开关，CI 恢复 fail 分支。
      实测依据：`PrimHit` 曾占日志 93%，且点击补全长期稳定运行。
- [ ] 上游 ERROR 属**原有噪音**、非本补丁引入（已核对）：
      `motion: Invalid duration`、`Invalid alpha motion id`、`Invalid id`、
      `float_to_int: Invalid value type`、`history_set: unexpected value ... Nil`。
- [ ] `TextResume` 保护可考虑反馈给上游（这是真实崩溃点）。
      **更新（2026-10-18）**：上游 `513b3955` 已独立做出同样的 `if contains(CONTEXT_STATUS_TEXT)`
      判断，并进一步做了源头清除（`cancel_text_requests`）。我们已移植（见 3.14）——
      因此这条可以**关闭**：上游已知晓并修好了。
- [ ] **WMV 音频：明确不做**（见 3.3.1）。若将来上游把 `wma/mdct.rs` 的
      `MdctNaive` 换成 FFT 实现（性能提升到实时以上），才值得重新评估
      "WMV 层效果也解音频"。**目前维持现状。**
- [ ] 可选：把 3.3.1 的结论同步给用户文档（"WMV 想有声音请转 MP4"）。
- [x] ~~**已知残留：上游贴图 bug**~~ → **已修**（见 3.9）。
      移植上游 `e95f275a`（mask dissolve）+ `ffc73809`（加法混合），
      真机实测**暗带消失** ✅。FVP 模块功能已基本完备（文本/视频/输入/虚拟控件）。
- [ ] **转场（dissolve）生硬 —— 已移植上游 10.15 的正确实现，待真机实测**（见 3.12）。
      根因是「盖黑」而非「擦画面」：上游 `2a9ff58a` 改成抓取转场起始帧再按遮罩擦除。
      我们已照搬（含 `dissolve_serial`、sRGB、`MaskFadeIn` 硬边语义）。
      待验证：转场是否从「黑一下」变成「擦出新场景」。

- [ ] **音频：`SoundSilentOn` 语义修正已应用，待真机确认**（见 3.11）。
      带 `YHPROBE SilentOn` 探针：既要对齐 10.15（不静音），
      也要用日志确认脚本到底调没调、调了几次。
      注：「声音糊/底噪」是另一条线，不在本补丁范围（10.15 也未专门处理它）。
- [ ] 关注上游 commit：出现 `dissolve` / `render` / `audio` 相关改动时回来看。
- [x] ~~**对话框背景不透明 + 设置里的颜色不生效**~~ → **已由上游修复（`cc8ac77`），我们已移植，真机实测通过**（2026-10-19）。
      ~~原计划交上游~~ → 上游当天就发了 `cc8ac77`，附 5 篇 ADR，**根因与我们的探针数据完全吻合**。
      详见 §3.16。

      **根因（ADR-0001，issue #55）**：脚本用 `PrimSetTile(513, -1, ...)` 创建对话框，
      而 **Tile id -1 在原引擎里不是调色板槽位** —— 它每帧读全局 `G[5..8]`
      作为 R/G/B/A（正是配置界面「窗口颜色」滑块编辑的那四个全局）。
      我们把它当成槽 255；同时 `ColorSet` 先 `as u8` 再检查，`ColorSet(-1, ...)`
      被截成 255 写进去，随后又被 `ColorSet(255, 255,255,255,255)` 覆盖成不透明白。
      两个 bug 叠加 → 对话框不透明白色。

      **证物对得上**：v1 探针日志里 `prim=513/514 tile=-1 tile_a=255` 正是这两个对话框；
      v2 探针 `id=115` 的写入值也与绘制端读到的 `tile_a` 逐个吻合。
      当时只把 -1 当「无效值」，没想到它是特殊通道 —— 这个教训记下来。

      **探针教训（仍然有效）**：
      v1 在 `collect_tree` 里逐帧打 alpha，17k 条把 Android logcat 配额打爆 ——
      **55 次 QUOTA 丢弃，累计 603,348 行被丢**（是保留下来的 37 倍）。
      **以后任何逐帧探针必须先限流/去重。**
- [x] ~~**老 FVP 按钮点不动（issue #49）**~~ → **已移植上游 `a1091393`，真机实测通过**（见 3.13）。
      上游已关闭我们提的 issue #49。注意：**「点击穿透」是我们自己修的，与上游无关**
      （上游 10.18 并未修穿透）。
- [ ] **10.18 未移植项（有意跳过）**：`7958dd37`（存档恢复时回放 GraphRGB）与
      `783d7d57`（存档保留被改过的贴图快照）。两者都改 `graph_buff.rs` 的存档格式，
      而我们在该文件已有 mask dissolve 的 `generation` 改动（基线→10.18 差 345 行）。
      风险是**旧存档读不回来**。除非真遇到「读档后画面不对」，否则不动。
- [ ] **10.18「音频噪音降低」= 未证实**。10.15→10.18 **零音频文件改动**
      （`audio_player/*`、`rfvp_audio/*` 全无变化，`kira.rs` 与 10.09 逐字节相同）。
      若仍有噪音，真凶在别处（可能是 10.15 的 `fix audio` 我们尚未移植的
      `channels.rs` 重构 + kira 容量限制），需要单独排查。
      **（2026-10-19 用户确认：噪音只在那一个作品的一段出现，不再追。）**
- [x] ~~**日志体检（2026-10-19，按钮修复后）**~~ → **无新增问题**。
      1,849 行 / 663 行 rfvp：**panic 0、SIGABRT 0、YHPROBE 0**。
      E 级 179 行全部归为**上游原有噪音**，逐条核对过：
      | 消息 | 次数 | 判定 |
      |---|---|---|
      | `motion: Invalid id` | 150 | 上游代码逐字相同（14 处 `log::error!`，基线 = 10.18）。集中在 383ms 内，是某段脚本批量调 `motion_*` 传了非 Int 的 id，函数**静默返回 Nil**，无副作用 |
      | `history_set: ... set_voice/set_name : Nil` | 19 | 上游原有，`history.rs` 日志语句与基线一致（差异 4 行均为 `args: &[Variant]` 签名迁移） |
      | `prim_set_xy: invalid id : Nil` | 3 | 上游原有 |
      | `audio_load: open BGM stream from vfs: voice2/000004` + `file not found` | 4 | **游戏数据本身缺文件**（`voice2.bin` 里没有 `000004`），不是引擎 bug |
      | `syscall error: file not found in pack .../HappyMarguerite` | 3 | 同上，资源缺失 |

      **顺带核实的两个「看着可疑、其实正常」**：
      - `text_patch: YHFVP-TEXTPATCH-1.0 (樱花，萌放。 ...) active` 出现在
        `はっぴぃ☆マーガレット！` 的会话里 —— `PATCH_TARGET` 是**编译期写死的标签**，
        真正的判据是「表 key 命中脚本偏移 >= 20%」（`hits * 5 < offsets.len()` 则跳过）。
        樱花那条是 `active`（真的生效）；**マーガレット 没有打这行日志 = 判据没过、正确跳过**。
      - `jis_tunnel active: uif_config.json (1004 entries, 1002 hits)` ——
        1002/1004 命中，正常。
- [x] ~~**官中版 SJIS 替身字还原：待真机验证**~~ → **已完成**（见 3.10.3）。
      真机实测三项全通过：对话文本 `什弗`→`什么`、破折号 `禳禳`→`——`、
      存档标题 `寅却稾稾地…`→`她却轻轻地…`；旧存档（带 `&#NNNNN;`）读出来也正常；
      日志 0 panic、E 级全是既有上游噪音。
- [x] ~~**HappyMarguerite 漢化版：待真机复验**~~ → **已完成**（2026-10-15，见 3.10.3）。
      真机实测四项全通过：**贴图/立绘恢复**（`graph.bin: CHR_…` 报错 4 → 0）、
      对话文字正常、回首过去正常、**樱花官中版在同一份日志里也跑了且一切照旧**
      （`Sakura.dll 1206 entries, 1206 hits`）。
      残留的 `SYS_WINDOW_UP/DW`、`se_sys: 013`、`voice2: 000004` 共 8 条
      **逐条验证过 restore 后不变**（且名字在资源包里根本不存在）→ 原版行为，非本补丁。
      日志体检：0 panic/FATAL/SIGSEGV/ANR/OOM；`YHPROBE` 0 条；
      配额丢弃 97 → **14 次**（探针撤除的副产品）。
- [ ] 可选：`uif_config.json` 的解析器目前**遇到反斜杠转义就整表放弃**。
      实测两个真实文件反斜杠数为 0，所以够用；若将来遇到带转义的版本，
      再补一个小型 JSON 字符串反转义即可（改动局限在 `parse_json_string`）。
- [ ] **替换/删除旧的乱码存档**：修复前写出的存档标题里带字面量 `&#NNNNN;`
      （已经写死在文件里，读回来仍是这个实体）。修复后**再存一次**就正常了。
- [ ] 虚拟控件相关均**在 APK 侧**（3.8），换 so 不影响；但**换 APK 必须重编**。

---

## 7. 排查方法（下次遇到类似问题）

1. **先加探针、再改代码**。本轮前期连续多次"基于推理的修复"全部失败，
   最终破案全靠数据（`REVEALMASK` + `SLOT click` 一次性定位）。
2. **区分"同一现象的不同机制"**：对话槽 `skip_mode=3`（不阻塞）与
   设置槽 `skip_mode=0`（阻塞）行为完全不同 —— 判据必须按槽区分。
3. **注意"顺序依赖"**：补全会改变判据依赖的状态，所以
   **先取快照、再执行副作用、再决策**。
4. **注意帧内顺序**：`vm_runner.tick()`（脚本）→ `scene.update_after_vm()`
   （文字/动作）→ 渲染。很多"时序"问题都由这个顺序决定。
5. **验证四件套**：① 锚点唯一命中；② 幂等；③ 七补丁串行全绿；
   ④ 括号/结构增量与上游一致。
6. **"日志里一行都没有"是最强的线索**。虚拟鼠标那次，
   `YukiGameCursor` 完全没有输出 —— 直接说明**代码根本没被执行到**，
   顺着第一行 guard 就找到了那个永远为 false 的开关。
   反过来，如果日志有输出但行为不对，才是逻辑问题。
7. **改 Java 前先用 stub + javac 真编译**。本轮因类型改动（`FrameLayout`→`Activity`）
   漏改三处，让用户白跑了一轮编译。做法：`/tmp/jcheck` 下放一套最小 android stub，
   `javac -sourcepath src ...` 验证；能提前发现 90% 的类型错误。
8. **GUI 参数尽量做成常量**（本轮虚拟按键外观改了 4 轮），每轮只改一个数字。

---

## 8. 换 so 后的功能对照（用户可见）

| 功能 | 依赖 | 说明 |
|---|---|---|
| 文字大小 1.0~2.0x | `rfvp_android_set_text_scale` | 引擎设置里选，下次启动生效 |
| OP / 电影播放（**MP4**，含音频） | video-sys + movie + audio 补丁 | **推荐格式**：画面+音频+可跳过 全功能 |
| 原版 `.wmv` 播放 | wmv 补丁 | **能播画面、可跳过、不卡死**；**无声音**（原版行为，见 3.3.1） |
| 原版 `.mpg/.mpeg` | — | 不做同步音频解码（同 WMV 理由） |
| FVP HD parts 背景暗带 | fvp render 补丁（mask dissolve + additive blend） | 见 3.9；**已实测暗带消失** ✅ |
| **中文化文本（两类）** | jis-tunnel 补丁（`jis_tunnel.rs`） | 见 3.10；**两款作品真机均确认**：樱花萌放官中（`Sakura.dll`）+ HappyMarguerite 漢化（`uif_config.json`）对话 / 回首过去 / 存档标题 / 立绘贴图全部正常 ✅ |
| 点击补全点字动画 | text_click 补丁 | 动画中点一下=显示完整句；再点一下才过 |
| **虚拟按键 ESC / 历史 / Ctrl** | APK 侧 `FvpVirtualKeys` | **与 so 无关**（键码走 `rfvp_android_key`），需重编 APK |
| **虚拟鼠标（含光标包）** | APK 侧 `gamecursor` | 同上；光标点击走 `rfvp_android_touch`（见 3.8）|
| 按钮点击不穿透 | APK 侧 `FvpActivity`（v4 输入映射） | **与 so 无关**，需重编 APK |
| 设置滑条 / 文本速度保存 | APK 侧直通映射 | 同上 |
| 樱花萌放汉化组版文本 | text_patch 补丁（`patch.dat` 查表） | 见 3.7；需游戏目录里有汉化组的 `patch.dat` |

> **两款中文化的关系**（互不干扰，各走各的路）：
> - **汉化组版**（日文原版 + `patch.dat`）→ 走 `apply_text_patch.py` 的偏移查表
> - **官中版**（`Sakura.dll` + 替身字）→ 走 `apply_fvp_jis_tunnel_patch.py`
>
> 判据完全不同（字节偏移 vs 字符集命中率），且都带自证阈值，不会互相误触发。
> 详见 3.7 与 3.10 各自的「为什么不会误伤」章节。

> **给用户的一句话**：想要 OP 有声音，**把 `movie/*.wmv` 转成 `.mp4`** 即可
> （同目录同名，引擎会优先用 `.mp4`）。
