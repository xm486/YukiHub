#!/usr/bin/env python3
"""YukiHub 补丁：还原「SJIS 替身字」（SJIS tunnel）——中文化把简体字塞进
有 JIS 码位的汉字码位里，引擎需要在解码后再做一次 Unicode→Unicode 还原。

适用于两类来源（`Sakura.dll` 二进制表 / `uif_config.json` 的 UIF 规则表）。

现象（两个作品，同一个机制）
--------------------------------
**案例 1 ·《樱花，萌放。》官方简体中文版**——对话里夹带冷僻汉字：

    寅喊着什弗。                          ← 实际显示
    她喊着什么。                          ← 应该是这样
    平日寡言少苫的寅，用我从未听到慓的音量，大声地喊着什弗。
    平日寡言少语的她，用我从未听到过的音量，大声地喊着什么。

**案例 2 ·《HappyMarguerite》漢化版（HappyMarguerite_CHS）**——同样症状：

    我己識戰経記个麼景无数次了。          ← 实际显示（見截图）
    我己经见过这个场景无数次了。          ← 应该是这样

**改字体没有任何效果**（字体没问题），改文本编码到 GBK / UTF-8 也都不对
（GBK 出的是 `彫铎` 这类假字，UTF-8 有 57,507/64,779 条直接解码失败）。

根因
----
简体中文**没法直接写进 Shift-JIS**——简体字绝大多数在
JIS X 0208 里**没有码位**（「么 / 这 / 为 / 着 / 过 / 语 / 说 / 话 / 车 / 时 …」
全部 encode('cp932') 失败）。

于是中文化用了一套**替身字表**：把每个简体字映射到一个**有 JIS 码位的
汉字**（往往是繁体字或日文汉字），再用那个码位写进脚本。PC 端在**解码之后**
再做一次 Unicode→Unicode 还原，所以 PC 上显示正常。rfvp 解到 Shift-JIS 就
停了，缺的正是这第二步 —— 所以替身字原样显示出来。

两个作品的差别只在「表存在哪里」：

| 案例 | 表来源 | 表格式 | PC 端怎么还原 |
|---|---|---|---|
| 樱花萌放 **官中版** | `Sakura.dll`（偏移 `0x47960`，`.rdata` 段） | 连续 **UTF-16LE** `(key, value)` 对，1,206 对 | 引擎内建（`babel::sjis_cp932_to_unicode_engine`，数据即 `jis_map.bin`） |
| HappyMarguerite **漢化版** | `uif_config.json`（`text_processor.rules[]`） | JSON `"type": "replace_chars"` 规则里的 `source_chars` / `target_chars` 两条**平行字符串**，1,004 对 | **UniversalInjectorFramework** 外部注入，在 `DrawTextA` 上做替换 |

共同点（这正是能用同一套判据处理的原因）：

| 特征 | 樱花官中 | HappyMarguerite |
|---|---|---|
| 对数 | 1,206 | 1,004 |
| key 有 JIS 码位 | 1,206 / 1,206 | **1,004 / 1,004** |
| value 有 JIS 码位 | 0 / 1,206 | **0 / 1,004** |
| key 两两不同 | ✅ 双射 | ✅ 双射 |
| value 两两不同 | ✅ 双射 | ✅ 双射 |
| key ∩ value | ∅ | ∅ |

> `value`「一个都没有 JIS 码位」是这种方案的**定义性特征**：需要替身，正是因为
> 真字写不进 Shift-JIS。`key` 反过来必须**全部**有码位，否则脚本里根本存不下。

案例 1 的实测证据（本机《樱花，萌放。》官方简体版）
--------------------------------------------------
| 项 | 值 |
|---|---|
| `Sakura.hcb` | 4,372,452 字节（日文版是 5,002,852，是**另一份脚本**） |
| 脚本 `push_string` 条数 | 64,779（按 opcode 表反汇编） |
| Shift-JIS 解码失败 | 1,729 条（**用 cp932 全部正常**） |
| 表位置 | `Sakura.dll` 偏移 `0x47960`（`.rdata` 段内），**1,206 对 UTF-16LE** |
| 表内容（开头） | 徼→这 · 戍→你 · 禳→— · 諍→说 · 弗→么 · 來→们 · 餤→为 · 肛→样 · 寅→她 |
| 替身字覆盖率 | 223,054 / 1,310,705 字符 = **17.02%**（平均每 6 字 1 个） |
| 表 key 所在区段 | **全部** JIS X 0208 标准区（cp932 与 shift_jis 解码结果完全一致） |
| `Sakura.dll` 内符号 | `babel::sjis_cp932_to_unicode_engine` / `SJIS tunnel limit exceeded` / `Invalid jis_map.bin format` |

案例 2 的实测证据（本机 HappyMarguerite_CHS）
---------------------------------------------
| 项 | 值 |
|---|---|
| `Marguerite.bch` | 3,193,758 字节 —— **汉化版脚本，游戏实际加载的就是它** |
| `patchdata/Marguerite.hcb` | 3,570,209 字节，mtime **2008-02-13** —— 日文原版（补丁原始备份） |
| 表位置 | `uif_config.json` 的 `text_processor.rules[0]`（`"type": "replace_chars"`） |
| 表内容（片段） | 這→变 · 説→为 · 記→这 · 識→经 · 戰→见 · 場→她 · 軽→啊 · 読→— |
| 表属性 | 1004 对，source 去重 1004、target 去重 1004、交集 0 |
| 替身字覆盖率 | 140,949 / 699,454 字符 = **20.15%** |
| 截图那行 | `我己識戰経記个麼景无数次了。` → `我己经见过这个场景无数次了。` |
| 脚本内命中 | `識戰経記` 在 `Marguerite.bch` 偏移 `0x6BAD6` 逐字节命中 |

> ⚠️ `uif_config.json` **是本补丁的一部分**（不是「旧汉化版残留」）：它的 mtime
> 与 `Marguerite.bch` / `README.md` 完全一致（2024-04-27 09:18），且 `README.md`
> 原文写着「**对于 sjis 编码不支持的汉字采用了新的方法进行处理，所以
> `uif_config.json` 也需进行替换**」。
> （该游戏目录里的 `.CTFontHook/` 属于**另一份已删除的汉化版**残留，与本补丁无关，
> 代码里也完全不读它。）

做法（**文字路径**，不是解析路径）
--------------------------------
新增模块 `crates/rfvp/src/jis_tunnel.rs`，在**四条路径**上各接一次：

| # | 位置 | 方向 | 函数 |
|---|---|---|---|
| 1 | `text_manager.rs` 的 `set_text_content()` | 对话文字 → 屏幕 | `restore()` |
| 2 | `history.rs` 的 `HistorySet(Name)` / `HistorySet(Content)` | 回首过去 → 屏幕 | `restore()` |
| 3 | `save_manager.rs` 的 `decode_bytes()` | 存档 → 屏幕 | `restore()` |
| 4 | `save_manager.rs` 的 `enc()` | 屏幕 → 存档 | `to_surrogate()` |

> 2 是「脚本**另存**了一份文字」造成的独立出口：屏幕上的那行（1）还原了，
> backlog 里那份（2）不会自动跟着变 —— 两个游戏都用 `HistorySet`（各 1 处）。
> 它俩都只拿得到对话文字/角色名，**拿不到资源名**，所以挂在这里安全。

**1 为什么必须是 `set_text_content()` 而不是 `decode_string_bytes()`**

第一版把 1 接在 `script/parser.rs` 的 `decode_string_bytes()` 上（那是所有脚本
字符串的唯一解码出口），结果**把资源名也一起改了**：HappyMarguerite 的立绘
文件名是**日文汉字**（`CHR_練_…`、`CHR_撫子`、`CHR_爺`），表把它们改成
`CHR_觑_…` / `CHR_垫子` / `CHR_练`，于是 `graph_load` 全部 `file not found`，
**立绘和贴图整片消失**（文字却正常，因为文字本来就该被改）。

樱花官中版没暴露这个问题，是因为它的资源名恰好是**纯 ASCII** ——
「表里 key 全是汉字所以碰不到资源名」这个结论**只在纯 ASCII 资源名时成立**，
对日文原版资源名完全不成立。这是第一版最根本的方法论错误。

正确做法是**把还原放在"文字变成屏幕内容"的那一步**，让资源名/存档名等
非显示字符串保持原样 —— 这也正是 PC 端 UIF 的方案（它在 `DrawTextA` 这个
**绘制** API 上替换，物理上碰不到文件名）：

| 阶段 | 是否包含资源名 | 能不能在这儿还原 |
|---|---|---|
| `decode_string_bytes()`（解析） | ✅ 包含 | ❌ |
| `set_text_content()` → `parse_content_text()`（文字） | ❌ 不包含 | ✅ |
| `text_print` / `text_reprint` 的 `history_set(Content)` | ❌ | ✅（已由 1 覆盖） |
| `save_manager` 的 3 个字段（title/scene/script） | ❌ | ✅（由 2/3 覆盖） |

`set_text_content` 全 workspace 只有 **2 个调用点**，都在 `text_print` 里
（`Variant::String` 与 `Variant::ConstString` 两个分支），是文字内容的**唯一入口**。

**脚本文件与存档文件都一个字节都不改**，所有字节偏移天然不变。

为什么「写出方向」也必须做（`&#NNNNN;` 的来历）
----------------------------------------------
表里 **全部 1,206 个真字都没有 JIS X 0208 码位**（这正是官方要用替身字的原因）。
`encoding_rs::SHIFT_JIS.encode()` 对无法映射的字符会按 WHATWG 规范输出
**数字字符引用** `&#NNNNN;`：

    encode("纸")  ->  "&#32440;"        had_unmappable = true
    encode("这")  ->  "&#36825;"

而引擎读回时**不会解析**这个实体（`decode("&#32440;") == "&#32440;"`），
所以存档界面上就会看到字面量 `&#32440;`。全 workspace 只有 `save_manager.rs`
这一处把文本编码回 Shift-JIS，所以修这一处就够。

反向映射是安全的：表是**双射**（1,206 个 key 两两不同，1,206 个 value 也两两不同，
重复 0 条），所以真字 → 替身字的查找没有歧义。往返已验证：

    真字「上放着用彩色糖纸包装起来的巧克力。」
      -> to_surrogate -> 「上放着用彩色糖驥包装起来的巧克力。」
      -> encode       -> 8fe3...939c e987 95ef...8142   ← 与游戏脚本原始字节逐字节一致
      -> decode+restore -> 「上放着用彩色糖纸包装起来的巧克力。」  ✅ 往返一致

**脚本文件一个字节都不改**，所有字节偏移天然不变。

为什么不会误伤别的版本（已实测）
--------------------------------
数据来源：**只读游戏目录里两个固定的文件名**，按顺序试：

1. `Sakura.dll` —— 二进制 UTF-16LE 表（樱花萌放官中版）
2. `uif_config.json` —— UIF 的 `replace_chars` 规则（汉化组 / 机翻补丁）

不做全目录 dll/exe 扫描。原因：FVP 生态很小，现实中出现的替身字方案就这两种；
显式命名 + 自证的文件**不可能**被游戏目录里恰好躺着的无关二进制误触发。
将来若还要支持第三种来源，就在 `load_table` 的那个数组里加一行名字。

判定为「自证 + 白名单」双保险：

1. **文件名白名单**：只认上面两个名字。
2. **候选表识别** —— 各来源用各自的严格形状校验：
   - `Sakura.dll`：找**最长的一段连续 `(u16>=0x2000, u16>=0x2000)`**（≥64 对），
     再**从头开始逐条读**，遇到第一条不合法（key 非 CJK 汉字，或 value < U+2000）就**停**。
     > 一开始用的是「跳过非法条目（`continue`）+ 把 value 限制在 `0x3000..=0x9FFF`」，
     > 结果把表里第 3 高频的 **`禳→—`**（value 是 U+2014 破折号，低于 CJK 区）丢掉了 ——
     > 它在脚本里出现 **9,211 次**，游戏里就是满屏「禳禳」粘在一起。现在的写法是
     > 「run 到哪算哪，遇到非法就结束」，实测提取结果与手工 1,206 条**完全一致**。
   - `uif_config.json`：`type` 必须是 **`replace_chars`**（其它 UIF 规则类型
     `replace_str` / `replace_raw_bytes` 不带字符表，按字符表解析会弄坏文本），
     且三个键必须按 `type` → `source_chars` → `target_chars` 的顺序出现
     （顺序先于取值校验，所以「`source_chars` 写在 `type` 前面」的文件会被拒绝
     而不是误解析），两条字符串**等长**且 **≥256 对**。
     解析器是朴素字节扫描，**遇到任何反斜杠转义就直接判定「不是表」** ——
     实测两个真实文件里反斜杠数量为 **0**、三个键各只出现 **1 次**，
     所以这个"不会遇到转义的假设"是量化验证过的，而不是猜的。
     （`source` / `target` 有一条对不上就静默不启用 = 保持上游行为。）
3. **自证机制**：用「表里的 key 是否出现在**本游戏脚本**用到的字符集里」打分。
   只有 **命中 ≥ 500 且命中率 ≥ 90%** 才启用；否则静默不启用（行为与上游一致）。
   > 注意这里刻意**没有**用「key 全部有 JIS 码位 / value 全部没有」这类编码属性判据：
   > 它依赖具体编码的语义（strict `shift_jis` 下 HappyMarguerite 的 value 是 0/1004
   > 无码位，但 `cp932` 下有 12 个有码位），写死会变成脆弱判据。命中率自证足够。

   阈值定得这么严是有实测依据的（**两个作品都适用同一条线**）：

   | 游戏 | 脚本（实际加载的） | 表来源 | 命中率 | 判定 |
   |---|---|---|---|---|
   | 樱花萌放 官中 | `Sakura.hcb` | 它自己的 `Sakura.dll` | **1,206 / 1,206 = 100%** | ✅ |
   | HappyMarguerite 漢化 | `Marguerite.bch` | 它自己的 `uif_config.json` | **1,002 / 1,004 = 99.8%** | ✅ |
   | 星空EH / 星空Wish | `Hoshimemo_HD.hcb` | 上面那张 JSON 表 | 731 / 1,004 = 72.8% | ❌ |
   | HappyMarguerite 日文原版 | `patchdata/Marguerite.hcb` | 上面那张 JSON 表 | 711 / 1,004 = 70.8% | ❌ |
   | 樱花萌放 日文版 | `Sakura.hcb` | 上面那张 JSON 表 | 520 / 1,004 = 51.8% | ❌ |

   > 表里这几行是**用真 Rust 模块**跑出来的（`FVP_BASE_PATH` / `find_script` /
   > `extract_table` / `script_charset` 全套真实代码），不是 Python 估算 ——
   > 之前用 Python 估过一次，两边口径不同（Python 那边按脚本里**每个出现过的字**
   > 统计，模块这边按**表 key 在脚本字符集里命中数**统计），数字对不上。
   > 结论一致（都在 50~73% 区间被拒），但文档只保留模块的实测值。

   - 阈值 = `max(500, 表长 × 9/10)`；1,004 对的表要 **903** 个 key 命中才算过。
   - 靠 90% 线最近的一次拒绝是 **731（72.8%）**，离阈值还差 172 个 key（17 个百分点）。
   - **两个作品永远不会互相误触发**：樱花脚本对 Marguerite 表只有 51.8%。
   - `patchdata/Marguerite.hcb` 与 `Marguerite.bch` 并排躺着，而
     `find_script()` 优先 `*.bch` —— 引擎真正加载哪个，判据就按哪个算。
     （实测：同一张 JSON 表，对 `Marguerite.bch` 是 99.8% 启用，对
     `patchdata/Marguerite.hcb` 是 70.8% 拒绝 —— 这个对比本身就证明了
     「按实际加载脚本判定」是必要的。）

本机全盘实测（樱花版的判据可分辨性，二进制来源）：
（这一组是最早用 Python 扫全目录候选二进制得到的，保留作历史记录；
  最终判据已收窄为**只读两个固定文件名**，见上。）

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

**来源与作品不是绑定的**：判据只看「表 key 是否出现在本游戏脚本里」，不看文件名，
所以将来若换个游戏用了同类方案（例如某个官中版改用 `uif_config.json` 分发），
不加一行代码就会自动生效。

资源名不会被误伤（实测）：表里所有 key 都是**汉字**，而资源名是纯 ASCII。
官方脚本里 7,267 条纯 ASCII 字符串、3,764 条含路径分隔符的字符串（`voice/01000060`
`se/001` …），被还原改动的**都是 0 条**。

为什么「写出方向」也必须做（`&#NNNNN;` 的来历）
----------------------------------------------
两个游戏的 `value` 都 100% 无 JIS 码位，所以一旦把真字直接交给
`SHIFT_JIS.encode`，存进存档的就是 `&#NNNNN;` 字面量。

依赖
----
- `encoding_rs`（已是 rfvp 的直接依赖，用于 `SHIFT_JIS` 解码）
- `FVP_BASE_PATH` 环境变量（已有，`apply_text_patch.py` 就用它）

整个模块在 `#[cfg(not(feature = "no_std"))]` 下（Android 构建不是 no_std）。

验证方法（重要，别再踩坑）
----------------------------
`rustc --emit=metadata <单个文件>` **查不出借用/类型错误**：缺依赖会先报一堆
`E0433`（名字解析失败），编译器直接在该阶段停住，**根本走不到借用检查**。
第一版就是这样漏掉了一个 `E0716`（`decode(&[lead, trail])` 的临时数组在语句
结束即释放，而返回值还借着它），结果 CI 构建失败。

可靠的本地验证方式（本仓库已实测可行）：建一个最小 crate，只依赖
`encoding_rs` + `log`，把生成出来的 `jis_tunnel.rs` 当 `src/lib.rs` 拷进去，
然后 `cargo build --offline`。这样借用检查会真跑，能直接复现/排除 E0716 一类问题：

    mkdir -p /tmp/jtcheck/src && cd /tmp/jtcheck
    printf '[package]\nname="jtcheck"\nversion="0.1.0"\nedition="2021"\n\n[dependencies]\nencoding_rs="=0.8.33"\nlog="=0.4.34"\n' > Cargo.toml
    python3 -c "import re,pathlib; \\
      s=pathlib.Path('apply_fvp_jis_tunnel_patch.py').read_text(encoding='utf-8'); \\
      b=re.search(r\\\"JIS_TUNNEL_RS = r'''(.*?)'''\\\", s, re.S).group(1); \\
      pathlib.Path('src/lib.rs').write_text(b.replace('crate::jis_tunnel::','crate::'), encoding='utf-8')"
    cargo build --offline

（注意：整个 rfvp workspace 在本机 cargo 1.75 上编不了 —— `resolver = "3"` 与
edition2024 依赖都需要更新的 cargo，所以只能这样单模块验证。）

**只编译还不够：还要真跑。** 本轮加 `uif_config.json` 来源时，真编译器 + 真运行
一共抓到两个只看代码发现不了的 bug：

1. `source != target.len()` —— 应该是 `source.len() != target.len()`（`E0277`）。
2. `std::str::from_utf8(&data[i..i + 4])` 取一个 UTF-8 字符 —— 4 字节窗口**几乎
   总会切在下一个字符中间**，于是整个切片不是合法 UTF-8，解析器对所有真实文件
   都返回 `None`（表现为「表读不出来」，静态看代码完全看不出）。改成按首字节
   算长度、只取恰好一个字符。

**第三个 bug（真机才暴露）：注入点选在了「数据」而不是「用途」上。**

第一版把还原挂在 `script/parser.rs` 的 `decode_string_bytes()` —— 那是**所有**脚本
字符串的出口，**资源名**也走它。HappyMarguerite 的立绘文件名是日文汉字：

    CHR_練_基_道着竹刀L  ->  CHR_觑_基_道着竹刀L
    CHR_撫子             ->  CHR_垫子
    CHR_爺               ->  CHR_练
    CHR_悠綺             ->  CHR_悠凿
    CHR_響               ->  CHR_晚

于是 `graph_load` 全部 `file not found`，**立绘与贴图整片消失**（文字却正常）。
樱花官中版完全没暴露，因为它资源名是**纯 ASCII**（实测 6,082 个标识符里含
非 ASCII 的 **0 个**）——「表 key 全是汉字所以碰不到资源名」这个结论
**只在纯 ASCII 资源名时成立**。

修法：把还原挪到**「文字 → 屏幕」这一步**（`text_manager.rs::set_text_content`），
而不是「字符串解码」这一步 —— 资源名/文件名物理上不经过它
（对齐 PC 端 UIF 在 `DrawTextA` 这个**绘制** API 上替换）。

> **口诀：替身字还原要挂在「文字」上，不要挂在「数据」上。**

第三个 bug 的教训还有一个方法论点：前两个 bug 是「代码写错」，这个 bug 是
**「位置选错」** —— 光靠单测/编译/静态检查**永远发现不了**，只有真机能暴露
（或者像本次这样，先看清调用链上「谁消费这个数据」再决定挂在哪）。
拔调用链时一定要问：**这个出口上还流过什么非文本数据？**

    # gate: 表能不能被接纳
    FVP_BASE_PATH=<游戏目录> ./jtprobe gate
    # charset: 打印 entries / hits / needed / 判定，换目录即可做判别力测试
    PROBE_DIR=<游戏目录> ./jtprobe charset

实测输出（本机四组判别 + 两个真作品）：

    Sakura.dll         entries= 1206 hits= 1206 needed= 1085 -> ACCEPT   # 樱花官中
    uif_config.json    entries= 1004 hits= 1002 needed=  903 -> ACCEPT   # Marguerite.bch
    uif_config.json    entries= 1004 hits=  731 needed=  903 -> reject   # Hoshimemo_HD.hcb
    uif_config.json    entries= 1004 hits=  711 needed=  903 -> reject   # Marguerite.hcb
    uif_config.json    entries= 1004 hits=  520 needed=  903 -> reject   # Sakura.hcb
"""
import sys
from pathlib import Path

