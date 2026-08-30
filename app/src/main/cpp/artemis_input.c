/*
 * Artemis 引擎输入注入桥。
 *
 * 背景：Artemis 是 NativeActivity，Surface 交给 native，Java View 树不参与绘制，
 * JNI 层也没有导出任何鼠标/触摸注入接口。无障碍手势方案试过多轮均无效
 * （手势能派发成功，但引擎不响应）。
 *
 * 本库的做法：dlopen 自己进程里已加载的 libartemis*.so，用 dlsym 取出
 * 引擎内部的输入单例与上报函数，直接调用 —— 相当于从引擎内部喂输入，
 * 完全绕过系统输入派发。
 *
 * 依赖的符号（三个变体库都已确认存在）：
 *   _ZN7artemis10CInputBase8instanceE               CInputBase* （8 字节指针）
 *   _ZN7artemis10CInputBase19ReportMousePositionERKNS_6TPointIiEE
 *   _ZN7artemis10CInputBase11ReportPressEib
 *   _ZN7artemis10CInputBase17ReportTouchStatusEb
 *
 * 编译（不需要 NDK，宿主是 aarch64 且有 Android 版 jni.h）：
 *
 *   # 1. 先造两个只用于链接的占位库，让产物记下正确的 DT_NEEDED
 *   gcc -shared -fPIC -nostdlib -Wl,-soname,libdl.so  -o libdl.so  dlstub.c
 *   gcc -shared -fPIC -nostdlib -Wl,-soname,liblog.so -o liblog.so logstub.c
 *   # dlstub.c:  void dlopen(void){} void dlsym(void){} void dlerror(void){} void dlclose(void){}
 *   # logstub.c: void __android_log_print(void){} void __android_log_write(void){}
 *
 *   # 2. 编本库
 *   gcc -shared -fPIC -O2 -nostdlib -I/usr/include/android \
 *       -o libyukiartemisinput.so artemis_input.c -L. -ldl -llog
 *   strip --strip-unneeded libyukiartemisinput.so
 *
 * 两个坑，都踩过：
 * - 不加 -nostdlib：产物带 `NEEDED libc.so.6`（glibc）。Android 用 bionic，
 *   这种库在设备上加载直接失败。
 * - 只加 -nostdlib 不给占位库：NEEDED 全没了，Android linker 不知道去哪找
 *   dlopen，报 `cannot locate symbol "dlopen" referenced by ...` 并拒绝加载。
 * 正确结果是 NEEDED 恰好为 libdl.so + liblog.so（都是 bionic 提供的）。
 *
 * 另外宿主没有 android/log.h，所以 __android_log_print 的原型是手写的。
 */

#include <jni.h>
#include <dlfcn.h>

/*
 * 手写 __android_log_print 原型：宿主环境没有 android/log.h，
 * 但这个符号在设备上由 bionic 的 liblog.so 提供，声明出来即可链接。
 */
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YukiArtemisNative", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "YukiArtemisNative", __VA_ARGS__)

/*
 * 引擎侧函数签名。C++ 非虚成员函数的 ABI 是 this 作第一个隐式参数。
 *
 * 注意 ReportMousePosition 不在这里 —— 它的第二个参数不是普通数据结构，
 * 而是一个带虚表的接口对象（反汇编：ldr x8,[x1] → ldr x8,[x8,#16] → blr x8，
 * 即调用参数对象的虚方法来取坐标）。无法从外部构造，直接调必然 SIGSEGV。
 * 位置改为直写字段，见 write_position。
 *
 * ReportPress 也不再调用：它只往 deque 排队，需要引擎 Execute() 结算，
 * 在帧循环外调用等于白排（实测调用成功但引擎无反应）。
 * 仅保留符号查找，用于判断 dlopen 到的是不是当前引擎用的那个库。
 *
 * ReportTouchStatus 仍在调用：无虚调用，且只置一个标志位。
 */
typedef void (*fn_report_touch)(void *self, unsigned char down);

