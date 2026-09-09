package com.yuki.yukihub.nextmoe;

import android.content.Context;

/** NextMoe 包内共享的 ApplicationContext 持有者（授权流程开浏览器用）。 */
final class NextMoeContexts {
    private static Context ctx;

    private NextMoeContexts() { }

    static void set(Context c) { ctx = c.getApplicationContext(); }

    static Context app() {
        if (ctx == null) throw new IllegalStateException("NextMoeContexts 未初始化");
        return ctx;
    }
}