LIB_RS = "crates/rfvp/src/lib.rs"
TEXT_RS = "crates/rfvp/src/subsystem/resources/text_manager.rs"
HIST_RS = "crates/rfvp/src/subsystem/components/syscalls/history.rs"
SAVE_RS = "crates/rfvp/src/subsystem/resources/save_manager.rs"
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
//! Detection is self-validating, and deliberately narrow: only two fixed file
//! names in the game directory are ever considered, and a table is accepted
//! only if it covers a large majority of the characters the script actually
//! uses. An unrelated file therefore cannot hijack the text.
//!
//! | # | source | layout | used by |
//! |---|---|---|---|
//! | 1 | `Sakura.dll` | array of consecutive UTF-16LE `(key, value)` pairs | 《樱花，萌放。》official zh-Hans |
//! | 2 | `uif_config.json` | JSON `"type": "replace_chars"` rule, `source_chars` / `target_chars` | UniversalInjectorFramework fan patches (e.g. HappyMarguerite_CHS) |
//!
//! Reference measurement — every row below is what the Rust module itself
//! reports on this device, not a Python approximation:
//!
//! | script (`find_script`) | table | keys found in script |
//! |---|---|---|
//! | Sakura.hcb, official zh-Hans | its own `Sakura.dll` | 1,206 / 1,206 = 100% |
//! | Marguerite.bch, HappyMarguerite_CHS | its own `uif_config.json` | 1,002 / 1,004 = 99.8% |
//! | Marguerite.hcb, 2008 Japanese original | that `uif_config.json` | 711 / 1,004 = 70.8% |
//! | Hoshimemo_HD.hcb (both 星空 memorias) | that `uif_config.json` | 731 / 1,004 = 72.8% |
//! | Sakura.hcb (Japanese original) | that `uif_config.json` | 520 / 1,004 = 51.8% |
//!
//! Accept threshold is `max(500, len * 9 / 10)` = 903 keys for a 1,004-entry
//! table. The tightest rejection is 731 (72.8%), i.e. 172 keys — 17 points —
//! below the bar, and the two titles never recognise each other's table.

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// YukiHub patch marker — CI greps the built .so for this string.
#[no_mangle]
pub static YHFVP_JIS_TUNNEL_MARKER: [u8; 19] = *b"YHFVP-JISTUNNEL-1.0";