/*
 * 鼠标坐标在 CInputBase 对象内的布局。
 *
 * ⚠ 这些偏移只能来自 ReportMousePosition 的 str 指令 —— 那才是引擎自己
 * 写坐标的地方。不要用 GetPosition / GetRawPosition 的返回值，血的教训：
 *
 *   libartemis.so 的四个偏移互不相同：
 *     ReportMousePosition 写 raw     14624 / 14628
 *     ReportMousePosition 写 scaled  14640
 *     GetPosition 返回               14632   ← 不是 14640
 *     GetRawPosition 返回            14616   ← 不是 14624
 *
 *   更致命的是 GetRawPosition 返回的那个偏移（兼容版 0x928）根本不是坐标，
 *   而是一个指针字段。CLayer::IsRollovered 会这样用它：
 *     add x19, x19, #0x928
 *     ldr x2, [x19]        ← 当指针读出来
 *     ldr x1, [x2, #16]    ← 解引用取虚表
 *   往那里写整数坐标 = 把指针改成 0x1c600000485 这种垃圾值，
 *   引擎一走悬停判定就 SIGSEGV。三个版本全崩过，就是这个原因。
 *
 * 之所以 IsRollovered 以前从不触发：鼠标位置一直是初始值，
 * 引擎认为鼠标不在任何图层上。位置一写对，悬停路径才第一次被走到。
 *
 * 各库实测布局（逐个反汇编 ReportMousePosition 核对）：
 *
 *   libartemis.so / libartemis-compatible-v2.so
 *     mov x20, x0                    x20 = this
 *     str w0, [x20, #14624]          raw x
 *     str w0, [x20, #14628]          raw y
 *     str d0, [x20, #14640]          scaled x,y（一个 double = 两个 float）
 *
 *   libartemis-compatible.so   —— 布局完全不同，不是简单平移
 *     mov x19, x0                    x19 = this
 *     add x20, x19, #0x928           x20 = this + 2344
 *     str w2, [x20, #8]              raw x  → this + 2352
 *     str w0, [x20, #12]             raw y  → this + 2356
 *     str s0, [x19, #2336]           scaled x
 *     str s1, [x19, #2340]           scaled y
 *
 * ratio/anchor 三库一致（GetRatio/GetAnchorX/GetAnchorY 都是
 * [x0,#12] / [x0,#16] / [x0,#20]），可以写死。
 *
 * 选表方式：用 GetPosition 返回的偏移作库指纹。它只用来识别是哪种布局，
 * 绝不直接当写入地址用。指纹不认识就整体禁用写入。
 */
struct layout {
    int fingerprint;    /* GetPosition 解出的偏移，仅作识别用 */
    int raw_x, raw_y;
    int scaled_x, scaled_y;
};

static const struct layout kLayouts[] = {
    /* libartemis.so 与 libartemis-compatible-v2.so 指纹与布局都相同 */
    { 14632, 14624, 14628, 14640, 14644 },
    /* libartemis-compatible.so */
    {  2328,  2352,  2356,  2336,  2340 },
};
#define LAYOUT_COUNT 2

#define RATIO_OFFSET     12
#define ANCHOR_X_OFFSET  16
#define ANCHOR_Y_OFFSET  20

static const struct layout *g_layout = 0;

/* AArch64 指令解码：只认这几种确定形态，识别不了就报失败。 */
static int decode_getposition_offset(const unsigned int *code) {
    unsigned int i0 = code[0];
    unsigned int i1 = code[1];

    /* 形态 A: add x0, x0, #imm12  ->  1001 0001 00 iiiiiiiiiiii nnnnn ddddd */
    if ((i0 & 0xFFC00000u) == 0x91000000u) {
        unsigned int rd = i0 & 0x1F;
        unsigned int rn = (i0 >> 5) & 0x1F;
        unsigned int imm = (i0 >> 10) & 0xFFF;
        unsigned int shift = (i0 >> 22) & 0x3;
        if (rd == 0 && rn == 0 && shift == 0) return (int) imm;
        if (rd == 0 && rn == 0 && shift == 1) return (int) (imm << 12);
    }

    /* 形态 B: mov wN, #imm16 ; add x0, x0, xN */
    if ((i0 & 0xFF800000u) == 0x52800000u) {          /* movz w<rd>, #imm16 */
        unsigned int mov_rd = i0 & 0x1F;
        unsigned int imm16 = (i0 >> 5) & 0xFFFF;
        unsigned int hw = (i0 >> 21) & 0x3;
        if (hw == 0) {
            /* add x0, x0, x<rm>  ->  1000 1011 000 mmmmm 000000 nnnnn ddddd */
            if ((i1 & 0xFFE0FC00u) == 0x8B000000u) {
                unsigned int add_rd = i1 & 0x1F;
                unsigned int add_rn = (i1 >> 5) & 0x1F;
                unsigned int add_rm = (i1 >> 16) & 0x1F;
                if (add_rd == 0 && add_rn == 0 && add_rm == mov_rd) {
                    return (int) imm16;
                }
            }
        }
    }
    return -1;
}

