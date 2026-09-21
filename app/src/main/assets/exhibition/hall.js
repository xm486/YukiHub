/**
 * YukiHub 离线个人展厅 · 陈列模块（M1）
 * =========================================================
 * 把本机库存变成"可以逛的收藏馆"：
 *   · 沿墙书架分格陈列，盒子正面贴封面
 *   · 状态即灯效：收藏=金框 / 在玩=呼吸光 / 玩过=亮 / 未玩=暗
 *   · 中央展台：把"最值得看的那一件"悬浮旋转展示
 *   · 渐进加载 + 分架可见性剔除（走近才建、才贴图，控制内存与绘制调用）
 *   · 点击盒子 → 详情面板（标题/时长/最近游玩/标签/徽标）
 *
 * 内存策略（重要）：
 *   封面统一降采样到 200×288 再上传 GPU（一张约 230KB），
 *   且**只为"已建出来且可见"的架子**创建纹理 —— 库存几百款也不会爆显存。
 *   NSFW 封面默认做"马赛克化"处理（低分辨率放大），与 App 的日常习惯一致；
 *   要原图直出把 BLUR_NSFW 改成 false 即可。
 */

import * as THREE from './vendor/three.module.min.js';
import { createViewer } from './viewer.js';

/* ==================== 配置 ==================== */

const CFG = {
    boxW: 0.50,
    boxH: 0.72,
    boxD: 0.10,
    rows: [4.80, 3.85, 2.90, 1.95, 1.00],   // 书架各层高度（从上层开始陈列，与分区牌顺序一致）
    spacing: 0.62,                     // 同一层内相邻盒子间距
    texW: 256,                         // 封面纹理宽（降采样目标）
    texH: 368,
    maxInflight: 1,                    // 同时加载的封面数（先串行，排除并发挂起；确认稳定后可调到 2~3）
    coverTimeoutMs: 6000,              // 单张封面加载超时（防止 inflight 永久卡死）
    coverMaxAttempts: 3,               // 单张封面最多尝试次数
    cullDist: 30,                      // 超过这个距离的架子不建/不显示（房间 34×22，30 保证远看也是满的）
    segSlots: 12,                      // 每段架子的位置数（分段越细剔除越准）
    minRowSlots: 3,                    // 每层最少格数：分区件数很少时也留出一小段架子，避免孤零零一格
    zoneSignCols: 3,                   // 左侧留给分区牌的格数（后墙的槽位从这里开始排）
};

const BLUR_NSFW = true;

const ROOM = { w: 34, d: 22, h: 7 };

/* ==================== 工具 ==================== */

/** 标题哈希 → 稳定色相（无封面时用） */
function hashHue(str) {
    let h = 0;
    for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) % 360;
    return h;
}

function fmtTime(ms) {
    if (!ms || ms <= 0) return '未记录';
    const h = ms / 3600000;
    if (h < 1) return Math.round(ms / 60000) + ' 分钟';
    return h.toFixed(1) + ' 小时';
}

