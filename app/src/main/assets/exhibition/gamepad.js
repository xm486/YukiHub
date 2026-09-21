/**
 * YukiHub 离线展厅 · 手柄 / 键盘输入（M4）
 * =========================================================
 * 设计要点（为"日后上 PC 复用"服务）：
 *
 *   输入设备 → 【动作层】 → 游戏逻辑
 *     手柄 ─┐
 *     键盘 ─┴─→  Move / Look / Interact / Cancel / Swap / Run / Menu
 *
 *   游戏逻辑**只认识动作**，不认识手柄还是键盘。
 *   所以上 PC 时只需再加一张"键盘映射表"，逻辑一行都不用改。
 *
 * 对外只有两个方法：
 *   poll()         每帧调用，返回本帧的动作快照（含模拟量）
 *   isConnected()  是否有手柄接入（用于 UI 提示）
 */

/** 手柄按键索引（W3C Gamepad 标准布局，Xbox 系为准） */
const BTN = {
    A: 0, B: 1, X: 2, Y: 3,
    LB: 4, RB: 5, LT: 6, RT: 7,
    SELECT: 8, START: 9,
    L3: 10, R3: 11,
    UP: 12, DOWN: 13, LEFT: 14, RIGHT: 15,
};

/** 摇杆死区：小于它的值视为 0（避免漂移） */
const DEADZONE = 0.18;

/** 动作触发方式：按下沿（EDGE）还是持续（HOLD） */
export function createGamepad() {
    const pads = [];                 // 当前连接的手柄快照
    const prev = new Map();          // index → { buttons: bool[] } 上一帧的按键状态
    let connected = false;
    let lastSeenAt = 0;              // 最近一次"看到手柄"的时间（用于 UI 淡出）

    /* ---------- 工具 ---------- */

    /** 应用死区：返回 -1..1 */
    function dz(v) {
        if (v === undefined || v === null) return 0;
        return Math.abs(v) < DEADZONE ? 0 : v;
    }

    /** 读取所有手柄（浏览器要求每帧重新调用 getGamepads） */
    function refresh() {
        pads.length = 0;
        const list = (navigator.getGamepads && navigator.getGamepads()) || [];
        for (const p of list) {
            if (p && p.connected) pads.push(p);
        }
        connected = pads.length > 0;
        if (connected) lastSeenAt = performance.now();
    }

    /** 是否有手柄接入（UI 提示用；断开后 1.5 秒内仍算"刚断开"以便提示） */
    function isConnected() {
        return connected;
    }

    /** 刚断开？（用于显示一次"已断开"提示） */
    function justDisconnected() {
        return !connected && lastSeenAt > 0 && (performance.now() - lastSeenAt) < 600;
    }

    /* ---------- 主循环：读一帧 ---------- */

    const snapshot = {
        connected: false,
        // 模拟量（-1..1）
        moveX: 0, moveY: 0,          // 左摇杆 → 前后左右
        lookX: 0, lookY: 0,          // 右摇杆 → 转视角
        // 动作（按下沿，本帧刚按下才为 true）
        act: {
            interact: false,         // A：交互 / 详情 / 确认（浮层里=选中）
            cancel: false,           // B：取消 / 关闭浮层
            swap: false,             // X：更换展品（= 点悬浮按钮）
            run: false,              // 扳机 / Shift：跑
            menu: false,             // Start：呼出/收起调试面板
        },
        // 方向键（数字方向，-1/0/1）
        dpadX: 0, dpadY: 0,
    };

    /** 每帧调用：把"手柄状态"翻译成"动作" */
    function poll() {
        refresh();

        // 无手柄时全部归零（避免拔线后摇杆值卡住）
        if (!connected) {
            snapshot.connected = false;
            snapshot.moveX = snapshot.moveY = 0;
            snapshot.lookX = snapshot.lookY = 0;
            snapshot.dpadX = snapshot.dpadY = 0;
            snapshot.act.interact = snapshot.act.cancel = false;
            snapshot.act.swap = snapshot.act.run = snapshot.act.menu = false;
            return snapshot;
        }
        snapshot.connected = true;

        // 只用第一个手柄（多人以后再扩）
        const p = pads[0];
        const ax = p.axes || [];
        snapshot.moveX = dz(ax[0]);
        snapshot.moveY = dz(ax[1]);
        snapshot.lookX = dz(ax[2]);
        snapshot.lookY = dz(ax[3]);

        // 方向键（当作数字输入）
        const b = p.buttons || [];
        const down = (i) => !!(b[i] && b[i].pressed);
        snapshot.dpadX = (down(BTN.RIGHT) ? 1 : 0) - (down(BTN.LEFT) ? 1 : 0);
        snapshot.dpadY = (down(BTN.DOWN) ? 1 : 0) - (down(BTN.UP) ? 1 : 0);

        // 按下沿检测：本帧按下 && 上帧没按
        const key = p.index;
        const before = prev.get(key) || {};
        const wasDown = (i) => !!before[i];
        const edge = (i) => down(i) && !wasDown(i);

        snapshot.act.interact = edge(BTN.A);
        snapshot.act.cancel = edge(BTN.B);
        snapshot.act.swap = edge(BTN.X);
        snapshot.act.menu = edge(BTN.START);
        // 跑：按住扳机或 L3（持续状态，不是按下沿）
        snapshot.act.run = down(BTN.RT) || down(BTN.LT) || down(BTN.L3);

        // 记录本帧状态供下一帧比较
        const nowMap = {};
        for (let i = 0; i < b.length; i++) nowMap[i] = down(i);
        prev.set(key, nowMap);

        return snapshot;
    }

    return { poll, isConnected, justDisconnected };
}