/*
 * 仅用于链接的占位 libandroid.so。
 *
 * ANativeWindow_fromSurface / ANativeWindow_release 在 Android 上由
 * libandroid.so 提供（bionic 侧）。宿主环境没有 Android 的 libandroid.so，
 * 所以链接期用这个空壳让它记下 `DT_NEEDED libandroid.so`，
 * 真机上由系统提供真实实现。
 *
 * 与 linkstub_dl.c / linkstub_log.c 同一套路，只在编译期参与链接，不进 APK。
 */
void* ANativeWindow_fromSurface(void* env, void* surface) { (void) env; (void) surface; return 0; }
void ANativeWindow_release(void* window) { (void) window; }
void ANativeWindow_acquire(void* window) { (void) window; }
int ANativeWindow_getWidth(void* window) { (void) window; return 0; }
int ANativeWindow_getHeight(void* window) { (void) window; return 0; }