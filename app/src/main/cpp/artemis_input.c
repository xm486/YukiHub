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
 * 全部来自 libartemis.so 的 ReportMousePosition 反汇编（非猜测）：
 *   str w0,[x20,#14624]                    原始 x（int）
 *   str w0,[x20,#14628]                    原始 y（int）
 *   ldr d1,[x20,#16]  ldr s2,[x20,#12]     锚点(anchor) 与 缩放比(ratio)
 *   scvtf → fsub v1 → fmul v2 → str d0,[x20,#14640]
 *                                          缩放后坐标（两个 float，供 GetPosition 用）
 * 也就是：scaled = ((float) raw - anchor) * ratio
 *
 * 锚点存的是一对 float（被当作 d1 整体加载，即 anchor.x / anchor.y），
 * 与 GetAnchorX/GetAnchorY 对应；ratio 是单个 float，两轴共用（SetRatio(float)）。
 *
 * 必须同时写 raw 与 scaled：引擎不同分支读的不是同一份
 * （GetRawPosition 读 14624，GetPosition 读 14640）。
 */
#define RAW_X_OFFSET     14624
#define RAW_Y_OFFSET     14628
#define SCALED_X_OFFSET  14640
#define SCALED_Y_OFFSET  14644
#define RATIO_OFFSET     12
#define ANCHOR_X_OFFSET  16
#define ANCHOR_Y_OFFSET  20

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
 *   0 = 抬起      1 = 刚按下（IsPush 真 + IsDown 真）
 *   2 = 持续按下（仅 IsDown 真）   3 = 也算 Push
 *
 * 为什么直接写这里而不调 ReportPress：
 * ReportPress/ReportPush 只是往一个 deque 排队（反汇编里全是 deque 操作，
 * 每键 48 字节的记录结构，基址 this+0x818），要等引擎调 Execute() 才会
 * 结算进上面这个状态数组。我们在引擎帧循环之外调用，排的队没人消费，
 * 所以点击一直无效 —— 这是实测确认过的（调用成功但引擎无反应）。
 * 直接写状态数组，引擎下一帧读 IsPush/IsDown 就能看到。
 */
#define KEY_STATE_BASE 24
#define KEY_STATE_MAX  256

/* 按键状态取值，语义由 IsPush/IsDown 反推 */
#define KEY_UP        0
#define KEY_PUSH      1  /* 刚按下：IsPush 与 IsDown 同时为真 */
#define KEY_HOLD      2  /* 持续按下：仅 IsDown 为真 */

static void **g_instance_slot = 0;      /* 指向 CInputBase::instance 这个全局槽位 */
static void *g_press_sym = 0;           /* 仅用于确认库匹配，不调用（见上方注释） */
static fn_report_touch g_touch = 0;
static int g_ready = 0;

/*
 * 三个变体库都可能被用到，按顺序试。
 * RTLD_NOLOAD 语义上更准确（我们只想拿到已加载的句柄，不想触发新加载），
 * 但部分 Android 版本对它支持不一致，所以退回普通 dlopen：
 * 目标库此刻必定已被引擎加载，dlopen 只会增加引用计数，不会重复初始化。
 */
static const char *kLibs[] = {
    "libartemis.so",
    "libartemis-compatible.so",
    "libartemis-compatible-v2.so",
    0
};

static int resolve(void) {
    int i;
    if (g_ready) return 1;
    for (i = 0; kLibs[i]; i++) {
        void *h = dlopen(kLibs[i], RTLD_NOW);
        if (!h) {
            LOGI("dlopen %s failed", kLibs[i]);
            continue;
        }
        g_instance_slot = (void **) dlsym(h, "_ZN7artemis10CInputBase8instanceE");
        g_press_sym = dlsym(h, "_ZN7artemis10CInputBase11ReportPressEib");
        g_touch = (fn_report_touch) dlsym(
                h, "_ZN7artemis10CInputBase17ReportTouchStatusEb");
        LOGI("dlopen %s ok: slot=%p press=%p touch=%p",
             kLibs[i], (void *) g_instance_slot,
             g_press_sym, (void *) g_touch);
        if (g_instance_slot && g_press_sym) {
            g_ready = 1;
            return 1;
        }
        /* 这个库不是当前引擎用的那个，清干净再试下一个 */
        g_instance_slot = 0;
        g_press_sym = 0;
        g_touch = 0;
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
 * 两份都写：GetRawPosition 读 raw，GetPosition 读 scaled，
 * 引擎不同分支用的不是同一份。
 */
static void write_position(void *self, int x, int y) {
    char *base = (char *) self;
    float ratio = *(float *) (base + RATIO_OFFSET);
    float ax = *(float *) (base + ANCHOR_X_OFFSET);
    float ay = *(float *) (base + ANCHOR_Y_OFFSET);
    /* ratio 为 0（未初始化）时按 1 处理，否则坐标会被清零 */
    if (ratio == 0.0f) ratio = 1.0f;
    *(int *) (base + RAW_X_OFFSET) = x;
    *(int *) (base + RAW_Y_OFFSET) = y;
    *(float *) (base + SCALED_X_OFFSET) = ((float) x - ax) * ratio;
    *(float *) (base + SCALED_Y_OFFSET) = ((float) y - ay) * ratio;
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
    /* 触摸标志：部分分支依赖它。这个函数无虚调用，可安全直调。 */
    if (g_touch) g_touch(self, 1);
    for (i = 0; i < CANDIDATE_KEY_COUNT; i++) {
        write_key_state(self, kCandidateKeys[i], KEY_PUSH);
    }
    return JNI_TRUE;
}

/* 抬起。与 nativeTap 配对，交由 Java 侧延时调用，构成完整的按下-抬起。 */
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
        write_key_state(self, kCandidateKeys[i], KEY_UP);
    }
    return JNI_TRUE;
}