/**
 * 音乐厅（Concert Hall）+ 前厅感应门 + 预留走廊
 * ============================================
 * 本文件承载"主展厅之外"的第二空间，独立于 hall.js（避免后者继续膨胀）。
 *
 * 组成：
 *   1. 前厅感应门（双开滑门）—— 位于主厅前墙正中，走近自动开、走远自动关（带迟滞）
 *   2. 预留走廊            —— 门后一段短通道，把主厅与音乐厅连接起来
 *   3. 音乐厅本体          —— 中央古典碟片机 + 后方专辑墙 + 前方大屏
 *
 * 设计约束：
 *   - 数据来源（音乐专辑 / PV）**暂未接入**，用占位数据先把空间与交互搭起来；
 *     后续接真数据时，只需替换 `loadAlbums()` / `playPV()` 两处即可。
 *   - 遵守项目共享几何约定：**循环里不 new 几何**（见 tools/audit.js 检查 1）。
 *   - 所有 raycaster 候选对象打 `userData.concertXxx` 标记，便于 pick 与审计追溯。
 */

import * as THREE from './vendor/three.module.min.js';

/* ==================== 布局常量 ==================== */

// 主厅尺寸（从 exh.js 的 ROOM 同步；主厅若改动需一并更新）
const ROOM_W = 34;
const ROOM_H_MAIN = 7;

// 主厅前墙位置（exh.js 里 ROOM.d/2 = 11）
const MAIN_FRONT_Z = 11;
const WALL_T = 0.32;              // 墙厚（做侧壁用）

// —— 感应门 ——
// 主厅门尺寸（音乐厅门在 buildLeaves 里单独给参数）
const DOOR_W = 5.0;               // 门洞总宽
const DOOR_H = 3.40;              // 门洞高

// —— 走廊 ——
const CORRIDOR_LEN = 8.0;         // 走廊纵深
const CORRIDOR_W = 7.0;           // 走廊宽度
const CORRIDOR_H = 4.6;           // 走廊层高

// —— 音乐厅 ——
const HALL_W = 26.0;
const HALL_D = 20.0;
const HALL_H = 9.0;
const HALL_Z0 = MAIN_FRONT_Z + CORRIDOR_LEN;      // 音乐厅后墙 z（= 19）
const HALL_CENTER_Z = HALL_Z0 + HALL_D / 2;       // 音乐厅中心 z（= 29）
// 玩家可达的最大 z（走进音乐厅中央）
const WALK_MAX_Z = HALL_Z0 + HALL_D - 2.0;

/* ==================== 共享几何（防泄漏）==================== */
// ⚠️ 规则：几何在模块级创建一次，循环中只做 clone/复用，绝不 new。
//
// ★ 墙体为什么用 Box 而不是 Plane：
//   PlaneGeometry 是**单面**的 —— 从背面看完全透明。
//   走廊在门的另一侧、音乐厅在主厅的另一侧，玩家一定会看到墙的背面，
//   所以全部改成"薄 Box"（厚 0.16），天然双面、法线正确、光照正常。
const WALL_THICK = 0.16;

const SHARED = {
    // 薄墙板（Box）：靠 scale 变形，服务所有墙 / 地板 / 天花
    slab: new THREE.BoxGeometry(1, 1, 1),
    // 装饰用平面（灯条、招牌、贴图面 —— 这些是"贴在别的面上"的，单面 OK）
    plane: new THREE.PlaneGeometry(1, 1),
    box: new THREE.BoxGeometry(1, 1, 1),
    // 碟片机
    discBase: new THREE.BoxGeometry(2.6, 0.42, 2.0),
    discPlatter: new THREE.CylinderGeometry(0.92, 0.92, 0.07, 40),
    discLabel: new THREE.CircleGeometry(0.30, 24),
    toneArm: new THREE.BoxGeometry(0.07, 0.07, 1.05),
    // 专辑盒
    albumBox: new THREE.BoxGeometry(1.0, 1.0, 0.16),
    // 大屏
    screen: new THREE.PlaneGeometry(1, 1),
    // 留声机号角（开口圆锥）
    horn: new THREE.CylinderGeometry(0.12, 0.86, 0.95, 24, 1, true),
};

/* ==================== 纹理工具 ==================== */

let sharedSlotTex = null;
/** 灯槽渐变贴图（复用主厅那套"两端淡、中间亮"的思路） */
function slotTexture() {
    if (sharedSlotTex) return sharedSlotTex;
    const c = document.createElement('canvas');
    c.width = 128; c.height = 16;
    const g = c.getContext('2d');
    const grd = g.createLinearGradient(0, 0, c.width, 0);
    grd.addColorStop(0.00, 'rgba(255,238,214,0.0)');
    grd.addColorStop(0.28, 'rgba(255,238,214,0.85)');
    grd.addColorStop(0.50, 'rgba(255,246,228,1.0)');
    grd.addColorStop(0.72, 'rgba(255,238,214,0.85)');
    grd.addColorStop(1.00, 'rgba(255,238,214,0.0)');
    g.fillStyle = grd;
    g.fillRect(0, 0, c.width, c.height);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    sharedSlotTex = t;
    return t;
}

/** 文字牌贴图（标题用）*/
function labelTexture(text, sub) {
    const c = document.createElement('canvas');
    c.width = 1024; c.height = 256;
    const g = c.getContext('2d');
    g.clearRect(0, 0, c.width, c.height);

    g.fillStyle = 'rgba(12,18,30,0.86)';
    g.fillRect(0, 0, c.width, c.height);
    g.strokeStyle = 'rgba(196,222,255,0.55)';
    g.lineWidth = 4;
    g.strokeRect(10, 10, c.width - 20, c.height - 20);

    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillStyle = '#EAF2FF';
    g.font = 'bold 96px system-ui, "PingFang SC", sans-serif';
    g.fillText(text, c.width / 2, sub ? 96 : c.height / 2);
    if (sub) {
        g.fillStyle = 'rgba(180,206,238,0.9)';
        g.font = '44px system-ui, "PingFang SC", sans-serif';
        g.fillText(sub, c.width / 2, 186);
    }
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
}

/** 大屏默认内容（无 PV 时的程序化动画 —— 由 update 里重绘）*/
function makeScreenCanvas() {
    const c = document.createElement('canvas');
    c.width = 512; c.height = 288;
    return c;
}

/**
 * 灯组开关：把"只对某个房间有意义"的点光收集起来，
 * 在 update 里按玩家所在房间整体开关。
 *
 * 为什么必须这么做：
 *   WebGL 的实时光照**不按距离剔除** —— 场景里有 17 盏点光时，
 *   每一个 Standard 材质的像素都要做 17 次光照计算，
 *   哪怕那盏灯在 30 米外的另一个房间里。
 *   主厅的灯 + 音乐厅的灯同时开着 = 纯粹浪费。
 */
function makeLightBank() {
    const lights = [];
    let on = true;
    return {
        add(l) { lights.push(l); return l; },
        setWant(v) {
            if (v === on) return;
            on = v;
            for (const l of lights) l.visible = v;
        },
        get count() { return lights.length; },
    };
}

/* ==================== 主体 ==================== */

