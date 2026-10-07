/*
 * FVP 引擎（rfvp）JNI 桥。
 *
 * rfvp 的 Android 端是「宿主驱动」模型：Rust 侧只导出一组 C ABI
 * （rfvp_android_create/step/resize/set_surface/touch/key/...），
 * Surface 生命周期与帧循环全部由 Java 侧持有。本库就是这两者之间的胶水：
 * dlopen librfvp.so → dlsym 取符号 → 转发 Java 侧调用。
 *
 * 上游参考：rfvp/crates/rfvp/src/android_host.rs（C ABI 定义）
 *
 * 编译（不需要 NDK，宿主是 aarch64 且有 Android 版 jni.h）：
 *
 *   # 1. 先造三个只用于链接的占位库，让产物记下正确的 DT_NEEDED
 *   gcc -shared -fPIC -nostdlib -Wl,-soname,libdl.so      -o libdl.so      linkstub_dl.c
 *   gcc -shared -fPIC -nostdlib -Wl,-soname,liblog.so     -o liblog.so     linkstub_log.c
 *   gcc -shared -fPIC -nostdlib -Wl,-soname,libandroid.so -o libandroid.so linkstub_android.c
 *
 *   # 2. 编本库
 *   gcc -shared -fPIC -O2 -nostdlib -I/usr/include/android \
 *       -o libyukirfvp.so rfvp_bridge.c -L. -ldl -llog -landroid
 *   strip --strip-unneeded libyukirfvp.so
 *
 * 与 artemis_input.c 相同的两个坑（别踩）：
 * - 不加 -nostdlib：产物带 NEEDED libc.so.6（glibc），Android 是 bionic，加载直接失败。
 * - 只加 -nostdlib 不给占位库：NEEDED 全没了，linker 报
 *   `cannot locate symbol "dlopen" referenced by ...` 拒绝加载。
 * 正确结果是 NEEDED 恰好为 libdl.so + liblog.so + libandroid.so。
 *
 * 另外宿主没有 android/log.h 与 android/native_window_jni.h，
 * 所以 __android_log_print 与 ANativeWindow_* 的原型是手写的；
 * 本文件按 **C** 编译（JNIEnv 走 (*env)-> 调用约定），不使用任何 C++ 运行时。
 *
 * 线程模型：create/setSurface/resize/destroy 都由 FvpActivity 主线程调用，
 * 因此窗口表不加锁（固定数组，避免引入 libc 的 malloc/容器）。
 */

#include <jni.h>
#include <stdint.h>
#include <dlfcn.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------- 手写的 bionic 原型 ---------------- */

typedef struct ANativeWindow ANativeWindow;

extern ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface);
extern void ANativeWindow_release(ANativeWindow* window);
extern int __android_log_print(int prio, const char* tag, const char* fmt, ...);

#define YK_LOG_ERROR 6
#define YK_LOG_WARN 5
#define YK_LOG_INFO 4

#define LOGE(...) __android_log_print(YK_LOG_ERROR, "YukiRfvpBridge", __VA_ARGS__)
#define LOGW(...) __android_log_print(YK_LOG_WARN, "YukiRfvpBridge", __VA_ARGS__)
#define LOGI(...) __android_log_print(YK_LOG_INFO, "YukiRfvpBridge", __VA_ARGS__)

/* ---------------- rfvp_android_* C ABI ---------------- */

typedef void (*init_context_fn_t)(void* java_vm_ptr, void* app_context_global_ref);
typedef void* (*create_fn_t)(void* native_window_ptr, unsigned int w_px, unsigned int h_px,
                             double scale, const char* game_dir_utf8, const char* nls_utf8);
typedef int (*step_fn_t)(void* handle, unsigned int dt_ms);
typedef void (*resize_fn_t)(void* handle, unsigned int w_px, unsigned int h_px);
typedef void (*set_surface_fn_t)(void* handle, void* native_window_ptr,
                                 unsigned int w_px, unsigned int h_px);
