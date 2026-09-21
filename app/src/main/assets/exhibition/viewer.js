/**
 * YukiHub 离线展厅 · 3D 展品查看器（点击盒子后弹出）
 * =========================================================
 * 左边是一个**可以拖动旋转**的 3D 游戏盒（真实正版盒感：正面封面 /
 * 侧面书脊 / 顶底塑料壳 / 背面压暗封面），右边是文字信息栏。
 *
 * 为什么单独开一个文件：
 *   hall.js 已经很大了（陈列逻辑），查看器是"独立的交互场景"，
 *   分开后各自可读，也方便以后单独改视觉。
 *
 * 性能要点：
 *   · 只在**打开时**创建 renderer（关闭即 dispose），不常驻占用 GPU
 *   · 拖动时暂停自动旋转，松手 1.5 秒后恢复
 *   · 内部用低分辨率 canvas（720×1080 的盒子用 512 纹理足够）
 */

import * as THREE from './vendor/three.module.min.js';

/**
 * @param {object} deps 依赖注入（复用 hall.js 里已验证的函数，避免重复实现）
 *   deps.makeBoxMaterials(game)  → 6 个材质
 *   deps.applyCoverToBox(mesh, tex)
 *   deps.disposeBoxMaterials(mesh)
 *   deps.textureFromImage(img, nsfw)
 */