function fmtDate(ms) {
    if (!ms || ms <= 0) return '从未';
    const d = new Date(ms);
    const now = Date.now();
    const days = Math.floor((now - ms) / 86400000);
    if (days <= 0) return '今天';
    if (days === 1) return '昨天';
    if (days < 30) return days + ' 天前';
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

/** 给封面加"装裱边框"：深色外框 + 内侧细高光 —— 从"海报墙"变成"装裱展品" */
function drawFrame(g, W, H) {
    g.strokeStyle = 'rgba(0,0,0,0.55)';
    g.lineWidth = 10;
    g.strokeRect(5, 5, W - 10, H - 10);
    g.strokeStyle = 'rgba(255,255,255,0.10)';
    g.lineWidth = 2;
    g.strokeRect(11, 11, W - 22, H - 22);
}

function wrapText(ctx, text, maxWidth, maxLines) {
    const lines = [];
    let cur = '';
    for (const ch of text) {
        const test = cur + ch;
        if (ctx.measureText(test).width > maxWidth && cur) {
            lines.push(cur);
            cur = ch;
            if (lines.length >= maxLines) break;
        } else {
            cur = test;
        }
    }
    if (lines.length < maxLines && cur) lines.push(cur);
    return lines;
}

/* ==================== 主模块 ==================== */

export function createHall(scene, opts = {}) {

    const log = (typeof opts.log === 'function') ? opts.log : function () { };

    const group = new THREE.Group();
    scene.add(group);

    /* ---------- 共享几何 / 材质 ---------- */

    const caseGeo = new THREE.BoxGeometry(CFG.boxW + 0.05, CFG.boxH + 0.05, CFG.boxD + 0.02);
    const caseMat = new THREE.MeshStandardMaterial({ color: 0xFFFFFF, roughness: 0.82, metalness: 0.06 });

    const coverGeo = new THREE.PlaneGeometry(CFG.boxW, CFG.boxH);

    const shelfGeo = new THREE.BoxGeometry(1, 1, 1);
    const shelfMat = new THREE.MeshStandardMaterial({ color: 0x3A4556, roughness: 0.85, metalness: 0.05 });

    // 灯带（自发光，不参与光照计算 → 几乎零开销，但"展厅感"全靠它）
    const lampMat = new THREE.MeshBasicMaterial({ color: 0xFFEFD2 });
    // 铭牌（金属小牌，用 InstancedMesh → 一整架只花 1 次绘制）
    const plateGeo = new THREE.BoxGeometry(CFG.boxW * 0.86, 0.055, 0.012);
    const plateMat = new THREE.MeshStandardMaterial({
        color: 0xC0A96B, roughness: 0.32, metalness: 0.78,
        emissive: 0x2A2313, emissiveIntensity: 0.55,
    });

    /* ---------- 展厅骨架：书架横板 + 中央展台 ---------- */

    const shelves = [];      // { center, built, slots:[{pos, quat, item}], cases, covers[], glow[] }
    const items = [];        // { game, slot }
    let pedestal = null;
    let signMesh = null;     // 馆藏标题牌（必须在这里声明：buildSign() 在文件靠前处就被调用）

    // 旋转展架的状态（同样必须在这里声明：buildRotator() 在下面几行就被调用）
    let rotator = null;
    let spinner = null;
    const rotatorMeshes = [];
    const ROTATOR_MAX = 5;
    const ROTATOR_POS = { x: -7.2, z: 2.4 };   // 出生点左前侧：一进场就能看见，又不挡主展墙
    const zoneSigns = [];   // 分区牌（必须在这里声明：buildZoneSigns() 在下面几行就被调用）

    // 左右墙信息展板（与书架"实物陈列"互补：这里放统计信息）
    let panelLeft = null;
    let panelRight = null;

    // 主题展台（玩家自定义陈列：4 座，两排各两座）
    // islands[i] = { group, mesh, slot, game }，mesh 为 null 表示空台
    const islands = [];
    const itemGames = [];    // 当前库存的原始列表（setGames 时灌入，供展台按 id 反查）
    let islandsBuilt = false;
    const ISLAND_POS = [
        { x: 7.5, z: -2.5 }, { x: 10.5, z: -2.5 },   // 第一排（靠后）
        { x: 7.5, z: 2.5 }, { x: 10.5, z: 2.5 },     // 第二排（靠前）
    ];
    // 盒子几何：**共用**（同一类盒子尺寸相同）。
    // 为什么必须共用：disposeBoxMaterials() 只释放材质不释放几何，
    // 而这些盒子会在"换展品 / 换库存 / 重填展架"时反复重建 ——
    // 若每次 new BoxGeometry，几何就会一直累积泄漏。
    const ISLAND_BOX_GEO = new THREE.BoxGeometry(0.52, 0.74, 0.11);
    const ROTATOR_BOX_GEO = new THREE.BoxGeometry(0.46, 0.66, 0.10);
    const PEDESTAL_BOX_GEO = new THREE.BoxGeometry(0.62, 0.9, 0.13);

    buildShelfSlots();
    buildPedestal();
    buildSign();
    buildZoneSigns();
    buildRotator();
    buildWallPanels();
    buildIslands();

    /**
     * 先把所有"位置"算出来（不建网格），后续按距离懒建。
     *
     * rowCounts：每层要陈列的件数（长度 = CFG.rows.length）
     *   · 传了 → **每层按该分区的件数自适应宽度**（件数少时保留 minRowSlots 个空位）
     *   · 不传 → 每层铺满整墙（初始化时的默认布局）
     *
     * 为什么必须自适应：后墙一层容量是 49 格，而库存可能只有 33 款 ——
     * 若每层都铺满，"每层一个分类"就永远只用到第一层，分区形同虚设。
     * 现在每层宽度跟着件数走，"一层一个分区"才真正成立。
     *
     * 每段架子都会记录 zone（属于哪一层 / 哪个分区），
     * 这样填格时可以**按层精确对应**，不会因为某层有空位而错位。
     *
     * 溢出去向：某分区的件数超过后墙容量时，继续铺到右墙 → 左墙（绕房间一圈）。
     */
    function buildShelfSlots(rowCounts) {
        const halfW = ROOM.w / 2, halfD = ROOM.d / 2;
        const wallOff = 0.42;          // 离墙距离
        const signSpan = CFG.zoneSignCols * CFG.spacing;   // 后墙左端留给分区牌的宽度
        const rightMargin = 1.34;
        const capBack = Math.floor((ROOM.w - signSpan - rightMargin) / CFG.spacing);
        const capSide = Math.floor((ROOM.d - 3.0) / CFG.spacing);

        // 每层把件数分配到：后墙 → 右墙 → 左墙
        // 件数 < minRowSlots 时，后墙仍留 minRowSlots 个槽位（多出来的是空位，不摆盒子）
        const alloc = CFG.rows.map((_, i) => {
            if (!rowCounts) return { back: capBack, right: capSide, left: capSide };
            const c = Math.max(0, rowCounts[i] | 0);
            const back = Math.min(Math.max(c, CFG.minRowSlots), capBack);
            let rem = c - back;
            const right = Math.min(Math.max(rem, 0), capSide); rem -= right;
            const left = Math.min(Math.max(rem, 0), capSide);
            return { back, right, left };
        });

        const yawBack = 0, yawLeft = Math.PI / 2, yawRight = -Math.PI / 2;
        const zBack = -halfD + wallOff;
        const xRight = halfW - wallOff;
        const xLeft = -halfW + wallOff;

        // 按"层 → 墙"顺序推入，保证填格时同一层的格子是连续的
        const specs = [];
        for (let i = 0; i < CFG.rows.length; i++) {
            const y = CFG.rows[i];
            const a = alloc[i];
            if (a.back > 0) specs.push({
                total: a.back, y, yaw: yawBack, zone: i, wall: 'back',
                // 后墙**左对齐**：从分区牌右侧开始往右排。
                // （注意：不能写成"居中"形式，否则每层都会挤在左侧同一处）
                pos: (k) => new THREE.Vector3(
                    -halfW + signSpan + k * CFG.spacing,
                    y, zBack),
            });
            if (a.right > 0) specs.push({
                total: a.right, y, yaw: yawRight, zone: i, wall: 'right',
                pos: (k) => new THREE.Vector3(xRight, y, -((a.right - 1) * CFG.spacing) / 2 + k * CFG.spacing),
            });
            if (a.left > 0) specs.push({
                total: a.left, y, yaw: yawLeft, zone: i, wall: 'left',
                pos: (k) => new THREE.Vector3(xLeft, y, -((a.left - 1) * CFG.spacing) / 2 + k * CFG.spacing),
            });
        }

        // 分段 → shelves
        for (const sp of specs) {
            for (let s = 0; s < sp.total; s += CFG.segSlots) {
                const n = Math.min(CFG.segSlots, sp.total - s);
                const slots = [];
                let sumX = 0, sumZ = 0;
                for (let k = 0; k < n; k++) {
                    const p = sp.pos(s + k);
                    sumX += p.x; sumZ += p.z;
                    const quat = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, sp.yaw, 0));
                    slots.push({ pos: p, quat, item: null });
                }
                shelves.push({
                    center: new THREE.Vector3(sumX / n, sp.y, sumZ / n),
                    yaw: sp.yaw,
                    wall: sp.wall,
                    zone: sp.zone,        // 属于哪一层（= 哪个分区），填格时按层对应
                    zonePos: s,           // 在该层内的顺序（0 = 最靠左/最靠前的一段）
                    slots,
                    built: false,
                    cases: null,
                    covers: [],
                    boards: [],
                });
            }
        }
    }

    /** 书架横板：每段架子配一块板，视觉上才像书架 */
    function buildBoards(shelf) {
        const n = shelf.slots.length;
        const width = n * CFG.spacing;
        const first = shelf.slots[0].pos;
        const cx = shelf.slots.reduce((a, s) => a + s.pos.x, 0) / n;
        const cz = shelf.slots.reduce((a, s) => a + s.pos.z, 0) / n;
        const y = first.y;

        const board = new THREE.Mesh(shelfGeo, shelfMat);
        board.scale.set(
            Math.abs(Math.cos(shelf.yaw)) > 0.5 ? width : 0.34,
            0.06,
            Math.abs(Math.cos(shelf.yaw)) > 0.5 ? 0.34 : width
        );
        board.position.set(cx, y - CFG.boxH / 2 - 0.07, cz);
        group.add(board);
        shelf.boards.push(board);

        // 隔板背板，遮住墙缝
        const back = new THREE.Mesh(shelfGeo, shelfMat);
        back.scale.set(
            Math.abs(Math.cos(shelf.yaw)) > 0.5 ? width : 0.05,
            CFG.boxH + 0.3,
            Math.abs(Math.cos(shelf.yaw)) > 0.5 ? 0.05 : width
        );
        const nrm = new THREE.Vector3(Math.sin(shelf.yaw), 0, Math.cos(shelf.yaw)).multiplyScalar(-0.13);
        back.position.set(cx + nrm.x, y, cz + nrm.z);
        group.add(back);
        shelf.boards.push(back);
    }

    /**
     * 柜体：侧板 + 顶板 + 底板 + 灯带 + 每格铭牌。
     * 有了这些，盒子才像"放在柜子里"，而不是"贴在墙上"。
     */
    function buildCabinet(shelf) {
        const n = shelf.slots.length;
        const width = n * CFG.spacing;
        const cx = shelf.slots.reduce((a, s) => a + s.pos.x, 0) / n;
        const cz = shelf.slots.reduce((a, s) => a + s.pos.z, 0) / n;
        const y = shelf.slots[0].pos.y;
        const alongX = Math.abs(Math.cos(shelf.yaw)) > 0.5;
        const depth = 0.44;
        const nrm = new THREE.Vector3(Math.sin(shelf.yaw), 0, Math.cos(shelf.yaw));
        const dir = new THREE.Vector3(alongX ? 1 : 0, 0, alongX ? 0 : 1);

        const addPanel = (w, h, dp, px, py, pz) => {
            const m = new THREE.Mesh(shelfGeo, shelfMat);
            m.scale.set(alongX ? w : dp, h, alongX ? dp : w);
            m.position.set(px, py, pz);
            group.add(m);
            shelf.boards.push(m);
            return m;
        };

        // 顶板 / 底板
        addPanel(width, 0.055, depth, cx, y + CFG.boxH / 2 + 0.105, cz);
        addPanel(width, 0.065, depth, cx, y - CFG.boxH / 2 - 0.105, cz);

        // 左右侧板
        const half = width / 2 - 0.025;
        addPanel(0.05, CFG.boxH + 0.36, depth, cx - dir.x * half, y, cz - dir.z * half);
        addPanel(0.05, CFG.boxH + 0.36, depth, cx + dir.x * half, y, cz + dir.z * half);

        // 顶部灯带（向外偏一点，光带正好落在展品上沿）
        const strip = new THREE.Mesh(shelfGeo, lampMat);
        strip.scale.set(alongX ? width - 0.12 : 0.055, 0.032, alongX ? 0.055 : width - 0.12);
        const soff = nrm.clone().multiplyScalar(depth / 2 - 0.07);
        strip.position.set(cx + soff.x, y + CFG.boxH / 2 + 0.065, cz + soff.z);
        group.add(strip);
        shelf.boards.push(strip);

        // 每格铭牌
        const plates = new THREE.InstancedMesh(plateGeo, plateMat, n);
        const m4 = new THREE.Matrix4();
        const sc = new THREE.Vector3(1, 1, 1);
        for (let i = 0; i < n; i++) {
            const s = shelf.slots[i];
            const poff = nrm.clone().multiplyScalar(depth / 2 - 0.03);
            const p = new THREE.Vector3(
                s.pos.x + poff.x,
                y - CFG.boxH / 2 - 0.05,
                s.pos.z + poff.z
            );
            m4.compose(p, s.quat, sc);
            plates.setMatrixAt(i, m4);
        }
        plates.instanceMatrix.needsUpdate = true;
        group.add(plates);
        shelf.boards.push(plates);
    }

    /** 馆藏概况信息墙（后墙正上方）：进场第一眼看到的"馆名 + 馆藏统计" */

    function buildSign() {
        signMesh = new THREE.Mesh(
            new THREE.PlaneGeometry(7.2, 1.5),
            new THREE.MeshBasicMaterial({ transparent: true })
        );
        signMesh.position.set(0, 6.0, -ROOM.d / 2 + 0.25);
        group.add(signMesh);
        drawSign(null);
    }

    function drawSign(st) {
        if (!signMesh) return;
        const W = 960, H = 200;          // 比例 4.8:1，与牌面 7.2×1.5 一致
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        g.fillStyle = 'rgba(16,22,34,0.92)';
        g.fillRect(0, 0, W, H);
        g.strokeStyle = 'rgba(196,169,107,0.85)';
        g.lineWidth = 5;
        g.strokeRect(8, 8, W - 16, H - 16);

        // 左侧：馆名 + 副标题
        g.fillStyle = '#F2E6C8';
        g.font = 'bold 46px sans-serif';
        g.textAlign = 'left';
        g.textBaseline = 'alphabetic';
        g.fillText('我的收藏馆', 40, 96);
        g.fillStyle = 'rgba(160,190,225,0.75)';
        g.font = '19px sans-serif';
        g.fillText('YukiHub 3D 展厅', 42, 132);
        g.fillText('个人藏品陈列', 42, 158);

        // 竖分隔线
        g.strokeStyle = 'rgba(196,169,107,0.45)';
        g.lineWidth = 3;
        g.beginPath();
        g.moveTo(262, 28);
        g.lineTo(262, H - 28);
        g.stroke();

        // 右侧：4 列 × 2 行 统计格
        //   前 4 项是"馆藏规模"，后 4 项是"5 个游玩状态"（收藏是属性，单独放在第 3 格）
        const cells = [
            ['馆藏', st ? st.total + ' 款' : '—'],
            ['总时长', st ? st.hours + ' 小时' : '—'],
            ['收藏(属性)', st ? st.fav + ' 件' : '—'],
            ['有封面', st ? st.withCover + ' 件' : '—'],
            ['正在游玩', st ? st.playing + ' 件' : '—'],
            ['玩过', st ? st.played + ' 件' : '—'],
            ['搁置', st ? st.onhold + ' 件' : '—'],
            ['抛弃 / 未玩', st ? (st.dropped + ' / ' + st.fresh) : '—'],
        ];
        const colW = 160, cols = 4;
        for (let i = 0; i < cells.length; i++) {
            const col = i % cols;
            const row = (i / cols) | 0;
            const cx = 280 + col * colW;
            const cy = 66 + row * 66;
            g.textAlign = 'left';
            g.fillStyle = 'rgba(150,170,196,0.85)';
            g.font = '20px sans-serif';
            g.fillText(cells[i][0], cx, cy - 8);
            g.fillStyle = '#EAF2FF';
            g.font = 'bold 32px sans-serif';
            g.fillText(cells[i][1], cx, cy + 34);
        }

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        if (signMesh.material.map) signMesh.material.map.dispose();
        signMesh.material.map = tex;
        signMesh.material.needsUpdate = true;
    }

    /**
     * 分区牌（贴在后墙左端、每一层书架旁）：
     * 主展墙按"每层一个分类"陈列，从上到下 = 收藏精选 / 正在游玩 / 玩过 / 未开始。
     * 这样任何库存规模都能保持"一眼看清分区"。
     */
    // zoneSigns 已在文件靠前处声明（buildZoneSigns 在初始化时就被调用）

    function buildZoneSigns() {
        const rows = CFG.rows;   // 与后墙陈列顺序一致（上→下）
        const names = ['正在游玩', '玩过', '搁置', '抛弃', '未玩'];
        for (let i = 0; i < rows.length; i++) {
            const mesh = new THREE.Mesh(
                new THREE.PlaneGeometry(1.6, 0.55),
                new THREE.MeshBasicMaterial({ transparent: true })
            );
            // 与书架同一左起点：贴在后墙最左端（书架第一格在 -15.14，牌子占更左的位置）
            mesh.position.set(-16.1, rows[i], -ROOM.d / 2 + 0.45);
            group.add(mesh);
            zoneSigns.push({ mesh, name: names[i], count: 0 });
        }
    }

    function drawZoneSign(z) {
        const W = 360, H = 124;                 // 画布比例贴合牌面 1.6×0.55
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        const empty = z.count <= 0;
        g.fillStyle = empty ? 'rgba(30,38,52,0.55)' : 'rgba(18,24,36,0.88)';
        g.fillRect(0, 0, W, H);
        g.strokeStyle = empty ? 'rgba(120,134,152,0.35)' : 'rgba(196,169,107,0.8)';
        g.lineWidth = 3;
        g.strokeRect(4, 4, W - 8, H - 8);
        g.fillStyle = empty ? 'rgba(160,175,195,0.55)' : '#F0E4C6';
        g.font = 'bold 46px sans-serif';
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillText(z.name, W / 2, 76);
        g.fillStyle = empty ? 'rgba(150,165,185,0.4)' : 'rgba(205,220,240,0.8)';
        g.font = '22px sans-serif';
        g.textAlign = 'right';
        g.textBaseline = 'alphabetic';
        g.fillText(z.count + ' 件', W - 16, 36);

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        if (z.mesh.material.map) z.mesh.material.map.dispose();
        z.mesh.material.map = tex;
        z.mesh.material.needsUpdate = true;
    }

    /** 更新分区牌（由 setGames 调用） */
    function updateZoneSigns(counts) {
        for (let i = 0; i < zoneSigns.length; i++) {
            zoneSigns[i].count = counts[i] || 0;
            drawZoneSign(zoneSigns[i]);
        }
    }

    /** 书脊配色：按标题哈希取低饱和深色（与书架柜体同一套观感） */
    function spineColorOf(title) {
        const s = String(title || '?');
        let h = 0;
        for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
        const c = new THREE.Color();
        c.setHSL((h % 360) / 360, 0.34, 0.26);
        return '#' + c.getHexString();
    }

    /** 书脊贴图：竖排标题 + 上下两道金线 —— 侧看才像真盒子 */
    function makeSpineTexture(game, colorHex) {
        const W = 64, H = 420;
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        g.fillStyle = colorHex;
        g.fillRect(0, 0, W, H);
        g.fillStyle = 'rgba(214,186,120,0.75)';
        g.fillRect(6, 14, W - 12, 3);
        g.fillRect(6, H - 17, W - 12, 3);
        g.save();
        g.translate(W / 2, H / 2);
        g.rotate(Math.PI / 2);
        g.fillStyle = 'rgba(238,244,255,0.92)';
        g.font = 'bold 28px sans-serif';
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        let t = String(game.title || '');
        const maxW = H - 76;
        while (t.length > 2 && g.measureText(t).width > maxW) t = t.slice(0, -1);
        g.fillText(t, 0, 0);
        g.restore();
        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        tex.anisotropy = 4;
        return tex;
    }

    /**
     * 实体盒的 6 个材质，顺序同 three.js BoxGeometry：[+x, -x, +y, -y, +z, -z]
     *   正/反面 = 游戏封面（背面压暗，像盒子背面）
     *   左右侧面 = 书脊（竖排标题）
     *   顶/底面  = 塑料壳
     */
    function makeBoxMaterials(game, coverTex) {
        const base = coverTex || makePlaceholderTexture(game);
        const plastic = new THREE.MeshStandardMaterial({ color: 0x2A313B, roughness: 0.70, metalness: 0.08 });
        const front = new THREE.MeshStandardMaterial({
            map: base, roughness: 0.42, metalness: 0.10,
            emissive: new THREE.Color(0x2A2410), emissiveIntensity: 0.50,
        });
        const back = new THREE.MeshStandardMaterial({
            map: base, color: 0x8E97A3, roughness: 0.62, metalness: 0.05,
        });
        const spine = new THREE.MeshStandardMaterial({
            map: makeSpineTexture(game, spineColorOf(game.title)),
            roughness: 0.56, metalness: 0.10,
        });
        return [spine, spine, plastic, plastic, front, back];
    }

    /** 把封面上到实体盒的正反面（下标 4=前、5=后） */
    function applyCoverToBox(mesh, tex) {
        if (!mesh || !Array.isArray(mesh.material)) return;
        const old = mesh.material[4] && mesh.material[4].map;
        for (const i of [4, 5]) {
            const m = mesh.material[i];
            if (!m) continue;
            m.map = tex;
            m.needsUpdate = true;
        }
        if (old && old !== tex) old.dispose();
    }

    /** 释放实体盒的（可能是数组的）材质与贴图 */
    function disposeBoxMaterials(mesh) {
        if (!mesh || !mesh.material) return;
        const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
        const seen = new Set();
        for (const m of mats) {
            if (!m || seen.has(m)) continue;
            seen.add(m);
            if (m.map) m.map.dispose();
            m.dispose();
        }
    }

    /** 悬挂小牌贴图（例如"最近游玩"） */
    function makeLabelTexture(text) {
        const W = 420, H = 108;
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        g.fillStyle = 'rgba(14,19,29,0.90)';
        g.fillRect(0, 0, W, H);
        g.strokeStyle = 'rgba(196,169,107,0.85)';
        g.lineWidth = 4;
        g.strokeRect(5, 5, W - 10, H - 10);
        g.fillStyle = '#F0E4C6';
        g.font = 'bold 52px sans-serif';
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillText(text, W / 2, H / 2 + 2);
        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        return tex;
    }

    /* ---------- 左右墙信息展板 ---------- */

    /**
     * 左右墙各挂一块展板，面对面放在 z=1.6（避开 x=±14.4 的两排柱子，柱子在 z∈{-8,-1.5,5}）。
     * 定位：x=±16.75（离墙 0.25m），中心高度 2.55m。
     * 内容与书架互补 —— 书架是"实物陈列"，这里放"统计信息"。
     */
    function buildWallPanels() {
        const geo = new THREE.PlaneGeometry(5.2, 2.4);
        panelLeft = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ transparent: true }));
        panelLeft.position.set(-16.75, 2.55, 1.6);
        panelLeft.rotation.y = Math.PI / 2;      // 面朝 +x（房间内侧）
        group.add(panelLeft);

        panelRight = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ transparent: true }));
        panelRight.position.set(16.75, 2.55, 1.6);
        panelRight.rotation.y = -Math.PI / 2;    // 面朝 -x
        group.add(panelRight);
    }

    /** 展板底板：深色底 + 金边 + 标题 + 分隔线（两块共用） */
    function panelBase(g, W, H, title) {
        g.fillStyle = 'rgba(14,19,29,0.90)';
        g.fillRect(0, 0, W, H);
        g.strokeStyle = 'rgba(196,169,107,0.80)';
        g.lineWidth = 5;
        g.strokeRect(10, 10, W - 20, H - 20);
        g.fillStyle = '#F2E6C8';
        g.font = 'bold 40px sans-serif';
        g.textAlign = 'left';
        g.textBaseline = 'alphabetic';
        g.fillText(title, 42, 74);
        g.strokeStyle = 'rgba(196,169,107,0.40)';
        g.lineWidth = 2;
        g.beginPath();
        g.moveTo(42, 96);
        g.lineTo(W - 42, 96);
        g.stroke();
    }

    /** 把一段文字截断到指定宽度内 */
    function fitText(g, text, maxW) {
        let t = String(text == null ? '—' : text);
        if (!t) t = '—';
        while (t.length > 2 && g.measureText(t).width > maxW) t = t.slice(0, -1);
        return t;
    }

    /** 更新两块展板（由 setGames 调用） */
    function drawPanels(st) {
        if (!st) return;
        const W = 1040, H = 480;      // 比例 2.1667，与牌面 5.2×2.4 一致

        // ---------- 左：馆藏概览（数字 + 分区条形图） ----------
        if (panelLeft) {
            const c = document.createElement('canvas');
            c.width = W; c.height = H;
            const g = c.getContext('2d');
            panelBase(g, W, H, '馆藏概览');

            const big = [
                ['馆藏', st.total + ' 款'],
                ['总时长', st.hours + ' h'],
                ['平均', st.avgHours + ' h'],
                ['封面', st.withCover + ' 张'],
            ];
            big.forEach((b, i) => {
                const cx = 60 + i * 240;
                g.fillStyle = 'rgba(150,170,196,0.85)';
                g.font = '24px sans-serif';
                g.textAlign = 'left';
                g.fillText(b[0], cx, 152);
                g.fillStyle = '#EAF2FF';
                g.font = 'bold 50px sans-serif';
                g.fillText(b[1], cx, 210);
            });

            const bars = [
                ['正在游玩', st.playing, '#66E0C0'],
                ['玩过', st.played, '#7FC4FF'],
                ['搁置', st.onhold, '#C08CD8'],
                ['抛弃', st.dropped, '#E88C8C'],
                ['未玩', st.fresh, '#8E9AAB'],
            ];
            const maxV = Math.max(1, bars.reduce((a, b) => Math.max(a, b[1]), 0));
            const barX = 300, barW = 620;
            bars.forEach((b, i) => {
                const y = 258 + i * 40;
                g.fillStyle = 'rgba(150,170,196,0.9)';
                g.font = '24px sans-serif';
                g.textAlign = 'left';
                g.fillText(b[0], 60, y + 21);
                g.fillStyle = 'rgba(255,255,255,0.07)';
                g.fillRect(barX, y, barW, 24);
                g.fillStyle = b[2];
                g.fillRect(barX, y, Math.max(3, barW * (b[1] / maxV)), 24);
                g.fillStyle = '#EAF2FF';
                g.font = 'bold 22px sans-serif';
                g.fillText(String(b[1]), barX + barW + 18, y + 20);
            });

            const tex = new THREE.CanvasTexture(c);
            tex.colorSpace = THREE.SRGBColorSpace;
            if (panelLeft.material.map) panelLeft.material.map.dispose();
            panelLeft.material.map = tex;
            panelLeft.material.needsUpdate = true;
        }

        // ---------- 右：游玩足迹（最近 / 最久 / 最早 / 引擎） ----------
        if (panelRight) {
            const c = document.createElement('canvas');
            c.width = W; c.height = H;
            const g = c.getContext('2d');
            panelBase(g, W, H, '游玩足迹');

            const rows = [
                ['最近游玩', st.recentTitle],
                ['玩得最久', st.topTitle],
                ['首次记录', st.firstDate],
                ['常玩引擎', st.engineTop],
            ];
            rows.forEach((r, i) => {
                const y = 158 + i * 78;
                g.fillStyle = 'rgba(150,170,196,0.85)';
                g.font = '24px sans-serif';
                g.textAlign = 'left';
                g.fillText(r[0], 42, y);
                g.fillStyle = '#EAF2FF';
                g.font = 'bold 34px sans-serif';
                g.fillText(fitText(g, r[1], W - 300), 260, y + 2);
            });

            const tex = new THREE.CanvasTexture(c);
            tex.colorSpace = THREE.SRGBColorSpace;
            if (panelRight.material.map) panelRight.material.map.dispose();
            panelRight.material.map = tex;
            panelRight.material.needsUpdate = true;
        }
    }

    /** 光柱贴图：纵向 alpha 渐变（顶端实、底端虚）—— 让射灯看得见 */
    function makeBeamTexture() {
        const c = document.createElement('canvas');
        c.width = 8;
        c.height = 128;
        const g = c.getContext('2d');
        const grad = g.createLinearGradient(0, 0, 0, 128);
        grad.addColorStop(0.00, 'rgba(255,255,255,0.90)');
        grad.addColorStop(0.35, 'rgba(255,255,255,0.34)');
        grad.addColorStop(1.00, 'rgba(255,255,255,0.00)');
        g.fillStyle = grad;
        g.fillRect(0, 0, 8, 128);
        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        return tex;
    }

        /* ---------- 主题展台（玩家自定义陈列：4 座，两排各两座） ---------- */
    /*
     * 与中央展台的区别：
     *   · 中央展台 = 系统自动挑（收藏 / 最近游玩），玩家不能改
     *   · 主题展台 = 玩家自己点上去摆，槽位存 App（SharedPreferences）
     *
     * 默认全空（只有台座 + 光柱 + 射灯），玩家点一下才弹选品浮层。
     * 不做投影：中央展台那盏已经是全场唯一的投影灯，再加 4 盏阴影开销不划算。
     */

    /**
     * 悬浮按钮（Lv-1）：展台上方的小圆牌，**始终正对相机**。
     * 返回 { group, disc, slot } —— disc 带 userData.islandButton = slot，供 pick() 识别。
     *
     * 为什么圆牌做得比铭牌大得多（⌀0.9m）：
     *   在 3m 外，⌀0.9m 的圆牌在屏幕上约占 110px（屏幕高 ~1400px、fov 72°），
     *   手指（触摸目标建议 ≥48px）轻松压中；而原来 0.68×0.17m 的铭牌只有几像素。
     */
    function makeIslandButton(slot) {
        const bg = new THREE.Group();

        // 背板圆盘（不透明，保证按钮醒目）
        // 尺寸：⌀0.72m（反馈"有点大"，从 0.9 收一点）。
        // 2.5m 外约 100px，仍远大于 48px 的触控建议下限。
        const disc = new THREE.Mesh(
            new THREE.CircleGeometry(0.36, 32),
            new THREE.MeshBasicMaterial({
                map: makeButtonTexture(),
                transparent: true,
                depthTest: false,       // 永远压在展台/盒子上方，不被遮挡
                depthWrite: false,
            })
        );
        disc.renderOrder = 20;          // 与 depthTest:false 配合，确保画在最上层
        disc.userData.islandButton = slot;
        bg.add(disc);

        // 外圈：细金环（提示"这是可点的"）
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(0.375, 0.415, 40),
            new THREE.MeshBasicMaterial({
                color: 0xFFD479, transparent: true, opacity: 0.85,
                side: THREE.DoubleSide,
                depthTest: false, depthWrite: false,
            })
        );
        ring.renderOrder = 21;
        bg.add(ring);

        return { group: bg, disc, ring, slot };
    }

    /** 悬浮按钮贴图：深底圆 + 金色"更换展品"+ 一个小笔刷图标（程序化，无素材） */
    function makeButtonTexture() {
        const S = 256;
        const c = document.createElement('canvas');
        c.width = c.height = S;
        const g = c.getContext('2d');

        // 圆形底色（径向渐变，中心亮一圈）
        const rg = g.createRadialGradient(S / 2, S / 2, 20, S / 2, S / 2, S / 2);
        rg.addColorStop(0.00, 'rgba(38,50,68,0.98)');
        rg.addColorStop(1.00, 'rgba(20,27,39,0.98)');
        g.fillStyle = rg;
        g.beginPath();
        g.arc(S / 2, S / 2, S / 2 - 4, 0, Math.PI * 2);
        g.fill();

        // 金边
        g.strokeStyle = 'rgba(255,212,121,0.92)';
        g.lineWidth = 5;
        g.stroke();

        // 笔刷图标（斜的胶囊 + 尖头）
        g.save();
        g.translate(S / 2, S / 2 - 34);
        g.rotate(-Math.PI / 4);
        g.fillStyle = '#FFD479';
        g.beginPath();
        g.roundRect ? g.roundRect(-11, -20, 22, 40, 8) : g.rect(-11, -20, 22, 40);
        g.fill();
        g.fillStyle = '#261E0E';
        g.fillRect(-11, 8, 22, 7);      // 笔尖分界线
        g.restore();

        // 文字
        g.fillStyle = '#F3E9CF';
        g.font = 'bold 40px sans-serif';
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillText('更换展品', S / 2, S / 2 + 56);

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        return tex;
    }

    /**
     * 每帧更新悬浮按钮：把可见性 + 朝向交给"离玩家有多近"。
     * 只有**距离 < BUTTON_SHOW_DIST** 的展台才显示按钮，避免 4 个圆牌一直晃。
     */
    const BUTTON_SHOW_DIST = 3.2;
    function updateIslandButtons(camera) {
        const cp = camera.position;
        for (const isl of islands) {
            const btn = isl.btn;
            if (!btn) continue;
            // 用展台的世界坐标算距离（group 的 position 就是世界坐标，无父级变换）
            const d = cp.distanceTo(isl.group.position);
            const show = d < BUTTON_SHOW_DIST;
            if (btn.group.visible !== show) btn.group.visible = show;
            if (!show) continue;

            // ★ billboard：让圆牌永远正对相机（否则走到侧面就看不见了）
            btn.group.quaternion.copy(camera.quaternion);
            // 接近时轻微呼吸，提示"可点"
            const k = 1 + 0.04 * Math.sin(phase * 2.4);
            btn.group.scale.set(k, k, 1);
        }
    }

    /** 单座展台（八棱双层台座 + 金腰线 + 铭牌 + 光柱 + 射灯 + 悬浮按钮） */
    function buildIsland(slot) {
        const p = ISLAND_POS[slot];
        const g = new THREE.Group();
        g.position.set(p.x, 0, p.z);

        // 台座材质：深灰石质 + 一点金属光泽（比纯黑圆柱有"展馆石台"感）
        const stoneMat = new THREE.MeshStandardMaterial({ color: 0x38424F, roughness: 0.55, metalness: 0.20 });
        const trimMat = new THREE.MeshStandardMaterial({ color: 0xC4A96B, roughness: 0.34, metalness: 0.72 });

        // 下座：八棱柱（八边形比圆柱更有棱线，"石台"感直接出来）
        // 高度加高到 0.60，整体台面抬到 0.92 —— 原来 0.63 太矮，展品像蹲在地上
        const lower = new THREE.Mesh(
            new THREE.CylinderGeometry(0.74, 0.86, 0.60, 8, 1),
            stoneMat
        );
        lower.position.y = 0.30;
        lower.rotation.y = Math.PI / 8;      // 让一个平面正对门厅方向，铭牌才贴得正
        lower.receiveShadow = true;
        g.add(lower);

        // 金腰线：上下座之间一道细亮圈（造型的"点睛"，也是唯一的金色来源）
        const trim = new THREE.Mesh(
            new THREE.CylinderGeometry(0.685, 0.685, 0.04, 8, 1),
            trimMat
        );
        trim.position.y = 0.62;
        trim.rotation.y = Math.PI / 8;
        g.add(trim);

        // 上座：略小一圈，顶面就是陈列台面
        const upper = new THREE.Mesh(
            new THREE.CylinderGeometry(0.62, 0.685, 0.26, 8, 1),
            stoneMat
        );
        upper.position.y = 0.77;
        upper.rotation.y = Math.PI / 8;
        upper.receiveShadow = true;
        g.add(upper);

        const TOP_Y = 0.90;                  // 台面高度（后续摆盒子以此为基准）

        // 台面发光环：柔和的内圈光，把"这里能放东西"说清楚
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(0.30, 0.56, 32),
            new THREE.MeshBasicMaterial({
                color: 0xFFE9C8, transparent: true, opacity: 0.22, side: THREE.DoubleSide,
            })
        );
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = TOP_Y + 0.002;     // 贴住台面，避免 z-fighting
        g.add(ring);

        // 铭牌：纯视觉，**不再承担点击**（Q3：太小、点不准，问题太多）。
        // 更换展品改由"靠近展台时浮出的悬浮按钮"负责（见 buildIslandButton / updateIslandButtons）。
        const plate = new THREE.Mesh(
            new THREE.PlaneGeometry(0.68, 0.17),
            new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.94 })
        );
        // 高度取下座中段（下座 0~0.60 → 0.36 居中），别贴地也别顶到金腰线
        plate.position.set(0, 0.36, 0.755);  // 下座外接半径 ≈0.80，贴到 0.755 略嵌进去
        g.add(plate);

        // 射灯 + 光柱（不投影：中央展台那盏已经是全场唯一的投影灯）
        // 强度 14→20：台面抬高后展品离灯更近，但距离也要跟着调（3.6→4.1）
        const spot = new THREE.SpotLight(0xFFE7C4, 20, 10, Math.PI / 8, 0.6, 1.8);
        spot.position.set(0, 4.1, 0);
        spot.target.position.set(0, TOP_Y, 0);
        g.add(spot);
        g.add(spot.target);

        // 体积光柱：底端必须压在**展品上方**。
        // 台面已抬到 0.90、展品顶到约 1.56，所以光柱中心取 2.35 才对得上
        // （原来 2.10 是按旧台面 0.63 算的，加高后会插进台座里）。
        const beam = new THREE.Mesh(
            new THREE.ConeGeometry(0.60, 2.9, 22, 1, true),
            new THREE.MeshBasicMaterial({
                map: makeBeamTexture(),
                color: 0xFFE7C4,
                transparent: true,
                opacity: 0.10,               // 比中央展台更弱：4 根别抢 C 位
                blending: THREE.AdditiveBlending,
                depthWrite: false,
                side: THREE.DoubleSide,
            })
        );
        beam.position.y = 2.35;
        g.add(beam);

        // 台座：纯视觉，不参与点击（Q3）。
        // 之前给 lower/upper/trim 都打了 islandSlot 标记，导致"点哪都能换、
        // 还会和盒子抢点击"，索性全部去掉 —— 更换入口只剩悬浮按钮。

        // ---- 悬浮按钮（Q1-甲）：靠近时才显示，贴在展台上方 ----
        // 为什么用 3D 小圆牌而不是铭牌：铭牌只有 0.68×0.17m，
        // 在 3~4m 外屏幕上只有几像素，手指根本压不准。
        // 悬浮牌做成 0.9m 见方且**始终正对相机**（billboard），手指好按得多。
        const btn = makeIslandButton(slot);
        btn.group.position.set(0, 2.05, 0);
        btn.group.visible = false;
        g.add(btn.group);
        // 按钮挂在 group 下，会跟着展台走；但 billboard 时用到相机四元数，
        // 而 group 本身没有旋转，所以 btn.group.quaternion 直接复制相机的是安全的。

        group.add(g);
        islands.push({
            group: g, mesh: null, slot, game: null,
            parts: [],                       // 台座不再参与点击（Q3），保留空数组以兼容旧结构
            plate, plateKey: '',             // 铭牌（纯视觉）+ 当前文字（避免重复重绘）
            btn,                             // 悬浮按钮 { group, disc, ring, slot }
            topY: TOP_Y,
        });
        drawIslandPlate(slot);               // 先画"点击陈列"
    }

    /** 画展台铭牌（空台 / 已陈列不同文案） */
    function drawIslandPlate(slot) {
        const isl = islands[slot];
        if (!isl || !isl.plate) return;
        const text = isl.game ? (isl.game.title || '(无标题)') : '点击陈列';
        if (isl.plateKey === text) return;   // 文字没变就不重绘（省一次 CanvasTexture）
        isl.plateKey = text;

        const W = 512, H = 132;
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g2 = c.getContext('2d');

        // 底板 + 金边（与悬挂小牌同一套视觉语言）
        g2.fillStyle = 'rgba(12,17,26,0.92)';
        g2.fillRect(0, 0, W, H);
        g2.strokeStyle = isl.game ? 'rgba(196,169,107,0.90)' : 'rgba(150,166,190,0.45)';
        g2.lineWidth = 5;
        g2.strokeRect(6, 6, W - 12, H - 12);

        g2.fillStyle = isl.game ? '#F0E4C6' : '#8FA0B8';
        g2.font = (isl.game ? 'bold 46px ' : '40px ') + 'sans-serif';
        g2.textAlign = 'center';
        g2.textBaseline = 'middle';
        // 标题过长就截断（铭牌尺寸固定，不缩字号，保持一排整齐）
        let t = text;
        if (t.length > 14) t = t.slice(0, 13) + '…';
        g2.fillText(t, W / 2, H / 2 + 2);

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        const old = isl.plate.material.map;
        isl.plate.material.map = tex;
        isl.plate.material.needsUpdate = true;
        if (old) old.dispose();
    }

    function buildIslands() {
        if (islandsBuilt) return;
        islandsBuilt = true;
        for (let i = 0; i < ISLAND_POS.length; i++) buildIsland(i);
        updateIslands(itemGames);
    }

    /** 把某座展台上的盒子换掉（game 为 null = 清空） */
    function setIslandGame(slot, game) {
        const isl = islands[slot];
        if (!isl) return;

        // 先撤掉旧的（材质/几何都要释放，否则反复换会漏显存）
        if (isl.mesh) {
            isl.group.remove(isl.mesh);
            disposeBoxMaterials(isl.mesh);
            isl.mesh = null;
        }
        isl.game = game || null;
        if (!game) { drawIslandPlate(slot); return; }

        // 单件展陈失败也只影响这一座台子，不该连累调用方（见 setGames 的注释）
        try {
            const mats = makeBoxMaterials(game);
            // 复用共享几何（不要在这里 new：反复换展品会漏几何）
            const mesh = new THREE.Mesh(ISLAND_BOX_GEO, mats);
            mesh.position.y = (isl.topY || 0.90) + 0.74 / 2 + 0.02;   // 立在台面上沿
            // 初始角度从 0 开始：它在 update() 里会持续自转，
            // 若这里设个非零初值，每次"重新摆上"都会看到盒子跳一下。
            mesh.rotation.y = 0;
            mesh.userData.game = game;
            // 不再打 islandSlot 标记：Q3 已取消"点展台换展品"，
            // 留着它会让 pick() 的判定意图含糊（和旧逻辑混淆）。
            isl.group.add(mesh);
            isl.mesh = mesh;

            // 贴封面（复用中央展台那套：直连加载，不走货架队列）
            if (game.cover) {
                const target = mesh;
                const img = new Image();
                img.onload = () => {
                    if (isl.mesh !== target) return;    // 期间被换掉了，丢弃
                    applyCoverToBox(target, textureFromImage(img, game.nsfw));
                };
                img.onerror = () => { /* 失败就留占位材质 */ };
                img.src = game.cover;
            }
            applyStatus(mats, game);
        } catch (e) {
            log('⚠️ 展台 ' + slot + ' 摆件失败：' + (e && e.message ? e.message : e));
        }
        drawIslandPlate(slot);              // 铭牌跟着更新（空台 / 标题）
    }

    /** 按当前槽位重建 4 座展台（打开展厅时、以及玩家选完后各调一次） */
    function updateIslands(list) {
        if (!islands.length) return;
        const ids = readIslandSlots();
        const byId = new Map();
        for (const g of (list || itemGames)) byId.set(String(g.id), g);
        for (const isl of islands) {
            const id = ids[isl.slot];
            setIslandGame(isl.slot, id ? (byId.get(String(id)) || null) : null);
        }
    }

    /**
     * 读槽位（与 picker.js 同一格式："12,,45,"）
     * 桥接不存在时退回 localStorage —— 保证 Java 侧没编译也能跑。
     */
    function readIslandSlots() {
        let raw = '';
        try {
            const b = window.ExhibitionBridge;
            if (b && typeof b.getDisplaySlots === 'function') raw = b.getDisplaySlots() || '';
            if (!raw) raw = localStorage.getItem('exhibition_display_slots') || '';
        } catch (e) { raw = ''; }
        const parts = String(raw).split(',');
        const out = new Array(ISLAND_POS.length).fill('');
        for (let i = 0; i < out.length; i++) {
            const v = parseInt(parts[i], 10);
            out[i] = (Number.isFinite(v) && v > 0) ? String(v) : '';
        }
        return out;
    }

    /** 中央展台 */
    function buildPedestal() {
        pedestal = new THREE.Group();
        const base = new THREE.Mesh(
            new THREE.CylinderGeometry(0.86, 1.0, 0.95, 28),
            new THREE.MeshStandardMaterial({ color: 0x303B4C, roughness: 0.6, metalness: 0.18 })
        );
        base.position.y = 0.475;
        base.receiveShadow = true;
        pedestal.add(base);

        const glass = new THREE.Mesh(
            new THREE.BoxGeometry(1.15, 1.25, 0.55),
            new THREE.MeshStandardMaterial({
                color: 0xAAD4FF, transparent: true, opacity: 0.10,
                roughness: 0.15, metalness: 0.0,
            })
        );
        glass.position.y = 1.58;
        pedestal.add(glass);

        const light = new THREE.PointLight(0xFFE6BE, 9, 9, 2);
        light.position.set(0, 3.0, 0);
        pedestal.add(light);

        // 展台射灯：从天花板打下来，并且**真的投影**（全场只有这一盏投影灯）
        const spot = new THREE.SpotLight(0xFFE7C4, 30, 13, Math.PI / 9, 0.5, 1.7);
        spot.position.set(0, 6.0, 0);
        spot.target.position.set(0, 1.15, 0);
        spot.castShadow = true;
        spot.shadow.mapSize.set(1024, 1024);
        spot.shadow.camera.near = 3.0;
        spot.shadow.camera.far = 8.5;
        spot.shadow.bias = -0.0016;
        spot.shadow.radius = 3;
        pedestal.add(spot);
        pedestal.add(spot.target);

        // 体积光柱：把"射灯"变成看得见的一束光（叠加混合、不写深度）
        const beam = new THREE.Mesh(
            new THREE.ConeGeometry(0.98, 4.0, 28, 1, true),
            new THREE.MeshBasicMaterial({
                map: makeBeamTexture(),
                color: 0xFFE7C4,
                transparent: true,
                opacity: 0.17,
                blending: THREE.AdditiveBlending,
                depthWrite: false,
                side: THREE.DoubleSide,
            })
        );
        beam.position.y = 4.0;
        pedestal.add(beam);

        // 玻璃罩棱线：让"罩子"看起来真是玻璃（线条比面更省、更像）
        const glassEdges = new THREE.LineSegments(
            new THREE.EdgesGeometry(glass.geometry),
            new THREE.LineBasicMaterial({ color: 0xA8D2FF, transparent: true, opacity: 0.42 })
        );
        glassEdges.position.copy(glass.position);
        pedestal.add(glassEdges);

        // 底座顶面发光环：把光"打在"展台上
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(0.60, 0.84, 36),
            new THREE.MeshBasicMaterial({ color: 0xFFE9C8, transparent: true, opacity: 0.30, side: THREE.DoubleSide })
        );
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = 0.97;
        pedestal.add(ring);

        pedestal.position.set(0, 0, 0);
        group.add(pedestal);
    }

    /* ---------- 「最近游玩」旋转展架（第二处 C 位） ---------- */
    /* 状态变量已在文件靠前处声明（buildRotator 在初始化时就被调用） */

    function buildRotator() {
        rotator = new THREE.Group();
        rotator.position.set(ROTATOR_POS.x, 0, ROTATOR_POS.z);

        // 底座：比中央展台更矮更宽的圆台
        const base = new THREE.Mesh(
            new THREE.CylinderGeometry(1.36, 1.52, 0.40, 36),
            new THREE.MeshStandardMaterial({ color: 0x2C3646, roughness: 0.62, metalness: 0.20 })
        );
        base.position.y = 0.20;
        base.receiveShadow = true;
        rotator.add(base);

        // 底座发光环
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(1.05, 1.34, 40),
            new THREE.MeshBasicMaterial({ color: 0xFFE9C8, transparent: true, opacity: 0.22, side: THREE.DoubleSide })
        );
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = 0.405;
        rotator.add(ring);

        // 中央轴
        const pole = new THREE.Mesh(
            new THREE.CylinderGeometry(0.055, 0.055, 2.0, 12),
            new THREE.MeshStandardMaterial({ color: 0x9FB0C6, roughness: 0.35, metalness: 0.75 })
        );
        pole.position.y = 1.40;
        rotator.add(pole);

        // 顶圈：细圆环 + 自发光，把展架"框"起来
        const canopy = new THREE.Mesh(
            new THREE.TorusGeometry(1.30, 0.032, 8, 44),
            new THREE.MeshStandardMaterial({
                color: 0xC9D8EA, roughness: 0.4, metalness: 0.5,
                emissive: new THREE.Color(0x8FB6E0), emissiveIntensity: 0.5,
            })
        );
        canopy.rotation.x = Math.PI / 2;
        canopy.position.y = 2.42;
        rotator.add(canopy);

        // 悬挂牌：说明这台展架是「最近游玩」。
        // 原来放在 (0, 1.70, 0) —— 正好和中央立杆(x=z=0, 高 0.4~2.4)重叠，
        // 从门口看过去杆子会把"最近游玩"四个字从中间劈开。
        // 修法：**略微前移 + 抬高**，让它挂在杆的前上方，完全不与杆相交。
        const plate = new THREE.Mesh(
            new THREE.PlaneGeometry(1.45, 0.375),
            new THREE.MeshBasicMaterial({
                map: makeLabelTexture('最近游玩'),
                transparent: true,
                side: THREE.DoubleSide,
            })
        );
        plate.position.set(0, 2.02, 0.62);   // z 前移 0.62（避开立杆），y 略抬到 2.02
        rotator.add(plate);

        // 顶灯：把展架上的盒子照亮
        const top = new THREE.PointLight(0xFFF0D6, 6, 7, 2);
        top.position.set(0, 2.30, 0);
        rotator.add(top);

        // 旋转部分
        spinner = new THREE.Group();
        spinner.position.y = 0.40;
        rotator.add(spinner);

        group.add(rotator);
    }

    /** 填充旋转展架：最多 5 款，角度均分、面朝外 */
    function fillRotator(list) {
        if (!spinner) return;
        for (const m of rotatorMeshes) {
            spinner.remove(m);
            disposeBoxMaterials(m);
        }
        rotatorMeshes.length = 0;

        const items = (list || []).slice(0, ROTATOR_MAX);
        items.forEach((game, i) => {
            const mats = makeBoxMaterials(game);
            const mesh = new THREE.Mesh(ROTATOR_BOX_GEO, mats);
            const a = (i / Math.max(1, items.length)) * Math.PI * 2;
            mesh.position.set(Math.cos(a) * 0.80, 0.52, Math.sin(a) * 0.80);
            mesh.rotation.y = Math.PI / 2 - a;      // 正面朝外
            mesh.userData.game = game;
            spinner.add(mesh);
            rotatorMeshes.push(mesh);

            if (game.hasCover && game.cover) {
                const img = new Image();
                img.onload = () => {
                    if (!rotatorMeshes.includes(mesh)) return;
                    applyCoverToBox(mesh, textureFromImage(img, game.nsfw));
                };
                img.src = game.cover;
            }
        });
    }

    /* ---------- 纹理生成 ---------- */

    /** 无封面时的程序化"书脊卡" */
    function makePlaceholderTexture(game) {
        const W = CFG.texW, H = CFG.texH;
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        const hue = hashHue(game.title || 'x');

        const grad = g.createLinearGradient(0, 0, W, H);
        grad.addColorStop(0, `hsl(${hue}, 26%, 22%)`);
        grad.addColorStop(1, `hsl(${(hue + 40) % 360}, 22%, 12%)`);
        g.fillStyle = grad;
        g.fillRect(0, 0, W, H);

        g.fillStyle = `hsla(${hue}, 70%, 62%, 0.85)`;
        g.fillRect(0, 0, W, 6);

        g.fillStyle = 'rgba(255,255,255,0.88)';
        g.font = 'bold 19px sans-serif';
        const lines = wrapText(g, game.title || '未命名', W - 26, 5);
        lines.forEach((ln, i) => g.fillText(ln, 13, 44 + i * 27));

        g.fillStyle = 'rgba(255,255,255,0.35)';
        g.font = '12px sans-serif';
        g.fillText('无封面', 13, H - 16);

        drawFrame(g, W, H);   // 装裱边框

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        tex.anisotropy = 4;
        return tex;
    }

    /** 封面图 → 降采样纹理；NSFW 走马赛克化 */
    function textureFromImage(img, nsfw) {
        const W = CFG.texW, H = CFG.texH;
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        const g = c.getContext('2d');
        g.imageSmoothingEnabled = !nsfw;

        if (nsfw && BLUR_NSFW) {
            // 先画成 14×20 的小图，再放大 → 得到马赛克效果
            const tiny = document.createElement('canvas');
            tiny.width = 14; tiny.height = 20;
            const tg = tiny.getContext('2d');
            tg.drawImage(img, 0, 0, 14, 20);
            g.drawImage(tiny, 0, 0, 14, 20, 0, 0, W, H);
        } else {
            // 等比裁切填充（cover）
            const sw = img.width, sh = img.height;
            const scale = Math.max(W / sw, H / sh);
            const dw = sw * scale, dh = sh * scale;
            g.drawImage(img, (W - dw) / 2, (H - dh) / 2, dw, dh);
        }

        drawFrame(g, W, H);   // 装裱边框（真实封面也加，风格统一）

        const tex = new THREE.CanvasTexture(c);
        tex.colorSpace = THREE.SRGBColorSpace;
        tex.anisotropy = 4;
        return tex;
    }

    /* ---------- 状态 → 材质外观 ---------- */

    /**
     * 给"游戏盒"应用状态灯效。
     * @param mat 单个材质，**或实体盒的 6 面材质数组**
     * @param game
     *
     * 为什么接受数组：实体盒改造后，调用方手里拿到的是 makeBoxMaterials() 的数组。
     * 早先只支持单材质，传数组进来会在 mat.emissive 上炸（undefined.setHex）。
     *
     * 数组时**只给正反面（下标 4/5）上状态色** —— 书脊/塑料壳不该被染成青的紫的。
     */
    function applyStatus(mat, game) {
        if (Array.isArray(mat)) {
            for (const i of [4, 5]) {
                if (mat[i]) applyStatusToMaterial(mat[i], game);
            }
            return;
        }
        if (!mat || !mat.emissive || !mat.color) return;   // 非标准材质，跳过（防御）
        applyStatusToMaterial(mat, game);
    }

    function applyStatusToMaterial(mat, game) {
        const st = game.playStatus || 'unplayed';
        // 让封面自带柔光（emissiveMap = 自身贴图）：
        // 这样即使在偏暗的展厅里，封面本身也是清晰可读的，不会整面糊成黑的。
        mat.emissiveMap = mat.map;
        mat.emissive.setHex(0xFFFFFF);

        if (game.favorite) {
            mat.emissive.setHex(0xFFD479);   // 收藏 → 金色
            mat.emissiveIntensity = 0.42;
            mat.color.setHex(0xFFFFFF);
        } else if (st === 'playing') {
            mat.emissive.setHex(0x66E0C0);   // 在玩 → 青色（会呼吸）
            mat.emissiveIntensity = 0.34;
            mat.color.setHex(0xFFFFFF);
        } else if (st === 'completed') {
            mat.emissiveIntensity = 0.30;    // 玩过 → 正常亮度
            mat.color.setHex(0xFFFFFF);
        } else if (st === 'onhold' || st === 'dropped') {
            mat.emissive.setHex(0xA88CD8);   // 搁置/抛弃 → 淡紫，一眼能区分
            mat.emissiveIntensity = 0.20;
            mat.color.setHex(0xD6DCE8);
        } else {
            mat.emissiveIntensity = 0.16;    // 未玩 → 略暗，但仍看得清封面
            mat.color.setHex(0xB9C4D2);
        }
    }

    /* ---------- 懒建：把一段架子变成实体 ---------- */

    function buildShelf(shelf) {
        if (shelf.built) return;
        shelf.built = true;

        buildBoards(shelf);
        buildCabinet(shelf);

        const n = shelf.slots.length;
        const cases = new THREE.InstancedMesh(caseGeo, caseMat, n);
        cases.instanceMatrix.setUsage(THREE.StaticDrawUsage);
        const m4 = new THREE.Matrix4();
        const sc = new THREE.Vector3(1, 1, 1);
        const tmpCol = new THREE.Color();

        for (let i = 0; i < n; i++) {
            const s = shelf.slots[i];
            m4.compose(s.pos, s.quat, sc);
            cases.setMatrixAt(i, m4);

            // 每格盒体按标题哈希上色 → 侧视时像一排"真盒子"的书脊（零额外绘制调用）
            if (s.item && s.item.game) {
                const hue = hashHue(s.item.game.title || 'x') / 360;
                tmpCol.setHSL(hue, 0.30, 0.30);
            } else {
                tmpCol.setHSL(0.58, 0.10, 0.20);   // 空位：中性冷淡色，视觉后退
            }
            cases.setColorAt(i, tmpCol);
        }
        cases.instanceMatrix.needsUpdate = true;
        if (cases.instanceColor) cases.instanceColor.needsUpdate = true;
        group.add(cases);
        shelf.cases = cases;

        // 为有游戏的格子建封面面板（无游戏则留空格）
        for (const s of shelf.slots) {
            if (!s.item) continue;
            const game = s.item.game;
            const mat = new THREE.MeshStandardMaterial({
                map: makePlaceholderTexture(game),
                roughness: 0.66,
                metalness: 0.04,
                side: THREE.FrontSide,
            });
            applyStatus(mat, game);

            const mesh = new THREE.Mesh(coverGeo, mat);
            const off = new THREE.Vector3(0, 0, CFG.boxD / 2 + 0.012).applyQuaternion(s.quat);
            mesh.position.copy(s.pos).add(off);
            mesh.quaternion.copy(s.quat);
            mesh.userData.game = game;
            group.add(mesh);
            shelf.covers.push(mesh);
        }

        shelf.needsCovers = shelf.slots.filter(s => s.item && s.item.game.cover && !s.item.textured);
        // 建出来时网格默认就是可见的，这里必须同步状态位，
        // 否则 pick()/呼吸光会因为 undefined 而被跳过（首次走近时点不中）
        shelf.visible = true;
    }

    function hideShelf(shelf) {
        if (!shelf.built) return;
        if (shelf.cases) shelf.cases.visible = false;
        shelf.covers.forEach(m => { m.visible = false; });
        shelf.boards.forEach(b => { b.visible = false; });
        shelf.visible = false;
    }

    function showShelf(shelf) {
        if (shelf.cases) shelf.cases.visible = true;
        shelf.covers.forEach(m => { m.visible = true; });
        shelf.boards.forEach(b => { b.visible = true; });
        shelf.visible = true;
    }

    /* ---------- 封面渐进加载 ---------- */

    let inflight = 0;
    const queue = [];
    const coverStats = { queued: 0, ok: 0, fail: 0, timeout: 0, retried: 0 };
    let lastCoverError = '';
    let drainLogged = false;

    function pumpQueue() {
        while (inflight < CFG.maxInflight && queue.length) {
            const item = queue.shift();
            if (!item || item.textured || !item.coverUrl) continue;
            loadCover(item);
        }
    }

    /**
     * 加载一张封面。
     * ⚠️ 必须带超时：Image 在某些情况下既不触发 onload 也不触发 onerror，
     *    那样 inflight 会永久占用，导致后续所有封面都排不进去（表现为"架子永远是空封面"）。
     */
    function loadCover(item) {
        inflight++;
        coverStats.queued++;

        const img = new Image();
        let done = false;

        const finish = (ok, why) => {
            if (done) return;
            done = true;
            inflight--;
            if (ok) {
                coverStats.ok++;
                if (item.mesh) {
                    item.mesh.userData.coverLoaded = true;
                    item.mesh.userData.queued = false;
                }
            } else {
                coverStats.fail++;
                if (why === 'timeout') coverStats.timeout++;
                lastCoverError = (why || 'error') + (item.game && item.game.title ? (' @' + item.game.title) : '');
                log('封面失败[' + (why || 'error') + ']: ' + (item.game ? item.game.title : '?'));
                if (item.mesh) item.mesh.userData.queued = false;   // 允许被扫描重新排队
                item.attempts = (item.attempts || 0) + 1;
                if (item.attempts < CFG.coverMaxAttempts) {
                    // 允许有限重试（网络/解码偶发失败不该永久留白）
                    coverStats.retried++;
                    queue.push(item);
                }
            }
            pumpQueue();
            // 队列排空时打一条汇总，方便在 logcat 里一眼看出封面到底加载成功了几张
            if (!drainLogged && inflight === 0 && queue.length === 0 && (coverStats.ok + coverStats.fail) > 0) {
                drainLogged = true;
                log('封面加载汇总: 成功' + coverStats.ok + ' 失败' + coverStats.fail + ' 超时' + coverStats.timeout);
            }
        };

        const timer = setTimeout(() => finish(false, 'timeout'), CFG.coverTimeoutMs);

        img.onload = () => {
            clearTimeout(timer);
            try {
                let mesh = item.mesh;
                if (!mesh && item.findMesh) mesh = item.findMesh();
                if (mesh && mesh.material) {
                    const old = mesh.material.map;
                    mesh.material.map = textureFromImage(img, item.game.nsfw);
                    // 换了 map 也要同步 emissiveMap（它俩指向同一张图）
                    mesh.material.emissiveMap = mesh.material.map;
                    mesh.material.needsUpdate = true;
                    if (old && old.dispose) old.dispose();
                    item.mesh = mesh;
                    finish(true);
                    return;
                }
                // 找不到对应的网格：算失败并重试
                finish(false, 'mesh-missing');
            } catch (e) {
                finish(false, 'tex-fail:' + (e && e.message ? e.message : e));
            }
        };
        img.onerror = () => {
            clearTimeout(timer);
            finish(false, 'error');
        };
        img.src = item.coverUrl;
    }

    /* ---------- 对外 API ---------- */

    let detailTimer = null;
    const dom = {
        detail: document.getElementById('detail'),
        title: document.getElementById('d-title'),
        sub: document.getElementById('d-sub'),
        meta: document.getElementById('d-meta'),
        tags: document.getElementById('d-tags'),
        close: document.getElementById('d-close'),
        ring: document.getElementById('d-ring'),
        ringText: document.getElementById('d-ring-text'),
    };

    // 3D 展品查看器：复用本文件里已验证的盒子函数（不重复实现）
    const viewer = createViewer({
        makeBoxMaterials,
        applyCoverToBox,
        disposeBoxMaterials,
        textureFromImage,
    });
    if (dom.close) {
        dom.close.addEventListener('click', () => closeDetail());
    }

    function openDetail(game) {
        if (!dom.detail) return;
        dom.title.textContent = game.title || '未命名';
        dom.sub.textContent = (game.originalTitle && game.originalTitle !== game.title)
            ? game.originalTitle : '';

        const badges = [];
        if (game.favorite) badges.push('<span class="d-badge fav">收藏</span>');
        const st = game.playStatus || 'unplayed';
        // 文案严格对齐 IconedText.labelForStatus：
        //   playing=在玩 / completed=玩过 / onhold=搁置 / dropped=抛弃 / 其余=未玩
        if (st === 'playing') badges.push('<span class="d-badge playing">在玩</span>');
        else if (st === 'completed') badges.push('<span class="d-badge done">玩过</span>');
        else if (st === 'onhold') badges.push('<span class="d-badge">搁置</span>');
        else if (st === 'dropped') badges.push('<span class="d-badge">抛弃</span>');
        else badges.push('<span class="d-badge unplayed">未玩</span>');
        if (game.nsfw) badges.push('<span class="d-badge nsfw">NSFW</span>');

        dom.meta.innerHTML = badges.join('')
            + '<br>游玩时长：' + fmtTime(game.totalPlayTime)
            + ' · 最近：' + fmtDate(game.lastPlayedAt)
            + (game.engine ? '<br>引擎：' + game.engine : '');
        dom.tags.textContent = game.tags ? ('标签：' + game.tags) : '';

        // 游玩时长进度环（以 50 小时为满格）
        drawTimeRing(game);

        dom.detail.hidden = false;

        // 3D 展品查看器：在 3D 展台上放一个可拖动旋转的实体盒
        if (viewer) {
            // 下一帧再打开：确保 #detail 已经可见、#d-stage 有真实尺寸
            requestAnimationFrame(() => { if (viewer) viewer.open(game); });
        }
    }

    /** 游玩时长进度环（canvas 手绘，避免引入图表库） */
    function drawTimeRing(game) {
        const cv = dom.ring;
        if (!cv) return;
        const ctx = cv.getContext('2d');
        const W = cv.width, H = cv.height;
        const cx = W / 2, cy = H / 2;
        const r = W / 2 - 7;
        const hours = (game.totalPlayTime || 0) / 3600000;
        const ratio = Math.max(0, Math.min(1, hours / 50));

        ctx.clearRect(0, 0, W, H);

        // 底环
        ctx.beginPath();
        ctx.arc(cx, cy, r, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(255,255,255,0.10)';
        ctx.lineWidth = 7;
        ctx.stroke();

        // 进度弧（从 12 点开始顺时针）
        if (ratio > 0) {
            ctx.beginPath();
            ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * ratio);
            const grad = ctx.createLinearGradient(0, 0, W, H);
            grad.addColorStop(0, '#7FC4FF');
            grad.addColorStop(1, '#8CE99A');
            ctx.strokeStyle = grad;
            ctx.lineWidth = 7;
            ctx.lineCap = 'round';
            ctx.stroke();
        }

        // 中心时长文字
        ctx.fillStyle = '#EAF2FF';
        ctx.font = 'bold 17px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(hours > 0 ? (hours >= 10 ? String(Math.round(hours)) : hours.toFixed(1)) : '0', cx, cy - 4);
        ctx.fillStyle = '#7E8B9C';
        ctx.font = '10px sans-serif';
        ctx.fillText('小时', cx, cy + 13);

        if (dom.ringText) {
            dom.ringText.innerHTML = '游玩时长 ' + fmtTime(game.totalPlayTime)
                + '<br>最近游玩 ' + fmtDate(game.lastPlayedAt)
                + '<br><span style="color:#6E7A8A">（进度环以 50 小时为满格）</span>';
        }
    }

    function closeDetail() {
        if (dom.detail) dom.detail.hidden = true;
        if (detailTimer) { clearTimeout(detailTimer); detailTimer = null; }
        // 关闭查看器：停掉它自己的渲染循环，避免后台空转
        if (viewer) viewer.close();
    }

    /** 窗口/屏幕变化时，让查看器跟着调整画布尺寸 */
    function resizeDetail() {
        if (viewer) viewer.resize();
    }

    /**
     * 灌入库存（game 字段：
     * id,title,originalTitle,engine,playStatus,totalPlayTime,lastPlayedAt,favorite,nsfw,tags,hasCover,cover）
     */
    function setGames(games) {
        items.length = 0;

        // 排序：收藏优先 → 最近玩过 → 时长 → 标题
        const sorted = games.slice().sort((a, b) => {
            if (!!b.favorite !== !!a.favorite) return b.favorite ? 1 : -1;
            if ((b.lastPlayedAt || 0) !== (a.lastPlayedAt || 0)) return (b.lastPlayedAt || 0) - (a.lastPlayedAt || 0);
            if ((b.totalPlayTime || 0) !== (a.totalPlayTime || 0)) return (b.totalPlayTime || 0) - (a.totalPlayTime || 0);
            return String(a.title).localeCompare(String(b.title));
        });

        // 记录原始库存：主题展台要按 id 反查"这一件是哪款"（不能靠已排序/已分区的数组）
        itemGames.length = 0;
        for (const g of games) itemGames.push(g);
        // 主题展台：首次进来时才建（默认空台）；已建过就按最新库存刷新槽位
        //
        // ⚠️ 必须 try 包起来：主题展台是"锦上添花"的功能，
        //    它自己出任何错都不该把整个 setGames（分区 / 书架 / 统计）一起带走。
        //    （曾经因为这里抛异常，导致书架全空、日志全无 —— 整个展厅像被清空）
        try {
            if (islandsBuilt) updateIslands(itemGames); else buildIslands();
        } catch (e) {
            log('⚠️ 主题展台初始化失败（不影响书架）：' + (e && e.message ? e.message : e));
        }

        // 库存很大时自动降低封面精度，避免"全部架子都建出来"后显存吃紧
        if (sorted.length > 120) {
            CFG.texW = 192;
            CFG.texH = 276;
        }

        // ★ 分区**只按 playStatus（游玩状态）**划分，共 5 个：
        //     playing=正在游玩 / completed=玩过 / onhold=搁置 / dropped=抛弃 / 其余=未玩
        //   ⚠️ **收藏（favorite）不参与分区** —— 它是"玩家喜好属性"，不是游玩状态。
        //      所以"收藏 + 在玩"的游戏会出现在"正在游玩"层里，不会和状态冲突；
        //      收藏通过盒子金色灯效、详情徽标、馆藏统计三处体现。
        //   另外两条曾经的错误（都改掉了）：
        //     1) 漏掉 onhold/dropped → 把"搁置/抛弃"错算进"玩过"
        //     2) 把"标记为 unplayed 但有游玩记录"的推断成"玩过" ← 越权改用户数据语义
        const stOf = g => {
            const s = g.playStatus || 'unplayed';
            // 非法/空值按 unplayed 兜底（与 IconedText 的"其余按 unplayed"一致），
            // 保证 5 个分区严格互斥且完备，不会有游戏被漏掉。
            return (s === 'playing' || s === 'completed' || s === 'onhold' || s === 'dropped')
                ? s : 'unplayed';
        };
        const zonePlaying = sorted.filter(g => stOf(g) === 'playing');
        const zonePlayed = sorted.filter(g => stOf(g) === 'completed');
        const zoneOnhold = sorted.filter(g => stOf(g) === 'onhold');
        const zoneDropped = sorted.filter(g => stOf(g) === 'dropped');
        const zoneFresh = sorted.filter(g => stOf(g) === 'unplayed');
        // 收藏：独立属性（只统计，不占分区）
        const favCount = sorted.filter(g => g.favorite).length;

        // 诊断日志：原始状态分布 + **完备性校验**（分类之和必须等于库存总数）
        const rawDist = {};
        for (const g of sorted) {
            const s = stOf(g);
            rawDist[s] = (rawDist[s] || 0) + 1;
        }
        const zoneSum = zonePlaying.length + zonePlayed.length + zoneOnhold.length
            + zoneDropped.length + zoneFresh.length;
        log('状态分布（原始标记）：'
            + Object.keys(rawDist).map(k => k + '=' + rawDist[k]).join(' / ')
            + '；收藏（属性）=' + favCount);
        log('分区完备性：' + zoneSum + ' / ' + sorted.length
            + (zoneSum === sorted.length ? ' ✅ 无遗漏' : ' ❌ 有游戏被漏掉'));
        // ★ 按分区重建布局：每层只铺该分区需要的格数（自适应宽度，件数少时保留最少格数）
        // 这一步必须在填格之前做，否则"一层一个分类"会退化成一整层塞满
        disposeAllShelves();
        const zoneList = [zonePlaying, zonePlayed, zoneOnhold, zoneDropped, zoneFresh];
        buildShelfSlots(zoneList.map(a => a.length));

        // 按层精确填格：每段架子只从**自己那一层**的分区里取件。
        // 必须这样做的原因：件数少的分区会保留空位（minRowSlots），
        // 若沿用"全局顺序 idx++"，下一层的件就会被填进上一层的空位里，整层错位。
        for (const shelf of shelves) {
            const pool = zoneList[shelf.zone] || [];
            let gi = shelf.zonePos;              // 该段在本层内是第几段 → 决定从本层第几件开始
            for (const slot of shelf.slots) {
                const game = pool[gi];
                if (!game) { slot.item = null; continue; }   // 空位：不摆盒子
                gi++;
                const item = {
                    game,
                    slot,
                    mesh: null,
                    textured: false,
                    coverUrl: game.cover || '',
                };
                slot.item = item;
                items.push(item);
            }
        }
        log('分区陈列：在玩 ' + zonePlaying.length + ' / 玩过 ' + zonePlayed.length
            + ' / 搁置 ' + zoneOnhold.length + ' / 抛弃 ' + zoneDropped.length
            + ' / 未玩 ' + zoneFresh.length
            + '（层宽自适应，共 ' + shelves.length + ' 段架子）');

        // 中央展台的"C位"
        const withCover = sorted.filter(g => g.cover).length;
        log('库存 ' + sorted.length + ' 款，其中有封面 ' + withCover + ' 款');
        // 馆藏概况（后墙正上方的信息墙 + 左右墙展板）：直接复用分区统计，口径与分区牌一致
        const totalMs = sorted.reduce((s, g) => s + (g.totalPlayTime || 0), 0);
        const byTime = sorted.slice().sort((a, b) => (b.totalPlayTime || 0) - (a.totalPlayTime || 0));
        const playedList = sorted.filter(g => (g.lastPlayedAt || 0) > 0);
        const byRecent = playedList.slice().sort((a, b) => (b.lastPlayedAt || 0) - (a.lastPlayedAt || 0));
        const byOldest = playedList.slice().sort((a, b) => (a.lastPlayedAt || 0) - (b.lastPlayedAt || 0));
        const engineCount = {};
        for (const g of sorted) {
            const e = g.engine || '未知';
            engineCount[e] = (engineCount[e] || 0) + 1;
        }
        const engineTop = Object.keys(engineCount).sort((a, b) => engineCount[b] - engineCount[a])[0];

        const stats = {
            total: sorted.length,
            hours: Math.round(totalMs / 3600000),
            avgHours: sorted.length ? (totalMs / 3600000 / sorted.length).toFixed(1) : '0.0',
            withCover,
            fav: favCount,
            playing: zonePlaying.length,
            played: zonePlayed.length,
            onhold: zoneOnhold.length,
            dropped: zoneDropped.length,
            fresh: zoneFresh.length,
            recentTitle: byRecent[0] ? byRecent[0].title : '暂无记录',
            topTitle: byTime[0] ? byTime[0].title : '—',
            firstDate: byOldest[0] ? fmtDate(byOldest[0].lastPlayedAt) : '—',
            engineTop: engineTop ? (engineTop + ' ×' + engineCount[engineTop]) : '—',
        };
        drawSign(stats);
        drawPanels(stats);
        log('馆藏概况：' + stats.total + ' 款 / 总时长 ' + stats.hours + 'h / 收藏(属性) '
            + stats.fav + ' / 在玩 ' + stats.playing + ' / 玩过 ' + stats.played
            + ' / 搁置 ' + stats.onhold + ' / 抛弃 ' + stats.dropped
            + ' / 未玩 ' + stats.fresh);
        updateZoneSigns([zonePlaying.length, zonePlayed.length, zoneOnhold.length,
            zoneDropped.length, zoneFresh.length]);
        // 中央展台的"C位"：优先收藏；**没有收藏就放最近游玩的那款**
        const recentForPedestal = sorted
            .filter(g => (g.lastPlayedAt || 0) > 0)
            .sort((a, b) => (b.lastPlayedAt || 0) - (a.lastPlayedAt || 0));
        pedestalGame = sorted.find(g => g.favorite)
            || recentForPedestal[0]
            || null;
        updatePedestal();

        // 「最近游玩」旋转展架：按最近游玩时间取前 5 款（没有记录时用前 5 款兜底，避免空架子）
        const recent = sorted
            .filter(g => (g.lastPlayedAt || 0) > 0)
            .sort((a, b) => (b.lastPlayedAt || 0) - (a.lastPlayedAt || 0));
        const rotatorList = recent.length ? recent : sorted.slice(0, ROTATOR_MAX);
        fillRotator(rotatorList);
        log('旋转展架：' + rotatorList.length + ' 款'
            + (recent.length ? '（按最近游玩）' : '（无游玩记录，用馆藏前 5 款兜底）'));

        // 布局已在上面按分区重建（disposeAllShelves + buildShelfSlots），这里只需让剔除重算
        lastCull = 0;
    }

    /** 释放所有已建的架子并清空列表（换展品、重算布局时用） */
    function disposeAllShelves() {
        for (const shelf of shelves) {
            if (shelf.built) disposeShelf(shelf);
        }
        shelves.length = 0;
    }

    function disposeShelf(shelf) {
        shelf.covers.forEach(m => {
            group.remove(m);
            if (m.material) {
                if (m.material.map) m.material.map.dispose();
                m.material.dispose();
            }
        });
        shelf.covers.length = 0;
        shelf.boards.forEach(b => group.remove(b));
        shelf.boards.length = 0;
        if (shelf.cases) { group.remove(shelf.cases); shelf.cases.dispose && shelf.cases.dispose(); shelf.cases = null; }
        shelf.visible = false;
    }

    let pedestalGame = null;
    let pedestalMesh = null;

    function updatePedestal() {
        if (pedestalMesh) {
            pedestal.remove(pedestalMesh);
            disposeBoxMaterials(pedestalMesh);
            pedestalMesh = null;
        }
        if (!pedestalGame) return;

        const mats = makeBoxMaterials(pedestalGame);
        pedestalMesh = new THREE.Mesh(PEDESTAL_BOX_GEO, mats);
        pedestalMesh.position.y = 1.58;
        pedestalMesh.castShadow = true;      // 只有这件展品投影
        pedestalMesh.userData.game = pedestalGame;
        pedestal.add(pedestalMesh);

        if (pedestalGame.hasCover && pedestalGame.cover) {
            const g = pedestalGame;
            const img = new Image();
            img.onload = () => {
                if (!pedestalMesh || pedestalMesh.userData.game !== g) return;
                applyCoverToBox(pedestalMesh, textureFromImage(img, g.nsfw));
            };
            img.src = g.cover;
        }
    }

    /* ---------- 每帧更新 ---------- */

    let lastCull = 0;
    let phase = 0;
    let dbgLeft = 8;          // 队列跳过原因只打印前几条，避免刷屏
    let sweepAcc = 0;         // 自愈扫描节流
    let cullLogged = false;

    function update(dt, camera) {
        phase += dt;

        // 1) 分架可见性（0.4s 一次，够用且省）
        lastCull += dt;
        if (lastCull > 0.4) {
            lastCull = 0;
            const cp = camera.position;
            for (const shelf of shelves) {
                const d = cp.distanceTo(shelf.center);
                if (d < CFG.cullDist) {
                    if (!shelf.built) buildShelf(shelf);
                    if (shelf.visible === false) showShelf(shelf);
                } else if (shelf.built && shelf.visible !== false) {
                    hideShelf(shelf);
                }
            }
            // 2) 只为"可见架子"排队贴真封面
            for (const shelf of shelves) {
            if (!shelf.visible || !shelf.needsCovers) {
                if (dbgLeft > 0 && shelf.built) {
                    dbgLeft--;
                    log('队列跳过: visible=' + shelf.visible
                        + ' needsCovers=' + (shelf.needsCovers ? shelf.needsCovers.length : 'undefined')
                        + ' 已建面板=' + shelf.covers.length);
                }
                continue;
            }
            const stillNeeded = [];
            for (const item of shelf.needsCovers) {
                if (item.textured) continue;
                // 记下"怎么找到自己的网格"，失败重试时能重新定位（架子可能被重建过）
                const shelfRef = shelf;
                item.findMesh = () => shelfRef.covers.find(m => m.userData.game === item.game) || null;
                if (!item.mesh) item.mesh = item.findMesh();
                if (!item.mesh) {
                    // ⚠️ 找不到网格不能直接丢弃（以前就是在这里把条目弄丢的，导致整架永远不贴图）
                    stillNeeded.push(item);
                    continue;
                }
                item.queued = true;
                queue.push(item);
            }
            shelf.needsCovers = stillNeeded;
        }
        pumpQueue();
    }

    /**
     * 自愈扫描：不管 needsCovers 那套簿记有没有出错，
     * 直接按"当前可见的展品面板"重新排队需要贴封面的盒子。
     * 这是封面加载的主路径（needsCovers 只作为补充）。
     */
    function sweepVisibleCovers() {
        let added = 0;
        for (const shelf of shelves) {
            if (!shelf.visible) continue;
            for (const mesh of shelf.covers) {
                const g = mesh.userData.game;
                if (!g || !g.cover) continue;   // 只要有封面 URL 就尝试（不再依赖 hasCover 标志）
                if (mesh.userData.coverLoaded || mesh.userData.queued) continue;
                mesh.userData.queued = true;
                queue.push({
                    game: g,
                    mesh: mesh,
                    textured: false,
                    coverUrl: g.cover,
                    findMesh: () => mesh,
                });
                added++;
            }
        }
        if (added > 0) {
            pumpQueue();
            log('扫描到待贴封面 ' + added + ' 张');
        }
        return added;
    }

        // 3) "在玩"呼吸光（只动材质，几乎零开销）
        //    注意：不再排除收藏 —— 收藏是属性，不影响它是否"正在游玩"，
        //    所以"收藏 + 在玩"的游戏同样会呼吸（金色底 + 呼吸亮度）。
        const pulse = 0.55 + 0.45 * Math.sin(phase * 1.6);
        for (const shelf of shelves) {
            if (!shelf.visible) continue;
            for (const m of shelf.covers) {
                const g = m.userData.game;
                if (!g) continue;
                if (g.playStatus === 'playing') {
                    m.material.emissiveIntensity = 0.35 + pulse;
                }
            }
        }

        // 3.5) 自愈扫描：可见展品里还没贴成功封面的，直接重新排队（封面加载的主路径）
        sweepAcc += dt;
        if (sweepAcc > 1.2) {
            sweepAcc = 0;
            sweepVisibleCovers();
        }

        // 4) 中央展台旋转
        if (pedestalMesh) pedestalMesh.rotation.y += dt * 0.35;
        if (spinner) spinner.rotation.y += dt * 0.22;   // 旋转展架：慢速自转

        // 5) 主题展台：展品绕自身垂直轴慢速自转（像"转台展示"，四个面都能看到）
        //    速度略慢于中央展台，避免 5 件都在转时眼花。
        for (const isl of islands) {
            if (isl.mesh) isl.mesh.rotation.y += dt * 0.28;
        }

        // 6) 悬浮按钮：只有靠近的展台才显示，且始终正对相机
        updateIslandButtons(camera);
    }

    /** 射线拾取：返回命中的 game；点空展台返回 { islandSlot:N }（与 game 严格区分） */
    const raycasterHits = [];
    function pick(raycaster) {
        raycasterHits.length = 0;
        for (const shelf of shelves) {
            if (!shelf.visible) continue;
            for (const m of shelf.covers) {
                if (m.visible) raycasterHits.push(m);
            }
        }
        if (pedestalMesh) raycasterHits.push(pedestalMesh);
        for (const m of rotatorMeshes) {
            if (m.visible) raycasterHits.push(m);
        }
        // 主题展台：只剩"可见的悬浮按钮"参与点击（台座/铭牌已按 Q3 摘掉）
        for (const isl of islands) {
            if (isl.mesh) raycasterHits.push(isl.mesh);
            if (isl.btn && isl.btn.group.visible) raycasterHits.push(isl.btn.disc);
        }

        const hits = raycaster.intersectObjects(raycasterHits, false);
        if (!hits.length) return null;

        /*
         * 判定策略（Q1-甲 / Q3 定稿）：
         *   · 悬浮按钮可见且被命中 → 换展品
         *   · 盒子被命中 → 看详情
         *   · 台座 / 铭牌 **完全不参与**（它们不在候选数组里，所以自动不会命中）
         *
         * 只扫描"最近命中"就够：按钮是独立的小圆牌、且绝不与盒子重叠
         * （它在 y=2.05，盒子顶 ≈1.56），不存在"按钮和盒子抢最近"的问题。
         */
        const obj = hits[0].object;
        if (obj.userData && obj.userData.islandButton !== undefined) {
            return { isIsland: true, slot: obj.userData.islandButton };
        }
        return obj.userData ? (obj.userData.game || null) : null;
    }

    return {
        setGames,
        update,
        pick,
        openDetail,
        closeDetail,
        resizeDetail,
        /** 主题展台：玩家选完展品后调用（slot 从 0 起） */
        setIsland(slot, game) {
            setIslandGame(slot | 0, game || null);
        },
        /**
         * 找"离给定位置最近、且在交互范围内"的展台槽位（手柄 X 键用）。
         * 范围与悬浮按钮的显示距离一致 → "看得见按钮就能按 X"。
         * 没有符合的就返回 -1。
         */
        nearestIslandSlot(pos) {
            let best = -1, bestD = BUTTON_SHOW_DIST;
            for (const isl of islands) {
                const d = pos.distanceTo(isl.group.position);
                if (d < bestD) { bestD = d; best = isl.slot; }
            }
            return best;
        },
        updateIslands() { updateIslands(itemGames); },
        getGames() { return itemGames.slice(); },
        stats() {
            let visible = 0, boxes = 0;
            for (const s of shelves) {
                if (s.visible) { visible++; boxes += s.covers.length; }
            }
            return {
                shelfCount: shelves.length,
                visibleShelves: visible,
                visibleBoxes: boxes,
                items: items.length,
                covers: {
                    queued: coverStats.queued,
                    ok: coverStats.ok,
                    fail: coverStats.fail,
                    timeout: coverStats.timeout,
                    inflight: inflight,
                    pending: queue.length,
                    lastError: lastCoverError,
                },
            };
        },
    };
}