typedef void (*touch_fn_t)(void* handle, int phase, double x_px, double y_px);
typedef void (*set_text_hidpi_fn_t)(void* handle, int enabled);
typedef void (*set_text_scale_fn_t)(void* handle, float scale);
typedef void (*set_text_line_scale_fn_t)(void* handle, float scale);
typedef void (*key_fn_t)(void* handle, int vk_code, int phase);
typedef void (*set_system_font_fn_t)(void* handle, int enabled);
typedef int (*add_font_fn_t)(void* handle, const char* font_path_utf8);
typedef void (*set_forced_font_fn_t)(void* handle, int font_id);
typedef void (*destroy_fn_t)(void* handle);

typedef struct RfvpApi {
    init_context_fn_t init_context;
    create_fn_t create;
    step_fn_t step;
    resize_fn_t resize;
    set_surface_fn_t set_surface;
    touch_fn_t touch;
    set_text_hidpi_fn_t set_text_hidpi;
    set_text_scale_fn_t set_text_scale;
    set_text_line_scale_fn_t set_text_line_scale;
    key_fn_t key;
    set_system_font_fn_t set_system_font;
    add_font_fn_t add_font;
    set_forced_font_fn_t set_forced_font;
    destroy_fn_t destroy;
} RfvpApi;

static RfvpApi g_api;
static int g_api_loaded;         /* 0 = 未尝试，1 = 已尝试 */
static void* g_lib_handle;
static JavaVM* g_java_vm;

static void* load_symbol(const char* sym) {
    void* p = dlsym(g_lib_handle, sym);
    if (p == 0) {
        /* dlerror() 返回静态缓冲区，直接塞给日志函数即可 */
        LOGE("dlsym failed: %s (%s)", sym, dlerror());
    }
    return p;
}

static void load_api_once(void) {
    if (g_api_loaded) return;
    g_api_loaded = 1;

    /* librfvp.so 由 APK 的 jniLibs 提供；同名 dlopen 会命中本进程 native
     * 库目录，与上游 rfvp / Tyranor 的做法一致。 */
    g_lib_handle = dlopen("librfvp.so", RTLD_NOW);
    if (g_lib_handle == 0) {
        LOGE("dlopen librfvp.so failed: %s", dlerror());
        return;
    }
    g_api.init_context = (init_context_fn_t) load_symbol("rfvp_android_init_context");
    g_api.create = (create_fn_t) load_symbol("rfvp_android_create");
    g_api.step = (step_fn_t) load_symbol("rfvp_android_step");
    g_api.resize = (resize_fn_t) load_symbol("rfvp_android_resize");
    g_api.set_surface = (set_surface_fn_t) load_symbol("rfvp_android_set_surface");
    g_api.touch = (touch_fn_t) load_symbol("rfvp_android_touch");
    g_api.set_text_hidpi = (set_text_hidpi_fn_t) load_symbol("rfvp_android_set_text_hidpi");
    g_api.set_text_scale = (set_text_scale_fn_t) load_symbol("rfvp_android_set_text_scale");
    g_api.set_text_line_scale =
            (set_text_line_scale_fn_t) load_symbol("rfvp_android_set_text_line_scale");
    g_api.key = (key_fn_t) load_symbol("rfvp_android_key");
    g_api.set_system_font = (set_system_font_fn_t) load_symbol("rfvp_android_set_system_font");
    g_api.add_font = (add_font_fn_t) load_symbol("rfvp_android_add_font");
    g_api.set_forced_font = (set_forced_font_fn_t) load_symbol("rfvp_android_set_forced_font");
    g_api.destroy = (destroy_fn_t) load_symbol("rfvp_android_destroy");

    if (g_api.create == 0 || g_api.step == 0 || g_api.resize == 0 ||
        g_api.set_surface == 0 || g_api.touch == 0 || g_api.destroy == 0 ||
        g_api.init_context == 0) {
        LOGE("missing one or more required rfvp_android_* symbols");
    }
    if (g_api.key == 0 || g_api.set_system_font == 0) {
        LOGW("rfvp_android_key/set_system_font missing; back key and system font fallback disabled");
    }
    if (g_api.add_font == 0 || g_api.set_forced_font == 0) {
        LOGW("rfvp_android_add_font/set_forced_font missing; custom font disabled");
    }
    LOGI("rfvp_android_* symbols resolved");
}

