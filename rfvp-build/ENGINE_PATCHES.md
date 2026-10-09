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
apply_android_log_patch.py     # 2. android logger + 压制 wgpu 噪音
apply_movie_patch.py           # 3. 电影播放（音频/层效果）
apply_audio_fix_patch.py       # 4. MP4 声道回填 + SampleBuffer 按需分配
apply_wmv_decoder_patch.py     # 5. WMA 解码器负数下标防护
apply_probe_patch.py           # 6. YHPROBE 输入探针（诊断期）
apply_text_click_patch.py      # 7. 点击补全文字 + vm_runner resume 保护
# 之后独立 git apply：
video-sys-cpu-read.patch       # 8. video-sys 解码器 ByteBuffer 模式
```

> ⚠️ 顺序有依赖：`apply_movie_patch` 与 `apply_audio_fix_patch` 会争抢
> `videoplayer.rs` 同一段代码，必须保持现有顺序（见提交 `a013595`）。

---

## 2. 补丁清单与职责

| 补丁 | 目标文件 | 解决的问题 |
|---|---|---|
| `apply_text_scale_patch.py` | `text_manager.rs` / `app.rs` / `android_host.rs` | 全局字号系数（1.0~2.0x），新增 C ABI `rfvp_android_set_text_scale` |
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

**根因**：上一轮把 WMV/MPEG 的 `LayerNoAudio` 分支也改成"照常解码音频"，
而 `decode_wmv_audio_to_wav_bytes` 是**主线程同步全量解码** → 阻塞 + panic。
另外 `wma/decoder.rs` 存在**负数下标 panic**（`ptab[-3 as usize]`）——
FFmpeg 用无符号比较 `(unsigned)last_exp >= 96` 拦截负数，Rust 移植只查了上界。

**修复**：

- `apply_movie_patch.py`：**WMV/MPEG 恢复原版 `(None, None)`**（不做同步音频解码）；
- `apply_wmv_decoder_patch.py`：负数/越界返回 `Err` 而非 panic；
- 补丁改 **fail-fast**（文件缺失 → 退出码 1，不再静默跳过）。

> ⚠️ **重要约定**：WMV 播放逻辑必须**遵循原版**。
> 实测部分游戏的原版 wmv 是可以正常播放的，不要为了 MP4 而改动 WMV 路径。

**验证日志**：`Movie: started` 仅 7ms（原为 15ms+ 同步解码）。

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
| OP / 电影播放（含音频） | video-sys + movie + audio 补丁 | MP4 优先；WMV 遵循原版 |
| 原版 .wmv 可跳过 | wmv 补丁 | 不再卡死白屏 |
| **点击补全点字动画** | text_click 补丁 | 动画中点一下=显示完整句；再点一下才过 |
| 按钮点击不穿透 | APK 侧 `FvpActivity`（v4 输入映射） | **与 so 无关**，需重编 APK |
| 设置滑条 / 文本速度保存 | APK 侧直通映射 | 同上 |