export function createViewer(deps) {
    const stage = document.getElementById('d-stage');
    let renderer = null;
    let scene = null;
    let camera = null;
    let box = null;
    let raf = 0;
    let disposed = true;

    // 交互状态
    let dragging = false;
    let lastX = 0;
    let lastY = 0;
    let spinY = 0.6;          // 当前偏航（自动旋转累加）
    let manualY = 0;          // 用户拖动的偏航
    let manualX = 0;          // 用户拖动的俯仰
    let velY = 0;             // 松手后的惯性
    let autoResumeAt = 0;     // 何时恢复自动旋转
    let lastT = 0;
    let currentGame = null;

    /** 初始化 renderer / scene / camera / 灯光（只做一次，之后复用） */
    function ensure() {
        if (renderer) return true;
        if (!stage) return false;

        const w = Math.max(1, stage.clientWidth);
        const h = Math.max(1, stage.clientHeight);

        try {
            renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
        } catch (e) {
            return false;
        }
        renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
        renderer.setSize(w, h, false);
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        renderer.toneMapping = THREE.ACESFilmicToneMapping;
        renderer.toneMappingExposure = 1.18;        // 与主场景一致：整体偏暗的修正
        stage.appendChild(renderer.domElement);

        scene = new THREE.Scene();

        // 相机：略微俯视，能同时看到正面和顶面（"盒感"最强的角度）
        // 距离 2.55 + fov 34° → 可见高度约 1.57m，盒子高 1.02m 约占屏高 65%，能完整看全
        camera = new THREE.PerspectiveCamera(34, w / h, 0.05, 20);
        camera.position.set(0, 0.46, 2.55);
        camera.lookAt(0, 0.02, 0);

        // 灯光：三点布光（主光 + 补光 + 轮廓光），保证封面清晰、书脊有立体感
        const key = new THREE.DirectionalLight(0xFFFFFF, 2.5);
        key.position.set(1.6, 2.2, 2.4);
        scene.add(key);

        const fill = new THREE.DirectionalLight(0x9FC4FF, 0.9);
        fill.position.set(-2.2, 0.6, 1.6);
        scene.add(fill);

        const rim = new THREE.DirectionalLight(0xFFE7C4, 1.3);
        rim.position.set(-1.2, 1.4, -2.4);
        scene.add(rim);

        scene.add(new THREE.AmbientLight(0xFFFFFF, 0.62));   // 0.45 → 0.62：暗部抬亮，避免书脊侧面发黑

        return true;
    }

    /** 打开查看器：为指定 game 建盒 */
    function open(game) {
        if (!game) return;
        if (!ensure()) return;

        clearBox();
        currentGame = game;

        const mats = deps.makeBoxMaterials(game);
        // 盒子尺寸按封面比例（gal 封面约 0.7:1），厚度给足"实体感"
        box = new THREE.Mesh(new THREE.BoxGeometry(0.72, 1.02, 0.16), mats);
        box.position.y = 0.06;
        scene.add(box);

        // 有真封面就异步换上（正反面）
        if (game.hasCover && game.cover) {
            const g = game;
            const img = new Image();
            img.onload = () => {
                if (!box || currentGame !== g) return;
                deps.applyCoverToBox(box, deps.textureFromImage(img, g.nsfw));
            };
            img.src = g.cover;
        }

        // 复位交互状态
        spinY = 0.6;
        manualY = 0;
        manualX = 0;
        velY = 0;
        autoResumeAt = 0;
        disposed = false;

        resize();
        bindPointer();
        lastT = performance.now();
        loop();
    }

    /** 关闭：停止渲染并释放（避免后台空转） */
    function close() {
        disposed = true;
        if (raf) { cancelAnimationFrame(raf); raf = 0; }
        clearBox();
        currentGame = null;
        dragging = false;
        if (renderer && renderer.domElement && renderer.domElement.parentNode) {
            renderer.domElement.parentNode.removeChild(renderer.domElement);
        }
        if (renderer) {
            try { renderer.dispose(); } catch (e) { }
            renderer = null;
        }
        scene = null;
        camera = null;
    }

    function clearBox() {
        if (box) {
            if (scene) scene.remove(box);
            deps.disposeBoxMaterials(box);
            if (box.geometry) box.geometry.dispose();
            box = null;
        }
    }

    /** 尺寸变化（横竖屏、窗口变化） */
    function resize() {
        if (!renderer || !stage) return;
        const w = Math.max(1, stage.clientWidth);
        const h = Math.max(1, stage.clientHeight);
        renderer.setSize(w, h, false);
        if (camera) {
            camera.aspect = w / h;
            camera.updateProjectionMatrix();
        }
    }

    /** 拖动旋转 */
    function bindPointer() {
        const el = stage;
        if (!el || el.__viewerBound) return;
        el.__viewerBound = true;

        el.addEventListener('pointerdown', (e) => {
            dragging = true;
            lastX = e.clientX;
            lastY = e.clientY;
            velY = 0;
            el.classList.add('dragging');
            el.setPointerCapture && el.setPointerCapture(e.pointerId);
        });

        el.addEventListener('pointermove', (e) => {
            if (!dragging) return;
            const dx = e.clientX - lastX;
            const dy = e.clientY - lastY;
            lastX = e.clientX;
            lastY = e.clientY;
            manualY += dx * 0.008;
            manualX += dy * 0.005;
            // 俯仰限制：别让盒子翻过去看到底（-0.5~0.75 弧度）
            manualX = Math.max(-0.5, Math.min(0.75, manualX));
            velY = dx * 0.008;          // 记下速度做惯性
            autoResumeAt = 0;
        });

        const end = (e) => {
            if (!dragging) return;
            dragging = false;
            el.classList.remove('dragging');
            // 松手 1.5 秒后恢复自动旋转
            autoResumeAt = performance.now() + 1500;
            el.releasePointerCapture && e && el.releasePointerCapture(e.pointerId);
        };
        el.addEventListener('pointerup', end);
        el.addEventListener('pointercancel', end);
        el.addEventListener('pointerleave', end);
    }

    function loop() {
        if (disposed) return;
        raf = requestAnimationFrame(loop);

        const now = performance.now();
        const dt = Math.min(0.05, (now - lastT) / 1000);
        lastT = now;

        if (box) {
            // 自动旋转（用户没在拖、且已过恢复时间）
            if (!dragging && now >= autoResumeAt) {
                spinY += dt * 0.55;
            }
            // 松手惯性衰减
            if (!dragging && Math.abs(velY) > 0.0005) {
                manualY += velY;
                velY *= 0.94;
            }
            box.rotation.y = spinY + manualY;
            box.rotation.x = manualX * 0.6;
            // 轻微上下浮动，像"被托着"
            box.position.y = 0.06 + Math.sin(now / 900) * 0.012;
        }

        if (renderer && scene && camera) renderer.render(scene, camera);
    }

    return { open, close, resize };
}