/* ANativeWindow 引用表：Rust 侧持有裸指针，必须保证其在引擎存活期间有效。 */
#define YK_MAX_WINDOWS 4
static jlong g_win_key[YK_MAX_WINDOWS];
static ANativeWindow* g_win_ptr[YK_MAX_WINDOWS];
static int g_win_count;

static void put_window(jlong key, ANativeWindow* win) {
    int i;
    for (i = 0; i < g_win_count; i++) {
        if (g_win_key[i] == key) {
            if (g_win_ptr[i] != 0) ANativeWindow_release(g_win_ptr[i]);
            g_win_ptr[i] = win;
            return;
        }
    }
    if (g_win_count < YK_MAX_WINDOWS) {
        g_win_key[g_win_count] = key;
        g_win_ptr[g_win_count] = win;
        g_win_count++;
    }
}

static void drop_window(jlong key) {
    int i;
    for (i = 0; i < g_win_count; i++) {
        if (g_win_key[i] == key) {
            if (g_win_ptr[i] != 0) ANativeWindow_release(g_win_ptr[i]);
            g_win_count--;
            g_win_key[i] = g_win_key[g_win_count];
            g_win_ptr[i] = g_win_ptr[g_win_count];
            g_win_key[g_win_count] = 0;
            g_win_ptr[g_win_count] = 0;
            return;
        }
    }
}

/* ---------------- JNI 入口（C 调用约定） ---------------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) reserved;
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_nativeInitAndroidContext(JNIEnv* env, jclass clazz,
                                                              jobject app_context) {
    jobject global;
    (void) clazz;
    load_api_once();
    if (g_api.init_context == 0) return;
    if (app_context == 0) {
        LOGW("nativeInitAndroidContext: null context");
        return;
    }
    /* ndk-context 需要进程级存活的 GlobalRef（音频后端在 create 时消费它）。 */
    global = (*env)->NewGlobalRef(env, app_context);
    if (global == 0) {
        LOGE("nativeInitAndroidContext: NewGlobalRef failed");
        return;
    }
    g_api.init_context((void*) g_java_vm, (void*) global);
    LOGI("ndk-context initialized");
}

JNIEXPORT jlong JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_create(JNIEnv* env, jclass clazz, jobject surface,
                                            jint width_px, jint height_px, jdouble native_scale,
                                            jstring game_dir, jstring nls) {
    ANativeWindow* window;
    const char* dir;
    const char* nls_value;
    void* handle;
    jlong key;
    (void) clazz;
    load_api_once();
    if (g_api.create == 0) {
        LOGE("create: engine symbols unavailable");
        return 0;
    }
    if (surface == 0) {
        LOGE("create: surface is null");
        return 0;
    }
    window = ANativeWindow_fromSurface(env, surface);
    if (window == 0) {
        LOGE("create: ANativeWindow_fromSurface failed");
        return 0;
    }
    dir = (game_dir == 0) ? 0 : (*env)->GetStringUTFChars(env, game_dir, 0);
    if (dir == 0 || dir[0] == 0) {
        LOGE("create: empty game dir");
        if (dir != 0) (*env)->ReleaseStringUTFChars(env, game_dir, dir);
        ANativeWindow_release(window);
        return 0;
    }
    nls_value = (nls == 0) ? 0 : (*env)->GetStringUTFChars(env, nls, 0);
    handle = g_api.create(window,
                          (unsigned int) (width_px > 0 ? width_px : 1),
                          (unsigned int) (height_px > 0 ? height_px : 1),
                          native_scale, dir,
                          (nls_value != 0 && nls_value[0] != 0) ? nls_value : 0);
    if (nls_value != 0) (*env)->ReleaseStringUTFChars(env, nls, nls_value);
    (*env)->ReleaseStringUTFChars(env, game_dir, dir);
    if (handle == 0) {
        LOGE("create: engine returned null handle");
        ANativeWindow_release(window);
        return 0;
    }
    key = (jlong) (intptr_t) handle;
    put_window(key, window);
    return key;
}

