/**
 * YukiHub 展厅 · 音乐厅播放器（M5-d）
 * =================================================
 * 「录入在 App，鉴赏在展厅」：本模块负责展厅侧的全部音乐鉴赏体验。
 *
 * 数据来源：`window.ExhibitionBridge.getMusicLibrary()`
 *   → 返回 { albums: [...], tracks: [...] }
 *   → 媒体走虚拟 origin：/music/audio/<id>、/music/pv/<id>、/music/cover/<id>
 *
 * 职责：
 *   ① 曲目浮层（点专辑墙 → 弹曲目列表 → 点曲目播放）
 *   ② 播放器（单例 Audio 元素，原生管线出声）
 *   ③ HUD 迷你播放条（SVG 图标 + 曲名）
 *   ④ PV 支持（大屏播视频，VideoTexture）
 *
 * ⚠️ 血泪教训（写代码前必读，均为真机实录）：
 *   1. HTML5 媒体的 play() 返回 promise——**每次 play/pause/src 切换都要走
 *      safePlay()**（先 pause 再 play + catch AbortError 并自动补播），
 *      否则连续切歌会互相打断（AbortError）且新歌不响。
 *   2. **不要把 <audio> 接进 WebAudio**（createMediaElementSource）——
 *      除非消费端（真频谱）已经就绪且 AudioContext 生命周期经过验证。
 *      接进去音频会完全改道 WebAudio 管线，Context suspended 时
 *      就是"UI 播放中、喇叭无声、零报错"。
 *   3. 切歌/切 PV 时**必须释放旧视频**（pause + src='' + load()），
 *      否则解码器不释放，切几首后 WebView 卡顿。
 *   4. UI 图标用**内联 SVG**，不用 emoji——ROM 渲染差异大且廉价感强。
 */
import * as THREE from './vendor/three.module.min.js';