/// A run shorter than this is never considered a table (binary layout).
const MIN_PAIRS: usize = 64;
/// A JSON `replace_chars` rule shorter than this is never considered a table.
/// Genuine UIF patches are hand-written and cover the whole Simplified-Chinese
/// repertoire; the two on this device both have 1,004 pairs.
const MIN_UIF_PAIRS: usize = 256;
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

    // Two fixed file names are considered, in order. Nothing else in the
    // directory is ever read.
    //
    // This began as a scan over every `*.dll` / `*.exe` in the directory (each
    // candidate validated against the script by the score below), which is what
    // a generic detector would do. It was narrowed on purpose:
    //   - FVP is a small ecosystem and a surrogate scheme in two files is what
    //     actually occurs in the wild, so there is nothing else to discover;
    //   - a single, explicitly named, self-validating candidate cannot suffer a
    //     "false enable" from an unrelated binary that happens to be lying in
    //     the game directory;
    //   - one small read, once per process.
    //
    // The score below keeps the decision safe, so even if one of these names
    // turned up in some other game's directory it would not misfire.
    for (name, kind) in [
        ("Sakura.dll", TableKind::Binary),
        ("uif_config.json", TableKind::UifJson),
    ] {
        let Ok(data) = std::fs::read(dir.join(name)) else {
            continue;
        };
        let Some(map) = extract_table(kind, &data) else {
            continue;
        };
        let hits = map.keys().filter(|k| used.contains(*k)).count();
        let needed = MIN_HITS.max(map.len() * MIN_RATE_NUM / MIN_RATE_DEN);
        if hits < needed {
            log::info!(
                "YukiHub jis-tunnel: {} table has {} entries, {} hit(s) < {} needed, ignored",
                name,
                map.len(),
                hits,
                needed
            );
            continue;
        }
        log::info!(
            "YukiHub jis-tunnel active: {} ({} entries, {} hits) = YHFVP-JISTUNNEL-1.0",
            name,
            map.len(),
            hits
        );
        return Some(map);
    }
    None
}