JNIEXPORT jint JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_step(JNIEnv* env, jclass clazz, jlong handle, jint dt_ms) {
    (void) env; (void) clazz;
    if (g_api.step == 0 || handle == 0) return 1;
    return g_api.step((void*) (intptr_t) handle, (unsigned int) (dt_ms > 0 ? dt_ms : 0));
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_resize(JNIEnv* env, jclass clazz, jlong handle,
                                            jint width_px, jint height_px) {
    (void) env; (void) clazz;
    if (g_api.resize == 0 || handle == 0) return;
    g_api.resize((void*) (intptr_t) handle,
                 (unsigned int) (width_px > 0 ? width_px : 1),
                 (unsigned int) (height_px > 0 ? height_px : 1));
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setSurface(JNIEnv* env, jclass clazz, jlong handle,
                                                jobject surface, jint width_px, jint height_px) {
    ANativeWindow* window;
    (void) clazz;
    if (g_api.set_surface == 0 || handle == 0) return;
    if (surface == 0) {
        LOGW("setSurface: surface is null (ignored)");
        return;
    }
    window = ANativeWindow_fromSurface(env, surface);
    if (window == 0) {
        LOGE("setSurface: ANativeWindow_fromSurface failed");
        return;
    }
    g_api.set_surface((void*) (intptr_t) handle, window,
                      (unsigned int) (width_px > 0 ? width_px : 1),
                      (unsigned int) (height_px > 0 ? height_px : 1));
    put_window(handle, window);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_touch(JNIEnv* env, jclass clazz, jlong handle, jint phase,
                                           jdouble x_px, jdouble y_px) {
    (void) env; (void) clazz;
    if (g_api.touch == 0 || handle == 0) return;
    g_api.touch((void*) (intptr_t) handle, phase, x_px, y_px);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setTextHidpi(JNIEnv* env, jclass clazz, jlong handle,
                                                  jboolean enabled) {
    (void) env; (void) clazz;
    if (g_api.set_text_hidpi == 0 || handle == 0) return;
    g_api.set_text_hidpi((void*) (intptr_t) handle, enabled == JNI_TRUE ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setTextScale(JNIEnv* env, jclass clazz, jlong handle,
                                                  jfloat scale) {
    (void) env; (void) clazz;
    if (g_api.set_text_scale == 0 || handle == 0) return;
    g_api.set_text_scale((void*) (intptr_t) handle, (float) scale);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setTextLineScale(JNIEnv* env, jclass clazz, jlong handle,
                                                      jfloat scale) {
    (void) env; (void) clazz;
    if (g_api.set_text_line_scale == 0 || handle == 0) return;
    g_api.set_text_line_scale((void*) (intptr_t) handle, (float) scale);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_keyEvent(JNIEnv* env, jclass clazz, jlong handle,
                                              jint vk_code, jint phase) {
    (void) env; (void) clazz;
    if (g_api.key == 0 || handle == 0) return;
    g_api.key((void*) (intptr_t) handle, vk_code, phase);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setSystemFont(JNIEnv* env, jclass clazz, jlong handle,
                                                   jboolean enabled) {
    (void) env; (void) clazz;
    if (g_api.set_system_font == 0 || handle == 0) return;
    g_api.set_system_font((void*) (intptr_t) handle, enabled == JNI_TRUE ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_addFont(JNIEnv* env, jclass clazz, jlong handle,
                                             jstring path) {
    const char* chars;
    int font_id;
    (void) clazz;
    if (g_api.add_font == 0 || handle == 0) return -1;
    chars = (path == 0) ? 0 : (*env)->GetStringUTFChars(env, path, 0);
    if (chars == 0 || chars[0] == 0) {
        if (chars != 0) (*env)->ReleaseStringUTFChars(env, path, chars);
        return -1;
    }
    font_id = g_api.add_font((void*) (intptr_t) handle, chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return (jint) font_id;
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_setForcedFont(JNIEnv* env, jclass clazz, jlong handle,
                                                   jint font_id) {
    (void) env; (void) clazz;
    if (g_api.set_forced_font == 0 || handle == 0) return;
    g_api.set_forced_font((void*) (intptr_t) handle, font_id);
}

JNIEXPORT void JNICALL
Java_com_yuki_yukihub_fvp_NativeRfvp_destroy(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (g_api.destroy == 0 || handle == 0) return;
    g_api.destroy((void*) (intptr_t) handle);
    drop_window(handle);
}

#ifdef __cplusplus
}
#endif