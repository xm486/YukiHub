# rfvp 文字缩放补丁（YukiHub 定制版 librfvp.so 构建套件）

> 📘 **全部引擎补丁的总览（含根因分析、证据链、排查方法）见
> [`ENGINE_PATCHES.md`](./ENGINE_PATCHES.md)** —— 本文只讲"文字缩放"这一个补丁。

给 rfvp 引擎加一个 **全局字号系数**：脚本里写死的 TextSize（比如 24px）在渲染时整体
乘一个系数（1.25 / 1.5 / 1.75 / 2.0），文字变大、行距换行自动跟随，UI 布局不动、
画面不拉伸不裁剪。系数由 YukiHub 在引擎设置里选择，通过 JNI 在引擎创建后下发。

## 文件

| 文件 | 作用 |
|---|---|
| `apply_text_scale_patch.py` | 补丁器。锚文本精确匹配 + 严格校验 + 幂等，任何锚点不匹配会非零退出（构建会失败，不会静默漏打） |
| `.github/workflows/build-librfvp.yml` | GitHub Actions，手动触发（workflow_dispatch），产出 `librfvp.so` 工件 |

## 引擎改动一览（补丁自动完成）

1. `text_manager.rs`：`TextItem`/`TextManager` 增加 `text_scale` 字段与 `set_text_scale()`；
   布局处 `main_size`/`ruby_size` 乘系数（先于一切派生量：draw size、步进测量、行高、换行）。
2. `app.rs`：`App::set_text_scale()`（与 `set_text_hidpi_enabled` 同模式）。
3. `android_host.rs`：新增 C ABI `rfvp_android_set_text_scale(handle, f32)`——
   旧宿主不调用完全无影响（桥按可选符号解析）。

注意事项：
- gaiji（外字位图）的槽位键**故意不缩放**，位图字不会被拉糊，但外字不随系数变大。
- 系数过大时文字可能超出对话框边界（这是脚本布局固有限制），建议从 1.25 试起。
- 非 1.0 系数下文字光栅化尺寸变大，内存占用略增（引擎本身有 MAX_HIDPI 上限保护）。

## 使用步骤（就在本仓库内）

1. 把 `rfvp-build/`（本目录）和 `.github/workflows/build-librfvp.yml` 一起提交并推送到 GitHub。
2. 仓库 Actions 页面 → **Build librfvp (YukiHub text-scale patch)** → **Run workflow**：
   - `rfvp_repo`：rfvp 源码仓库（默认 `xmoezzz/rfvp`；想锁版本就 fork 后填自己的 fork）
   - `rfvp_ref`：分支/tag（默认 `main`）
   - `android_api`：默认 24
3. 构建成功后到该次 run 的 **Artifacts** 下载 `librfvp-textscale-aarch64`
   （里面有编译好并验证过导出符号的 `librfvp.so`）。
4. 换进 YukiHub：用新的 `librfvp.so` 覆盖
   `YukiHub/app/src/main/jniLibs/arm64-v8a/librfvp.so`，重新打包 APK。
5. 用完即弃：确认没问题后直接删掉 `rfvp-build/` 和这个 workflow 即可。

> 已知事实：YukiHub 当前用的 Tyranor 预编译 `librfvp.so` 与 rfvp-main 上游 ABI 一致
> （12 个 `rfvp_android_*` 入口全部存在于上游源码），因此上游产物可直接替换；
> 新增的 `rfvp_android_set_text_scale` 只有装了配套 YukiHub 版本才会被调用。

## 换成新 so 后怎么用

YukiHub → 游戏详情 → 引擎设置（FVP）→ **文字大小**：1.0x（脚本原大）/ 1.25x / 1.5x / 1.75x / 2.0x，
下次启动游戏生效。旧 so 没有该符号时设置不生效也无害（桥只打 warning）。