export function createMusic(deps = {}) {
    const scene = deps.scene;
    const audio = deps.audio;             // 展厅音频（>100% 音量走它的主总线增益）
    const showHint = deps.showHint || function () { };
    const log = deps.log || function () { };

    /* ==================== 状态 ==================== */

    let albums = [];      // [{id,title,trackCount,cover}]
    let tracks = [];      // [{id,albumId,trackNo,title,artist,durationMs,audioUrl,hasPv,pvUrl}]
    let loaded = false;   // 是否已成功拉取过数据

    /** 当前播放 */
    let current = null;    // 当前曲目对象
    let playing = false;
    let shuffle = false;

    /** 播放列表（当前专辑的曲目数组 + 索引） */
    let queue = [];
    let queueIndex = -1;
    let queueReturnFocus = null;

    /* ==================== SVG 图标（内联，24×24 线性风格） ==================== */

    const SVG_PLAY = '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M8 5.5v13c0 .8.87 1.28 1.54.86l10.2-6.5a1 1 0 0 0 0-1.72L9.54 4.64A1 1 0 0 0 8 5.5z"/></svg>';
    const SVG_PAUSE = '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><rect x="6.5" y="5" width="3.6" height="14" rx="1.1"/><rect x="13.9" y="5" width="3.6" height="14" rx="1.1"/></svg>';
    const SVG_PREV = '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M7 6a1 1 0 0 1 2 0v12a1 1 0 0 1-2 0V6zm10.53.15c.62-.4 1.47.05 1.47.8v10.1c0 .75-.85 1.2-1.47.8l-7.06-5.05a.98.98 0 0 1 0-1.6L17.53 6.15z"/></svg>';
    const SVG_NEXT = '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M15 6a1 1 0 0 1 2 0v12a1 1 0 0 1-2 0V6zM6.47 6.15c-.62-.4-1.47.05-1.47.8v10.1c0 .75.85 1.2 1.47.8l7.06-5.05a.98.98 0 0 0 0-1.6L6.47 6.15z"/></svg>';
    const SVG_SHUFFLE = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 3h5v5"/><path d="M4 20L21 3"/><path d="M21 16v5h-5"/><path d="M15 15l6 6"/><path d="M4 4l5 5"/></svg>';
    const SVG_LIST = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4" cy="6" r=".7" fill="currentColor"/><circle cx="4" cy="12" r=".7" fill="currentColor"/><circle cx="4" cy="18" r=".7" fill="currentColor"/></svg>';
    /** 音量（用户拍板：不做调节，默认 200% 定死 —— 媒体 100% + 展厅总线增益 2×） */
    const VOL_FIXED = 2.0;
    /* ==================== DOM 构建 ==================== */

    let overlayEl = null;   // 曲目浮层
    let queueEl = null;     // 播放队列底部抽屉
    let barEl = null;       // HUD 播放条
    /**
     * ★ 浮层打开/关闭时刻（点击穿透防御，与 picker.js 的 openedAt 同源）：
     *
     * 打开侧（用户实测复现"点专辑直接选歌"）：
     *   3D 点专辑位 → openAlbum() 浮层弹出 → 浏览器把**同一手势**的合成 click
     *   派发给浮层上被按的坐标 → 若那里恰好是曲目行 → 直接开始播放！
     *   → 打开后 GUARD_MS 内，浮层内所有 click 一律忽略。
     *
     * 关闭侧：点曲目 → 浮层关闭 → 合成 click 落到 3D canvas →
     *   handleTap 可能命中碟机/专辑位 → toggle 把刚播的歌又暂停
     *   → 关闭后 GUARD_MS 内，3D 侧 tap（tapGuarded）忽略。
     */
    let overlayGuardAt = 0;
    const OVERLAY_GUARD_MS = 400;
    /** 浮层内 click 是否处于冷却（打开/关闭后短时间内都算"手势余波"） */
    function overlayGuarded() {
        return (performance.now() - overlayGuardAt) < OVERLAY_GUARD_MS;
    }

    function ensureDom() {
        if (overlayEl) return;

        // —— 曲目浮层 ——
        overlayEl = document.createElement('div');
        overlayEl.id = 'music-overlay';
        overlayEl.hidden = true;
        overlayEl.innerHTML =
            '<div id="mo-panel">' +
            '  <div id="mo-head">' +
            '    <div id="mo-title">曲目</div>' +
            '    <div id="mo-close">关闭</div>' +
            '  </div>' +
            '  <div id="mo-sub"></div>' +
            '  <div id="mo-list"></div>' +
            '</div>';
        document.body.appendChild(overlayEl);

        // ★ 打开侧穿透防御：冷却期内的浮层内 click 全部忽略（含关闭按钮/行/遮罩）
        const guardClick = (fn) => (e) => {
            if (overlayGuarded()) return;
            fn(e);
        };
        overlayEl.querySelector('#mo-close').addEventListener('click', guardClick(() => closeOverlay()));
        overlayEl.addEventListener('click', (e) => {
            if (e.target === overlayEl && !overlayGuarded()) closeOverlay();
        });

        // —— 播放队列底部抽屉 ——
        queueEl = document.createElement('div');
        queueEl.id = 'music-queue';
        queueEl.hidden = true;
        queueEl.innerHTML =
            '<div id="mq-panel">' +
            '  <div id="mq-grab"></div>' +
            '  <div id="mq-head"><div><div id="mq-title">播放队列</div><div id="mq-sub"></div></div>' +
            '    <button id="mq-close" type="button" aria-label="关闭">×</button></div>' +
            '  <div id="mq-now"></div>' +
            '  <div id="mq-next-title">接下来播放</div>' +
            '  <div id="mq-list"></div>' +
            '</div>';
        document.body.appendChild(queueEl);
        queueEl.querySelector('#mq-close').addEventListener('click', closeQueue);
        queueEl.addEventListener('click', (e) => {
            if (e.target === queueEl) closeQueue();
        });

        // —— HUD 播放条 ——
        barEl = document.createElement('div');
        barEl.id = 'music-bar';
        barEl.hidden = true;
        // 图标用内联 SVG（emoji 在不同 ROM 上渲染差异大，且廉价感强）
        barEl.innerHTML =
            '<div id="mb-controls">' +
            '<button class="mb-btn" id="mb-prev" title="上一首">' + SVG_PREV + '</button>' +
            '<button class="mb-btn mb-play" id="mb-play" title="播放/暂停">' + SVG_PLAY + '</button>' +
            '<button class="mb-btn" id="mb-next" title="下一首">' + SVG_NEXT + '</button>' +
            '<div id="mb-info">' +
            '  <div id="mb-title"></div>' +
            '  <div id="mb-sub"></div>' +
            '</div>' +
            '<div id="mb-pv" hidden>PV</div>' +
            '<button class="mb-btn mb-queue" id="mb-queue" title="播放队列" aria-label="播放队列">' + SVG_LIST + '</button>' +
            '<button class="mb-btn mb-shuffle" id="mb-shuffle" title="随机播放">' + SVG_SHUFFLE + '</button>' +
            '</div>' +
            '<div id="mb-timeline">' +
            '  <span id="mb-time-current">0:00</span>' +
            '  <input id="mb-seek" type="range" min="0" max="1000" step="1" value="0" aria-label="播放进度">' +
            '  <span id="mb-time-total">0:00</span>' +
            '</div>';
        document.body.appendChild(barEl);

        // ★ 进度条：输入中只预览时间，松手（change）才真正 seek，避免拖动时疯狂跳转
        const seekInput = barEl.querySelector('#mb-seek');
        seekInput.addEventListener('input', () => onSeekInput());
        seekInput.addEventListener('change', () => commitSeek());
        // 拖动进度条时不要触发播放条的"点一下展开/收起"逻辑
        ['pointerdown', 'touchstart'].forEach(t => seekInput.addEventListener(t, (e) => {
            e.stopPropagation();
            wakeBar();
        }, { passive: true }));
        ['pointerup', 'touchend', 'click'].forEach(t => seekInput.addEventListener(t, (e) => {
            e.stopPropagation();
        }, { passive: true }));

        // ★ 收起态：任何点击只负责"展开"，不触发功能（避免误暂停）
        const ifExpanded = (fn) => (e) => {
            if (barEl.classList.contains('mb-min')) {
                e.stopPropagation();
                wakeBar();
                return;
            }
            wakeBar();
            fn();
        };
        barEl.querySelector('#mb-prev').addEventListener('click', ifExpanded(() => prev()));
        barEl.querySelector('#mb-next').addEventListener('click', ifExpanded(() => next()));
        barEl.querySelector('#mb-play').addEventListener('click', ifExpanded(() => toggle()));
        barEl.querySelector('#mb-queue').addEventListener('click', ifExpanded(() => openQueue()));
        barEl.querySelector('#mb-shuffle').addEventListener('click', ifExpanded(() => {
            shuffle = !shuffle;
            barEl.querySelector('#mb-shuffle').classList.toggle('on', shuffle);
            showHint(shuffle ? '随机播放：开' : '随机播放：关');
        }));
        // 收起态点条身任意处也展开
        barEl.addEventListener('click', () => wakeBar());
    }

    /**
     * 音量（用户拍板）：
     *   · 媒体元素（音乐/PV）volume = 100%（HTML5 上限）；
     *   · 展厅 WebAudio 主总线 setVolumeBoost(2.0) → 环境音/脚步/交互音全部 2×；
     *   · 不做档位调节（调节 UI 在低端 WebView 上引发过卡死，砍掉）。
     */
    function setVolume(v) {
        mediaVolume = Math.min(v, 1.0);
        if (el) el.volume = mediaVolume;
        if (pvVideo) pvVideo.volume = mediaVolume;
        // 总线增益：媒体到顶后，把整个展厅一起放大（这正是"全局音量"语义）
        if (audio && audio.setVolumeBoost) {
            audio.setVolumeBoost(v);
        }
    }

    /* ==================== 样式注入（一次性） ==================== */

    function injectCss() {
        if (document.getElementById('music-css')) return;
        const css = document.createElement('style');
        css.id = 'music-css';
        css.textContent = `
/* —— 曲目浮层 —— */
#music-overlay{position:fixed;inset:0;z-index:60;display:flex;align-items:center;justify-content:center;
  background:rgba(5,7,12,.66);backdrop-filter:blur(3px);}
#music-overlay[hidden]{display:none;}
#mo-panel{width:min(560px,92vw);max-height:78vh;display:flex;flex-direction:column;
  border-radius:14px;border:1px solid rgba(150,190,255,.28);background:rgba(13,19,32,.96);
  box-shadow:0 18px 60px rgba(0,0,0,.55);overflow:hidden;}
#mo-head{display:flex;align-items:center;justify-content:space-between;
  padding:14px 16px 10px;border-bottom:1px solid rgba(150,190,255,.16);}
#mo-title{font-size:16px;font-weight:700;color:#EAF2FF;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
#mo-close{font-size:13px;color:#9FB3CC;padding:6px 10px;cursor:pointer;border-radius:8px;}
#mo-close:active{background:rgba(255,255,255,.08);}
#mo-sub{padding:8px 16px 4px;font-size:12px;color:#9FB3CC;}
#mo-list{overflow-y:auto;padding:6px 10px 12px;flex:1;}
.mo-row{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px;cursor:pointer;}
.mo-row:active{background:rgba(138,180,255,.12);}
.mo-row.now{background:rgba(138,180,255,.16);}
.mo-no{width:26px;text-align:center;font-size:11px;color:#9FB3CC;flex:0 0 auto;}
.mo-main{flex:1;min-width:0;}
.mo-name{font-size:14px;color:#EAF2FF;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mo-meta{font-size:11px;color:#9FB3CC;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mo-pv{font-size:10px;color:#8AB4FF;border:1px solid rgba(122,158,217,.55);border-radius:999px;padding:1px 7px;flex:0 0 auto;}
.mo-empty{padding:26px 16px;text-align:center;font-size:13px;color:#9FB3CC;line-height:2;}

/* —— 正在播放 / 接下来队列抽屉 —— */
#music-queue{position:fixed;inset:0;z-index:70;display:flex;align-items:center;justify-content:center;
  padding:14px 16px calc(14px + env(safe-area-inset-bottom,0px));
  background:rgba(3,6,12,.62);backdrop-filter:blur(4px);}
#music-queue[hidden]{display:none;}
#mq-panel{width:min(390px,calc(100vw - 48px));height:min(650px,86vh);max-height:calc(100vh - 28px);
  display:flex;flex-direction:column;padding:0 13px 10px;
  border-radius:22px;border:1px solid rgba(150,190,255,.24);
  background:linear-gradient(180deg,rgba(21,30,48,.99),rgba(9,14,24,.99));
  box-shadow:0 22px 70px rgba(0,0,0,.62);overflow:hidden;}
#mq-grab{width:38px;height:4px;border-radius:99px;background:rgba(210,225,245,.35);margin:10px auto 6px;flex:0 0 auto;}
#mq-head{display:flex;align-items:center;justify-content:space-between;padding:8px 2px 12px;flex:0 0 auto;}
#mq-title{font-size:16px;font-weight:700;color:#EEF4FF;}
#mq-sub{font-size:11px;color:#91A4BF;margin-top:3px;}
#mq-close{width:34px;height:34px;border:0;border-radius:50%;background:rgba(255,255,255,.07);color:#C9D6E6;font-size:22px;line-height:1;flex:0 0 auto;}
#mq-now{display:flex;align-items:center;gap:12px;padding:12px;border:1px solid rgba(138,180,255,.28);
  border-radius:14px;background:linear-gradient(110deg,rgba(74,112,176,.24),rgba(35,50,75,.18));flex:0 0 auto;}
.mq-cover{width:46px;height:46px;flex:0 0 auto;border-radius:9px;object-fit:cover;background:linear-gradient(145deg,#29466f,#111a2a);}
.mq-nowtext{flex:1;min-width:0;}
.mq-kicker{font-size:10px;letter-spacing:.08em;color:#8AB4FF;margin-bottom:4px;}
.mq-song{font-size:14px;font-weight:650;color:#F1F5FC;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mq-detail{font-size:11px;color:#9EADC1;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mq-playing{font-size:10px;color:#8FE0BD;flex:0 0 auto;}
#mq-next-title{font-size:12px;font-weight:600;color:#B9C8DC;padding:9px 3px 4px;flex:0 0 auto;}
#mq-list{flex:1 1 0;min-height:0;overflow-y:auto;overscroll-behavior:contain;padding-bottom:4px;}
.mq-row{display:flex;align-items:center;gap:8px;min-height:46px;padding:5px 6px;border-radius:9px;color:#DCE5F2;}
.mq-row:active{background:rgba(138,180,255,.12);}
.mq-row.current{background:linear-gradient(90deg,rgba(100,155,235,.25),rgba(100,155,235,.08));border:1px solid rgba(138,180,255,.5);}
.mq-row.current .mq-rowtitle{color:#F5F8FF;font-weight:650;}
.mq-row.current .mq-index::after{content:' 现在';font-size:9px;color:#8FE0BD;}
.mq-row.current .mq-rowpv{color:#8FE0BD;border-color:rgba(143,224,189,.5);}
.mq-index{width:22px;text-align:center;font-size:12px;color:#8193AB;flex:0 0 auto;}
.mq-row.current .mq-index{color:#8AB4FF;}
.mq-rowtext{flex:1;min-width:0;}
.mq-rowtitle{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mq-rowmeta{font-size:10px;color:#91A0B5;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.mq-rowpv{font-size:9px;color:#8AB4FF;border:1px solid rgba(122,158,217,.42);border-radius:99px;padding:2px 6px;}
.mq-empty{padding:16px 8px;color:#8393A8;font-size:12px;text-align:center;}

/* —— HUD 播放条 —— */
#music-bar{position:fixed;left:50%;bottom:calc(14px + env(safe-area-inset-bottom,0px));
  transform:translateX(-50%);z-index:55;display:flex;flex-direction:column;align-items:center;gap:2px;
  width:min(430px,calc(100vw - 24px));padding:5px 10px 6px;border-radius:22px;
  border:1px solid rgba(150,190,255,.22);
  background:rgba(10,15,26,.78);backdrop-filter:blur(8px);
  box-shadow:0 8px 32px rgba(0,0,0,.5), inset 0 1px 0 rgba(255,255,255,.06);
  -webkit-user-select:none;user-select:none;
  transform-origin:50% 100%;
  transition:padding .32s cubic-bezier(.4,0,.2,1), border-radius .32s ease,
             opacity .28s ease, box-shadow .3s ease;}
#mb-controls{display:flex;align-items:center;justify-content:center;gap:2px;width:100%;}
/* ★ 收起态（真·缩小）：整条收成一颗小胶囊，只留播放键
   ⚠️ 必须 width:auto —— 展开态是固定 430px，收起态若继承该宽度会变成一根长条 */
#music-bar.mb-min{width:auto;padding:0 4px;border-radius:15px;opacity:.86;
  box-shadow:0 4px 16px rgba(0,0,0,.42), inset 0 1px 0 rgba(255,255,255,.05);}
#music-bar.mb-min #mb-timeline{display:none;}
#music-bar.mb-min #mb-controls{width:auto;height:30px;}
#music-bar.mb-min .mb-btn{width:0;height:0;opacity:0;margin:0;pointer-events:none;}
#music-bar.mb-min .mb-play{width:24px;height:24px;opacity:1;margin:0 3px;
  border-width:0;background:transparent;pointer-events:auto;}
#music-bar.mb-min .mb-play svg{width:14px;height:14px;}
#music-bar.mb-min #mb-info{max-width:92px;min-width:0;margin:0 6px 0 2px;}
#music-bar.mb-min #mb-title{font-size:10px;}
#music-bar.mb-min #mb-sub{display:none;}
#music-bar.mb-min #mb-queue,#music-bar.mb-min #mb-shuffle{display:none;}
#music-bar.mb-min #mb-pv{font-size:8px;padding:1px 5px;}
/* 子元素跟着一起做尺寸过渡，避免"突变" */
.mb-btn,#mb-info,#mb-title,#mb-sub,#mb-pv,#mb-controls{
  transition:width .3s cubic-bezier(.4,0,.2,1), height .3s cubic-bezier(.4,0,.2,1),
             opacity .24s ease, font-size .3s ease, margin .3s ease, max-width .3s ease;}
#music-bar[hidden]{display:none;}
.mb-btn{display:flex;align-items:center;justify-content:center;flex:0 0 auto;
  width:38px;height:38px;margin:0;padding:0;border:0;border-radius:50%;
  background:transparent;color:#C9D6E6;cursor:pointer;
  transition:background .15s ease, color .15s ease;}
.mb-btn:active{background:rgba(138,180,255,.16);color:#EAF2FF;}
.mb-play{width:42px;height:42px;background:rgba(138,180,255,.14);
  border:1px solid rgba(138,180,255,.35);color:#EAF2FF;margin:0 4px;}
.mb-play:active{background:rgba(138,180,255,.26);}
.mb-shuffle{width:32px;height:32px;opacity:.42;}
.mb-shuffle.on{opacity:1;color:#8AB4FF;}
.mb-queue{width:32px;height:32px;color:#9FB3CC;}
#mb-info{flex:0 1 auto;min-width:74px;max-width:min(300px,40vw);margin:0 8px 0 6px;
  overflow:hidden;}
#mb-title{font-size:13px;font-weight:500;color:#EAF2FF;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
#mb-sub{font-size:10px;color:#9FB3CC;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
#mb-pv{font-size:9px;letter-spacing:.5px;color:#8AB4FF;border:1px solid rgba(122,158,217,.5);
  border-radius:999px;padding:2px 8px;flex:0 0 auto;}
/* —— 进度条：已播 / 总时长 + 可拖动滑块（音频与 PV 共用） —— */
#mb-timeline{display:flex;align-items:center;gap:8px;width:100%;height:16px;padding:0 4px;box-sizing:border-box;}
#mb-time-current,#mb-time-total{flex:0 0 34px;width:34px;text-align:center;font-size:9px;
  font-variant-numeric:tabular-nums;color:#9FB3CC;letter-spacing:.2px;}
#mb-seek{--mb-fill:0%;appearance:none;-webkit-appearance:none;flex:1 1 auto;min-width:0;
  height:16px;margin:0;padding:0;background:transparent;cursor:pointer;touch-action:none;}
#mb-seek:disabled{opacity:.45;cursor:default;}
/* ★ 坑 28：源不可 seek（回退流式）时滑块只做进度展示，拖动无效 → 视觉上弱化 */
#mb-seek.mb-noseek{pointer-events:none;opacity:.7;cursor:default;}
#mb-seek::-webkit-slider-runnable-track{height:3px;border-radius:2px;
  background:linear-gradient(to right,#8AB4FF 0%,#8AB4FF var(--mb-fill,0%),
             rgba(150,190,255,.26) var(--mb-fill,0%),rgba(150,190,255,.26) 100%);}
#mb-seek::-webkit-slider-thumb{-webkit-appearance:none;width:10px;height:10px;margin-top:-3.5px;
  border:0;border-radius:50%;background:#EAF2FF;box-shadow:0 0 6px rgba(138,180,255,.85);}
#mb-seek::-moz-range-track{height:3px;border-radius:2px;background:rgba(150,190,255,.26);}
#mb-seek::-moz-range-progress{height:3px;border-radius:2px;background:#8AB4FF;}
#mb-seek::-moz-range-thumb{width:10px;height:10px;border:0;border-radius:50%;background:#EAF2FF;
  box-shadow:0 0 6px rgba(138,180,255,.85);}
`;
        document.head.appendChild(css);
    }

    /* ==================== 播放器核心 ==================== */

    /** 单例 Audio 元素（复用，切歌只换 src） */
    let el = null;
    /** 媒体音量（0~1，音量按钮控制；同时作用于音乐与 PV）★ 默认 100% */
    let mediaVolume = 1.0;

    /** PV 视频元素（大屏播放用） */
    let pvVideo = null;
    let pvTexture = null;
    let pvActive = false;
    /** PV 看门狗定时器（8s 未进入 playing 就回退原曲，见 startPv） */
    let pvWatchdog = null;

    /* ==================== 播放进度（音频 / PV 共用一条 HUD） ==================== */

    /** 拖动进度条中（true 时不回写 value，避免跟手指抢） */
    let seeking = false;
    /** 拖动预览时间（秒），仅 seeking 时有效 */
    let seekPreviewSec = 0;
    /** 进度刷新定时器（250ms 一次，够顺滑又几乎不耗电） */
    let progressTimer = null;

    /** 当前真正在出声/出画的媒体元素；PV 优先（PV 规则：只播 PV） */
    function mediaEl() {
        if (pvActive && pvVideo) return pvVideo;
        return el;
    }

    /** 媒体总时长（秒）；未知返回 0（此时进度条禁用） */
    function mediaDurationSec() {
        const m = mediaEl();
        if (!m) return 0;
        const d = Number(m.duration);
        return (Number.isFinite(d) && d > 0) ? d : 0;
    }

    /** 秒 → m:ss（超过 1 小时显示 h:mm:ss） */
    function fmtTime(sec) {
        if (!Number.isFinite(sec) || sec < 0) return '0:00';
        const s = Math.floor(sec);
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const ss = String(s % 60).padStart(2, '0');
        return h > 0 ? (h + ':' + String(m).padStart(2, '0') + ':' + ss) : (m + ':' + ss);
    }

    /** 拖动中：只更新时间文字和填充，不动媒体 */
    function onSeekInput() {
        if (!barEl) return;
        const dur = mediaDurationSec();
        const input = barEl.querySelector('#mb-seek');
        if (!input) return;
        if (dur <= 0) return;
        seeking = true;
        seekPreviewSec = dur * (Number(input.value) / 1000);
        paintProgress(seekPreviewSec, dur);
    }

    /** 松手：真正 seek 到目标位置 */
    function commitSeek() {
        if (!barEl) return;
        const input = barEl.querySelector('#mb-seek');
        const m = mediaEl();
        if (!input) return;
        const dur = mediaDurationSec();
        if (m && dur > 0) {
            const target = Math.max(0, Math.min(dur, dur * (Number(input.value) / 1000)));
            // ★ 诊断（坑 28）：只有"可 seek"的源（blob）才真的能跳。
            //   旧实现（拦截流 200 全量）seekable 为空，赋 currentTime 会被钳回 0，
            //   表现就是"点哪都从头播"。这里显式检查，让问题在日志里一眼可见。
            let canSeek = true;
            try {
                if (typeof m.seekable === 'object' && m.seekable && m.seekable.length > 0) {
                    const s = m.seekable.start(0), e = m.seekable.end(0);
                    canSeek = target >= s - 0.5 && target <= e + 0.5;
                }
            } catch (err) { canSeek = true; }
            try {
                m.currentTime = target;
            } catch (e) {
                log('seek 失败: ' + (e && e.message ? e.message : e));
            }
            if (!canSeek) {
                log('⚠️ 该源不可 seek（seekable 空）→ 回退流式所致；进度条将无法跳转');
                showHint('⚠️ 当前音源不支持拖动跳转');
            } else {
                log('seek → ' + fmtTime(target));
            }
        }
        seeking = false;
        updateProgress(true);
    }

    /** 画进度条：填充 + 已播/总时长文字 */
    function paintProgress(cur, dur) {
        if (!barEl) return;
        const input = barEl.querySelector('#mb-seek');
        const tCur = barEl.querySelector('#mb-time-current');
        const tTot = barEl.querySelector('#mb-time-total');
        if (!input || !tCur || !tTot) return;
        if (tCur.textContent !== fmtTime(cur)) tCur.textContent = fmtTime(cur);
        if (tTot.textContent !== fmtTime(dur)) tTot.textContent = fmtTime(dur);
        const pct = dur > 0 ? Math.max(0, Math.min(100, cur / dur * 100)) : 0;
        input.style.setProperty('--mb-fill', pct.toFixed(2) + '%');
    }

    /**
     * 同步进度（force=true 立即刷新，否则只由定时器调用）。
     * 拖动中不覆盖 input.value，否则滑块会跳回。
     */
    function updateProgress(force) {
        if (!barEl || barEl.hidden) return;
        const input = barEl.querySelector('#mb-seek');
        if (!input) return;
        const dur = mediaDurationSec();
        const m0 = mediaEl();
        // ★ 坑 28：时长为 0（元数据未到）或源不可 seek 时，滑块给"禁用"视觉
        let seekable = true;
        try {
            if (m0 && typeof m0.seekable === 'object' && m0.seekable) seekable = m0.seekable.length > 0;
        } catch (e) { }
        input.disabled = dur <= 0;
        input.classList.toggle('mb-noseek', dur > 0 && !seekable);
        if (dur <= 0) {
            paintProgress(0, 0);
            if (force) input.value = '0';
            return;
        }
        const m = mediaEl();
        const cur = seeking ? seekPreviewSec
            : (m && Number.isFinite(Number(m.currentTime)) ? Number(m.currentTime) : 0);
        if (!seeking) {
            const val = String(Math.round(Math.max(0, Math.min(1, cur / dur)) * 1000));
            if (force || input.value !== val) input.value = val;
        }
        paintProgress(cur, dur);
    }

    function startProgressTimer() {
        if (progressTimer) return;
        progressTimer = setInterval(() => updateProgress(false), 250);
    }

    function stopProgressTimer() {
        if (progressTimer) { clearInterval(progressTimer); progressTimer = null; }
        seeking = false;
        seekPreviewSec = 0;
    }

    /* ==================== 媒体源装载（blob 化 → 让 seek 真正可用） ==================== */

    /**
     * ★★★ 坑 28（真机实录）：拖动进度条无效，点哪都从头播。
     *
     * 根因：展厅媒体走"虚拟 origin 拦截流"（ExhibitionActivity.serveMedia），
     * 而这条通道受坑 20 约束——**只能 200 全量、给不了 206**
     * （WebResourceResponse 在部分 OEM WebView 上对 206 支持不可靠）。
     * Chromium 的媒体 seek 必须拿到 206，拿不到就认为"不可 seek"：
     * seekable 为空 → 给 currentTime 赋值被钳到 0 → 表现即"点哪都从头播"。
     *
     * 修法（纯 JS，不改 Java、不动已稳定的播放管线）：播放前先把媒体
     * fetch 成 Blob，用 blob: URL 交给媒体元素。blob 是浏览器自己的资源，
     * **天然完整可 seek**，与 HTTP Range 完全无关。数据本来就在 App 私有
     * 缓存里，本地 fetch 极快。
     *
     * 兜底：fetch 失败（内存不足 / 超时）→ 回退原来的流式 URL。
     * 原则："最差也能正常播，最好还能拖进度"。
     */
    /** 装载序号：每次换曲 +1，用于作废迟到的异步回调 */
    let mediaToken = 0;
    /** 媒体元素 → 当前 blob URL（换曲时释放，防内存泄漏） */
    const mediaBlobUrl = new WeakMap();

    /** 释放某媒体元素的 blob URL（务必在赋上新 src 之后再调） */
    function releaseBlobUrl(media) {
        if (!media) return;
        const old = mediaBlobUrl.get(media);
        if (old) {
            mediaBlobUrl.delete(media);
            try { URL.revokeObjectURL(old); } catch (e) { }
        }
    }

    /**
     * 给媒体元素挂源：优先 blob（可 seek），失败回退流式。
     * 回调里若 token 已过期必须直接放弃（用户可能已切歌）。
     * @returns Promise<'blob' | 'stream' | 'stale'>
     */
    function attachMediaSrc(media, url, token) {
        // ★ 体积上限（坑 28 的配套安全阀）：blob 要把整个文件读进内存，
        //   超过阈值就放弃 seek、退回流式 —— 宁可拖不了，也不能 OOM 崩掉展厅。
        const MAX_BLOB_BYTES = 150 * 1024 * 1024;
        const useStream = (why) => {
            const prev = mediaBlobUrl.get(media);
            media.src = url;                                      // 兜底：流式（不可 seek，但能播）
            mediaBlobUrl.delete(media);
            if (prev) { try { URL.revokeObjectURL(prev); } catch (e2) { } }
            log('媒体走流式(' + why + ')，进度条只读不可拖');
            return 'stream';
        };
        return fetch(url).then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const len = Number(r.headers.get('content-length'));
            if (Number.isFinite(len) && len > MAX_BLOB_BYTES) {
                return useStream('文件 ' + Math.round(len / 1048576) + 'MB 超过上限');
            }
            return r.blob();
        }).then(b => {
            if (b === 'stream') return 'stream';
            if (token !== mediaToken) return 'stale';
            const prev = mediaBlobUrl.get(media);
            const objUrl = URL.createObjectURL(b);
            media.src = objUrl;                                   // ★ 先赋新
            mediaBlobUrl.set(media, objUrl);
            if (prev) { try { URL.revokeObjectURL(prev); } catch (e) { } }  // 再放旧
            return 'blob';
        }).catch(e => {
            if (token !== mediaToken) return 'stale';
            log('媒体 blob 化失败，回退流式: ' + (e && e.message ? e.message : e));
            return useStream('fetch 失败');
        });
    }

    /**
     * ⚠️ 血泪教训（真机实录：UI 显示播放中、实际无声、零报错）：
     * 之前把 el 接进 WebAudio（createMediaElementSource）想给大屏供频谱——
     * 但 createMediaElementSource 会把音频**完全改道 WebAudio 管线**，
     * AudioContext 一旦 suspended / 时序不巧，就是"元素在播、喇叭无声"。
     * 而当前没有任何代码消费分析器数据（大屏频谱是程序化动画）。
     * 结论：**不接 WebAudio，走元素原生出声**——零改道零风险。
     * 以后要做真频谱，再单独开任务验证 AudioContext 生命周期。
     */
    function ensurePlayer() {
        if (el) return;
        el = new Audio();
        el.preload = 'auto';
        el.volume = mediaVolume;
        // ★ 进度：时长为 0（元数据未到）时进度条禁用；元数据/跳转后立即刷新一次
        ['loadedmetadata', 'durationchange', 'seeked', 'ended'].forEach(evt => {
            el.addEventListener(evt, () => updateProgress(true));
        });
        // 用户拍板：全局音量 200% 定死（媒体 100% + 展厅总线 2×）。
        // 播放必然发生在用户手势之后（首次触摸已 audio.start()），
        // 此时设置总线增益才能真实生效。
        setVolume(VOL_FIXED);
        el.addEventListener('ended', () => next(true));
        el.addEventListener('error', () => {
            if (current) {
                showHint('⚠️ 文件不可用：' + current.title);
                log('音频加载失败: ' + (current ? current.audioUrl : '?'));
            }
        });
        // ★ 可见性诊断：到底有没有真的在播（无声问题的唯一权威信号）
        el.addEventListener('playing', () => log('音频 playing: ' + (current ? current.title : '?')));
    }

    /**
     * 安全播放（goo.gl/LdLk22 的标准处理）：
     *   · play() 前先把元素 pause 干净，避免"旧 promise 被 pause/load 打断"的 AbortError；
     *   · play() 返回的 promise 一定挂 catch —— AbortError 静默 + 120ms 后补一次重试；
     *     NotSupportedError 才是真失败（文件/流问题），交给 error 监听器提示。
     */
    function safePlay(media) {
        try { media.pause(); } catch (e) { }
        const p = media.play();
        if (p && p.catch) {
            p.catch(err => {
                if (err && err.name === 'AbortError') {
                    // 时序竞争：等一小拍，若仍是"想播但停在暂停"，就补一次
                    setTimeout(() => {
                        try {
                            if (media.paused) {
                                const p2 = media.play();
                                if (p2 && p2.catch) p2.catch(() => { });
                            }
                        } catch (e) { }
                    }, 120);
                } else {
                    log('播放被拒: ' + (err && err.name ? err.name : err));
                }
            });
        }
    }

    /** 展厅"声音开关"联动：直接用元素静音（原生管线，绝对生效） */
    function setMuted(muted) {
        if (el) el.muted = !!muted;
        if (pvVideo) pvVideo.muted = !!muted;
    }

    /* ==================== 数据加载 ==================== */

    /** 拉取音乐库（页面启动 / 从管理页返回时调用） */
    function loadLibrary() {
        const bridge = window.ExhibitionBridge;
        if (!bridge || typeof bridge.getMusicLibrary !== 'function') {
            log('无桥接，音乐厅使用占位数据');
            return;
        }
        try {
            const raw = bridge.getMusicLibrary();
            if (!raw) return;
            const data = JSON.parse(raw);
            if (data.error) {
                log('getMusicLibrary 错误: ' + data.error);
                return;
            }
            albums = Array.isArray(data.albums) ? data.albums : [];
            tracks = Array.isArray(data.tracks) ? data.tracks : [];
            loaded = true;
            log('音乐库已加载: ' + albums.length + ' 张专辑 / ' + tracks.length + ' 首曲目');
            if (deps.onLibrary) deps.onLibrary(albums, tracks);
        } catch (e) {
            log('音乐库解析失败: ' + e);
        }
    }

    /* ==================== 曲目浮层 ==================== */

    /** 打开某张专辑的曲目列表 */
    function openAlbum(albumId) {
        ensureDom();
        injectCss();
        // ★ 打开侧穿透防御（核心！）：记录打开时刻，GUARD_MS 内浮层内的
        //   合成 click（同一手势余波）一律忽略 —— 否则"点专辑位"会直接
        //   触发某曲目行的 click，表现为"点击直接选歌而不是选择界面"。
        overlayGuardAt = performance.now();

        const album = albums.find(a => a.id === albumId);
        const list = tracks.filter(t => t.albumId === albumId);

        const titleEl = overlayEl.querySelector('#mo-title');
        const subEl = overlayEl.querySelector('#mo-sub');
        const listEl = overlayEl.querySelector('#mo-list');

        titleEl.textContent = album ? album.title : '曲目';
        subEl.textContent = list.length
            ? (list.length + ' 首 · 点击开始播放')
            : '';
        listEl.innerHTML = '';

        if (!list.length) {
            const empty = document.createElement('div');
            empty.className = 'mo-empty';
            empty.textContent = '这张专辑还没有曲目\n\n去 App 的「音乐库」添加 ♪';
            listEl.appendChild(empty);
        } else {
            list.forEach((t, i) => {
                const row = document.createElement('div');
                row.className = 'mo-row' + (current && current.id === t.id ? ' now' : '');
                row.innerHTML =
                    '<div class="mo-no">' + (t.trackNo > 0 ? t.trackNo : (i + 1)) + '</div>' +
                    '<div class="mo-main">' +
                    '  <div class="mo-name"></div>' +
                    '  <div class="mo-meta"></div>' +
                    '</div>' +
                    (t.hasPv ? '<div class="mo-pv">PV</div>' : '');
                row.querySelector('.mo-name').textContent = t.title || '(未命名)';
                row.querySelector('.mo-meta').textContent = fmtMeta(t);
                row.addEventListener('click', () => {
                    if (overlayGuarded()) return;   // ★ 打开侧穿透防御
                    playFrom(list, i);
                    closeOverlay();
                });
                listEl.appendChild(row);
            });
        }

        overlayEl.hidden = false;
    }

    function closeOverlay() {
        if (overlayEl && !overlayEl.hidden) {
            overlayEl.hidden = true;
            // ★ 关闭侧穿透防御：400ms 内 3D 侧 tap 视为关闭手势余波并忽略
            overlayGuardAt = performance.now();
        }
    }

    /** 打开当前播放队列抽屉（正在播放 + 后续曲目） */
    function openQueue() {
        if (!current || !queue.length) {
            showHint('当前没有播放队列');
            return;
        }
        ensureDom();
        injectCss();
        renderQueue();
        queueEl.hidden = false;
        queueReturnFocus = document.activeElement;
    }

    function closeQueue() {
        if (queueEl) queueEl.hidden = true;
        if (queueReturnFocus && queueReturnFocus.focus) {
            try { queueReturnFocus.focus(); } catch (e) { }
        }
        queueReturnFocus = null;
    }

    /**
     * 点队列里的曲目：以所点曲目作为新队列起点，后面的曲目继续排队。
     * 当前曲目显示独立卡片；列表默认只列出它后面的曲目。
     */
    function playQueueIndex(index) {
        if (index < 0 || index >= queue.length) return;
        // 保留整张专辑队列，不能 slice；用户可以回听前一首，也能继续下一首。
        queueIndex = index;
        playCurrent();
        renderQueue();
    }

    function renderQueue() {
        if (!queueEl) return;
        const album = current ? albums.find(a => a.id === current.albumId) : null;
        const titleEl = queueEl.querySelector('#mq-title');
        const subEl = queueEl.querySelector('#mq-sub');
        const nowEl = queueEl.querySelector('#mq-now');
        const listEl = queueEl.querySelector('#mq-list');
        titleEl.textContent = '播放队列';
        queueEl.querySelector('#mq-next-title').textContent = '本专辑曲目 · 点击切换';
        subEl.textContent = (album ? album.title + ' · ' : '') + queue.length + ' 首 · 可回听';
        nowEl.replaceChildren();

        const cover = document.createElement('img');
        cover.className = 'mq-cover';
        cover.alt = '';
        if (album && album.cover) cover.src = album.cover;
        const text = document.createElement('div');
        text.className = 'mq-nowtext';
        const kicker = document.createElement('div');
        kicker.className = 'mq-kicker';
        kicker.textContent = playing ? '正在播放' : '已暂停';
        const song = document.createElement('div');
        song.className = 'mq-song';
        song.textContent = current ? (current.title || '(未命名)') : '';
        const detail = document.createElement('div');
        detail.className = 'mq-detail';
        detail.textContent = [current && current.artist, album && album.title].filter(Boolean).join(' · ');
        text.append(kicker, song, detail);
        const state = document.createElement('div');
        state.className = 'mq-playing';
        state.textContent = playing ? 'LIVE' : '';
        nowEl.append(cover, text, state);

        listEl.replaceChildren();
        let upcomingCount = 0;
        for (let i = 0; i < queue.length; i++) {
            const t = queue[i];
            const row = document.createElement('div');
            row.className = 'mq-row' + (i === queueIndex ? ' current' : '');
            const number = document.createElement('div');
            number.className = 'mq-index';
            number.textContent = String(i + 1).padStart(2, '0');
            const body = document.createElement('div');
            body.className = 'mq-rowtext';
            const name = document.createElement('div');
            name.className = 'mq-rowtitle';
            name.textContent = t.title || '(未命名)';
            const meta = document.createElement('div');
            meta.className = 'mq-rowmeta';
            meta.textContent = fmtMeta(t) || '';
            body.append(name, meta);
            row.append(number, body);
            if (t.hasPv) {
                const pv = document.createElement('span');
                pv.className = 'mq-rowpv';
                pv.textContent = 'PV';
                row.appendChild(pv);
            }
            row.addEventListener('click', () => playQueueIndex(i));
            listEl.appendChild(row);
            upcomingCount++;
        }
        if (!upcomingCount) {
            const empty = document.createElement('div');
            empty.className = 'mq-empty';
            empty.textContent = '没有下一首了';
            listEl.appendChild(empty);
        }
    }

    /** 3D 侧 tap 是否应被忽略（浮层刚关闭的冷却期内） */
    function tapGuarded() {
        return (performance.now() - overlayGuardAt) < OVERLAY_GUARD_MS;
    }

    function fmtMeta(t) {
        const parts = [];
        if (t.durationMs > 0) {
            const s = Math.round(t.durationMs / 1000);
            parts.push(Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'));
        }
        if (t.artist) parts.push(t.artist);
        return parts.join(' · ');
    }

    /* ==================== 播放控制 ==================== */

    /** 从某个列表的第 i 首开始播 */
    function playFrom(list, i) {
        if (!list || !list.length) return;
        ensurePlayer();
        queue = list.slice();
        queueIndex = i;
        playCurrent();
    }

    function playCurrent() {
        const t = queue[queueIndex];
        if (!t) return;
        current = t;
        if (queueEl && !queueEl.hidden) renderQueue();

        // 先释放旧 PV（⚠️ 血泪教训 2：不释放解码器会累积）
        releasePv();

        // ★ M5-d 规则（用户拍板）：绑了 PV 的歌**只播 PV 的音视频**，歌曲本身不出声；
        //   黑胶转盘照样转（播放状态由 onPlayingChange 统一下发）。
        if (t.hasPv && t.pvUrl) {
            startPv(t);
        } else {
            startAudio(t);
        }
        updateBar();
        notifyPlaying();
    }

    /** 通知外部播放状态变化（留声机转盘等跟着走） */
    function notifyPlaying() {
        if (deps.onPlayingChange) {
            try { deps.onPlayingChange(playing); } catch (e) { }
        }
    }

    /** 纯音频播放 */
    function startAudio(t) {
        ensurePlayer();
        try {
            const token = ++mediaToken;
            // ★ blob 化有 fetch 延迟，这段时间旧曲会继续响 → 立刻静音旧的，
            //   保持与旧实现（换 src 即刻停）一致的手感。
            try { el.pause(); } catch (e) { }
            playing = true;
            startProgressTimer();
            updateProgress(true);
            // ★ 先取回本地字节（blob），再赋 src 播放 —— 换取可 seek，详见坑 28
            attachMediaSrc(el, t.audioUrl, token).then(r => {
                if (r === 'stale' || current !== t) return;
                log('音频源就绪(' + r + '): ' + t.title);
                safePlay(el);
            });
        } catch (e) {
            log('播放失败: ' + e);
        }
    }

    /** PV 播放（视频自带音轨，用视频的 audio） */
    function startPv(t) {
        if (!pvVideo) {
            pvVideo = document.createElement('video');
            pvVideo.playsInline = true;
            pvVideo.setAttribute('playsinline', '');
            pvVideo.loop = false;
            pvVideo.preload = 'auto';
            pvVideo.volume = mediaVolume;
            // ★★★ 坑 21（真机实录）：video **必须挂进 DOM** 才有视频帧输出！
            //   游离元素（不 append）chromium 只跑音频管线——PV playing 事件照发、
            //   喇叭有声，但 VideoTexture 永远采不到画面 → 大屏全黑。
            //   ★ 尺寸不能太小：部分 ROM 会把 2px / display:none 的视频判定为
            //   "不可见"而跳过帧输出。这里给 160×90 的真实尺寸，靠
            //   透明度 + 负 z-index 藏在左下角（不挡操作、不影响观感）。
            pvVideo.style.cssText = 'position:fixed;left:0;bottom:0;width:160px;height:90px;' +
                'opacity:0.02;pointer-events:none;z-index:-1;';
            document.body.appendChild(pvVideo);
            pvVideo.addEventListener('ended', () => next(true));
            // ★ PV 没有 error 监听 → 播不出时用户完全无感（真机实录教训）
            pvVideo.addEventListener('error', () => {
                log('PV 加载失败: ' + (t ? t.pvUrl : '?') + ' → 回退播放歌曲本体');
                showHint('⚠️ PV 无法播放，改播原曲');
                pvActive = false;
                // ★ 兜底：PV 放不了就播歌曲本体（总比整首哑掉强）
                if (current === t) {
                    startAudio(t);
                    updateBar();
                }
            });
            pvVideo.addEventListener('loadedmetadata', () => updateProgress(true));
            pvVideo.addEventListener('durationchange', () => updateProgress(true));
            pvVideo.addEventListener('seeked', () => updateProgress(true));
            pvVideo.addEventListener('playing', () => log('PV playing: ' + (t ? t.title : '?')));
        }
        try {
            const token = ++mediaToken;
            // ★★★ Bug（真机实录）：切到 PV 歌时**没停掉正在播的音频元素**，
            //   导致"上一首歌 + PV" 两路音频同时响。PV 规则是"只播 PV 的音视频"，
            //   所以这里必须先把音乐元素彻底停住。
            if (el) {
                try { el.pause(); el.removeAttribute('src'); el.load(); } catch (e) { }
                releaseBlobUrl(el);
            }
            pvActive = true;
            playing = true;
            startProgressTimer();
            updateProgress(true);

            // ★ 先取回本地字节（blob），再赋 src 播放 —— 换取可 seek，详见坑 28
            attachMediaSrc(pvVideo, t.pvUrl, token).then(r => {
                if (r === 'stale' || current !== t || !pvActive) return;
                log('PV 源就绪(' + r + '): ' + t.title);
                safePlay(pvVideo);
                if (deps.onPvStart) deps.onPvStart(pvVideo);
            });

            // ★ PV 看门狗（坑 16 的终极形态）：error 事件在"无限重拉"型失败里
            //   根本不触发（chromium 内部重试不报错）。现在 PV 先经 fetch 取字节、
            //   再本地播放，窗口统一放宽到 15s；超时未进 playing 视为失败 → 回退原曲。
            if (pvWatchdog) clearTimeout(pvWatchdog);
            pvWatchdog = setTimeout(() => {
                if (pvActive && pvVideo && pvVideo.paused && current === t) {
                    log('PV 看门狗: 15s 未进入 playing → 回退原曲');
                    showHint('⚠️ PV 播放超时，改播原曲');
                    pvActive = false;
                    releasePv();
                    startAudio(t);
                    updateBar();
                }
            }, 15000);
        } catch (e) {
            log('PV 播放失败: ' + e);
        }
    }

    /** 释放 PV 资源 */
    function releasePv() {
        if (pvWatchdog) { clearTimeout(pvWatchdog); pvWatchdog = null; }
        if (pvVideo) {
            try {
                pvVideo.pause();
                pvVideo.removeAttribute('src');
                pvVideo.load();
            } catch (e) { /* ignore */ }
            releaseBlobUrl(pvVideo);
        }
        pvActive = false;
        if (deps.onPvEnd) deps.onPvEnd();
    }

    function toggle() {
        if (!current) {
            // 没在播 → 从全部曲目开始
            if (tracks.length) playFrom(tracks, 0);
            return;
        }
        ensurePlayer();
        if (playing) {
            if (pvActive && pvVideo) pvVideo.pause();
            else el.pause();
            playing = false;
            stopProgressTimer();
        } else {
            if (pvActive && pvVideo) safePlay(pvVideo);
            else safePlay(el);
            playing = true;
            startProgressTimer();
            updateProgress(true);
        }
        updateBar();
        if (queueEl && !queueEl.hidden) renderQueue();
        notifyPlaying();
    }

    function next(auto) {
        if (!queue.length) return;
        if (shuffle) {
            queueIndex = Math.floor(Math.random() * queue.length);
        } else {
            queueIndex = (queueIndex + 1) % queue.length;
        }
        playCurrent();
    }

    function prev() {
        if (!queue.length) return;
        queueIndex = (queueIndex - 1 + queue.length) % queue.length;
        playCurrent();
    }

    function stop() {
        releasePv();
        stopProgressTimer();
        if (el) {
            try { el.pause(); } catch (e) { }
        }
        playing = false;
        current = null;
        updateBar();
        notifyPlaying();
    }

    /* ==================== HUD 播放条 ==================== */

    /**
     * ★ 自动收起（用户需求）：几秒无操作后滑到屏幕底边只露一条弧，
     *   点一下弹回。播放中 5s / 暂停 12s。
     */
    let barMinTimer = null;
    function wakeBar() {
        if (!barEl) return;
        barEl.classList.remove('mb-min');
        if (barMinTimer) clearTimeout(barMinTimer);
        barMinTimer = setTimeout(() => {
            if (barEl && current && !barEl.hidden) barEl.classList.add('mb-min');
        }, playing ? 5000 : 12000);
    }

    function updateBar() {
        ensureDom();
        injectCss();
        if (!current) {
            barEl.hidden = true;
            if (queueEl) queueEl.hidden = true;
            stopProgressTimer();
            return;
        }
        barEl.hidden = false;
        wakeBar();
        barEl.querySelector('#mb-play').innerHTML = playing ? SVG_PAUSE : SVG_PLAY;
        barEl.querySelector('#mb-title').textContent = current.title || '(未命名)';
        const album = albums.find(a => a.id === current.albumId);
        barEl.querySelector('#mb-sub').textContent =
            (album ? album.title : '') + (current.artist ? ' · ' + current.artist : '');
        const pvTag = barEl.querySelector('#mb-pv');
        pvTag.hidden = !current.hasPv;
        // ★ 换曲/UI 刷新时同步一次进度（时长可能还没到，会显示 0:00 并禁用滑块）
        updateProgress(true);
        if (queueEl && !queueEl.hidden) renderQueue();
    }

    /* ==================== 对外接口 ==================== */

    return {
        loadLibrary,
        openAlbum,
        closeOverlay,
        openQueue,
        closeQueue,
        /** 3D 侧 tap 冷却检查（点击穿透防御，见 closeOverlay 注释） */
        tapGuarded,
        toggle,
        next,
        prev,
        stop,
        setMuted,
        setVolume,
        isPlaying: () => playing,
        currentTrack: () => current,
        /** 给大屏用的 PV 视频元素（可能为 null） */
        pvVideo: () => (pvActive ? pvVideo : null),
        /** 数据（供调试） */
        albums: () => albums,
        tracks: () => tracks,
        isLoaded: () => loaded,
    };
}