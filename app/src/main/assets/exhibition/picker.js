/**
 * YukiHub 离线展厅 · 选品浮层（picker）
 * =========================================================
 * 用途：玩家点"主题展台"时弹出，从自己的库里挑一件摆上去。
 *
 * 与 hall.js 的分工：
 *   · hall.js 负责"展台长什么样、被点中了通知谁"
 *   · picker.js 负责"选哪一件"这个交互（列表、封面、清空、保存）
 *
 * 存储策略（双通道，自动降级）：
 *   ① 优先 window.ExhibitionBridge.getDisplaySlots() / setDisplaySlots()
 *      —— 存进 App 的 SharedPreferences，清缓存不丢
 *   ② 桥接不存在（Java 侧还没编译）时，退回 localStorage
 *   —— 这样 JS 侧可以先跑，Java 侧什么时候编译都行。
 *
 * 槽位格式：逗号分隔的 4 段字符串，空段 = 空台
 *   例 "12,,45,"  →  0号摆12 / 1号空 / 2号摆45 / 3号空
 */

const SLOT_COUNT = 4;
const LS_KEY = 'exhibition_display_slots';

/** 桥接对象（可能为 null） */
function getBridge() {
    const b = window.ExhibitionBridge;
    return (typeof b === 'object' && b !== null) ? b : null;
}

/** 读取 4 个槽位的 gameId（0 表示空台） */
function loadSlots() {
    let raw = '';
    try {
        const b = getBridge();
        raw = (b && typeof b.getDisplaySlots === 'function') ? (b.getDisplaySlots() || '') : '';
        if (!raw) {
            // 桥接没有或返回空 → 退回 localStorage
            raw = localStorage.getItem(LS_KEY) || '';
        }
    } catch (e) {
        raw = '';
    }
    const parts = String(raw).split(',');
    const out = new Array(SLOT_COUNT).fill(0);
    for (let i = 0; i < SLOT_COUNT; i++) {
        const n = parseInt(parts[i], 10);
        out[i] = (Number.isFinite(n) && n > 0) ? n : 0;
    }
    return out;
}

/** 保存 4 个槽位 */
function saveSlots(slots) {
    const csv = slots.map(v => (v > 0 ? String(v) : '')).join(',');
    let ok = false;
    try {
        const b = getBridge();
        if (b && typeof b.setDisplaySlots === 'function') {
            ok = !!b.setDisplaySlots(csv);
        }
    } catch (e) { ok = false; }
    // 双写 localStorage：既是降级通道，也便于调试期对照
    try { localStorage.setItem(LS_KEY, csv); } catch (e) { /* 隐私模式等，忽略 */ }
    return ok;
}

/**
 * 创建选品浮层
 * @param {object} deps
 *   deps.games      () => Game[]      当前库存（与展厅同一份数据）
 *   deps.onPick     (slot, game|null) 玩家选好后回调（null = 清空）
 *   deps.thumbUrl   (game) => string  封面缩略图 URL（复用展厅的 /cover/<uid> 机制）
 *   deps.toast      (msg) => void     轻提示（可选）
 */