/*
 * 用 GetPosition 的偏移作指纹，挑对应的布局表。
 *
 * 只做识别，不拿它当写入地址（上一轮就是这么错的）。
 * 指纹对不上就返回 0，位置写入整体禁用 —— 宁可没鼠标也不能乱写内存。
 */
static int probe_offsets(void *lib) {
    const unsigned int *code = (const unsigned int *) dlsym(
            lib, "_ZN7artemis10CInputBase11GetPositionEv");
    int fp, i;
    if (!code) {
        LOGW("GetPosition missing, position write disabled");
        return 0;
    }
    fp = decode_getposition_offset(code);
    if (fp <= 0) {
        LOGW("fingerprint decode failed (%08x %08x), position write disabled",
             code[0], code[1]);
        return 0;
    }
    for (i = 0; i < LAYOUT_COUNT; i++) {
        if (kLayouts[i].fingerprint == fp) {
            g_layout = &kLayouts[i];
            LOGI("layout matched: fp=%d raw=%d/%d scaled=%d/%d",
                 fp, g_layout->raw_x, g_layout->raw_y,
                 g_layout->scaled_x, g_layout->scaled_y);
            return 1;
        }
    }
    LOGW("unknown layout fingerprint=%d, position write disabled", fp);
    return 0;
}

/*
 * 按键状态数组：起始于 this+24，每键一个 int（key 索引 0..255）。
 *
 * 来自 IsPush / IsDown 的反汇编（三条指令，无歧义）：
 *   IsPush(key): x8 = this + key*4;  w8 = [x8, #24]
 *                orr w8,w8,#2; cmp w8,#3  → 真当且仅当 state ∈ {1, 3}
 *   IsDown(key): 同样取 [this + 24 + key*4]
 *                sub w8,w8,#1; cmp w8,#3 (unsigned lo) → 真当 state ∈ {1, 2, 3}
 *
 * 于是状态语义可以反推出来：
 *   0 = 空闲    1 = 刚按下    2 = 持续按下    3 = 重复按下（连发）
 *   4 = 刚抬起  ← 关键，见下
 *
 * 完整判定表（三个库逐一反汇编核对，结论一致）：
 *
 *   IsDownEdge(key)  state == 1        刚按下这一帧
 *   IsPush(key)      state ∈ {1, 3}    按下（含连发重复）
 *   IsDown(key)      state ∈ {1, 2, 3} 持续按下
 *   IsUpEdge(key)    state == 4        刚抬起这一帧
 *
 * 兼容版的 IsPush/IsDown 指令写法与另两库不同（and/sub 而非 orr），
 * 但结果集完全相同，所以状态取值是通用的：
 *   标准版 IsPush: orr w8,w8,#2 ; cmp w8,#3   → {1,3}
 *   兼容版 IsPush: and w0,w0,#~2 ; cmp w0,#1  → {1,3}
 *
 * ⚠ 抬起必须写 4，且必须由我们自己再清回 0。
 * galgame 的按钮确认大多用**抬起边沿**触发，而 IsUpEdge 要求 state == 4。
 * 写 0 的话：按下时 IsDownEdge/IsPush 为真 → 按钮高亮、播按下动画，
 * 但 IsUpEdge 永远不成立 → 确认动作永不执行（实测症状）。
 *
 * 但只写 4 也不行 —— 引擎不会帮我们清。
 * Execute() 的反汇编（标准版 529bb4 / 兼容版 440898）都是同一个形状：
 *   ldr x8, [deque 指针]
 *   cbz x8, <skip>        ← deque 为空就整条跳过，状态数组一个字节都不碰
 *   ...
 *   str w8, [状态数组]    ← 只有 deque 非空才写
 * 我们直写状态数组、从不往 deque 排队，所以引擎**永远不会推进我们写的值**。
 * state 卡在 4 的后果：IsUpEdge 每帧为真 → 拖到哪都算一次点击
 * （兼容版实测"拖动鼠标在哪都算点击"），标准版则表现为悬停时灵时不灵。
 *
 * 所以抬起是两步：先写 4（让 IsUpEdge 成立，引擎在下一帧读到），
 * 再延时清 0。延时要够引擎至少跑一帧，见 nativeRelease。
 */