export function createConcert(scene, opts) {
    const audio = (opts && opts.audio) || null;   // 开门音效（由 exh.js 注入）
    const log = (opts && opts.log) || function () { };   // 桥接日志（PV 诊断用）
    const group = new THREE.Group();
    group.name = 'concert';

    // 门状态（★ 现在有**两扇**门：主厅→走廊、走廊→音乐厅）
    //   doors[i] = { leaves, glowMat, light, z, openDist, closeDist, open, t }
    let doors = [];
    let doorOpen = false;             // 主厅门状态（= doors[0].open，供 isDoorOpen 用）
    let doorT = 0;                    // 主厅门 0..1 开度（供调试用）
    // 大屏
    let screenTex = null, screenCvs = null, screenCtx = null;
    // 专辑墙
    const albumSlots = [];            // { mesh, slot }
    // 射线候选
    const pickables = [];
    // 灯组：主厅侧/音乐厅侧分开，按玩家位置开关（省光照开销）
    const hallLights = makeLightBank();       // 音乐厅的灯
    const corridorLights = makeLightBank();   // 走廊的灯（单独一组，避免跨 z 时整条走廊变黑）

    let built = false;

    /* ---------------- 材质工厂 ---------------- */

    function wallMat(color, rough) {
        return new THREE.MeshStandardMaterial({ color: color, roughness: rough, metalness: 0.04 });
    }

/**
     * ★ 通用：造一扇"感应双开滑门"（含门框 + 门楣 + 发光灯条 + 点光）
     *
     * 为什么抽成函数：
     *   现在有两扇门 —— ① 主厅→走廊（z=11，宽 5.0）
     *                      ② 走廊→音乐厅（z=19，宽 7.0 与走廊同宽）
     *   两扇门的结构完全一样，只是位置/尺寸/朝向不同。
     *   写两遍必然"改一处漏一处"（前面已经踩过这种坑）。
     *
     * @param opts { z, width, height, frameH, faceSign, glow }
     *   z        门所在 z
     *   width    门洞宽
     *   height   门洞高
     *   frameH   门框所在空间的总高（用于补门楣上方的墙块高度）
     *   faceSign 门扇上灯条朝向（+1 或 -1）
     *   glow     是否加氛围灯（主厅门有，音乐厅门也加）
     */
    function makeSlidingDoor(opts) {
        const { z, width, height, frameH, faceSign, slideCap } = opts;
        const leafW = width / 2;

        const frameMat = new THREE.MeshStandardMaterial({ color: 0x2A3444, roughness: 0.65, metalness: 0.20 });
        const leafMat = new THREE.MeshStandardMaterial({
            color: 0x8FA6C4, roughness: 0.30, metalness: 0.72,
            emissive: new THREE.Color(0x1A2A44), emissiveIntensity: 0.6,
        });

        // —— 门框：两侧立柱 + 上沿 ——
        for (const sgn of [-1, 1]) {
            const p = new THREE.Mesh(SHARED.box, frameMat);
            p.scale.set(0.22, height, WALL_T);
            p.position.set(sgn * (width / 2 + 0.11), height / 2, z);
            scene.add(p);
        }
        const head = new THREE.Mesh(SHARED.box, frameMat);
        head.scale.set(width + 0.44, 0.22, WALL_T);
        head.position.set(0, height + 0.11, z);
        scene.add(head);

        // —— 门扇（两扇，各 width/2 宽）——
        // ★ 用户定稿方案："别做独立的中缝条（还要淡出），
        //   直接在门内两侧本身就加一层黑边/标志性图案，随门移动，严丝合缝。"
        //   → 每扇门自己带**内缘封边**（深色金属条 + 一条细高光）。
        //     两扇关上时，两条封边紧贴 → 中间自然出现一条"中线"；
        //     门一开，两条封边**各随各门走**，中间自然分开。
        //     没有生命周期问题，不需要任何"淡出"逻辑。
        const leaves = [];
        for (const sgn of [-1, 1]) {
            // ⚠️ 不在这里 new 几何（审计规则 10：SHARED 块之后禁止 new）。
            //    SHARED.box 是单位立方体，靠 scale 变形即可得到任意宽度的门扇 ——
            //    这样两扇门共用同一份几何，且永远不会泄漏。
            // ★ 位置：每扇铺满自己那一半（外缘贴门框立柱，内缘到中线）
            //   两扇合起来正好铺满门洞 → 严丝合缝。
            const closedX = sgn * (leafW / 2);
            const leaf = new THREE.Mesh(SHARED.box, leafMat);
            leaf.scale.set(leafW, height, 0.10);
            leaf.position.set(closedX, height / 2, z);
            leaf.userData.closedX = closedX;
            leaf.userData.leafW = leafW;
            // ★ 开门行程：默认滑到"比自身宽一点"（确保完全让开门洞）。
            //   ⚠️ 但必须受 slideCap 限制 —— 门外面是墙的话，滑过头就穿墙了。
            //   门②（走廊尽头）：走廊才 7m 宽、门洞也 7m，两侧只有 0.22m 余量
            //   → 若不限制，门扇外缘会跑到 ±7.06，直接插进 ±3.72 的侧墙里。
            //   设 slideCap 后，行程被压到"刚好让开门洞"（门扇半嵌在门框边）。
            const maxSlide = (typeof slideCap === 'number') ? Math.max(0, slideCap) : 99;
            leaf.userData.slide = Math.min(leafW + 0.06, maxSlide);
            scene.add(leaf);
            leaves.push(leaf);

            // ★ 门扇：不挂任何装饰（朴素方案）
            //   ────────────────────────────────────────────────
            //   ⚠️ 血泪史（别再往门扇上加东西了）：
            //   曾尝试在门扇上加"内缘封边 + 高光线"作为两扇门的"中线标识"，
            //   连改四轮都不对，因为存在**三重物理矛盾**：
            //     ① 挂在门扇内缘（接触面）→ 闭门时被另一扇挡住看不见；
            //     ② 只加在 faceSign 那一面 → 玩家常站在另一侧，看不到；
            //     ③ **感应门 openDist=2.5m，玩家一走近门就开了**
            //        → 玩家根本没有任何机会在"闭门状态"下近距离看中线。
            //   结论：**门扇上什么都不加**。门的开合感由门框氛围灯 + 状态灯表达，
            //         不需要"中线"这种静态标识。
        }

        // —— 氛围灯（门框灯条 + 门中央点光）——
        // ⚠️ 为什么不用 PlaneGeometry 做灯条：
        //   平面是"零厚度"的，一旦旋转（如门楣那条要平铺朝下），
        //   从侧面/背面看就会**退化成一个无限细的白线**（用户截图里那道横贯线）。
        //   改成**薄 Box**（厚 0.03），任何角度看都有实体感，不会再拉线。
        const glowMat = new THREE.MeshBasicMaterial({
            color: 0xBFD6FF, transparent: true, opacity: 0.55,
            blending: THREE.AdditiveBlending, depthWrite: false,
        });

        // 左右立柱内侧的竖灯条
        for (const sgn of [-1, 1]) {
            const strip = new THREE.Mesh(SHARED.box, glowMat);
            strip.scale.set(0.06, height - 0.22, 0.30);
            strip.position.set(sgn * (width / 2 - 0.10), height / 2, z);
            scene.add(strip);
        }
        // 门楣下沿的横灯条（薄 Box：宽 × 0.06 × 0.30，藏在门楣正下方）
        const headStrip = new THREE.Mesh(SHARED.box, glowMat);
        headStrip.scale.set(width - 0.30, 0.06, 0.30);
        headStrip.position.set(0, height - 0.10, z);
        scene.add(headStrip);

        // ★ 状态灯：门框两侧各一颗小圆灯，颜色/亮度跟随门的开度
        //   闭门时是"冷蓝待机"，开门时变成"暖白激活" —— 直观反映门的感应状态。
        const statusDots = [];
        for (const sgn of [-1, 1]) {
            const dot = new THREE.Mesh(SHARED.discLabel, new THREE.MeshBasicMaterial({
                color: 0x9FC4FF, transparent: true, opacity: 0.9, side: THREE.DoubleSide,
            }));
            dot.scale.set(0.16, 0.16, 1);
            // 贴在门框立柱的**内侧**、离地 1.6m（人眼高度），朝向门洞
            dot.position.set(sgn * (width / 2 - 0.02), 1.6, z - faceSign * 0.02);
            dot.rotation.y = faceSign > 0 ? Math.PI : 0;
            scene.add(dot);
            statusDots.push(dot);
        }

        const light = new THREE.PointLight(0x9FC4FF, 0.6, 9, 2);
        light.position.set(0, height - 0.3, z - faceSign * 0.6);
        scene.add(light);
        hallLights.add(light);

        return { leaves, glowMat, light, statusDots };
    }

    /* ---------------- 1b. 主厅 → 走廊 的门 ---------------- */

/**
     * 主厅 → 走廊 的入口。
     * 只建"门洞周围的墙块"（左/右/门楣上方），门框 + 门扇 + 灯由 makeSlidingDoor 负责。
     */
    function buildDoorWall() {
        const halfW = ROOM_W / 2;
        const sideW = halfW - DOOR_W / 2;      // 门洞两侧剩余宽度
        const aboveH = ROOM_H_MAIN - DOOR_H;   // 门洞上方剩余高度

        const mat = wallMat(0x38445A, 0.92);

        // 左 / 右墙块
        for (const sgn of [-1, 1]) {
            const m = new THREE.Mesh(SHARED.slab, mat);
            m.scale.set(sideW, ROOM_H_MAIN, WALL_THICK);
            m.position.set(sgn * (DOOR_W / 2 + sideW / 2), ROOM_H_MAIN / 2, MAIN_FRONT_Z);
            scene.add(m);
        }
        // 门楣上方墙块
        const top = new THREE.Mesh(SHARED.slab, mat);
        top.scale.set(DOOR_W, aboveH, WALL_THICK);
        top.position.set(0, DOOR_H + aboveH / 2, MAIN_FRONT_Z);
        scene.add(top);
    }

    /**
     * 建两扇门：
     *   ① 主厅 → 走廊（z = 11，宽 5.0，朝主厅那侧打灯条）
     *   ② 走廊 → 音乐厅（z = 19，宽 = 走廊宽 7.0，朝走廊那侧打灯条）
     *
     * doors = [{ leaves, glowMat, light, z, openDist, closeDist, open, t }]
     *   update() 里按玩家到每扇门的距离分别开关。
     *
     * ⚠️⚠️ 必须显式初始化 `open: false, t: 0`！
     *   曾踩过的坑：忘了给 `t` 初值 → update 里 `D.t += step` 得到 **NaN**
     *   → `leaf.position.x = NaN` → **门扇整个消失**；`glowMat.opacity = NaN`
     *   → **门框灯条闪烁/异常**。（用户截图里"门扇不见 + 门框在闪"就是这个）
     *
     * ⚠️⚠️ `slideCap`：门扇能滑多远（**不能超过门外可容纳的空间**）！
     *   门②（走廊尽头）外面就是**走廊侧墙**，走廊才 7m 宽，
     *   而门洞本身也 7m → 单扇 3.5m + 行程 3.56m = 外缘 ±7.06m，
     *   **直接穿进 ±3.72m 的侧墙里**（穿模）。
     *   这里用 `slideCap` 把行程压到"刚好让开门洞"：
     *     需要让开 = 单扇宽 leafW；所以 slide 至少要 leafW。
     *     但外缘最多到（门洞边 + 墙厚）→ 走廊墙在 3.72，
     *     门洞边 3.50 → 只允许再往外 0.22。
     *   → 做不到"完全滑进墙里"时，就**让门扇叠在门框内侧**
     *     （像真滑门那样"半嵌"），并保持不穿墙。
     */
    function buildLeaves() {
        doors = [
            Object.assign(makeSlidingDoor({
                z: MAIN_FRONT_Z, width: DOOR_W, height: DOOR_H,
                frameH: ROOM_H_MAIN, faceSign: -1,
                // 主厅门前墙两侧各有 14.5m 墙块 → 门扇可以完全藏进墙里
                slideCap: 99,
            }), { z: MAIN_FRONT_Z, openDist: 2.5, closeDist: 4.2, open: false, t: 0 }),

            Object.assign(makeSlidingDoor({
                z: HALL_Z0, width: CORRIDOR_W, height: CORRIDOR_H - 0.4,
                frameH: HALL_H, faceSign: 1,
                // ⚠️ 暂不限制行程（写 99）。门② 的“穿墙 vs 开不了门”矛盾
                //    尚未解决，见文档待办 S。此值待定方案后修改。
                slideCap: 99,
            }), { z: HALL_Z0, openDist: 2.8, closeDist: 4.5, open: false, t: 0 }),
        ];
    }

    /* ---------------- 2. 走廊 ---------------- */

    function buildCorridor() {
        const z0 = MAIN_FRONT_Z;                    // 走廊起点
        const z1 = MAIN_FRONT_Z + CORRIDOR_LEN;     // 走廊终点（= 音乐厅前墙）
        const cz = (z0 + z1) / 2;
        const halfW = CORRIDOR_W / 2;

        const wallM = wallMat(0x333E52, 0.94);
        const floorM = new THREE.MeshStandardMaterial({ color: 0x2B3446, roughness: 0.55, metalness: 0.10 });
        const ceilM = new THREE.MeshStandardMaterial({ color: 0x232C3A, roughness: 0.96 });

        // 地板 / 天花
        const fl = new THREE.Mesh(SHARED.slab, floorM);
        fl.scale.set(CORRIDOR_W, WALL_THICK, CORRIDOR_LEN);
        fl.position.set(0, -WALL_THICK / 2, cz);
        scene.add(fl);

        const ce = new THREE.Mesh(SHARED.slab, ceilM);
        ce.scale.set(CORRIDOR_W, WALL_THICK, CORRIDOR_LEN);
        ce.position.set(0, CORRIDOR_H + WALL_THICK / 2, cz);
        scene.add(ce);

        // 左右侧墙
        for (const sgn of [-1, 1]) {
            const w = new THREE.Mesh(SHARED.slab, wallM);
            w.scale.set(WALL_THICK, CORRIDOR_H, CORRIDOR_LEN);
            w.position.set(sgn * (halfW + WALL_THICK / 2), CORRIDOR_H / 2, cz);
            scene.add(w);
        }

        // ★★ 门套墙墩（用户方案："把墙加厚把穿模覆盖掉，看不见不就行了"）★★
        //   ────────────────────────────────────────────────────────────
        //   问题：门②（走廊→音乐厅）洞宽 = 走廊宽 = 7.0m，两侧**零余量**。
        //         门扇滑开时外缘跑到 ±7.06m，而走廊侧墙只到 ±3.72m
        //         → 门扇**穿出走廊外墙**，从走廊里能直接看到两根白亮竖条。
        //   解决：在走廊尽头（z = z1 附近）两侧各加一块**宽墙墩**，
        //         把"门扇滑出去的那段空间"围起来，玩家就看不到穿模了。
        //   尺寸：x 从 ±3.5 到 ±7.3（宽 3.8m）
        //         z 从 z1-1.7 到 z1+0.1（厚 1.8m，**向后多盖 10cm**，
        //         因为门扇正贴在 z=z1 上，厚 0.10 → 必须盖住 z1+0.05 才完整）
        //         高度 = 走廊高
        const pierW = 3.8;
        const pierD = 1.8;
        const pierH = CORRIDOR_H;
        const pierM = wallMat(0x2E3848, 0.95);
        for (const sgn of [-1, 1]) {
            const p = new THREE.Mesh(SHARED.slab, pierM);
            p.scale.set(pierW, pierH, pierD);
            // 中心 z = z1 + 0.1 - pierD/2 = z1 - 0.8
            p.position.set(sgn * (halfW + pierW / 2), pierH / 2, z1 + 0.1 - pierD / 2);
            scene.add(p);
        }

        // 两侧嵌入式灯槽（氛围，用户要求"乙：放点氛围"）
        const slotM = new THREE.MeshBasicMaterial({ map: slotTexture(), transparent: true });
        for (const sgn of [-1, 1]) {
            const s = new THREE.Mesh(SHARED.plane, slotM);
            s.scale.set(CORRIDOR_LEN * 0.86, 0.16, 1);
            s.position.set(sgn * (halfW - 0.04), CORRIDOR_H - 0.55, cz);
            s.rotation.y = sgn > 0 ? -Math.PI / 2 : Math.PI / 2;
            scene.add(s);
        }
        // 顶灯（走廊要有光，但别太多 —— 只一盏主灯 + 门口一盏补光）
        const cl = new THREE.PointLight(0xDCE8FF, 5.5, 13, 2);
        cl.position.set(0, CORRIDOR_H - 0.5, cz);
        scene.add(cl);
        corridorLights.add(cl);   // ★ 走廊灯单独一组

        // ★ 走廊尽头（= 音乐厅门口）补光：
        //   玩家从走廊跨进音乐厅的那几步，两边的灯都"够不着" →
        //   这里放一盏稍亮的补光，跨门时不会出现黑洞。
        const doorFill = new THREE.PointLight(0xE6D6B8, 7.5, 11, 2);
        doorFill.position.set(0, CORRIDOR_H - 0.6, z1 - 0.6);
        scene.add(doorFill);
        corridorLights.add(doorFill);

        // ★ 音乐厅**内侧**门口补光：
        //   跨过门之后、厅内的灯（轨道/屏幕/碟机）都还在更深处，
        //   这里放一盏，保证"刚进门那 3~4 米"也是亮的。
        const inFill = new THREE.PointLight(0xFFE0BC, 8.5, 13, 2);
        inFill.position.set(0, 4.2, z1 + 2.2);
        scene.add(inFill);
        hallLights.add(inFill);    // 归音乐厅组（进厅就亮）

        // 走廊口标题牌（"音乐厅"指引）
        const sign = new THREE.Mesh(SHARED.plane, new THREE.MeshBasicMaterial({
            map: labelTexture('音乐厅', 'CONCERT HALL'), transparent: true,
        }));
        sign.scale.set(3.4, 0.85, 1);
        sign.position.set(0, DOOR_H + 0.75, MAIN_FRONT_Z - 0.02);
        sign.rotation.y = Math.PI;
        scene.add(sign);
    }

    /* ---------------- 3. 音乐厅 ---------------- */

    function buildHall() {
        const halfW = HALL_W / 2;
        const z0 = HALL_Z0, z1 = HALL_Z0 + HALL_D;
        const cz = (z0 + z1) / 2;

        const floorM = new THREE.MeshStandardMaterial({ color: 0x3A2E28, roughness: 0.42, metalness: 0.08 });
        const wallM = wallMat(0x2E3A50, 0.90);
        const ceilM = new THREE.MeshStandardMaterial({ color: 0x1E2733, roughness: 0.96 });

        // 地板（暖木色 —— 音乐厅该有的质感）
        // ★ 全部用 slab（薄 Box）而不是 plane：plane 是单面，从背面看透明。
        const fl = new THREE.Mesh(SHARED.slab, floorM);
        fl.scale.set(HALL_W, WALL_THICK, HALL_D);
        fl.position.set(0, -WALL_THICK / 2, cz);
        fl.receiveShadow = true;
        scene.add(fl);
        // 天花
        const ce = new THREE.Mesh(SHARED.slab, ceilM);
        ce.scale.set(HALL_W, WALL_THICK, HALL_D);
        ce.position.set(0, HALL_H + WALL_THICK / 2, cz);
        scene.add(ce);
        // 左右侧墙
        for (const sgn of [-1, 1]) {
            const w = new THREE.Mesh(SHARED.slab, wallM);
            w.scale.set(WALL_THICK, HALL_H, HALL_D);
            w.position.set(sgn * (halfW + WALL_THICK / 2), HALL_H / 2, cz);
            scene.add(w);
        }
        // 后墙（音乐厅最里侧，z = z1）
        const back = new THREE.Mesh(SHARED.slab, wallM);
        back.scale.set(HALL_W, HALL_H, WALL_THICK);
        back.position.set(0, HALL_H / 2, z1 + WALL_THICK / 2);
        scene.add(back);

        // 前墙（与走廊相接那面）—— 留门洞（走廊开口）
        const sideW = halfW - CORRIDOR_W / 2;
        for (const sgn of [-1, 1]) {
            const w = new THREE.Mesh(SHARED.slab, wallM);
            w.scale.set(sideW, HALL_H, WALL_THICK);
            w.position.set(sgn * (CORRIDOR_W / 2 + sideW / 2), HALL_H / 2, z0 - WALL_THICK / 2);
            scene.add(w);
        }
        const topW = new THREE.Mesh(SHARED.slab, wallM);
        topW.scale.set(CORRIDOR_W, HALL_H - CORRIDOR_H, WALL_THICK);
        topW.position.set(0, CORRIDOR_H + (HALL_H - CORRIDOR_H) / 2, z0 - WALL_THICK / 2);
        scene.add(topW);

        // 天花轨道灯（沿 z 两列）
        // ⚠️ 灯数控制：WebView 的 WebGL 实时光照开销 = O(灯数 × 面数)，
        //    所以这里**只放 4 盏"真光"**（每列 2 盏，覆盖大厅纵深），
        //    另用 4 条"发光灯条"（MeshBasicMaterial，零光照开销）补足视觉密度。
        const trackM = new THREE.MeshStandardMaterial({ color: 0x4A566B, roughness: 0.5, metalness: 0.5 });
        const glowM = new THREE.MeshBasicMaterial({
            map: slotTexture(), transparent: true, opacity: 0.9,
            blending: THREE.AdditiveBlending, depthWrite: false,
        });
        for (const x of [-6.0, 6.0]) {
            // 轨道
            const tr = new THREE.Mesh(SHARED.box, trackM);
            tr.scale.set(0.16, 0.16, HALL_D * 0.8);
            tr.position.set(x, HALL_H - 0.30, cz);
            scene.add(tr);

            // 真光：每列 2 盏
            for (let i = 0; i < 2; i++) {
                const lp = new THREE.PointLight(0xFFE9C8, 5.0, 20, 2);
                lp.position.set(x, HALL_H - 0.75, z0 + 4.5 + i * (HALL_D - 9));
                scene.add(lp);
                hallLights.add(lp);
            }
            // 视觉灯条：每列 4 条（不参与光照计算，只是"看起来有很多灯"）
            // ⚠️ 用薄 Box 而不是 plane：这里是**朝下平铺**的，零厚度平面
            //    从侧面看会退化成白线（和其它灯条同一个坑）。
            for (let i = 0; i < 4; i++) {
                const bar = new THREE.Mesh(SHARED.box, glowM);
                bar.scale.set(0.10, 0.04, HALL_D * 0.18);
                bar.position.set(x, HALL_H - 0.42, z0 + 2.5 + i * (HALL_D - 5) / 3);
                scene.add(bar);
            }
        }
        // 环境光（音乐厅整体比主厅暖）
        const amb = new THREE.HemisphereLight(0xFFE0BC, 0x1A1410, 0.85);
        scene.add(amb);
    }

    /* ---------------- 4. 中央碟片机 ---------------- */

    let platter = null, discMesh = null, spinner = null;   // spinner = 转盘组（播放时旋转）
    let playing = false;                                   // 播放状态（点击碟机切换）
    /** M5-d：黑胶标签材质（播放时贴当前专辑封面） */
    let discLabelMat = null;

    /**
     * 中央碟机：**复古立式留声机**（v4）
     *
     * v1~v3 一路踩的坑：
     *   v1 黑方盒+圆盘 → 认不出
     *   v2 3.25:1 矮胖 → 像书桌
     *   v3 加号角 → 用户："喇叭太丑，还做得那么高，我人都看不到了"
     *
     * v4 定稿（用户明确要求）：
     *   · **去掉号角喇叭**
     *   · **整体压低** —— 箱体 2.2 → 1.35 高，加顶盖后约 2.0m（略高于人眼，不挡视线）
     *   · **保留"点播放时能旋转"** → 转盘 + 黑胶做成可旋转，并由 `playing` 状态驱动
     *   · 保留复古元素：木箱 + 黄铜包边 + 正面大铜盘 + 摇柄
     */
    function buildTurntable() {
        const cz = HALL_CENTER_Z;
        const g = new THREE.Group();

        const woodM = new THREE.MeshStandardMaterial({ color: 0x5A3B22, roughness: 0.52, metalness: 0.12 });
        const woodDarkM = new THREE.MeshStandardMaterial({ color: 0x331F0F, roughness: 0.60, metalness: 0.12 });
        const brassM = new THREE.MeshStandardMaterial({ color: 0xC9A15A, roughness: 0.26, metalness: 0.94 });
        const brassDark = new THREE.MeshStandardMaterial({ color: 0x8A6A2E, roughness: 0.35, metalness: 0.90 });

        // ★ 尺寸定稿（用户："还是太高了" → 再压一档）
        //   用户视高 1.66m。上一版总高 ~1.57m，几乎平视 → 显得"顶到天"。
        //   现在压到 **总高 ~1.05m**（箱体 0.88 + 顶盖 + 转盘），
        //   站着是俯视，才有"操作一台机器"的感觉。
        const BW = 1.55, BH = 0.88, BD = 1.55;

        // —— 箱体 ——
        const cab = new THREE.Mesh(SHARED.box, woodM);
        cab.scale.set(BW, BH, BD);
        cab.position.y = BH / 2;
        g.add(cab);

        // 顶盖 / 底座
        const lid = new THREE.Mesh(SHARED.box, woodDarkM);
        lid.scale.set(BW + 0.18, 0.10, BD + 0.18);
        lid.position.y = BH + 0.05;
        g.add(lid);

        const plinth = new THREE.Mesh(SHARED.box, woodDarkM);
        plinth.scale.set(BW + 0.24, 0.16, BD + 0.24);
        plinth.position.y = 0.08;
        g.add(plinth);

        // 黄铜包边
        for (const y of [0.20, BH - 0.08]) {
            const trim = new THREE.Mesh(SHARED.box, brassM);
            trim.scale.set(BW + 0.06, 0.045, BD + 0.06);
            trim.position.y = y;
            g.add(trim);
        }

        // 正面大铜盘（声孔）—— 保留，这是"这就是台机器"的信息
        const faceDisc = new THREE.Mesh(SHARED.discLabel, brassDark);
        faceDisc.scale.set(1.05, 1.05, 1);
        faceDisc.position.set(0, BH * 0.52, BD / 2 + 0.015);
        g.add(faceDisc);
        const faceRing = new THREE.Mesh(SHARED.discPlatter, brassM);
        faceRing.scale.set(0.36, 0.05, 0.36);
        faceRing.rotation.x = Math.PI / 2;
        faceRing.position.set(0, BH * 0.52, BD / 2 + 0.01);
        g.add(faceRing);

        // —— 箱顶：转盘 + 黑胶（★ 会被 playing 状态驱动旋转）——
        //    转盘与黑胶放进一个 group，这样绕自身轴转不受箱体朝向影响
        spinner = new THREE.Group();
        spinner.position.set(0, BH + 0.12, 0);
        g.add(spinner);

        const platM = new THREE.MeshStandardMaterial({ color: 0x22222A, roughness: 0.42, metalness: 0.72 });
        platter = new THREE.Mesh(SHARED.discPlatter, platM);
        platter.scale.set(0.62, 0.5, 0.62);
        platter.position.y = 0;
        spinner.add(platter);

        const discM = new THREE.MeshStandardMaterial({ color: 0x0C0C10, roughness: 0.20, metalness: 0.38 });
        discMesh = new THREE.Mesh(SHARED.discPlatter, discM);
        discMesh.scale.set(0.54, 0.30, 0.54);
        discMesh.position.y = 0.05;
        spinner.add(discMesh);

        // —— M5-d：黑胶顶面的"专辑标签"（CD 造型：外圈是封面图，中心是"孔"）——
        //    ⚠️ 几何换算（上一版翻车）：discLabel = CircleGeometry(半径0.30)，
        //    scale 是**倍数**不是目标半径！scale 0.52 → 实际半径只有 0.156，
        //    而黑胶面半径 ≈ 0.92×0.54 ≈ 0.50 → 封面缩成中心一小点 = "CD里套CD"。
        //    正确：目标半径 0.50 ÷ 0.30 ≈ 1.67 倍。
        const labelM = new THREE.MeshStandardMaterial({
            color: 0x3A404C, roughness: 0.45, metalness: 0.30,
        });
        const discLabelMesh = new THREE.Mesh(SHARED.discLabel, labelM);
        discLabelMesh.rotation.x = -Math.PI / 2;
        discLabelMesh.scale.set(1.67, 1.67, 1);   // 0.30×1.67 ≈ 0.50 → 与黑胶面几乎同大
        discLabelMesh.position.y = 0.068;
        spinner.add(discLabelMesh);
        discLabelMat = labelM;   // 供 setDiscCover 替换 map

        // 中心"孔"：深色小圆片（真 CD 中孔比例 ≈ 直径的 1/7）
        //    目标半径 0.075 ÷ 0.30 = 0.25 倍
        const holeM = new THREE.MeshStandardMaterial({
            color: 0x0A0A0D, roughness: 0.85, metalness: 0.10,
        });
        const discHole = new THREE.Mesh(SHARED.discLabel, holeM);
        discHole.rotation.x = -Math.PI / 2;
        discHole.scale.set(0.25, 0.25, 1);
        discHole.position.y = 0.072;
        spinner.add(discHole);

        // —— 唱臂（细金属臂，压在碟片上）——
        const armM = new THREE.MeshStandardMaterial({ color: 0xC6CCD8, roughness: 0.24, metalness: 0.92 });
        const arm = new THREE.Mesh(SHARED.toneArm, armM);
        arm.scale.set(0.45, 0.45, 0.52);
        arm.position.set(0.58, BH + 0.26, -0.16);
        arm.rotation.y = -0.62;
        arm.rotation.x = 0.10;
        g.add(arm);
        const post = new THREE.Mesh(SHARED.box, brassM);
        post.scale.set(0.07, 0.22, 0.07);
        post.position.set(0.64, BH + 0.20, -0.42);
        g.add(post);
        const pivotKnob = new THREE.Mesh(SHARED.discLabel, brassM);
        pivotKnob.rotation.x = -Math.PI / 2;
        pivotKnob.scale.set(0.34, 0.34, 1);
        pivotKnob.position.set(0.64, BH + 0.32, -0.42);
        g.add(pivotKnob);

        // —— 侧面上弦摇柄（保留，侧面不挡视线）——
        const crankArm = new THREE.Mesh(SHARED.box, brassM);
        crankArm.scale.set(0.05, 0.32, 0.05);
        crankArm.position.set(-(BW / 2 + 0.18), 0.62, 0.28);
        crankArm.rotation.z = 0.18;
        g.add(crankArm);
        const crankKnob = new THREE.Mesh(SHARED.discLabel, woodDarkM);
        crankKnob.rotation.y = Math.PI / 2;
        crankKnob.scale.set(0.24, 0.24, 1);
        crankKnob.position.set(-(BW / 2 + 0.18), 0.78, 0.28);
        g.add(crankKnob);

        // —— 台面聚光（压低到箱顶正上方，不再顶着天花板）——
        const spot = new THREE.PointLight(0xFFE4B0, 7, 8, 2);
        spot.position.set(0, BH + 1.6, 0.3);
        g.add(spot);
        hallLights.add(spot);

        // —— 底部圆地毯 ——
        const rugM = new THREE.MeshStandardMaterial({ color: 0x5A2A2E, roughness: 0.92, metalness: 0.02 });
        const rug = new THREE.Mesh(SHARED.discPlatter, rugM);
        rug.scale.set(1.9, 0.05, 1.9);
        rug.position.y = 0.02;
        g.add(rug);

        // 正面（局部 +z）朝着门（-z 方向）→ 进门就看到正面铜盘 + 转盘
        g.position.set(0, 0, cz);
        g.rotation.y = 0;
        scene.add(g);

        cab.userData.concertDeck = true;
        cab.userData.concertPart = true;
        pickables.push(cab);
    }

    /* ---------------- 5. 后方专辑墙（占位数据）---------------- */

    /**
     * 专辑墙（v2）：左右侧墙各一列**带封面 + 曲目条 + 黄铜托架**的专辑位。
     *
     * 上一版为什么稀烂（用户："做成稀烂，不行"）：
     *   · 只有 6 根木条当背景，专辑位是**纯暗色方盒**，看不出是"专辑"
     *   · 没有封面、没有标题、没有序号，像一堵没装修完的墙
     *
     * v2 每张专辑是一条完整的"唱片陈列"：
     *   · 侧立的**唱片封套**（用 canvas 画：底色 + 标题 + 圆角黑胶圆 + 序号）
     *   · 下方**黄铜托架**（一条金属横档，像真的唱片架）
     *   · 顶部**列标题牌**（木质底 + 铜字）
     *   · 每层加**射灯**（小圆锥光，从上方打下来）
     */
    function buildAlbumWall() {
        const wx = HALL_W / 2 + WALL_THICK / 2;     // 侧墙内表面 x

        const COLS = 3;          // 沿 z 方向（纵深）3 张
        const ROWS = 2;          // 高度方向 2 层
        const gapZ = 2.30, gapY = 1.72;
        const yBase = 4.30;      // 上层 y
        const z0 = HALL_CENTER_Z - (COLS - 1) / 2 * gapZ;
        const coverW = 1.55, coverH = 1.55;

        const woodPM = new THREE.MeshStandardMaterial({ color: 0x4A3A2E, roughness: 0.74, metalness: 0.10 });
        const brassBarM = new THREE.MeshStandardMaterial({ color: 0xB08A46, roughness: 0.30, metalness: 0.85 });

        for (const sgn of [-1, 1]) {
            const faceY = sgn > 0 ? -Math.PI / 2 : Math.PI / 2;   // 面朝厅内

            // 背板（木格栅）—— slab 双面
            const panel = new THREE.Mesh(SHARED.slab, woodPM);
            panel.scale.set(0.08, ROWS * (gapY + 0.35) + 0.7, COLS * gapZ + 0.7);
            panel.position.set(sgn * (wx - 0.06), yBase - gapY / 2, z0);
            scene.add(panel);

            // 背板上的竖木条（7 条，做"格栅"肌理）
            for (let i = 0; i < 7; i++) {
                const rib = new THREE.Mesh(SHARED.box, brassBarM);
                rib.scale.set(0.02, ROWS * (gapY + 0.35) + 0.5, 0.045);
                rib.position.set(sgn * (wx - 0.12), yBase - gapY / 2, z0 - COLS * gapZ / 2 + 0.2 + i * (COLS * gapZ - 0.4) / 6);
                scene.add(rib);
            }

            for (let r = 0; r < ROWS; r++) {
                const y = yBase - r * gapY;

                // 该层的黄铜托架（一条横档）
                const rail = new THREE.Mesh(SHARED.box, brassBarM);
                rail.scale.set(0.10, 0.055, COLS * gapZ - 0.15);
                rail.position.set(sgn * (wx - 0.24), y - coverH / 2 - 0.10, z0);
                scene.add(rail);

                for (let c = 0; c < COLS; c++) {
                    const idx = (sgn < 0 ? 0 : 1) * (ROWS * COLS) + r * COLS + c;
                    const z = z0 + (c - (COLS - 1) / 2) * gapZ;

                    // —— 唱片封套（带 canvas 封面；M5-d 起支持真实专辑封面）——
                    const tex = makeAlbumCover(idx);
                    const faceMat = new THREE.MeshStandardMaterial({
                        map: tex, roughness: 0.72, metalness: 0.06,
                        emissive: new THREE.Color(0x141A24), emissiveIntensity: 0.55,
                    });
                    const mesh = new THREE.Mesh(SHARED.albumBox, [
                        woodPM, woodPM, woodPM, woodPM,          // ±x ±y 侧面（看不见，随便）
                        faceMat,
                        new THREE.MeshStandardMaterial({ color: 0x1A1F28, roughness: 0.85, metalness: 0.05 }),
                    ]);
                    mesh.scale.set(coverW, coverH, 0.10);
                    mesh.position.set(sgn * (wx - 0.40), y, z);
                    // Box 的 +z 面是 material[4]，要让它朝厅内 → 绕 y 转
                    mesh.rotation.y = sgn > 0 ? -Math.PI / 2 : Math.PI / 2;
                    mesh.userData.concertAlbumSlot = idx;
                    mesh.userData.concertPlaceholder = true;
                    mesh.userData.concertPart = true;
                    mesh.userData.albumFaceMat = faceMat;   // M5-d：供真实封面替换
                    scene.add(mesh);
                    pickables.push(mesh);
                    albumSlots.push({ mesh: mesh, slot: idx });

                    // ⚠️ 不放专辑射灯：WebView 的点光是逐像素开销，6 盏射灯换来的效果
                    //    不如直接把封面做"自发光"（emissive 已在材质里，零开销）。
                    //    这样音乐厅总灯数控制在 9 盏。
                }
            }

            // 列标题牌
            const sign = new THREE.Mesh(SHARED.slab, woodPM);
            sign.scale.set(0.10, 0.62, 2.6);
            sign.position.set(sgn * (wx - 0.20), yBase + gapY * 1.05, z0);
            scene.add(sign);
            const signTxt = new THREE.Mesh(SHARED.plane, new THREE.MeshBasicMaterial({
                map: labelTexture(sgn < 0 ? '音乐专辑 · 左' : '音乐专辑 · 右', '点击封面放置'),
                transparent: true,
            }));
            signTxt.scale.set(2.5, 0.55, 1);
            signTxt.position.set(sgn * (wx - 0.26), yBase + gapY * 1.05, z0);
            signTxt.rotation.y = faceY;
            scene.add(signTxt);
        }
    }

    /* 生成一张"专辑封面"贴图（占位）：底色 + 圆角 + 标题 + 黑胶圆 + 序号 */
    const _coverCache = [];
    function makeAlbumCover(idx) {
        if (_coverCache[idx]) return _coverCache[idx];
        const c = document.createElement('canvas');
        c.width = 256; c.height = 256;
        const g = c.getContext('2d');

        // 底色：按索引给一组克制的深色调（不能花，否则音乐厅会乱）
        const palettes = [
            ['#1E3A5F', '#2C5288'], ['#3A2A4A', '#553A6E'], ['#1F4038', '#2E6050'],
            ['#4A3520', '#6E4E2E'], ['#3A2430', '#5C3548'], ['#26303E', '#3A4859'],
            ['#2A3A24', '#41603A'], ['#402E24', '#63472F'], ['#242E46', '#37456A'],
            ['#3E2A2A', '#5E4040'], ['#2A3E44', '#3F5F68'], ['#38302A', '#584B40'],
        ];
        const p = palettes[idx % palettes.length];
        const grd = g.createLinearGradient(0, 0, 256, 256);
        grd.addColorStop(0, p[0]); grd.addColorStop(1, p[1]);
        g.fillStyle = grd; g.fillRect(0, 0, 256, 256);

        // 黑胶圆（右下角，暗示"这是唱片"）
        g.globalAlpha = 0.30;
        g.fillStyle = '#0A0A0C';
        g.beginPath(); g.arc(196, 196, 88, 0, Math.PI * 2); g.fill();
        g.globalAlpha = 0.55;
        g.strokeStyle = 'rgba(220,230,255,0.30)';
        g.lineWidth = 2;
        for (let i = 0; i < 3; i++) { g.beginPath(); g.arc(196, 196, 40 + i * 18, 0, Math.PI * 2); g.stroke(); }
        g.globalAlpha = 1;

        // 细描边
        g.strokeStyle = 'rgba(200,220,255,0.35)';
        g.lineWidth = 3;
        g.strokeRect(8, 8, 240, 240);

        // 序号
        g.fillStyle = 'rgba(226,238,255,0.85)';
        g.font = 'bold 22px system-ui, sans-serif';
        g.fillText(String(idx + 1).padStart(2, '0'), 20, 36);

        // 标题（未指定专辑）
        g.fillStyle = 'rgba(238,244,255,0.96)';
        g.font = 'bold 30px system-ui, "PingFang SC", sans-serif';
        g.fillText('未指定专辑', 20, 108);

        // 副标题
        g.fillStyle = 'rgba(180,206,238,0.75)';
        g.font = '19px system-ui, "PingFang SC", sans-serif';
        g.fillText('点击放置', 20, 140);

        const t = new THREE.CanvasTexture(c);
        t.colorSpace = THREE.SRGBColorSpace;
        _coverCache[idx] = t;
        return t;
    }

    /* ---------------- 6. 前方大屏 ---------------- */

    let screenMesh = null;
    /** PV 原生视频纹理；更新随 Three.js 渲染循环受右上角帧率上限约束 */
    let pvScreenTex = null;
    let pvOnScreen = false;
    let pvScreenRequest = 0;
    let pvPendingVideo = null;
    let pvPendingListener = null;

    /**
     * 大屏：挂在**进门正对的那面墙**（z = HALL_Z0 + HALL_D，厅的最深处）。
     *
     * ★ 位置修正（用户明确）：
     *   玩家从走廊走进来，**正对的是最深处那面墙** —— 大屏必须在那面。
     *   之前我贴在 z = HALL_Z0（门所在的那面墙），结果"进门迎面一块屏幕挡路"。
     *
     * ★ 屏幕朝向：PlaneGeometry 默认法线 +z；音乐厅玩家位于屏幕前方 z<zBack，
 *   因此屏幕旋转 Y=π 朝 -z，使用 FrontSide 渲染，正面文字不镜像。
     */
    function buildScreen() {
        const zBack = HALL_Z0 + HALL_D;    // 厅的最深处（= 39）
        const sw = 10.5, sh = 5.9;
        const sy = 3.5;
        const wallFrontZ = zBack;           // 后墙朝厅内的表面
        const screenFaceZ = wallFrontZ - 0.12; // 平面在墙前，朝向厅内观察者
        const frameFrontZ = screenFaceZ - 0.06; // 边框在屏幕前方，只留窄框，不盖画面

        // 屏幕内容贴图（程序化动画 —— 无 PV 时的默认表现）
        screenCvs = makeScreenCanvas();
        screenCtx = screenCvs.getContext('2d');
        screenTex = new THREE.CanvasTexture(screenCvs);
        screenTex.colorSpace = THREE.SRGBColorSpace;
        screenTex.minFilter = THREE.LinearFilter;
        screenTex.magFilter = THREE.LinearFilter;
        screenTex.generateMipmaps = false;

        screenMesh = new THREE.Mesh(SHARED.screen, new THREE.MeshBasicMaterial({
            map: screenTex,
            // 不再让 Y=π 旋转引入水平镜像；双面材质从厅内可见原始 UV。
            side: THREE.DoubleSide,
        }));
        screenMesh.scale.set(sw, sh, 1);
        screenMesh.rotation.y = Math.PI;
        screenMesh.position.set(0, sy, screenFaceZ);
        scene.add(screenMesh);
        log('大屏方向诊断: rotationY=PI scaleX=+1 side=DoubleSide uv=original z=' + screenFaceZ);

        // 只做四边框，绝不再用覆盖中心画面的整块 Box。
        // 四条边框在显示平面前方 6cm，深度 12cm，仅边框区域覆盖贴图。
        const frameM = new THREE.MeshStandardMaterial({ color: 0x14181F, roughness: 0.6, metalness: 0.4 });
        const frameT = 0.30, frameD = 0.12;
        const frameBars = [
            { sx: frameT, sy: sh + frameT * 2, x: -(sw + frameT) / 2, y: sy },
            { sx: frameT, sy: sh + frameT * 2, x:  (sw + frameT) / 2, y: sy },
            { sx: sw, sy: frameT, x: 0, y: sy + (sh + frameT) / 2 },
            { sx: sw, sy: frameT, x: 0, y: sy - (sh + frameT) / 2 },
        ];
        for (const b of frameBars) {
            const bar = new THREE.Mesh(SHARED.box, frameM);
            bar.scale.set(b.sx, b.sy, frameD);
            bar.position.set(b.x, b.y, frameFrontZ);
            scene.add(bar);
        }

        // 屏幕自发光（让画面"亮起来"，照亮厅内）
        const sl = new THREE.PointLight(0xBCD8FF, 7.0, 18, 2);
        sl.position.set(0, sy, zBack - 1.8);
        scene.add(sl);
        hallLights.add(sl);
    }

    /* ---------------- 6.2 大屏 PV 切换（M5-d） ---------------- */

    /*
     * 大屏 PV：使用 three.js 原生 VideoTexture，由 r160 的
     * requestVideoFrameCallback 按视频帧更新。实际显示频率受主渲染循环限制：
     * min(PV 原生帧率, 右上角设置的渲染 FPS 上限)。不做 15fps canvas 中转。
     * 黑屏的已确认因素是旧版整块实心边框遮挡显示面，边框已改成四条窄条。
     */
    function orientScreenTexture(tex) {
        // 保留为诊断工具：当前屏幕改用 rotationY=0 + 原始 UV，不调用此翻转。
        if (!tex) return;
        tex.wrapS = THREE.ClampToEdgeWrapping;
        tex.repeat.x = 1;
        tex.offset.x = 0;
        tex.needsUpdate = true;
    }

    /** 不依赖 CanvasTexture / VideoTexture 内部源，只由屏幕几何面朝厅内 */
    function setScreenPv(video) {
        if (!screenMesh) return;
        const request = ++pvScreenRequest;
        if (pvPendingVideo && pvPendingListener) {
            pvPendingVideo.removeEventListener('loadeddata', pvPendingListener);
            pvPendingVideo = null;
            pvPendingListener = null;
        }
        if (video) {
            // 先有可解码帧再创建 VideoTexture，避免 texImage2D: no video。
            const attach = () => {
                if (request !== pvScreenRequest) return;
                if (video.readyState < video.HAVE_CURRENT_DATA || video.videoWidth <= 0) return;
                video.removeEventListener('loadeddata', attach);
                pvPendingVideo = null;
                pvPendingListener = null;
                if (!pvScreenTex) {
                    pvScreenTex = new THREE.VideoTexture(video);
                    pvScreenTex.colorSpace = THREE.SRGBColorSpace;
                    pvScreenTex.minFilter = THREE.LinearFilter;
                    pvScreenTex.magFilter = THREE.LinearFilter;
                    pvScreenTex.generateMipmaps = false;
                } else if (pvScreenTex.image !== video) {
                    // 播放器正常只复用一个 pvVideo；仅异对象时重建纹理。
                    pvScreenTex.dispose();
                    pvScreenTex = new THREE.VideoTexture(video);
                    pvScreenTex.colorSpace = THREE.SRGBColorSpace;
                    pvScreenTex.minFilter = THREE.LinearFilter;
                    pvScreenTex.magFilter = THREE.LinearFilter;
                    pvScreenTex.generateMipmaps = false;
                }
                // PV 与默认 Canvas 使用同一份原始 UV，不额外翻转。
                screenMesh.material.map = pvScreenTex;
                screenMesh.material.needsUpdate = true;
                pvOnScreen = true;
            };
            if (video.readyState >= video.HAVE_CURRENT_DATA && video.videoWidth > 0) {
                attach();
            } else {
                pvPendingVideo = video;
                pvPendingListener = attach;
                video.addEventListener('loadeddata', attach);
            }
        } else {
            screenMesh.material.map = screenTex;
            screenMesh.material.needsUpdate = true;
            pvOnScreen = false;
            // 保留单例 VideoTexture：Three r160 的 requestVideoFrameCallback
            // 会递归排下一帧；播放器也复用同一 HTMLVideoElement。
            // dispose 后再次播放会新建第二条 callback 链，造成回调越积越多。
        }
    }

    /**
     * M5-d：把当前播放曲目的专辑封面贴到黑胶标签上（随转盘旋转）。
     * 传 null = 恢复素面。
     * @param {string|null} coverUrl 封面 URL（/music/cover/<albumId>）
     */
    function setDiscCover(coverUrl) {
        if (!discLabelMat) return;
        if (!coverUrl) {
            discLabelMat.map = null;
            discLabelMat.color.setHex(0x2A2F3A);
            discLabelMat.needsUpdate = true;
            return;
        }
        new THREE.TextureLoader().load(
            coverUrl,
            (tex) => {
                tex.colorSpace = THREE.SRGBColorSpace;
                discLabelMat.map = tex;
                discLabelMat.color.setHex(0xFFFFFF);
                discLabelMat.needsUpdate = true;
            },
            undefined,
            () => { /* 加载失败保持素面 */ }
        );
    }

    /* ---------------- 6.5 O：吸音板 + 座椅 ---------------- */

    function buildAcoustics() {
        // —— 吸音板：贴在左右侧墙（x = ±HALL_W/2），
        //    做成"竖向长条 + 细缝"的阵列，是音乐厅最典型的视觉语言。
        //    ★ 位置：夹在"专辑墙"（z≈28.4~30.6）和"大屏"（z=39）之间。
        const panelM = new THREE.MeshStandardMaterial({ color: 0x6B4A38, roughness: 0.86, metalness: 0.04 });
        const slotM = new THREE.MeshStandardMaterial({
            color: 0x2A1E16, roughness: 0.95, metalness: 0.0,
        });

        const z0 = HALL_CENTER_Z + 1.8;                 // 专辑墙之后
        const z1 = HALL_Z0 + HALL_D - 3.2;              // 大屏之前（大屏占 z≈39-1.5）
        const span = z1 - z0;
        const n = 4;                                    // 每侧 4 条
        const panelH = 3.4, panelY = 4.2;

        for (const sgn of [-1, 1]) {
            const wx = sgn * (HALL_W / 2 - 0.06);
            for (let i = 0; i < n; i++) {
                // 木条
                const t = i / (n - 1);
                const z = z0 + t * span;
                const strip = new THREE.Mesh(SHARED.box, panelM);
                strip.scale.set(0.10, panelH, span / n * 0.62);
                strip.position.set(wx, panelY, z);
                strip.rotation.y = sgn > 0 ? -Math.PI / 2 : Math.PI / 2;
                scene.add(strip);
            }
            // 木条后面的深色底（做出"缝隙"的层次）
            const back = new THREE.Mesh(SHARED.plane, slotM);
            back.scale.set(span, panelH + 0.5, 1);
            back.position.set(wx - sgn * 0.10, panelY, (z0 + z1) / 2);
            back.rotation.y = sgn > 0 ? -Math.PI / 2 : Math.PI / 2;
            scene.add(back);
        }

        // 座椅已删除（用户要求：椅子不好看，去掉）
        //   —— 音乐厅保持空旷，视线全落在中央留声机上。
    }

    /* ---------------- 组装 ---------------- */

    function build() {
        if (built) return;
        built = true;
        buildDoorWall();
        buildLeaves();
        buildCorridor();
        buildHall();
        buildTurntable();
        buildAlbumWall();
        buildScreen();
        buildAcoustics();      // O：吸音板 + 座椅
    }

    /* ---------------- 更新 ---------------- */

    let phase = 0;
    let hallOn = null;        // 音乐厅灯组上一帧状态
    let corrOn = null;        // 走廊灯组上一帧状态
    let spinVel = 0;          // 转盘当前角速度（rad/s）—— 播放时加速、停止时惯性减速

    function update(dt, camera) {
        phase += dt;

        // ★ 灯组按房间开关（修正版 2）
        //   v1 的 bug：单组 + z>10，跨门槛整条走廊全黑（已修）
        //   v2 的 bug：走廊组在 z>22 关、音乐厅组在 z>17.5 开 —— 看似重叠，
        //              但**音乐厅的灯只覆盖 z=23~37**（轨道灯在厅内），
        //              玩家刚进音乐厅（z=20~23）时，屏幕光在 z=37、碟机光在 z=29，
        //              都还"够不着" → 看起来还是黑。
        //
        //   v3 的做法：**放宽阈值 + 提前开灯**，并且**走廊组一直开到音乐厅深处**。
        //     走廊组：z ∈ (8, 26)   ← 覆盖"门口→走廊→音乐厅前部"
        //     音乐厅组：z > 16      ← 一进走廊尽头就亮，进门瞬间不会黑
        const cz2 = camera.position.z;

        const wantCorridor = cz2 > MAIN_FRONT_Z - 3.0 && cz2 < HALL_Z0 + 7.0;
        if (corrOn !== wantCorridor) { corrOn = wantCorridor; corridorLights.setWant(wantCorridor); }

        const wantHall = cz2 > HALL_Z0 - 3.0;
        if (hallOn !== wantHall) { hallOn = wantHall; hallLights.setWant(wantHall); }

        // —— 两扇感应门：各自按"玩家到门的 z 距离"独立开关 ——
        //   ⚠️ 迟滞（开门 2.5m / 关门 4.2m）必须保留：否则站在临界点会疯狂开合。
        const pz = camera.position.z;
        const px = camera.position.x;
        for (let di = 0; di < doors.length; di++) {
            const D = doors[di];
            const dz = Math.abs(pz - D.z);
            // 门洞 x 范围之外（贴墙走）不触发，避免从侧面"擦"到门
            const inRange = Math.abs(px) < 4.6;

            const wasOpen = D.open;
            if (inRange && dz < D.openDist) D.open = true;
            else if (!inRange || dz > D.closeDist) D.open = false;
            // ★ 从"关→开"或"开→关"的那一帧，播一次滑门声（只播一次，不是每帧）
            if (D.open !== wasOpen && audio && audio.doorSlide) {
                audio.doorSlide(D.open);
            }

            const target = D.open ? 1 : 0;
            // ⚠️ 防御：D.t 万一不是数字（历史 bug / 外部改动），先归零再推进，
            //    避免 NaN 传染到 leaf.position.x（那会让整扇门消失）。
            if (typeof D.t !== 'number' || !isFinite(D.t)) D.t = 0;
            if (D.t !== target) {
                const step = dt / 0.6;
                D.t += (target > D.t) ? Math.min(step, target - D.t) : -Math.min(step, D.t - target);
                D.t = Math.max(0, Math.min(1, D.t));        // 夹紧，防累积误差
                const e = D.t * D.t * (3 - 2 * D.t);        // smoothstep
                for (const leaf of D.leaves) {
                    const sgn = Math.sign(leaf.userData.closedX) || 1;
                    leaf.position.x = leaf.userData.closedX + sgn * e * leaf.userData.slide;
                }
            }
        }
        // 兼容旧字段（供 isDoorOpen 用）
        if (doors[0]) { doorOpen = doors[0].open; doorT = doors[0].t; }

        // 转盘：**只在播放时旋转**（用户要求："保持点播放的时候能旋转就行"）
        //   停止时缓慢减速停住，不是硬停 —— 像真机的惯性。
        if (spinner) {
            const targetSpeed = playing ? 5.2 : 0;      // rad/s
            spinVel += (targetSpeed - spinVel) * Math.min(1, dt * 2.2);
            spinner.rotation.y -= spinVel * dt;
        }

        // ★ 门框氛围灯：每扇门独立"呼吸 + 开门高亮"
        //   呼吸：0.30~0.46 慢速正弦；开门时叠加到 t 的高亮档。
        const breathe = 0.30 + 0.16 * (0.5 + 0.5 * Math.sin(phase * 2.4));
        for (const D of doors) {
            if (D.glowMat) D.glowMat.opacity = breathe + D.t * 0.62;
            if (D.light) {
                D.light.intensity = 0.5 + D.t * 5.5;
                D.light.color.setHSL(0.58, 0.55, 0.62 + D.t * 0.16);
            }
            // ★ 状态灯：冷蓝（待机）→ 暖白（激活），亮度也跟着走
            //   还带一点轻微呼吸，让"待机"看起来是活的。
            if (D.statusDots) {
                for (const dot of D.statusDots) {
                    if (!dot.material) continue;
                    dot.material.color.setHSL(0.58 - D.t * 0.47, 0.62, 0.62 + D.t * 0.16);
                    dot.material.opacity = 0.55 + D.t * 0.45 + 0.10 * Math.sin(phase * 3.0);
                }
            }
            // 注：中缝的"界线"由**门扇自带的封边**表现（随门移动），
            //     这里不需要任何额外逻辑 —— 门一开，两条封边自然分开。
        }

        // 大屏程序化动画（无 PV 时的默认内容）
        drawScreen(dt);
    }

    /* 大屏：程序化动画（频谱条 + 呼吸光晕 + 提示文字）*/
    function drawScreen() {
        if (!screenCtx || pvOnScreen) return;
        const W = screenCvs.width, H = screenCvs.height;
        const t = phase;

        // 底：深蓝渐变
        const grd = screenCtx.createLinearGradient(0, 0, 0, H);
        grd.addColorStop(0, '#0B1424');
        grd.addColorStop(1, '#050910');
        screenCtx.fillStyle = grd;
        screenCtx.fillRect(0, 0, W, H);

        // 呼吸光晕
        const cx = W / 2, cy = H * 0.46;
        const r = 90 + Math.sin(t * 1.2) * 18;
        const rg = screenCtx.createRadialGradient(cx, cy, 0, cx, cy, r * 2.1);
        rg.addColorStop(0, 'rgba(120,180,255,0.30)');
        rg.addColorStop(1, 'rgba(120,180,255,0)');
        screenCtx.fillStyle = rg;
        screenCtx.fillRect(0, 0, W, H);

        // 频谱条（伪随机、随相位起伏）
        const bars = 40;
        const bw = W / bars;
        for (let i = 0; i < bars; i++) {
            const k = (i / bars) * 6.0 + t * 2.2;
            const h = (Math.sin(k) * 0.5 + 0.5) * 0.55 + (Math.sin(k * 1.7 + 1.1) * 0.5 + 0.5) * 0.30;
            const bh = Math.max(4, h * H * 0.42);
            screenCtx.fillStyle = 'rgba(150,200,255,' + (0.35 + h * 0.45).toFixed(3) + ')';
            screenCtx.fillRect(i * bw + bw * 0.16, H - bh - 26, bw * 0.68, bh);
        }

        // 提示文字
        screenCtx.textAlign = 'center';
        screenCtx.fillStyle = 'rgba(226,238,255,0.92)';
        screenCtx.font = 'bold 34px system-ui, "PingFang SC", sans-serif';
        screenCtx.fillText('NO PV', cx, cy - 6);
        screenCtx.fillStyle = 'rgba(168,196,232,0.75)';
        screenCtx.font = '20px system-ui, "PingFang SC", sans-serif';
        screenCtx.fillText('尚未设定 PV 动画', cx, cy + 26);

        screenTex.needsUpdate = true;
    }

    /* ---------------- 对外接口 ---------------- */
    return {
        group: group,
        build: build,
        update: update,
        pickables: pickables,
        /** 门是否开着（供外部调试/提示用）*/
        isDoorOpen: () => doorOpen,
        /** 专辑位（M5-d：真实数据接入后由 applyAlbums 更新封面） */
        albumSlots: albumSlots,
        /** 点击碟机：切换"播放/暂停"（转盘转不转由它决定）*/
        togglePlay: () => { playing = !playing; return playing; },
        isPlaying: () => playing,
        /** M5-d：外部播放器驱动转盘（比 togglePlay 更可控 —— 真实播放状态） */
        setPlaying: (on) => { playing = !!on; },
        /** M5-d：大屏挂/摘 PV（传 video 元素或 null） */
        setScreenPv: setScreenPv,
        /** M5-d：黑胶标签贴当前专辑封面（传 URL 或 null 恢复素面） */
        setDiscCover: setDiscCover,
        /**
         * M5-d：把真实专辑数据铺到专辑墙上。
         *
         * @param {Array} albums [{id,title,cover,gameId,trackCount}]
         * @param {number} limit 最多铺几张（默认 12 = 墙的容量）
         *
         * 规则：按传入顺序铺（桥接层已按"最近添加优先"排好）。
         *      没有 cover 的专辑保留占位 canvas；有 cover 的异步加载替换。
         *      超出 limit 的专辑：不铺（由调用方 showHint 提示）。
         */
        applyAlbums: (albums, limit) => {
            const list = Array.isArray(albums) ? albums : [];
            const texLoader = new THREE.TextureLoader();

            for (let i = 0; i < albumSlots.length; i++) {
                const slot = albumSlots[i];
                const album = list[i];
                if (!album) {
                    // 没数据 → 保持占位
                    slot.mesh.userData.album = null;
                    continue;
                }
                slot.mesh.userData.album = album;
                slot.mesh.userData.concertPlaceholder = false;

                if (album.cover) {
                    // 异步加载真实封面（失败保持占位，不打断）
                    texLoader.load(
                        album.cover,
                        (tex) => {
                            tex.colorSpace = THREE.SRGBColorSpace;
                            const mat = slot.mesh.userData.albumFaceMat;
                            if (mat) {
                                mat.map = tex;
                                mat.emissiveIntensity = 0.35;   // 真实封面稍降自发光
                                mat.needsUpdate = true;
                            }
                        },
                        undefined,
                        () => { /* 加载失败：保持占位 */ }
                    );
                }
            }
        },
        /** 射线命中的对象是否属于音乐厅（供 exh.js 的 pick 分支用）
         *  ⚠️ 用 userData 标记判断，而不是"沿 parent 找 group" ——
         *     因为 build* 里全用 scene.add()，group 并非真正的父节点。*/
        owns: (obj) => !!(obj && obj.userData && obj.userData.concertPart),
    };
}


