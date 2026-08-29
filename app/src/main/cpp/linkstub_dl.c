/*
 * 仅用于链接的占位 libdl.so。
 *
 * 目的：让产物记录 `DT_NEEDED libdl.so`（Android/bionic 里 dlopen 的所在），
 * 同时不引入 glibc 依赖。
 *
 * 为什么需要：单用 -nostdlib 会连 NEEDED 一起去掉，
 * 结果 Android linker 找不到 dlopen 而拒绝加载
 * （logcat: cannot locate symbol "dlopen"）。
 *
 * 这个 stub 不会被打进 APK，只在编译期参与链接。
 */
void dlopen(void) { }
void dlsym(void) { }
void dlerror(void) { }
void dlclose(void) { }