#define KEY_STATE_BASE 24
#define KEY_STATE_MAX  256

/* 按键状态取值，语义由 IsDownEdge/IsPush/IsDown/IsUpEdge 反推 */
#define KEY_UP        0  /* 空闲 */
#define KEY_PUSH      1  /* 刚按下：IsDownEdge / IsPush / IsDown 均真 */
#define KEY_HOLD      2  /* 持续按下：仅 IsDown 真 */
#define KEY_UP_EDGE   4  /* 刚抬起：IsUpEdge 真 —— 按钮确认靠它 */

static void **g_instance_slot = 0;      /* 指向 CInputBase::instance 这个全局槽位 */
static void *g_press_sym = 0;           /* 仅用于确认库匹配，不调用（见上方注释） */
static fn_report_touch g_touch = 0;
static int g_ready = 0;
/* 上次上报的位置，用于跳过重复上报（见 nativeMove） */
static int g_last_x = 0, g_last_y = 0, g_have_last = 0;

/*
 * 三个变体库都可能被用到。
 *
 * 关键：必须选引擎**实际加载**的那个，不能选第一个符号齐全的。
 * 三个库都导出 CInputBase 系符号，直接 dlopen 挨个试会匹配到错的库：
 * 实测兼容版游戏日志显示 "dlopen libartemis.so ok"，
 * 但崩溃栈里是 libartemis-compatible.so —— 拿 A 库的偏移写 B 库的对象。
 *
 * 所以先用 RTLD_NOLOAD 只认已加载的库（引擎启动时必已加载它用的那个），
 * 全都不中时才退回普通 dlopen 逐个尝试（兼容 RTLD_NOLOAD 支持不佳的系统）。
 */
static const char *kLibs[] = {
    "libartemis.so",
    "libartemis-compatible.so",
    "libartemis-compatible-v2.so",
    0
};

static int try_lib(const char *name, int flags) {
    void *h = dlopen(name, flags);
    if (!h) return 0;
    g_instance_slot = (void **) dlsym(h, "_ZN7artemis10CInputBase8instanceE");
    g_press_sym = dlsym(h, "_ZN7artemis10CInputBase11ReportPressEib");
    g_touch = (fn_report_touch) dlsym(
            h, "_ZN7artemis10CInputBase17ReportTouchStatusEb");
    if (!g_instance_slot || !g_press_sym) {
        g_instance_slot = 0;
        g_press_sym = 0;
        g_touch = 0;
        return 0;
    }
    /* 偏移探测失败就整体放弃这个库：宁可没有鼠标，也不能乱写内存 */
    if (!probe_offsets(h)) {
        g_instance_slot = 0;
        g_press_sym = 0;
        g_touch = 0;
        return 0;
    }
    LOGI("using %s: slot=%p press=%p touch=%p (loaded=%s)",
         name, (void *) g_instance_slot, g_press_sym, (void *) g_touch,
         (flags & RTLD_NOLOAD) ? "yes" : "unknown");
    g_ready = 1;
    return 1;
}

static int resolve(void) {
    int i;
    if (g_ready) return 1;
    g_have_last = 0;   /* 换库/重新初始化：位置缓存作废 */
    /* 第一轮：只认引擎已经加载的库 */
    for (i = 0; kLibs[i]; i++) {
        if (try_lib(kLibs[i], RTLD_NOW | RTLD_NOLOAD)) return 1;
    }
    /* 第二轮：RTLD_NOLOAD 不被支持时的退路 */
    for (i = 0; kLibs[i]; i++) {
        if (try_lib(kLibs[i], RTLD_NOW)) return 1;
    }
    LOGW("no usable artemis lib found");
    return 0;
}

/*
 * 取当前的引擎输入对象。
 *
 * 两道检查都必要：
 * - 槽位为空：引擎还没构造 CInputBase（probe=1 的情形）
 * - 指针明显非法：低位地址或未对齐。我们要往 this+14644 处写内存，
 *   拿到野指针会直接踩坏引擎内存或 SIGSEGV。
 *   上一版崩在 hover 路径（每 16ms 一次），这种高频路径必须严格。
 */
