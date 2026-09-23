/**
 * YukiHub 离线个人展厅 · M0（空房间 + 帧率基线）
 * =================================================
 * 目标：不是"做好看"，而是**先量出这台设备的真实 WebGL 性能**，
 *       为 M1（盒子阵列陈列）确定可承受的规模上限。
 *
 * 测量项：平均 FPS、1% 低帧、最差帧、绘制调用数、三角面数、渲染倍率影响。
 * 可通过右下角按钮切换：渲染倍率 / 压力测试（60 个带贴图盒子）/ 详细数据。
 *
 * 说明：
 *  - 完全离线，不请求任何网络资源（three.js 也是本地 vendor 文件）。
 *  - 跑在 App 内 WebView 的虚拟 origin（https://exhibition.local/）下。
 *  - 桥接可选：window.ExhibitionBridge 存在时会把帧率摘要回传原生。
 */

import * as THREE from './vendor/three.module.min.js';
import { createHall } from './hall.js';
import { createAudio } from './audio.js';
import { createPicker } from './picker.js';
import { createGamepad } from './gamepad.js';
import { createConcert } from './concert.js';
import { createMusic } from './music.js';

// 看门狗标记：index.html 用它判断模块是否真的启动成功
window.__exhBooted = true;

/* ==================== 常量 ==================== */

const ROOM = { w: 34, d: 22, h: 7 };   // 展厅尺寸（米）
const EYE_STAND = 1.66;                // 站立视高
const EYE_CROUCH = 0.95;               // 蹲下视高
const EYE_LERP = 12.0;                 // 站/蹲过渡速度（越大越快）
const WALK_SPEED = 3.3;                // 常速（m/s）
const RUN_SPEED = 6.0;                 // 奔跑（m/s）
const CROUCH_SPEED_MUL = 0.5;          // 蹲行速度倍率
const GRAVITY = 18.0;                  // 重力（m/s²，偏大更跟手）
const JUMP_SPEED = 5.2;                // 起跳初速（m/s）
const WALL_MARGIN = 0.7;               // 离墙最小距离
const LOOK_SENS_MOUSE = 0.0026;        // 鼠标转视角灵敏度
const LOOK_SENS_TOUCH = 0.0034;        // 触摸转视角灵敏度（略高于鼠标，更跟手）
const STICK_RADIUS = 72;               // 摇杆最大半径（px）
const BOB_AMP = 0.032;                 // 走动时头部微摆幅度（米）

// 渲染倍率档位（循环切换）
// 已删 0.75：现在设备性能普遍够用，0.75 太糊没人会用；
// 已加 2.5 / 3.0：给旗舰设备留"把画面拉到最清晰"的空间（1440p+ 屏能吃满）。
// 帧率上限档位（0 = 不限，跟屏幕刷新率走）
// 用途：省电 / 降温 / 避免"设备跑不满高刷反而抖动"。
// 注意：软件限帧只能**往下压**，无法把 60Hz 屏幕抬到 120。
//       要真正上高刷还得靠系统（见 ExhibitionActivity 的 preferredRefreshRate）。
const FPS_STEPS = [0, 60, 90, 30];
const FPS_LABELS = { 0: '不限', 90: '90', 60: '60', 30: '30' };
const SCALE_STEPS = [1.0, 1.5, 2.0, 2.5, 3.0];

/* ==================== DOM ==================== */

const $ = (id) => document.getElementById(id);
const dom = {
    stage: $('stage'),
    hud: $('hud'),
    fps: $('hud-fps'),
    detail: $('hud-detail'),
    debug: $('debug'),
    actions: $('actions'),
    joystick: $('joystick'),
    joyKnob: $('joy-knob'),
    hint: $('hint'),
    loading: $('loading'),
    overlay: $('overlay'),
    ovTitle: $('ov-title'),
    ovDesc: $('ov-desc'),
    ovCode: $('ov-code'),
    btnScale: $('btn-scale'),
    btnFps: $('btn-fps'),
    btnDetail: $('btn-detail'),
    btnSound: $('btn-sound'),
    btnJump: $('btn-jump'),
    btnCrouch: $('btn-crouch'),
    btnRun: $('btn-run'),
};

/** 显示错误覆盖层（避免静默白屏） */
function fail(title, desc, code) {
    if (dom.loading) dom.loading.classList.add('hide');
    if (dom.ovTitle) dom.ovTitle.textContent = title;
    if (dom.ovDesc) dom.ovDesc.textContent = desc;
    if (dom.ovCode) dom.ovCode.textContent = code || '';
    if (dom.overlay) dom.overlay.classList.add('show');
}

window.addEventListener('error', (e) => {
    fail('运行时错误', '展厅脚本执行中出错（M0 调试信息如下）。',
        (e && (e.message || e.error)) ? String(e.message || e.error) : 'unknown');
});

/* ==================== 状态 ==================== */

const state = {
    scaleIndex: 2,               // 默认 1.5x（实测 2.0x 都能稳 90fps，取 1.5x 兼顾清晰与省电）
    fpsIndex: 1,                 // 帧率上限档（默认 60 —— 设备会发烫，别默认跑满）
    detail: true,

    yaw: 0,
    pitch: -0.04,
    pos: new THREE.Vector3(0, EYE_STAND, ROOM.d / 2 - 3.5),
    vel: new THREE.Vector3(),

    // 垂直运动（M0.5 新增）
    eye: EYE_STAND,              // 当前视高（站/蹲平滑过渡用）
    velY: 0,
    grounded: true,
    crouching: false,
    walkPhase: 0,                // 步行摆动相位

    // 输入
    keyForward: 0,
    keyStrafe: 0,
    stickForward: 0,
    stickStrafe: 0,
    running: false,
    lastStepIdx: 0,              // 脚步节拍（用于触发脚步声）
};

const bridge = (typeof window.ExhibitionBridge === 'object' && window.ExhibitionBridge !== null)
    ? window.ExhibitionBridge
    : null;

// 程序化音频（首次用户手势时启动，符合 WebAudio 的自动播放策略）
const audio = createAudio();
// 用户拍板：不做音量调节 UI，整个展厅固定 200%（主总线倍率 2.0）。
// 提前设置，确保环境音/脚步/交互音从首次启动开始就统一放大。
if (audio && audio.setVolumeBoost) audio.setVolumeBoost(2.0);

/* ==================== three 主体 ==================== */

let renderer, scene, camera, hall, picker, gamepad, concert, music;

function initRenderer() {
    renderer = new THREE.WebGLRenderer({
        antialias: true,
        powerPreference: 'high-performance',
        stencil: false,
    });
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    // 电影化色调映射：高光不再死白、暗部更通透，"自然度"提升最明显的一处改动
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.18;         // 1.05 → 1.18：整体提亮一档
    renderer.shadowMap.enabled = true;           // G：只为中央展台射灯开阴影（其余网格不 castShadow，开销极小）
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.setPixelRatio(SCALE_STEPS[state.scaleIndex]);
    renderer.setSize(window.innerWidth, window.innerHeight, false);

    const canvas = renderer.domElement;
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    canvas.style.touchAction = 'none';   // 阻止 WebView 把拖动手势当成滚动/缩放
    dom.stage.appendChild(canvas);

    // 上下文丢失（移动端常见于内存压力）：给出明确提示
    canvas.addEventListener('webglcontextlost', (e) => {
        e.preventDefault();
        fail('渲染上下文丢失', 'GPU 上下文被系统回收（通常与内存压力有关）。请返回重进展厅。', '');
    });
}

