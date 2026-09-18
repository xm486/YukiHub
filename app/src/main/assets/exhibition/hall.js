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

    buildShelfSlots();
    buildPedestal();
    buildSign();
    buildZoneSigns();
    buildRotator();
    buildWallPanels();

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

        // 悬挂牌：说明这台展架是「最近游玩」（挂在旋转盒子上方，不挡盒子）
        const plate = new THREE.Mesh(
            new THREE.PlaneGeometry(1.45, 0.375),
            new THREE.MeshBasicMaterial({
                map: makeLabelTexture('最近游玩'),
                transparent: true,
                side: THREE.DoubleSide,
            })
        );
        plate.position.set(0, 1.70, 0);
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
            const mesh = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.66, 0.10), mats);
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

    function applyStatus(mat, game) {
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
        pedestalMesh = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.9, 0.13), mats);
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
    }

    /** 射线拾取：返回命中的 game（只查可见架子） */
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

        const hits = raycaster.intersectObjects(raycasterHits, false);
        if (!hits.length) return null;
        return hits[0].object.userData.game || null;
    }

    return {
        setGames,
        update,
        pick,
        openDetail,
        closeDetail,
        resizeDetail,
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