/// Which layout [`extract_table`] should parse.
#[derive(Clone, Copy)]
enum TableKind {
    /// Consecutive UTF-16LE `(key, value)` pairs, as embedded in `Sakura.dll`.
    Binary,
    /// A UniversalInjectorFramework `uif_config.json`, whose
    /// `"type": "replace_chars"` rule carries the table as two parallel strings.
    UifJson,
}

/// Extract the surrogate table from `data`, or `None` if this file does not
/// look like one.
fn extract_table(kind: TableKind, data: &[u8]) -> Option<HashMap<char, char>> {
    match kind {
        TableKind::Binary => extract_binary_table(data),
        TableKind::UifJson => extract_uif_table(data),
    }
}

/// Parse the two parallel strings out of a UIF `replace_chars` rule.
///
/// Layout, byte for byte:
///
/// ```text
///     "type": "replace_chars",
///     "source_chars": "<the characters to look for>",
///     "target_chars": "<the characters to draw instead>",
/// ```
///
/// The strings are **UTF-8** and, in the real patches, contain no JSON escape
/// sequence at all (verified: zero `\` bytes and exactly one occurrence of each
/// key), so the parser is a plain byte scan that refuses anything containing a
/// backslash. That keeps it tiny while still failing closed on JSON it does not
/// understand: the length check makes the "two parallel strings" assumption
/// explicit rather than silent. (A backslash or an odd `source`/`target` length
/// simply yields `None`, i.e. "not a table", and the engine stays stock.)
///
/// `type` must be `replace_chars`: the other UIF rule kinds (`replace_str`,
/// `replace_raw_bytes`) do not carry a character table, and treating one as if
/// it did would corrupt text. This is checked *before* the two strings are read,
/// so a file where `source_chars` precedes `type` is rejected rather than
/// mis-parsed.
fn extract_uif_table(data: &[u8]) -> Option<HashMap<char, char>> {
    fn find_from(hay: &[u8], needle: &[u8], from: usize) -> Option<usize> {
        memchr(hay, needle, from)
    }
    fn memchr(hay: &[u8], needle: &[u8], from: usize) -> Option<usize> {
        if needle.is_empty() || hay.len() < needle.len() {
            return None;
        }
        let last = hay.len() - needle.len();
        let mut i = from;
        while i <= last {
            if &hay[i..i + needle.len()] == needle {
                return Some(i);
            }
            i += 1;
        }
        None
    }

    const TYPE_KEY: &[u8] = b"\"type\"";
    const SOURCE_KEY: &[u8] = b"\"source_chars\"";
    const TARGET_KEY: &[u8] = b"\"target_chars\"";

    let type_at = find_from(data, TYPE_KEY, 0)?;
    let source_at = find_from(data, SOURCE_KEY, 0)?;
    let target_at = find_from(data, TARGET_KEY, 0)?;
    // The rule has to be laid out `type`, `source_chars`, `target_chars`.
    if !(type_at < source_at && source_at < target_at) {
        return None;
    }
    if !data[type_at..source_at].windows(13).any(|w| w == b"replace_chars") {
        return None;
    }

    let source = parse_json_string(data, source_at + SOURCE_KEY.len())?;
    let target = parse_json_string(data, target_at + TARGET_KEY.len())?;
    if source.len() != target.len() || source.len() < MIN_UIF_PAIRS {
        return None;
    }

    let mut map = HashMap::with_capacity(source.len().min(4096));
    for row in 0..source.len() {
        map.entry(source[row]).or_insert(target[row]);
    }
    if map.len() >= MIN_UIF_PAIRS {
        Some(map)
    } else {
        None
    }
}