export function createPicker(deps = {}) {
    const games = typeof deps.games === 'function' ? deps.games : () => [];
    const thumbUrl = typeof deps.thumbUrl === 'function' ? deps.thumbUrl : () => '';
    const toast = typeof deps.toast === 'function' ? deps.toast : () => {};

    let root = null;        // 浮层根节点
    let gridEl = null;      // 封面网格
    let clearBtn = null;    // 「清空此展台」
    let curSlot = -1;       // 当前正在编辑的槽位
    let opened = false;
    let openedAt = 0;       // 打开时刻（用于屏蔽"开启那一下"的手势）

    /*
     * ★ 关键：屏蔽"打开浮层那一次触摸"的后续事件。
     *
     * 问题现象：点悬浮按钮 → 浮层刚打开 → 同一次抬手（pointerup）
     *          紧接着被合成为 click，派发给"抬起坐标下"的 DOM 元素，
     *          而此时浮层已盖在那里 → 卡片直接收到 click → 瞬间选中游戏。
     *
     * 这在触屏上必然发生：3D 场景用 pointerup，卡片用 click，
     * 同一次手势被两个界面各消费一次。
     *
     * 解法：记录打开时刻，凡是在 OPEN_GUARD_MS 内到达的点击一律忽略。
     *       350ms 远大于"抬手→click 合成"的间隔（通常 <100ms），
     *       又短于人手"看到界面再点一下"的最短反应（约 400ms+），不会误伤正常操作。
     */
    const OPEN_GUARD_MS = 350;
    function guarded() {
        return (performance.now() - openedAt) < OPEN_GUARD_MS;
    }

    /** 懒建 DOM（只在第一次打开时插到页面上） */
    function ensure() {
        if (root) return true;
        root = document.getElementById('picker');
        if (!root) return false;

        gridEl = document.getElementById('pk-grid');
        clearBtn = document.getElementById('pk-clear');
        const close = document.getElementById('pk-close');

        if (close) close.addEventListener('click', () => { if (!guarded()) closePicker(); });
        // 点遮罩空白处也关闭（点内容区不关）
        root.addEventListener('click', (e) => {
            if (e.target === root && !guarded()) closePicker();
        });
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                if (guarded()) return;                 // 冷却期内忽略（见 guarded 注释）
                if (curSlot < 0) return;
                const s = loadSlots();
                s[curSlot] = 0;
                saveSlots(s);
                deps.onPick && deps.onPick(curSlot, null);
                toast('已清空该展台');
                closePicker();
            });
        }
        return true;
    }

    /** 建一张卡片 */
    function makeCard(g, occupied) {
        const card = document.createElement('div');
        card.className = 'pk-card' + (occupied ? ' pk-card-cur' : '');

        const img = document.createElement('img');
        img.className = 'pk-thumb';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.alt = g.title || '';
        const url = thumbUrl(g);
        if (url) {
            img.src = url;
        } else {
            img.style.visibility = 'hidden';
        }
        // 加载失败 → 退化成"无封面"占位，不留破图标
        img.addEventListener('error', () => { img.style.visibility = 'hidden'; });
        card.appendChild(img);

        if (g.nsfw) {
            const tag = document.createElement('span');
            tag.className = 'pk-tag';
            tag.textContent = 'R18';
            card.appendChild(tag);
        }

        const name = document.createElement('div');
        name.className = 'pk-name';
        name.textContent = g.title || '(无标题)';
        card.appendChild(name);

        card.addEventListener('click', () => {
            // ★ 冷却期内的点击一律忽略：这是"打开浮层那一次抬手"的余波，
            //   不是玩家真的点了这张卡（否则会出现"点按钮就直接选中游戏"）。
            if (guarded()) return;
            if (curSlot < 0) return;
            const s = loadSlots();
            s[curSlot] = g.id;
            saveSlots(s);
            deps.onPick && deps.onPick(curSlot, g);
            closePicker();
        });
        return card;
    }

    /** 渲染列表 */
    function render() {
        if (!gridEl) return;
        gridEl.textContent = '';
        const list = games() || [];
        const cur = (curSlot >= 0) ? loadSlots()[curSlot] : 0;

        if (!list.length) {
            const empty = document.createElement('div');
            empty.className = 'pk-empty';
            empty.textContent = '库里还没有游戏';
            gridEl.appendChild(empty);
            return;
        }
        for (const g of list) {
            gridEl.appendChild(makeCard(g, g.id === cur));
        }
        // 已陈列时才有"清空"
        if (clearBtn) clearBtn.hidden = !(cur > 0);
    }

    function openPicker(slot) {
        curSlot = slot | 0;
        if (!ensure()) {
            toast('选品界面加载失败');
            return;
        }
        render();
        root.hidden = false;
        opened = true;
        // ★ 记录打开时刻：之后 OPEN_GUARD_MS 内到达的 click 都视为
        //   "打开浮层那一次手势的余波"并忽略（见 guarded 的注释）。
        openedAt = performance.now();
    }

    function closePicker() {
        if (root) root.hidden = true;
        opened = false;
        curSlot = -1;
    }

    return { openPicker, closePicker, isOpen: () => opened, loadSlots, saveSlots };
}
