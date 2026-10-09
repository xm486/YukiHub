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
apply_probe_patch.py           # 7. YHPROBE 输入探针（诊断期）
apply_text_click_patch.py      # 8. 点击补全文字 + vm_runner resume 保护
# 之后独立 git apply：
video-sys-cpu-read.patch       # 9. video-sys 解码器 ByteBuffer 模式
```

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
| `apply_probe_patch.py` | `syscalls/input.rs` 等 | YHPROBE 输入探针（`InputGetDown/Up/Event/SetClick/PrimHit`） |
| `apply_text_click_patch.py` | `app.rs` / `text_manager.rs` / `vm_runner.rs` | **点击补全点字动画** + `TextResume` 状态保护 |
| `video-sys-cpu-read.patch` | `video-sys` crate | 解码器输出改 ByteBuffer 模式（修 Qualcomm UBWC 白屏） |

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

## 4. YHPROBE 探针一览（诊断期）

> 过滤：`adb logcat -s rfvp | grep YHPROBE`

| 探针 | 位置 | 用途 |
|---|---|---|
| `InputGetDown/Up/Event/SetClick` | `syscalls/input.rs` | 脚本实际读取的输入通道 |
| `PrimHit` | `syscalls/graph.rs` | 按钮判定（id + 结果 + 光标） |
| `frame=` | `app.rs::next_frame` | 每帧输入快照（有输入才打） |
| `TextPrint id=.. spd=.. skip=.. g0=.. block=..` | `text_manager.rs` | 打印决策：为什么（不）阻塞 |
| `TextWaitArm/Done slot=.. thread=..` | `text_manager.rs` | 哪个槽卡住了哪个线程 |
| `TextSpeed / TextSkip` | `syscalls/text.rs` | 脚本设置的显示速度 / skip 模式 |
| `TextForceReveal` | `text_manager.rs` | 走了 Ctrl/ControlPulse 全显路径 |
| `REVEALMASK 0x........ -> 0x........` | `motion_manager/mod.rs` | **逐帧**哪些槽在逐字出现（bit i = slot i） |
| `SLOT click i=.. ld=.. su=.. sk=.. sp=.. v=AA/BB swa=..` | `app.rs` | 按下瞬间**无条件** dump 全部 32 槽 |
| `click complete+SWALLOW / complete only` | `app.rs` | 补全/吞点击的实际决策 |
| `TextResume ignored` | `vm_runner.rs` | 崩溃保护拦截记录 |

**已知会逐字出现的槽**（由 `REVEALMASK` 实测）：
`0x1` = slot 0（对话）、`0x20` = slot 5、`0x80000000` = slot 31（设置页）。

---

## 5. 遗留事项 / TODO

- [ ] **移除诊断探针**：确认点击补全长期稳定后，删除 `apply_probe_patch.py`
      与 `apply_text_click_patch.py` 中的 YHPROBE 日志，并把
      `build-librfvp.yml` 的"探针检查"恢复为 **fail** 分支
      （当前是诊断期临时放行的 `::warning::`）。
- [ ] 上游 ERROR 属**原有噪音**、非本补丁引入（已核对）：
      `motion: Invalid duration`、`Invalid alpha motion id`、`Invalid id`、
      `float_to_int: Invalid value type`、`history_set: unexpected value ... Nil`。
- [ ] `TextResume` 保护可考虑反馈给上游（这是真实崩溃点）。
- [ ] **WMV 音频：明确不做**（见 3.3.1）。若将来上游把 `wma/mdct.rs` 的
      `MdctNaive` 换成 FFT 实现（性能提升到实时以上），才值得重新评估
      "WMV 层效果也解音频"。**目前维持现状。**
- [ ] 可选：把 3.3.1 的结论同步给用户文档（"WMV 想有声音请转 MP4"）。

---

## 6. 排查方法（下次遇到类似问题）

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

---

## 7. 换 so 后的功能对照（用户可见）

| 功能 | 依赖 | 说明 |
|---|---|---|
| 文字大小 1.0~2.0x | `rfvp_android_set_text_scale` | 引擎设置里选，下次启动生效 |
| OP / 电影播放（**MP4**，含音频） | video-sys + movie + audio 补丁 | **推荐格式**：画面+音频+可跳过 全功能 |
| 原版 `.wmv` 播放 | wmv 补丁 | **能播画面、可跳过、不卡死**；**无声音**（原版行为，见 3.3.1） |
| 原版 `.mpg/.mpeg` | — | 不做同步音频解码（同 WMV 理由） |
| **点击补全点字动画** | text_click 补丁 | 动画中点一下=显示完整句；再点一下才过 |
| 按钮点击不穿透 | APK 侧 `FvpActivity`（v4 输入映射） | **与 so 无关**，需重编 APK |
| 设置滑条 / 文本速度保存 | APK 侧直通映射 | 同上 |

> **给用户的一句话**：想要 OP 有声音，**把 `movie/*.wmv` 转成 `.mp4`** 即可
> （同目录同名，引擎会优先用 `.mp4`）。