function initScene() {
    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x18202F);
    scene.fog = new THREE.Fog(0x18202F, 30, 95);   // 同背景色；视距也一并放宽，远处不糊成一团

    camera = new THREE.PerspectiveCamera(72, window.innerWidth / window.innerHeight, 0.08, 220);
    camera.rotation.order = 'YXZ';

    buildRoom();
    buildAtmosphere();
    // buildStressBoxes()：压力测试已移除（基准早已测完）

    // M1：个人收藏馆陈列（书架阵列 + 中央展台）
    hall = createHall(scene, {
        // 把页面侧的封面加载情况打到 logcat（tag: ExhibitionBridge），便于定位问题
        log: (msg) => { try { if (bridge && bridge.log) bridge.log(String(msg)); } catch (e) { } },
    });

    // 音乐厅 + 前厅感应门 + 走廊（数据来源待接入，先搭空间）
    concert = createConcert(scene, {
        audio,   // 注入 audio：开门音效用它
        log: (msg) => { try { if (bridge && bridge.log) bridge.log(String(msg)); } catch (e) { } },
    });
    concert.build();

    // M5-d：音乐厅播放器（曲目浮层 / 播放 / 大屏 PV / HUD 条）
    music = createMusic({
        scene,
        audio,
        showHint: (msg) => showHint(msg),
        log: (msg) => { try { if (bridge && bridge.log) bridge.log(String(msg)); } catch (e) { } },
        // 数据到位 → 铺专辑墙
        onLibrary: (albums, tracks) => {
            if (concert && concert.applyAlbums) {
                concert.applyAlbums(albums, 12);
            }
            if (!albums.length) {
                showHint('音乐厅还没有专辑 · 去 App「音乐库」添加 ♪');
            } else if (albums.length > 12) {
                showHint('专辑墙已满（12 张），还有 ' + (albums.length - 12) + ' 张未展示');
            }
        },
        // PV 开始 → 挂到大屏
        onPvStart: (video) => {
            if (concert && concert.setScreenPv) concert.setScreenPv(video);
            if (concert && concert.setPlaying) concert.setPlaying(true);
        },
        // PV 结束 → 摘掉，恢复程序化频谱
        onPvEnd: () => {
            if (concert && concert.setScreenPv) concert.setScreenPv(null);
        },
        // 播放状态变化 → 留声机转盘跟着转/停 + 黑胶标签换封面
        onPlayingChange: (on) => {
            if (concert && concert.setPlaying) concert.setPlaying(on);
            // 贴/清当前曲目所属专辑的封面
            if (concert && concert.setDiscCover) {
                const t = music ? music.currentTrack() : null;
                if (on && t) {
                    const album = music.albums().find(a => a.id === t.albumId);
                    concert.setDiscCover(album && album.cover ? album.cover : null);
                } else {
                    concert.setDiscCover(null);
                }
            }
        },
    });
    // 页面级刷新钩子：从音乐库管理页返回时由原生调用（见 ExhibitionActivity.onResume）
    window.__exhibitionRefreshMusic = () => {
        try {
            music.loadLibrary();
            showHint('音乐库已刷新 ♪');
        } catch (e) { }
    };

    // 主题展台的选品浮层：库存、缩略图 URL、选完的回调都从这里接
    picker = createPicker({
        games: () => hall.getGames(),
        // 复用桥接直出的 /cover/<id>（与展厅里盒子走的是同一条路）
        thumbUrl: (g) => g.cover || '',
        toast: (msg) => { try { if (bridge && bridge.toast) bridge.toast(msg); } catch (e) { } },
        onPick: (slot, game) => {
            hall.setIsland(slot, game);       // 立刻摆上（或清空）
            hall.closeDetail();
        },
    });

    loadLibrary();

    // M5-d：音乐库（桥接调用是同步的）
    if (music) music.loadLibrary();

    // 手柄/键盘输入（M4）：每帧 poll 一次，把设备状态翻译成"动作"
    gamepad = createGamepad();
    setPadHint(false);
}

/* ---------- 房间 ---------- */

/** 程序化地砖贴图：无外部资源，同时给走动提供运动参照 */
function makeFloorTexture() {
    const S = 512;
    const c = document.createElement('canvas');
    c.width = c.height = S;
    const g = c.getContext('2d');

    g.fillStyle = '#2F3A4B';
    g.fillRect(0, 0, S, S);

    // 2x2 深浅棋盘
    const half = S / 2;
    g.fillStyle = 'rgba(255,255,255,0.035)';
    g.fillRect(0, 0, half, half);
    g.fillRect(half, half, half, half);

    // 砖缝
    g.strokeStyle = 'rgba(0,0,0,0.26)';
    g.lineWidth = 3;
    for (let i = 0; i <= 2; i++) {
        const p = i * half;
        g.beginPath(); g.moveTo(p, 0); g.lineTo(p, S); g.stroke();
        g.beginPath(); g.moveTo(0, p); g.lineTo(S, p); g.stroke();
    }

    // 细纹
    g.strokeStyle = 'rgba(255,255,255,0.030)';
    g.lineWidth = 1;
    for (let i = 0; i < 40; i++) {
        const y = Math.random() * S;
        g.beginPath(); g.moveTo(0, y); g.lineTo(S, y); g.stroke();
    }

    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
    tex.repeat.set(ROOM.w / 4, ROOM.d / 4);
    tex.anisotropy = Math.min(4, renderer.capabilities.getMaxAnisotropy());
    return tex;
}