static void *input_self(void) {
    void *p;
    if (!resolve()) return 0;
    if (!g_instance_slot) return 0;
    p = *g_instance_slot;
    if (!p) return 0;
    /* 合法堆/数据地址不会落在低 64KB，且对象至少要对齐到 8 字节 */
    if ((unsigned long) p < 0x10000UL) return 0;
    if (((unsigned long) p & 7UL) != 0UL) return 0;
    return p;
}

/*
 * 写入鼠标位置。
 *
 * 复刻 ReportMousePosition 的逻辑，但不调用它
 * （它的参数是带虚表的接口对象，无法从外部构造 —— 直接调会 SIGSEGV，已踩过）。
 *
 *   raw    = (int) 屏幕坐标
 *   scaled = ((float) raw - anchor) * ratio
 *
 * 两份都写：引擎不同分支读的不是同一份（ReportMousePosition 自己也是
 * 两份都写）。注意读取用的 getter 返回的偏移与这里写的字段并不相同，
 * 别拿 getter 的偏移当写入地址 —— 见上面 kLayouts 的说明。
 */
static void write_position(void *self, int x, int y) {
    char *base = (char *) self;
    float ratio, ax, ay;
    /* 布局未识别时绝不写：拿错偏移会破坏引擎内部指针，
     * 引擎走到悬停判定 IsRollovered 就 SIGSEGV（三版全崩过）。 */
    if (!g_layout) return;
    ratio = *(float *) (base + RATIO_OFFSET);
    ax = *(float *) (base + ANCHOR_X_OFFSET);
    ay = *(float *) (base + ANCHOR_Y_OFFSET);
    /* ratio 为 0（未初始化）时按 1 处理，否则坐标会被清零 */
    if (ratio == 0.0f) ratio = 1.0f;
    *(int *) (base + g_layout->raw_x) = x;
    *(int *) (base + g_layout->raw_y) = y;
    *(float *) (base + g_layout->scaled_x) = ((float) x - ax) * ratio;
    *(float *) (base + g_layout->scaled_y) = ((float) y - ay) * ratio;
}

/*
 * 写入按键状态。
 *
 * 直接写状态数组，不走 ReportPress —— 后者只是往 deque 排队，
 * 需要引擎调 Execute() 结算，我们在帧循环外调用排的队没人消费
 * （实测：调用返回成功但引擎完全无反应）。
 */
static void write_key_state(void *self, int key, int state) {
    if (key < 0 || key >= KEY_STATE_MAX) return;
    *(int *) ((char *) self + KEY_STATE_BASE + key * 4) = state;
}

static int read_key_state(void *self, int key) {
    if (key < 0 || key >= KEY_STATE_MAX) return -1;
    return *(int *) ((char *) self + KEY_STATE_BASE + key * 4);
}

/*
 * 诊断：把当前所有非零的按键状态打进 logcat。
 *
 * 用途是找出「鼠标左键/确认」对应的 key 索引 —— 该索引是引擎自己的
 * 键位枚举，从符号表推不出来。真实触摸屏幕的瞬间调用本函数，
 * 引擎写了哪个槽位就一目了然。
 */
JNIEXPORT void JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeDumpKeys(
        JNIEnv *env, jclass clazz) {
    void *self;
    int i;
    int found = 0;
    (void) env;
    (void) clazz;
    self = input_self();
    if (!self) {
        LOGW("dumpKeys: engine not ready");
        return;
    }
    for (i = 0; i < KEY_STATE_MAX; i++) {
        int s = read_key_state(self, i);
        if (s != 0) {
            LOGI("key[%d] = %d", i, s);
            found++;
        }
    }
    if (!found) LOGI("dumpKeys: all keys are 0");
}

/* 探测：返回 0=库或符号缺失，1=符号就绪但引擎未初始化，2=完全可用 */
JNIEXPORT jint JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeProbe(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!resolve()) return 0;
    return input_self() ? 2 : 1;
}

/*
 * 移动光标（悬停）。只写位置，不按下 —— 这就是鼠标悬停：
 * 引擎的按钮高亮逻辑读的是 CInputBase 的当前位置。
 */