/// Read one JSON string starting at the first `"` at or after `from`.
///
/// Multi-byte UTF-8 passes through untouched. A backslash makes the whole parse
/// fail (`None`): the real files have none, and silently mishandling an escape
/// would be worse than doing nothing.
fn parse_json_string(data: &[u8], from: usize) -> Option<Vec<char>> {
    let open = data[from..].iter().position(|&b| b == b'"')? + from;
    let mut out = Vec::new();
    let mut i = open + 1;
    while i < data.len() {
        match data[i] {
            b'"' => return Some(out),
            b'\\' => return None,
            b if b < 0x80 => {
                out.push(b as char);
                i += 1;
            }
            _ => {
                // Decode exactly one UTF-8 scalar by its lead byte.
                //
                // `std::str::from_utf8(&data[i..i + 4])` looks tempting but is
                // wrong: the 4-byte window usually ends *inside* the next
                // character, so the slice is not valid UTF-8 and the whole
                // parse would be rejected. (Caught by the real-compile probe.)
                let b0 = data[i];
                let need = if b0 >= 0xF0 {
                    4
                } else if b0 >= 0xE0 {
                    3
                } else if b0 >= 0xC0 {
                    2
                } else {
                    return None; // stray continuation byte
                };
                if i + need > data.len() {
                    return None;
                }
                let s = std::str::from_utf8(&data[i..i + need]).ok()?;
                let mut it = s.chars();
                let c = it.next()?;
                if it.next().is_some() || c.len_utf8() != need {
                    return None;
                }
                out.push(c);
                i += need;
            }
        }
    }
    None
}