function buildRoom() {
    const { w, d, h } = ROOM;
    const halfW = w / 2, halfD = d / 2;

    // 地板
    const floor = new THREE.Mesh(
        new THREE.PlaneGeometry(w, d),
        new THREE.MeshStandardMaterial({
            map: makeFloorTexture(),
            color: 0xFFFFFF,
            roughness: 0.62,
            metalness: 0.06,
        })
    );
    floor.rotation.x = -Math.PI / 2;
    floor.name = 'floor';
    floor.receiveShadow = true;                  // 接收中央展台的投影
    scene.add(floor);

    // 天花板
    const ceil = new THREE.Mesh(
        new THREE.PlaneGeometry(w, d),
        new THREE.MeshStandardMaterial({ color: 0x27313F, roughness: 0.95, metalness: 0.0 })
    );
    ceil.rotation.x = Math.PI / 2;
    ceil.position.y = h;
    scene.add(ceil);

    // 四面墙
    // ⚠️ 前墙（z = +halfD）**不在这里建** —— 它由 concert.js 建成"带门洞"的
    //    拼装墙（左块 + 右块 + 门楣上方块 + 门框）。如果这里再建一整面，
    //    会把门洞整个盖住，玩家走进门后看到的是"穿墙"。
    const wallMat = new THREE.MeshStandardMaterial({ color: 0x38445A, roughness: 0.92, metalness: 0.02 });
    const walls = [
        { w: w, pos: [0, h / 2, -halfD], rotY: 0 },              // 后墙
        { w: d, pos: [-halfW, h / 2, 0], rotY: Math.PI / 2 },    // 左墙
        { w: d, pos: [halfW, h / 2, 0], rotY: -Math.PI / 2 },    // 右墙
    ];
    for (const def of walls) {
        const m = new THREE.Mesh(new THREE.PlaneGeometry(def.w, h), wallMat);
        m.position.set(def.pos[0], def.pos[1], def.pos[2]);
        m.rotation.y = def.rotY;
        scene.add(m);
    }

    // 柱廊：已移除
    // 原来左右各 3 根（x=±14.4，z=-8/-1.5/+5）纯粹杵在空地上，
    // 不承担任何结构或陈列功能，却会从门口直视时挡住后墙书架。
    // 入口门厅那两根"仪式感方柱"保留（见 buildAtmosphere）。

    // 顶部灯带：**已移除**。
    // 原来是在天花上沿 z 铺 5.2m 长的纯白方条（MeshBasicMaterial → 不受光照、
    // 永远满亮度，看着像"贴在天花上的白纸"），而且它比横梁还长 16 倍，
    // 和横梁交叉成一团乱麻。氛围光改由横梁内的"嵌入式灯槽"承担（见 buildAtmosphere）。

    // 光照：降低"大面积平行光"的权重（那会让画面很平、很假），
    // 改以带衰减的顶灯为主 → 有明暗层次，更像真实展厅
    scene.add(new THREE.HemisphereLight(0xB8D2FF, 0x141A26, 1.05));

    const key = new THREE.DirectionalLight(0xFFFFFF, 0.70);
    key.position.set(7, 14, 9);
    scene.add(key);

    const fill = new THREE.DirectionalLight(0xAECCFF, 0.34);
    fill.position.set(-9, 8, -7);
    scene.add(fill);

    // 中庭补光（新增）：从顶部中央往下打的一束柔和面光，
    // 专门照亮"房间中央 / 展台 / 地毯"这一带 —— 原来只有四角顶灯，
    // 中央区域离所有灯都远，衰减下来就偏暗。
    const core = new THREE.PointLight(0xFFEBD0, 22, 30, 2);
    core.position.set(0, h - 1.4, 0);
    scene.add(core);

    // 四盏顶灯（带衰减）：真正负责"照亮展品"的是它们
    // 位置下移（h-1.2 → h-2.2）并适度加强：灯越贴天花板，
    // 经过 2 次衰减落到 1.6m 视高时越弱，这是"整体偏暗"的主因。
    for (const x of [-8, 8]) {
        for (const z of [-5, 5]) {
            const lamp = new THREE.PointLight(0xFFE9C8, 46, 34, 2);
            lamp.position.set(x, h - 2.2, z);
            scene.add(lamp);
        }
    }
}

/* ---------- 氛围：天花横梁 + 中央地毯 + 浮尘 ---------- */

let dusty = null;
let dustAttr = null;
let dustVel = null;
let dustN = 0;
let dustH = 7;

/** 浮尘：逐个粒子缓慢上升 + 轻微横向漂移，升到顶部就回到底部 */
function updateDust(dt) {
    if (!dustAttr) return;
    const a = dustAttr.array;
    for (let i = 0; i < dustN; i++) {
        const i3 = i * 3;
        a[i3] += dustVel[i3] * dt;
        a[i3 + 1] += dustVel[i3 + 1] * dt;
        a[i3 + 2] += dustVel[i3 + 2] * dt;
        if (a[i3 + 1] > dustH - 0.3) a[i3 + 1] = 0.4;
    }
    dustAttr.needsUpdate = true;
}

/**
 * 灯槽贴图：纵向（沿 x）"两端淡、中间亮"的暖白渐变。
 * 用途：让横梁下的灯槽看起来像"一条发光的缝"，而不是一块死白的板子。
 * 用 AdditiveBlending 叠加，所以贴图越黑越"透明"。
 */
