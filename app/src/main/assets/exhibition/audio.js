/**
 * YukiHub 离线展厅 · 程序化音频（M2）
 * =========================================================
 * 全部用 WebAudio **现场合成**，不引入任何外部音频素材：
 *   · 环境垫音：三个低音正弦（55 / 82.5 / 110 Hz）过低通，极缓慢起伏
 *   · 房间底噪：极低音量的带通噪声，让"安静"有质感
 *   · 偶发风铃：随机间隔的小铃音（五声音阶），像远处展馆的提示音
 *   · 脚步：噪声脉冲过带通，音量/音色随速度与蹲姿变化
 *   · 交互音：轻点展品的短促提示音
 *
 * 为什么不用音频文件：
 *   1) 零版权风险（不引入来路不明的素材）
 *   2) 零体积增加（不入 APK）
 *   3) 参数可调（速度→脚步音量这种联动很自然）
 *
 * 注意：WebAudio 需要用户手势才能出声，所以 start() 首次点击/按键时调用。
 */

export function createAudio() {
    let ctx = null;
    let master = null;
    let noise = null;      // 复用同一段噪声缓冲
    let started = false;
    let muted = false;
    let chimeTimer = null;

    function ensure() {
        if (ctx) return true;
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return false;
        try {
            ctx = new AC();
            master = ctx.createGain();
            master.gain.value = 0;             // 淡入由 start() 负责
            master.connect(ctx.destination);
            return true;
        } catch (e) {
            ctx = null;
            return false;
        }
    }

    function noiseBuffer(seconds) {
        if (noise) return noise;
        const len = Math.max(1, Math.floor(ctx.sampleRate * seconds));
        const buf = ctx.createBuffer(1, len, ctx.sampleRate);
        const d = buf.getChannelData(0);
        for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
        noise = buf;
        return buf;
    }

    /** 启动音频（必须在用户手势里调用一次） */
    function start() {
        if (started) return;
        if (!ensure()) return;
        started = true;
        if (ctx.state === 'suspended') { try { ctx.resume(); } catch (e) { } }

        // ---------- 环境垫音 ----------
        const pad = ctx.createGain();
        pad.gain.value = 0.055;
        const lp = ctx.createBiquadFilter();
        lp.type = 'lowpass';
        lp.frequency.value = 360;
        lp.Q.value = 0.4;
        lp.connect(pad);
        pad.connect(master);

        [55, 82.5, 110].forEach((f, i) => {
            const o = ctx.createOscillator();
            o.type = (i === 2) ? 'triangle' : 'sine';
            o.frequency.value = f;
            const g = ctx.createGain();
            g.gain.value = (i === 0) ? 0.5 : 0.22;
            o.connect(g);
            g.connect(lp);
            try { o.start(); } catch (e) { }
        });

        // 极缓慢的起伏（LFO 加在 pad 的音量上）
        const lfo = ctx.createOscillator();
        lfo.frequency.value = 0.045;
        const lfoGain = ctx.createGain();
        lfoGain.gain.value = 0.022;
        lfo.connect(lfoGain);
        lfoGain.connect(pad.gain);
        try { lfo.start(); } catch (e) { }

        // ---------- 房间底噪 ----------
        const rt = ctx.createBufferSource();
        rt.buffer = noiseBuffer(4);
        rt.loop = true;
        const rtF = ctx.createBiquadFilter();
        rtF.type = 'bandpass';
        rtF.frequency.value = 240;
        rtF.Q.value = 0.5;
        const rtG = ctx.createGain();
        rtG.gain.value = 0.016;
        rt.connect(rtF);
        rtF.connect(rtG);
        rtG.connect(master);
        try { rt.start(); } catch (e) { }

        // ---------- 总音量淡入 ----------
        const t = ctx.currentTime;
        master.gain.cancelScheduledValues(t);
        master.gain.setValueAtTime(0, t);
        master.gain.linearRampToValueAtTime(muted ? 0 : 0.85, t + 2.0);

        // ---------- 偶发风铃 ----------
        const scheduleChime = () => {
            const delay = 18000 + Math.random() * 26000;
            chimeTimer = setTimeout(() => {
                chime();
                scheduleChime();
            }, delay);
        };
        scheduleChime();
    }

    /** 脚步：噪声脉冲过带通，音量随速度变化；蹲行更轻更低 */
    function footstep(vol, crouch) {
        if (!ctx || !started || muted) return;
        const t = ctx.currentTime;
        const src = ctx.createBufferSource();
        src.buffer = noiseBuffer(1);

        const bp = ctx.createBiquadFilter();
        bp.type = 'bandpass';
        bp.frequency.value = (crouch ? 680 : 1050) + Math.random() * 380;
        bp.Q.value = 1.2;

        const g = ctx.createGain();
        const peak = (crouch ? 0.030 : 0.070) * Math.max(0.15, Math.min(1, vol));
        g.gain.setValueAtTime(0.0001, t);
        g.gain.linearRampToValueAtTime(peak, t + 0.006);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.13);

        src.connect(bp);
        bp.connect(g);
        g.connect(master);
        try { src.start(t); src.stop(t + 0.2); } catch (e) { }
    }

    /** 交互音：轻点展品 */
    function click() {
        if (!ctx || !started || muted) return;
        const t = ctx.currentTime;
        const o = ctx.createOscillator();
        o.type = 'sine';
        o.frequency.setValueAtTime(760, t);
        o.frequency.exponentialRampToValueAtTime(1180, t + 0.07);
        const g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.linearRampToValueAtTime(0.05, t + 0.008);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
        o.connect(g);
        g.connect(master);
        try { o.start(t); o.stop(t + 0.2); } catch (e) { }
    }

    /** 风铃：五声音阶里的一个音，带长尾 */
    function chime() {
        if (!ctx || !started || muted) return;
        const scale = [523.25, 587.33, 659.25, 783.99, 880.0];
        const f = scale[Math.floor(Math.random() * scale.length)];
        const t = ctx.currentTime;

        [f, f * 2.01].forEach((freq, i) => {
            const o = ctx.createOscillator();
            o.type = 'sine';
            o.frequency.value = freq;
            const g = ctx.createGain();
            const peak = i === 0 ? 0.038 : 0.014;
            g.gain.setValueAtTime(0.0001, t);
            g.gain.linearRampToValueAtTime(peak, t + 0.02);
            g.gain.exponentialRampToValueAtTime(0.0001, t + 2.6);
            o.connect(g);
            g.connect(master);
            try { o.start(t); o.stop(t + 2.8); } catch (e) { }
        });
    }

    /** 静音开关：返回切换后的状态（true = 已静音） */
    function toggle() {
        muted = !muted;
        if (ctx && master) {
            const t = ctx.currentTime;
            master.gain.cancelScheduledValues(t);
            master.gain.setTargetAtTime(muted ? 0 : 0.85, t, 0.12);
        }
        if (!muted) start();   // 从静音切回时确保已经启动
        return muted;
    }

    return {
        start,
        footstep,
        click,
        chime,
        toggle,
        isMuted: () => muted,
        isStarted: () => started,
    };
}