JNIEXPORT jboolean JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeMove(
        JNIEnv *env, jclass clazz, jint x, jint y) {
    void *self;
    (void) env;
    (void) clazz;
    self = input_self();
    if (!self) return JNI_FALSE;
    /*
     * 位置没变就不写。
     *
     * 引擎的悬停判定（CLayer::IsRollovered）会遍历整棵图层树，
     * 位置一变就得重算一遍。手指停住时 MOVE 事件仍在以 60Hz 到达，
     * 重复上报同一坐标只是白白让引擎多跑几十次遍历 —— 实测这是
     * 「悬停变卡」的主要来源（位置写入修对之后悬停判定才真正开始执行）。
     */
    if (g_have_last && g_last_x == (int) x && g_last_y == (int) y) {
        return JNI_TRUE;
    }
    g_last_x = (int) x;
    g_last_y = (int) y;
    g_have_last = 1;
    write_position(self, (int) x, (int) y);
    return JNI_TRUE;
}

/*
 * 点击：写位置 + 写按键状态。
 *
 * key 索引是引擎自己的键位枚举，符号表推不出来，所以同时写多个候选：
 * 0（常见的主键/左键）、1、2，以及 SetTouchKeys 相关的低位索引。
 * 引擎只会响应它认的那一个，多写的几个对应不存在的功能键，无副作用
 * （状态数组是 256 项的普通 int 数组，写了没人读就是没影响）。
 *
 * 真正的索引可以用 nativeDumpKeys 在真实触摸时抓出来，之后再收窄。
 */
static const int kCandidateKeys[] = {0, 1, 2, 3};
#define CANDIDATE_KEY_COUNT 4

JNIEXPORT jboolean JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeTap(
        JNIEnv *env, jclass clazz, jint x, jint y) {
    void *self;
    int i;
    (void) env;
    (void) clazz;
    self = input_self();
    if (!self) return JNI_FALSE;
    write_position(self, (int) x, (int) y);
    /* 同步位置缓存：点击也算一次上报，否则紧随其后的 hover 会被误判为"未变化" */
    g_last_x = (int) x;
    g_last_y = (int) y;
    g_have_last = 1;
    /* 触摸标志：部分分支依赖它。这个函数无虚调用，可安全直调。
     * 兼容版没有这个符号（纯鼠标模型），g_touch 为 0，跳过即可。 */
    if (g_touch) g_touch(self, 1);
    for (i = 0; i < CANDIDATE_KEY_COUNT; i++) {
        write_key_state(self, kCandidateKeys[i], KEY_PUSH);
    }
    return JNI_TRUE;
}

/*
 * 抬起第一步：写抬起边沿（state=4）。
 *
 * 引擎在下一帧读 IsUpEdge 时会看到，按钮确认就在这时触发。
 * 必须紧跟 nativeClearKeys 把它清掉 —— 引擎不会帮我们清，
 * 详见上方状态机注释。
 */
JNIEXPORT jboolean JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeRelease(
        JNIEnv *env, jclass clazz) {
    void *self;
    int i;
    (void) env;
    (void) clazz;
    self = input_self();
    if (!self) return JNI_FALSE;
    if (g_touch) g_touch(self, 0);
    for (i = 0; i < CANDIDATE_KEY_COUNT; i++) {
        write_key_state(self, kCandidateKeys[i], KEY_UP_EDGE);
    }
    return JNI_TRUE;
}

/*
 * 抬起第二步：把状态清回空闲（state=0）。
 *
 * 由 Java 侧在 nativeRelease 之后延时调用，延时要够引擎跑至少一帧。
 * 不清的话 state 永久停在 4，IsUpEdge 每帧为真 ——
 * 表现为"拖到哪都算一次点击"，以及悬停判定被持续干扰。
 */
JNIEXPORT jboolean JNICALL
Java_com_yuki_yukihub_gamecursor_ArtemisNativeInput_nativeClearKeys(
        JNIEnv *env, jclass clazz) {
    void *self;
    int i;
    (void) env;
    (void) clazz;
    self = input_self();
    if (!self) return JNI_FALSE;
    for (i = 0; i < CANDIDATE_KEY_COUNT; i++) {
        write_key_state(self, kCandidateKeys[i], KEY_UP);
    }
    return JNI_TRUE;
}