function makeSlotTexture() {
    const W = 256, H = 8;
    const c = document.createElement('canvas');
    c.width = W; c.height = H;
    const g = c.getContext('2d');
    const grad = g.createLinearGradient(0, 0, W, 0);
    grad.addColorStop(0.00, 'rgba(0,0,0,0)');
    grad.addColorStop(0.12, 'rgba(255,214,158,0.45)');
    grad.addColorStop(0.50, 'rgba(255,236,204,0.95)');   // 中间最亮
    grad.addColorStop(0.88, 'rgba(255,214,158,0.45)');
    grad.addColorStop(1.00, 'rgba(0,0,0,0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, W, H);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    return tex;
}

function buildAtmosphere() {
    const { w, d, h } = ROOM;
    const halfD = d / 2;

    // 天花横梁：改细 + 嵌入**发光灯槽**（氛围光全靠它，替代原来那片死白灯带）
    const beamMat = new THREE.MeshStandardMaterial({ color: 0x20293A, roughness: 0.92, metalness: 0.04 });
    const beamGeo = new THREE.BoxGeometry(w - 0.6, 0.16, 0.32);

    // 灯槽用**渐变色带贴图**而不是纯色：两端淡、中间亮，
    // 这样它看起来是"一条发光的缝"，而不是"一块贴在天花上的白板"。
    const slotTex = makeSlotTexture();
    const slotMat = new THREE.MeshBasicMaterial({
        map: slotTex, transparent: true, opacity: 0.85,
        blending: THREE.AdditiveBlending, depthWrite: false,
    });
    // 宽度贴合梁内（梁深 0.32 → 灯槽 0.10），只做"梁下的缝"，
    // 不再横向铺满整个天花（那正是原来显得乱的原因）。
    const slotGeo = new THREE.BoxGeometry(w - 1.0, 0.03, 0.10);
    for (let z = -halfD + 2.2; z <= halfD - 2.2; z += 4.4) {
        const b = new THREE.Mesh(beamGeo, beamMat);
        b.position.set(0, h - 0.12, z);
        scene.add(b);
        const s = new THREE.Mesh(slotGeo, slotMat);
        s.position.set(0, h - 0.205, z);   // 贴在梁的下沿
        scene.add(s);
    }

    // 入口门厅：两根方柱 + 门楣 + 洗墙灯带（"走进展馆"的仪式感）
    const portMat = new THREE.MeshStandardMaterial({ color: 0x2A3444, roughness: 0.72, metalness: 0.14 });
    const colGeo = new THREE.BoxGeometry(0.62, h, 0.62);
    for (const x of [-6.4, 6.4]) {
        const c = new THREE.Mesh(colGeo, portMat);
        c.position.set(x, h / 2, halfD - 1.3);
        scene.add(c);
    }
    const lintel = new THREE.Mesh(new THREE.BoxGeometry(14.0, 0.72, 0.72), portMat);
    lintel.position.set(0, h - 0.5, halfD - 1.3);
    scene.add(lintel);
    const portLamp = new THREE.Mesh(new THREE.BoxGeometry(12.6, 0.07, 0.14), slotMat);
    portLamp.position.set(0, h - 0.92, halfD - 1.66);
    scene.add(portLamp);

    // 中央长地毯：给动线一个视觉引导
    const cvs = document.createElement('canvas');
    cvs.width = 256; cvs.height = 512;
    const g = cvs.getContext('2d');
    g.fillStyle = '#2A3547';
    g.fillRect(0, 0, 256, 512);
    g.fillStyle = 'rgba(158,196,255,0.06)';
    g.fillRect(18, 18, 220, 476);
    g.strokeStyle = 'rgba(170,205,255,0.22)';
    g.lineWidth = 3;
    g.strokeRect(32, 32, 192, 448);
    g.strokeRect(42, 42, 172, 428);
    const carpetTex = new THREE.CanvasTexture(cvs);
    carpetTex.colorSpace = THREE.SRGBColorSpace;
    const carpet = new THREE.Mesh(
        new THREE.PlaneGeometry(4.8, d - 3.5),
        new THREE.MeshStandardMaterial({ map: carpetTex, roughness: 0.96, metalness: 0.0 })
    );
    carpet.rotation.x = -Math.PI / 2;
    carpet.position.y = 0.012;
    scene.add(carpet);

    // 浮尘：数量调少（原来 380 太多，像噪点），改为缓慢上升 + 横向漂移
    const N = 120;
    dustN = N;
    dustH = h;
    const pos = new Float32Array(N * 3);
    for (let i = 0; i < N; i++) {
        pos[i * 3] = (Math.random() - 0.5) * (w - 2);
        pos[i * 3 + 1] = Math.random() * (h - 1) + 0.4;
        pos[i * 3 + 2] = (Math.random() - 0.5) * (d - 2);
    }
    const dustGeo = new THREE.BufferGeometry();
    dustVel = new Float32Array(N * 3);
    for (let i = 0; i < N; i++) {
        dustVel[i * 3] = (Math.random() - 0.5) * 0.09;
        dustVel[i * 3 + 1] = 0.035 + Math.random() * 0.055;   // 整体缓慢上升
        dustVel[i * 3 + 2] = (Math.random() - 0.5) * 0.09;
    }
    dustAttr = new THREE.BufferAttribute(pos, 3);
    dustGeo.setAttribute('position', dustAttr);
    dusty = new THREE.Points(dustGeo, new THREE.PointsMaterial({
        color: 0xD8E6FF, size: 0.042, transparent: true, opacity: 0.30,
        depthWrite: false, sizeAttenuation: true,
    }));
    scene.add(dusty);
}

/* ---------- 压力测试：60 个带贴图盒子（模拟 M1 的封面阵列） ---------- */

/** 生成近似封面比例的贴图（256×384），M1 会换成真实游戏封面 */
function makeCoverTexture(seed) {
    const W = 256, H = 384;
    const c = document.createElement('canvas');
    c.width = W; c.height = H;
    const g = c.getContext('2d');

    const hue = (seed * 47) % 360;
    const grad = g.createLinearGradient(0, 0, W, H);
    grad.addColorStop(0, `hsl(${hue}, 46%, 38%)`);
    grad.addColorStop(1, `hsl(${(hue + 58) % 360}, 40%, 18%)`);
    g.fillStyle = grad;
    g.fillRect(0, 0, W, H);

    g.fillStyle = 'rgba(255,255,255,0.09)';
    for (let i = 0; i < 5; i++) {
        g.fillRect(18, 40 + i * 54, W - 36, 22);
    }
    g.fillStyle = 'rgba(255,255,255,0.85)';
    g.font = 'bold 30px sans-serif';
    g.fillText('#' + seed, 20, H - 24);

    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    return tex;
}

/* ==================== 输入 ==================== */

const pointers = new Map(); // pointerId → { role, lastX, lastY }

/* ---------- 动态摇杆（左半屏按下处浮现，跟手拖动） ---------- */

const joy = { pointerId: null, baseX: 0, baseY: 0, dx: 0, dy: 0 };

function joyShow(x, y) {
    joy.baseX = x; joy.baseY = y; joy.dx = 0; joy.dy = 0;
    dom.joystick.style.left = x + 'px';
    dom.joystick.style.top = y + 'px';
    dom.joystick.classList.add('on');
    joyUpdateKnob();
    state.stickForward = 0;
    state.stickStrafe = 0;
}

function joyMove(x, y) {
    let dx = x - joy.baseX;
    let dy = y - joy.baseY;
    const len = Math.hypot(dx, dy);
    if (len > STICK_RADIUS) {
        dx = dx / len * STICK_RADIUS;
        dy = dy / len * STICK_RADIUS;
    }
    joy.dx = dx; joy.dy = dy;
    state.stickStrafe = dx / STICK_RADIUS;
    state.stickForward = -dy / STICK_RADIUS;   // 上推 = 前进
    joyUpdateKnob();
}

function joyUpdateKnob() {
    dom.joyKnob.style.transform =
        'translate(calc(-50% + ' + joy.dx.toFixed(1) + 'px), calc(-50% + ' + joy.dy.toFixed(1) + 'px))';
}

function joyHide() {
    joy.pointerId = null;
    dom.joystick.classList.remove('on');
    state.stickForward = 0;
    state.stickStrafe = 0;
}

/* ---------- 动作（跳跃 / 蹲下 / 奔跑） ---------- */

function tryJump() {
    if (!state.grounded) return;
    state.velY = JUMP_SPEED;
    state.grounded = false;
}

function toggleCrouch() {
    state.crouching = !state.crouching;
    if (dom.btnCrouch) dom.btnCrouch.classList.toggle('on', state.crouching);
}

function setRunning(on) {
    state.running = on;
    if (dom.btnRun) dom.btnRun.classList.toggle('on', on);
}

function bindInput() {
    const el = renderer.domElement;

    // --- 键盘（外接键盘 / 桌面调试）
    window.addEventListener('keydown', (e) => {
        if (e.repeat) return;
        audio.start();   // 首次按键即启动音频（WebAudio 需要用户手势）
        switch (e.code) {
            case 'KeyW': case 'ArrowUp': state.keyForward = 1; break;
            case 'KeyS': case 'ArrowDown': state.keyForward = -1; break;
            case 'KeyA': case 'ArrowLeft': state.keyStrafe = -1; break;
            case 'KeyD': case 'ArrowRight': state.keyStrafe = 1; break;
            case 'Space': tryJump(); break;
            case 'KeyC': toggleCrouch(); break;
            case 'KeyT': cycleFps(); break;   // 原「压力测试」键位，改作帧率上限切换
            case 'KeyH': toggleDetail(); break;
            case 'KeyR': cycleScale(); break;
            case 'ShiftLeft': case 'ShiftRight': setRunning(true); break;
        }
    });
    window.addEventListener('keyup', (e) => {
        switch (e.code) {
            case 'ShiftLeft': case 'ShiftRight': setRunning(false); break;
            case 'KeyW': case 'ArrowUp':
                if (state.keyForward > 0) state.keyForward = 0; break;
            case 'KeyS': case 'ArrowDown':
                if (state.keyForward < 0) state.keyForward = 0; break;
            case 'KeyA': case 'ArrowLeft':
                if (state.keyStrafe < 0) state.keyStrafe = 0; break;
            case 'KeyD': case 'ArrowRight':
                if (state.keyStrafe > 0) state.keyStrafe = 0; break;
        }
    });

    // --- 触摸：左半屏按下 → 摇杆浮现并跟手；右半屏拖动 → 转视角
    //     鼠标：拖动即为转视角（移动交给键盘）
    el.addEventListener('pointerdown', (e) => {
        el.setPointerCapture && el.setPointerCapture(e.pointerId);
        audio.start();   // 首次触摸即启动音频（WebAudio 需要用户手势）
        const isMouse = e.pointerType === 'mouse';

        if (!isMouse && e.clientX < window.innerWidth * 0.5 && joy.pointerId === null) {
            joy.pointerId = e.pointerId;
            joyShow(e.clientX, e.clientY);
            pointers.set(e.pointerId, { role: 'move' });
            return;
        }
        pointers.set(e.pointerId, {
            role: 'look',
            lastX: e.clientX, lastY: e.clientY,
            startX: e.clientX, startY: e.clientY,
            startT: performance.now(),
            moved: 0,
        });
    }, { passive: true });

    el.addEventListener('pointermove', (e) => {
        const p = pointers.get(e.pointerId);
        if (!p) return;

        if (p.role === 'move') {
            joyMove(e.clientX, e.clientY);
        } else {
            const dx = e.clientX - p.lastX;
            const dy = e.clientY - p.lastY;
            p.lastX = e.clientX; p.lastY = e.clientY;
            p.moved = (p.moved || 0) + Math.abs(dx) + Math.abs(dy);
            const sens = (e.pointerType === 'mouse') ? LOOK_SENS_MOUSE : LOOK_SENS_TOUCH;
            state.yaw -= dx * sens;
            state.pitch -= dy * sens;
            clampPitch();
        }
    }, { passive: true });

    const release = (e) => {
        const p = pointers.get(e.pointerId);
        if (!p) return;
        pointers.delete(e.pointerId);
        if (p.role === 'move') {
            joyHide();
            return;
        }
        // 轻点（没怎么移动、时间短）= 选中展品；拖动则是转视角
        const dur = performance.now() - (p.startT || 0);
        if ((p.moved || 0) < 10 && dur < 400) {
            handleTap(e.clientX, e.clientY);
        }
    };
    el.addEventListener('pointerup', release, { passive: true });
    el.addEventListener('pointercancel', release, { passive: true });
    el.addEventListener('contextmenu', (e) => e.preventDefault());

    // --- 尺寸/旋转变化
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', () => setTimeout(onResize, 220));
}

function clampPitch() {
    const lim = Math.PI / 2 - 0.06;
    if (state.pitch > lim) state.pitch = lim;
    if (state.pitch < -lim) state.pitch = -lim;
}

function onResize() {
    if (!renderer || !camera) return;
    const w = window.innerWidth, h = window.innerHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
    // 3D 展品查看器若开着，它的画布尺寸也要跟着调整
    if (hall && typeof hall.resizeDetail === 'function') hall.resizeDetail();
}

/* ==================== 每帧更新 ==================== */

const tmpForward = new THREE.Vector3();
const tmpRight = new THREE.Vector3();
const tmpTarget = new THREE.Vector3();

function updateMovement(dt) {
    // 三个输入源统一汇到 forward / strafe（动作层思想：逻辑不关心谁在驱动）
    //   键盘：keyForward/keyStrafe（±1）
    //   触屏：stickForward/stickStrafe（摇杆模拟量）
    //   手柄：pad.moveY（前推为负 → 取反）/ pad.moveX
    const pad = state.pad || { moveX: 0, moveY: 0 };
    let forward = state.keyForward + state.stickForward - pad.moveY;
    let strafe = state.keyStrafe + state.stickStrafe + pad.moveX;

    // 归一化，避免斜向加速
    const mag = Math.hypot(forward, strafe);
    if (mag > 1) { forward /= mag; strafe /= mag; }
    // 摇杆死区：手指几乎不动时不漂移
    if (Math.abs(forward) < 0.06) forward = 0;
    if (Math.abs(strafe) < 0.06) strafe = 0;

    let speed = state.running ? RUN_SPEED : WALK_SPEED;
    if (state.crouching) speed *= CROUCH_SPEED_MUL;

    // 朝向只取 yaw（走路不因抬头低头而上下飞）
    tmpForward.set(-Math.sin(state.yaw), 0, -Math.cos(state.yaw));
    tmpRight.set(Math.cos(state.yaw), 0, -Math.sin(state.yaw));

    tmpTarget.set(0, 0, 0)
        .addScaledVector(tmpForward, forward * speed)
        .addScaledVector(tmpRight, strafe * speed);

    // 水平平滑（帧率无关的指数逼近）
    const k = 1 - Math.pow(0.0012, dt);
    state.vel.lerp(tmpTarget, k);

    state.pos.x += state.vel.x * dt;
    state.pos.z += state.vel.z * dt;

    // 房间边界约束
    // ★ 多空间改造：主厅 / 走廊 / 音乐厅 三段不同宽度，
    //   靠 z 分段判定，而不是原来的"单一矩形"。
    //   门的 x 范围（|x| < DOOR_HALF）内允许 z 越过主厅前墙 → 才能走进门。
    const DOOR_HALF = 2.5;          // 门洞半宽（与 concert.js 的 DOOR_W/2 同步）
    const MAIN_Z = ROOM.d / 2;      // 主厅前墙 z = 11
    const CORR_W = 3.5;             // 走廊半宽
    const HL_Z0 = MAIN_Z + 8;       // 音乐厅后墙 z = 19（走廊长 8）
    const HL_W = 13.0;              // 音乐厅半宽
    const HL_Z1 = HL_Z0 + 20;       // 音乐厅最里 z = 39

    if (state.pos.z <= MAIN_Z - WALL_MARGIN) {
        // —— 主厅内：原矩形约束 ——
        const halfW = ROOM.w / 2 - WALL_MARGIN;
        const halfD = ROOM.d / 2 - WALL_MARGIN;
        if (state.pos.x > halfW) { state.pos.x = halfW; state.vel.x = 0; }
        if (state.pos.x < -halfW) { state.pos.x = -halfW; state.vel.x = 0; }
        if (state.pos.z < -halfD) { state.pos.z = -halfD; state.vel.z = 0; }
        // 走到门前（z 接近前墙）时，只有门洞范围内才允许继续前进
        if (state.pos.z > halfD) {
            if (Math.abs(state.pos.x) > DOOR_HALF - 0.05) {
                state.pos.z = halfD; state.vel.z = 0;
            }
        }
    } else if (state.pos.z <= HL_Z0 - WALL_MARGIN) {
        // —— 走廊内：窄矩形 ——
        if (state.pos.x > CORR_W - WALL_MARGIN) { state.pos.x = CORR_W - WALL_MARGIN; state.vel.x = 0; }
        if (state.pos.x < -(CORR_W - WALL_MARGIN)) { state.pos.x = -(CORR_W - WALL_MARGIN); state.vel.x = 0; }
    } else {
        // —— 音乐厅内：宽矩形 ——
        if (state.pos.x > HL_W) { state.pos.x = HL_W; state.vel.x = 0; }
        if (state.pos.x < -HL_W) { state.pos.x = -HL_W; state.vel.x = 0; }
        if (state.pos.z > HL_Z1 - WALL_MARGIN) { state.pos.z = HL_Z1 - WALL_MARGIN; state.vel.z = 0; }
    }

    // ---------- 垂直：站/蹲过渡 + 跳跃重力 ----------
    const targetEye = state.crouching ? EYE_CROUCH : EYE_STAND;
    state.eye += (targetEye - state.eye) * Math.min(1, dt * EYE_LERP);

    if (state.grounded) {
        state.pos.y = state.eye;      // 站/蹲平滑过渡直接体现在视高上
        state.velY = 0;
    } else {
        state.velY -= GRAVITY * dt;
        state.pos.y += state.velY * dt;
        if (state.pos.y <= state.eye) {
            state.pos.y = state.eye;
            state.velY = 0;
            state.grounded = true;
        }
    }

    // ---------- 走动头部微摆（很轻，只为体感） ----------
    const hspeed = Math.hypot(state.vel.x, state.vel.z);
    let bob = 0;
    if (state.grounded && hspeed > 0.25) {
        state.walkPhase += dt * (6.0 + hspeed * 1.1);
        bob = Math.sin(state.walkPhase) * BOB_AMP * Math.min(1, hspeed / WALK_SPEED);

        // 脚步：相位每跨过 π 落一步（音量随速度，蹲行更轻）
        const stepIdx = Math.floor(state.walkPhase / Math.PI);
        if (stepIdx !== state.lastStepIdx) {
            state.lastStepIdx = stepIdx;
            audio.footstep(hspeed / WALK_SPEED, state.crouching);
        }
    }

    camera.position.set(state.pos.x, state.pos.y + bob, state.pos.z);
    camera.rotation.set(state.pitch, state.yaw, 0, 'YXZ');
}

/* ==================== 性能统计 ==================== */

const perf = {
    deltas: [],          // 最近 180 帧耗时（ms）
    lastT: 0,
    nextAt: 0,           // 限帧用的"下一帧最早时刻"游标
    acc: 0,
    hudAcc: 0,
    reportAcc: 0,
    worstMs: 0,
    stalls: 0,           // 超过 50ms 的卡顿次数
    frames: 0,
};

function resetPerf() {
    perf.deltas.length = 0;
    perf.worstMs = 0;
    perf.stalls = 0;
    perf.frames = 0;
}

function pushFrame(ms) {
    perf.deltas.push(ms);
    if (perf.deltas.length > 180) perf.deltas.shift();
    if (ms > perf.worstMs) perf.worstMs = ms;
    if (ms > 50) perf.stalls++;
    perf.frames++;
}

function stats() {
    const n = perf.deltas.length;
    if (!n) return { fps: 0, low1: 0, worst: 0 };
    let sum = 0;
    for (let i = 0; i < n; i++) sum += perf.deltas[i];
    const mean = sum / n;

    // 1% 低帧：最差 1% 帧的平均耗时（更能反映卡顿体感）
    const sorted = perf.deltas.slice().sort((a, b) => a - b);
    const idx = Math.max(0, Math.floor(sorted.length * 0.99) - 1);
    const p99 = sorted[idx] || mean;

    return {
        fps: 1000 / mean,
        low1: 1000 / p99,
        worst: perf.worstMs,
    };
}

function updateHud() {
    const s = stats();
    const fpsTxt = s.fps.toFixed(1);
    dom.fps.innerHTML = `${fpsTxt}<small>FPS</small>`;

    let cls = 'good';
    if (s.fps < 30) cls = 'bad';
    else if (s.fps < 50) cls = 'mid';
    dom.fps.className = cls;

    if (!state.detail) { dom.detail.textContent = ''; return; }

    const info = renderer.info;
    const size = renderer.getSize(new THREE.Vector2());
    const ratio = renderer.getPixelRatio();
    const px = `${Math.round(size.x * ratio)}×${Math.round(size.y * ratio)}`;

    const hs = hall ? hall.stats() : null;
    const hallLine = hs
        ? `\n展品 ${hs.items} · 可见架 ${hs.visibleShelves}/${hs.shelfCount} · 可见盒 ${hs.visibleBoxes}`
        + `\n封面 成功${hs.covers.ok}/排队${hs.covers.queued} · 失败${hs.covers.fail}(超时${hs.covers.timeout}) · 进行中${hs.covers.inflight}+${hs.covers.pending}`
        : '';

    dom.detail.textContent =
        `绘制 ${info.render.calls} 次 · 三角 ${info.render.triangles.toLocaleString()} · 贴图 ${info.memory.textures}\n` +
        `渲染倍率 ${ratio.toFixed(2)}x（${px}）\n` +
        `1% 低帧 ${s.low1.toFixed(1)} · 最差 ${s.worst.toFixed(1)}ms · 卡顿 ${perf.stalls} 次` +
        hallLine;
}

/* ==================== UI 交互 ==================== */

function cycleScale() {
    state.scaleIndex = (state.scaleIndex + 1) % SCALE_STEPS.length;
    const r = SCALE_STEPS[state.scaleIndex];
    renderer.setPixelRatio(r);
    onResize();
    resetPerf();
    dom.btnScale.textContent = `渲染倍率 ${r.toFixed(2)}x`;
    dom.btnScale.classList.toggle('on', r !== 1.0);
}

/**
 * 帧率上限循环切换：不限 → 90 → 60 → 30。
 * 目的：省电 / 降温 / 避免"设备跑不满高刷反而抖动"。
 */
function cycleFps() {
    state.fpsIndex = (state.fpsIndex + 1) % FPS_STEPS.length;
    const cap = FPS_STEPS[state.fpsIndex];
    dom.btnFps.textContent = `帧率上限：${FPS_LABELS[cap]}`;
    dom.btnFps.classList.toggle('on', cap !== 0);
    // 重置限帧游标：否则切档后可能苦等旧的 nextAt，表现为"切了没反应"
    perf.nextAt = 0;
    perf.lastT = 0;
    resetPerf();   // 重算帧率统计，免得旧数据干扰判断
}

/**
 * 渲染倍率循环切换。
 * 旧的「压力测试」开关已移除（M4：基准在 M0 就测完了，留着只是干扰）。
 */

function toggleDetail() {
    state.detail = !state.detail;
    dom.detail.style.display = state.detail ? '' : 'none';
    dom.btnDetail.textContent = `详细数据：${state.detail ? '开' : '关'}`;
    dom.btnDetail.classList.toggle('on', state.detail);
}

/** 声音开关（默认开：首次触摸后自动启动） */
function toggleSound() {
    const muted = audio.toggle();
    if (dom.btnSound) {
        dom.btnSound.textContent = '声音：' + (muted ? '关' : '开');
        dom.btnSound.classList.toggle('on', !muted);
    }
    // M5-d：音乐播放器跟着"声音开关"走（用 gain 节点控，不是 el.volume）
    if (music) music.setMuted(muted);
}

function bindUi() {
    dom.btnScale.addEventListener('click', cycleScale);
    if (dom.btnFps) dom.btnFps.addEventListener('click', cycleFps);
    // dom.btnStress.addEventListener(...)：压力测试已移除
    dom.btnDetail.addEventListener('click', toggleDetail);
    if (dom.btnSound) dom.btnSound.addEventListener('click', toggleSound);

    // 动作键：用 pointerdown 触发（比 click 更跟手），并给按压反馈
    const bindAction = (el, fn) => {
        if (!el) return;
        el.addEventListener('pointerdown', (e) => {
            e.preventDefault();
            e.stopPropagation();
            el.classList.add('press');
            fn();
        }, { passive: false });
        const up = () => el.classList.remove('press');
        el.addEventListener('pointerup', up, { passive: true });
        el.addEventListener('pointercancel', up, { passive: true });
        el.addEventListener('pointerleave', up, { passive: true });
    };
    bindAction(dom.btnJump, tryJump);
    bindAction(dom.btnCrouch, toggleCrouch);
    bindAction(dom.btnRun, () => setRunning(!state.running));

    dom.btnScale.classList.toggle('on', SCALE_STEPS[state.scaleIndex] !== 1.0);
    if (dom.btnSound) dom.btnSound.classList.add('on');
    dom.btnDetail.classList.add('on');
}

/* ==================== 个人收藏馆：数据接入 ==================== */

const tapRay = new THREE.Raycaster();
const tapNdc = new THREE.Vector2();

/** 从原生桥接读取本机库存并灌入展厅 */
function loadLibrary() {
    if (!hall) return;

    if (!bridge || typeof bridge.getMyLibrary !== 'function') {
        if (dom.hint) dom.hint.textContent = '桥接不可用：离线展厅需要在 YukiHub App 内打开';
        return;
    }
    try {
        const raw = bridge.getMyLibrary();
        const data = JSON.parse(raw);
        if (data && data.error) {
            if (dom.hint) dom.hint.textContent = '读取本机库存失败：' + data.error;
            return;
        }
        const games = (data && data.games) ? data.games : [];
        hall.setGames(games);
        if (dom.hint) {
            dom.hint.textContent = games.length
                ? ('本机库存 ' + games.length + ' 款 · 左半屏摇杆移动 · 右半屏转视角 · 轻点盒子看详情')
                : '本机还没有游戏库存，先回 App 扫描 / 添加游戏再来逛';
        }
    } catch (e) {
        if (dom.hint) dom.hint.textContent = '库存解析失败：' + ((e && e.message) ? e.message : e);
    }
}
/**
 * 底部提示条：临时改文案，2.2 秒后恢复成原始（库存）提示。
 * 用于"播放/暂停""专辑位待接入"这类轻量反馈 —— 不引入新的 DOM 元素。
 */
let _hintTimer = 0;
let _hintOrig = '';
function showHint(msg) {
    if (!dom.hint) return;
    if (!_hintOrig) _hintOrig = dom.hint.textContent;
    dom.hint.textContent = msg;
    clearTimeout(_hintTimer);
    _hintTimer = setTimeout(() => {
        if (dom.hint) dom.hint.textContent = _hintOrig;
    }, 2200);
}

/** 轻点屏幕 → 射线拾取展品（点空处则收起详情） */
function handleTap(clientX, clientY) {
    if (!hall || !renderer || !camera) return;
    // 选品浮层开着的时候，点击归浮层处理，不要在 3D 场景里误拾取
    if (picker && picker.isOpen()) return;
    // M5-d：曲目浮层刚关闭的冷却期内忽略 tap（同一手势的合成 click
    // 会穿透到 3D 画布 —— 不挡的话会误触碟机/专辑位，表现为"切歌后马上又停"）
    if (music && music.tapGuarded && music.tapGuarded()) return;
    const rect = renderer.domElement.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    tapNdc.x = ((clientX - rect.left) / rect.width) * 2 - 1;
    tapNdc.y = -((clientY - rect.top) / rect.height) * 2 + 1;
    tapRay.setFromCamera(tapNdc, camera);

    // ★ 音乐厅的物件（碟机 / 专辑位）采用**独立射线集**，
    //   优先检测：它们与主厅相距 20 米以上，几何上不会互相干扰，
    //   但分成两套后 pick 逻辑各自独立、互不牵连（也便于以后接真数据）。
    if (concert && concert.pickables && concert.pickables.length) {
        const chits = tapRay.intersectObjects(concert.pickables, false);
        if (chits.length) {
            const obj = chits[0].object;
            audio.click();
            if (obj.userData && obj.userData.concertDeck) {
                // M5-d：点碟机 → 切换真实播放（没有曲目时给引导）
                if (music && (music.currentTrack() || (music.tracks() && music.tracks().length))) {
                    music.toggle();
                } else {
                    showHint('留声机还没有曲目 · 去 App「音乐库」添加 ♪');
                }
                return;
            }
            if (obj.userData && obj.userData.concertAlbumSlot !== undefined) {
                // M5-d：点专辑位 → 弹曲目浮层（真实数据）；没有数据时给引导
                const slot = obj.userData.concertAlbumSlot;
                const album = obj.userData.album;
                if (album && album.id !== undefined) {
                    music.openAlbum(album.id);
                } else {
                    showHint('这个位置还没有专辑 · 去 App「音乐库」添加 ♪');
                }
                return;
            }
        }
    }

    const hit = hall.pick(tapRay);
    if (!hit) { hall.closeDetail(); return; }

    audio.click();
    // 主题展台（空台/点到底盘）→ 弹选品浮层；其余 → 原来的详情卡
    if (hit && typeof hit === 'object' && hit.isIsland === true) {
        hall.closeDetail();
        // ★ 同步打开选品浮层。
        //   注意：这次 pointerup 之后，浏览器还会把同一手势合成为一次 click
        //   派发给"抬起坐标下"的 DOM 元素 —— 此时浮层已盖在那里，卡片会直接
        //   收到 click 并"瞬间选中游戏"。picker 内部用 openedAt + 冷却期挡掉了。
        picker.openPicker(hit.slot);
        return;
    }
    hall.openDetail(hit);
}

/* ==================== 主循环 ==================== */

/**
 * 把"手柄动作"翻译成游戏行为（动作层 → 逻辑）。
 * 每个动作都尽量复用已有函数（handleTap / picker / toggleDetail），
 * 避免"手柄走一套、触屏走另一套"的分裂实现。
 */
function applyPadActions(pad) {
    // ⚠️ 没接手柄时**必须整段跳过**。
    //    否则 poll() 把 act.run 归零后，下面那句 setRunning(false) 会每帧执行，
    //    把键盘 Shift / 触摸奔跑键设的 true 立刻改回 false —— 表现为"奔跑完全失灵"。
    if (!pad || !pad.connected) return;

    // ---- 视角：右摇杆（模拟量 → 逐帧累积，和触屏拖动同一条路径）----
    const LOOK = 2.4;               // 弧度/秒（满推时）
    if (pad.lookX) state.yaw -= pad.lookX * LOOK * (1 / 60);
    if (pad.lookY) state.pitch -= pad.lookY * LOOK * (1 / 60);
    clampPitch();

    // ---- 跑：扳机 / L3（持续状态）----
    if (pad.act.run !== state.running) setRunning(pad.act.run);

    // ---- A：交互 ----
    if (pad.act.interact) {
        if (picker && picker.isOpen()) {
            // 浮层开着：A = 选中当前高亮项（这里简化为"确认第一张"由 picker 自己处理）
            // 为保证行为可预期，A 在浮层里等同于"关闭浮层"，避免误选。
            picker.closePicker();
        } else if (detailOpen()) {
            closeDetail();
        } else {
            // 对屏幕中心做一次射线（十字准星交互），命中什么就交给 handleTap 同款逻辑
            doCenterInteract();
        }
    }

    // ---- B：取消 / 关闭 ----
    if (pad.act.cancel) {
        if (picker && picker.isOpen()) picker.closePicker();
        else if (detailOpen()) closeDetail();
    }

    // ---- X：更换展品（等价于点悬浮按钮）----
    if (pad.act.swap) {
        const slot = nearestIslandSlot();
        if (slot >= 0 && picker) {
            audio.click();
            picker.openPicker(slot);
        }
    }

    // ---- Start：调试面板 ----
    if (pad.act.menu) {
        const d = document.getElementById('debug');
        if (d) d.hidden = !d.hidden;
    }
}

/** 屏幕中心射线交互（手柄十字准星） */
function doCenterInteract() {
    if (!hall || !renderer || !camera) return;
    const rect = renderer.domElement.getBoundingClientRect();
    // 用屏幕正中心替代手指坐标
    handleTap(rect.left + rect.width / 2, rect.top + rect.height / 2);
}

/** 详情面板是否开着 */
function detailOpen() {
    const d = document.getElementById('detail');
    return !!(d && !d.hidden);
}

/**
 * 找出"离玩家最近且在交互范围内的展台槽位"。
 * 范围与悬浮按钮的显示距离一致（3.2m），保证"看得见按钮就能按 X"。
 */
function nearestIslandSlot() {
    if (!hall || !camera) return -1;
    return hall.nearestIslandSlot ? hall.nearestIslandSlot(camera.position) : -1;
}

/** 手柄连接提示（Q4）：右上角小标签 */
function setPadHint(on) {
    const el = document.getElementById('pad-hint');
    if (!el) return;
    el.hidden = !on;
}

/**
 * 手柄连接/断开的提示刷新（每 0.5 秒查一次，不必每帧）。
 */
let padHintAcc = 0;
function updatePadHint(dt) {
    padHintAcc += dt;
    if (padHintAcc < 0.5) return;
    padHintAcc = 0;
    if (!gamepad) return;
    const on = gamepad.isConnected();
    if (on !== state.padHintOn) {
        state.padHintOn = on;
        setPadHint(on);
        if (on) audio.click();          // 连上给一声反馈
    }
}

function tick() {
    requestAnimationFrame(tick);

    const now = performance.now();
    if (!perf.lastT) perf.lastT = now;

    // ---- 软件限帧 ----
    // 上限为 0 表示"不限"，直接跟屏幕刷新率走。
    // 做法：距上一帧不足 minInterval 就跳过本次渲染（但 rAF 照常排队）。
    // 注意这会**降低**帧率，不会提高 —— 想上 120 得靠系统给高刷。
    const cap = FPS_STEPS[state.fpsIndex] || 0;
    if (cap > 0) {
        const interval = 1000 / cap;
        // 累计误差补偿：
        //   不能用"距上一帧 < interval 就 return"那种写法 ——
        //   rAF 只在屏幕 vsync 边界触发，150Hz 屏上跳一帧会直接掉到 ~75，
        //   120Hz 屏上"跳过一帧"就变成 60（这就是"90 档实际只跑 60"的原因）。
        //   改成维护 nextAt 游标：到点才渲染，并把游标按 interval 递增（超过就追平），
        //   这样平均帧率能贴近目标值，而不是被锁到屏幕刷新率的整数分之一。
        if (now < perf.nextAt) return;
        perf.nextAt += interval;
        if (perf.nextAt < now) perf.nextAt = now + interval;   // 落后太多就追平，避免连续补帧
    }
let dt = (now - perf.lastT) / 1000;
    perf.lastT = now;

    // ---- 手柄：读状态 → 应用动作 ----
    if (gamepad) {
        const pad = gamepad.poll();
        state.pad = pad;                 // updateMovement 会读它做移动
        applyPadActions(pad);
        updatePadHint(dt);
    }


    pushFrame(dt * 1000);
    if (dt > 0.1) dt = 0.1;          // 后台切回时防止瞬移

    updateMovement(dt);
    if (hall) hall.update(dt, camera);
    if (concert) concert.update(dt, camera);
    if (dusty) updateDust(dt);
    renderer.render(scene, camera);

    perf.hudAcc += dt;
    if (perf.hudAcc >= 0.25) {
        perf.hudAcc = 0;
        updateHud();
    }

    // 每 5 秒把性能摘要回传原生（有桥接时），便于直接在 App 里看结果
    perf.reportAcc += dt;
    if (perf.reportAcc >= 5) {
        perf.reportAcc = 0;
        if (bridge && typeof bridge.onPerf === 'function') {
            const s = stats();
            try {
                bridge.onPerf(
                    JSON.stringify({
                        fps: Number(s.fps.toFixed(1)),
                        low1: Number(s.low1.toFixed(1)),
                        worstMs: Number(s.worst.toFixed(1)),
                        calls: renderer.info.render.calls,
                        triangles: renderer.info.render.triangles,
                        textures: renderer.info.memory.textures,
                        pixelRatio: renderer.getPixelRatio(),
                        stalls: perf.stalls,
                    })
                );
            } catch (err) { /* 桥接异常不影响渲染 */ }
        }
    }
}

/* ==================== 启动 ==================== */

function boot() {
    try {
        initRenderer();
        initScene();
        bindInput();
        bindUi();
        onResize();

        dom.loading.classList.add('hide');
        dom.hud.hidden = false;
        dom.debug.hidden = false;
        dom.actions.hidden = false;
        dom.hint.hidden = false;

        // 进场淡入：从黑场淡出，像"推门走进展厅"
        const fade = document.getElementById('fade');
        if (fade) {
            fade.classList.add('hide');
            setTimeout(() => { if (fade.parentNode) fade.parentNode.removeChild(fade); }, 950);
        }

        perf.lastT = 0;
        tick();

        if (bridge && typeof bridge.onReady === 'function') {
            try { bridge.onReady('M0'); } catch (err) { /* ignore */ }
        }
    } catch (err) {
        fail('展厅初始化失败', 'WebGL 初始化过程中出错。', String((err && err.message) || err));
    }
}

boot();