/// Extract the surrogate table from a binary blob shaped as consecutive
/// UTF-16LE `(key, value)` pairs. Returns the longest plausible run.
///
/// Both halves are required to be `>= 0x2000` while scanning (this is what
/// separates the table from surrounding code/data), then keys are restricted to
/// CJK ideographs and values to the CJK + CJK-punctuation block.
fn extract_binary_table(data: &[u8]) -> Option<HashMap<char, char>> {
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
        // The run *is* the table: stop at the first entry that is not a
        // `(CJK key, character)` pair instead of skipping it.
        //
        // Skipping (`continue`) would silently drop the first entry — `禳 -> —`,
        // whose value U+2014 sits *below* the CJK blocks — and, because the
        // original code filtered every entry against a `0x3000..=0x9FFF` value
        // range, also everything after it. `禳` occurs 9,211 times in the
        // official script, so that mistake is very visible in game.
        let key_is_kanji = (0x4E00..=0x9FFF).contains(&a) || (0x3400..=0x4DBF).contains(&a);
        if !key_is_kanji || b < 0x2000 {
            break;
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

/// Inverse of [`restore`]: put the surrogate kanji back.
///
/// Needed on the way *out*, i.e. whenever the engine writes text into a
/// Shift-JIS container — save files are the only such case. Every value in the
/// table has **no JIS X 0208 code point**, so `encoding_rs::SHIFT_JIS.encode`
/// emits a numeric character reference (`&#NNNNN;`, per the WHATWG encoding
/// standard) for it, which the engine then never turns back into a character:
/// the load screen shows a literal `&#32440;`.
///
/// The mapping is a bijection (all 1,206 keys and all 1,206 values are
/// distinct), so the reverse lookup is unambiguous.
pub fn to_surrogate(s: &str) -> Cow<'_, str> {
    let rev = reverse_table();
    if !s.chars().any(|c| rev.contains_key(&c)) {
        return Cow::Borrowed(s);
    }
    Cow::Owned(s.chars().map(|c| rev.get(&c).copied().unwrap_or(c)).collect())
}

fn reverse_table() -> &'static HashMap<char, char> {
    static REVERSE: OnceLock<HashMap<char, char>> = OnceLock::new();
    REVERSE.get_or_init(|| table().iter().map(|(k, v)| (*v, *k)).collect())
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
                // Bind the bytes first: `decode` returns a `Cow` that borrows the
                // input, so an inline `&[lead, trail]` temporary would be dropped
                // at the end of the statement (E0716).
                let pair = [lead, trail];
                let (decoded, _, had_err) = encoding_rs::SHIFT_JIS.decode(&pair);
                if !had_err {
                    let mut chars = decoded.chars();
                    // A two-byte Shift-JIS sequence must yield exactly one char.
                    if let (Some(c), None) = (chars.next(), chars.next()) {
                        set.insert(c);
                        i += 2;
                        continue;
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
// YukiHub patch: SJIS-tunnel surrogate restoration (Chinese releases store their
// text as Shift-JIS with surrogate kanji; see the module docs).
#[cfg(not(feature = "no_std"))]
pub mod jis_tunnel;
"""
# ------------------------------------------------- text_manager.rs（注入点 1）
#
# `set_text_content` 是**显示文字**的唯一入口 —— 全 workspace 只有 2 个调用点，
# 都在 `syscalls/text.rs` 的 `text_print` 里（`Variant::String` 与
# `Variant::ConstString` 两个分支）。
#
# 为什么不是 `script/parser.rs` 的 `decode_string_bytes()`：
#   那是**所有**脚本字符串的出口，**资源名**也走它。HappyMarguerite 的立绘
#   文件名是日文汉字（`CHR_練_…` / `CHR_撫子` / `CHR_爺`），第一次就把它们改成了
#   `CHR_觑_…` / `CHR_垫子` / `CHR_练` → `graph_load` 全部 file not found
#   → **立绘和贴图整片消失**。樱花官中版资源名是纯 ASCII 才没暴露这个问题。
#   放在这里，资源名/文件名**永远不会被碰**（这也对齐 PC 端 UIF 在 DrawTextA 上替换）。
#
# 注意 text_manager.rs 这个 impl 块**没有 cfg 属性**，所以注入点不能带
# `#[cfg(not(feature = "no_std"))]`（会让属性贴到 `let` 语句上报错）。
# `jis_tunnel` 模块与 `String` 在两种构建下都可用，无需门控。
TEXT_OLD = """    pub fn set_text_content(&mut self, id: i32, content_text: &str) {
        self.items[id as usize].parse_content_text(content_text);
    }"""

TEXT_NEW = """    pub fn set_text_content(&mut self, id: i32, content_text: &str) {
        // YukiHub patch: restore SJIS-tunnel surrogate kanji here, on the *text*
        // path. Doing it in the script parser instead would also rewrite the
        // resource / file names the script carries, which silently breaks every
        // `graph_load` for titles whose asset names use kanji.
        let content_text = crate::jis_tunnel::restore(content_text);
        self.items[id as usize].parse_content_text(&content_text);
    }"""

# ------------------------------------------------------- history.rs（注入点 2）
#
# `HistorySet(Name/Content, 文字)` 是**回首过去（backlog）**的文字入口。
# 这两个分支只拿得到对话文字与角色名，**拿不到任何资源名**，所以挂在这儿安全。
#
# 为什么必须有：显示路径（set_text_content）还原了，但 backlog 是脚本**另存**
# 的一份文字 —— 两个游戏都用 `HistorySet`（各 1 处），不补这里的话，
# 历史界面会显示载体字。
#
# 顺带给引擎留个「出口也还原」的兜底：脚本读 HistoryGet 拿到的也是真字，
# 避免脚本自己拿去做二次显示时又是载体字。
HIST_NAME_OLD = """        Ok(HistoryFunction::Name) => match value.as_string() {
            Some(value) => {
                game_data.history_manager.set_name(value.to_owned());
            }"""

HIST_NAME_NEW = """        Ok(HistoryFunction::Name) => match value.as_string() {
            Some(value) => {
                // YukiHub patch: backlog text is a *second* copy of the line, so
                // it needs the same restoration the on-screen text gets.
                let value = crate::jis_tunnel::restore(value);
                game_data.history_manager.set_name(value.to_owned());
            }"""

HIST_CONTENT_OLD = """        Ok(HistoryFunction::Content) => match value.as_string() {
            Some(value) => {
                game_data.history_manager.set_content(value.to_owned());
            }"""

HIST_CONTENT_NEW = """        Ok(HistoryFunction::Content) => match value.as_string() {
            Some(value) => {
                // YukiHub patch: see the Name branch above — backlog content.
                let value = crate::jis_tunnel::restore(value);
                game_data.history_manager.set_content(value.to_owned());
            }"""


# ------------------------------------------------------ save_manager.rs
#
# 存档是引擎里**唯一**把文本编码回 Shift-JIS 的地方（全 workspace 只有这一处
# `SHIFT_JIS.encode`，其余都在 assembler / lua2hcb_compiler 这类 PC 端工具里）。
# 必须先把真汉字换回替身字，否则 encoding_rs 会对每个无 SJIS 码位的字输出
# `&#NNNNN;`（WHATWG 数字字符引用），读档时就显示成字面量。
#
# 注意：save_manager.rs 本身**没有任何 cfg 属性**，所以注入点不能带
# `#[cfg(not(feature = "no_std"))]` —— 那会让属性贴到 `let` 语句上而报错。
# 好在 `jis_tunnel` 模块与 `String` 在两种构建下都可用，无需门控。
SAVE_OLD = """            match nls {
                Nls::ShiftJIS => {
                    let (cow, _, _) = encoding_rs::SHIFT_JIS.encode(s);
                    cow.into_owned()
                }"""

SAVE_NEW = """            match nls {
                Nls::ShiftJIS => {
                    // YukiHub patch: put the SJIS-tunnel surrogate kanji back
                    // before encoding. The real characters have no JIS X 0208
                    // code point, so encoding them directly would emit
                    // `&#NNNNN;` character references into the save file.
                    #[cfg(not(feature = "no_std"))]
                    let s = crate::jis_tunnel::to_surrogate(s);
                    let (cow, _, _) = encoding_rs::SHIFT_JIS.encode(&s);
                    cow.into_owned()
                }"""

# `decode_bytes` 是存档**读取**的唯一出口（`load_from_file` / `load_from_mem`
# 里 6 个调用点全走它）。读出来的标题/场景名/剧本预览又带回了替身字，必须在
# 这里再 `restore()` 一次 —— 否则存档界面显示「寅却辉辉地握住了我的手」。
#
# 存档文件本身**不需要改**：读进来是替身字、显示时还原；写出去时再换回替身字。
# 这样格式与 PC 版完全一致，存档还能拿到 PC 上用。
SAVE_DECODE_OLD = """            Nls::ShiftJIS => encoding_rs::SHIFT_JIS.decode(bytes).0.to_string(),"""

SAVE_DECODE_NEW = """            Nls::ShiftJIS => {
                // YukiHub patch: mirror of `to_surrogate` — the bytes on disk
                // still hold the surrogate kanji, so restore them here.
                let decoded = encoding_rs::SHIFT_JIS.decode(bytes).0;
                #[cfg(not(feature = "no_std"))]
                let decoded = crate::jis_tunnel::restore(&decoded);
                decoded.to_string()
            }"""



def apply_anchors(text: str, rel: str, pairs) -> "tuple[int, str]":
    for name, old, new in pairs:
        n = text.count(old)
        if n != 1:
            print(f"[FAIL] {rel}: 锚点 {name} 命中 {n} 次（期望 1）—— 上游源码已变动")
            return 1, text
        text = text.replace(old, new, 1)
    return 0, text


def main() -> int:
    for rel in (LIB_RS, TEXT_RS, HIST_RS, SAVE_RS):
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

    # text_manager.rs（注入点 1：显示文字）
    txt_path = Path(TEXT_RS)
    txt_text = txt_path.read_text(encoding="utf-8")
    if "crate::jis_tunnel::restore" in txt_text:
        print(f"[OK]   {TEXT_RS}: 已应用（幂等）")
    else:
        rc, txt_text = apply_anchors(
            txt_text, TEXT_RS, [("set_text_content 入口", TEXT_OLD, TEXT_NEW)]
        )
        if rc != 0:
            return rc
        txt_path.write_text(txt_text, encoding="utf-8")
        print(f"[OK]   {TEXT_RS}: 替身字还原已接入 set_text_content（文字路径）")

    # history.rs（注入点 2：回首过去 / backlog 的文字，是脚本另存的一份）
    hist_path = Path(HIST_RS)
    hist_text = hist_path.read_text(encoding="utf-8")
    if "crate::jis_tunnel::restore" in hist_text:
        print(f"[OK]   {HIST_RS}: 已应用（幂等）")
    else:
        rc, hist_text = apply_anchors(
            hist_text,
            HIST_RS,
            [
                ("HistorySet(Name) 入口", HIST_NAME_OLD, HIST_NAME_NEW),
                ("HistorySet(Content) 入口", HIST_CONTENT_OLD, HIST_CONTENT_NEW),
            ],
        )
        if rc != 0:
            return rc
        hist_path.write_text(hist_text, encoding="utf-8")
        print(f"[OK]   {HIST_RS}: backlog 的 Name/Content 两个入口已接入")

    # save_manager.rs（写入方向 + 读取方向，同一文件）
    sav_path = Path(SAVE_RS)
    sav_text = sav_path.read_text(encoding="utf-8")
    if "crate::jis_tunnel::to_surrogate" in sav_text and "crate::jis_tunnel::restore" in sav_text:
        print(f"[OK]   {SAVE_RS}: 已应用（幂等）")
    else:
        rc, sav_text = apply_anchors(
            sav_text,
            SAVE_RS,
            [
                ("存档 decode_bytes（读）", SAVE_DECODE_OLD, SAVE_DECODE_NEW),
                ("存档 ShiftJIS encode（写）", SAVE_OLD, SAVE_NEW),
            ],
        )
        if rc != 0:
            return rc
        sav_path.write_text(sav_text, encoding="utf-8")
        print(f"[OK]   {SAVE_RS}: 存档读/写两个方向都已接入")

    print("YHFVP-JISTUNNEL-1.0 全部锚点应用成功 ✓（可重复执行，幂等）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
