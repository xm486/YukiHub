package com.yuki.yukihub;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.akira.tyranoemu.remote.ArtemisActivityV1;
import com.akira.tyranoemu.remote.ArtemisActivityV2;
import com.akira.tyranoemu.remote.ArtemisActivityV3;
import com.akira.tyranoemu.remote.Kirikiroid134;
import com.akira.tyranoemu.remote.Kirikiroid139;
import com.yuki.yukihub.util.CutoutCompat;
import com.yuki.yukihub.util.UiScaleUtil;

public class YukiHubApp extends Application {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScaleUtil.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 引擎 Activity 的宿主基类在编译依赖中（final 类无法继承），这里通过全局生命周期
        // 回调在引擎 Activity 创建/恢复时注入刘海挖孔绘制模式，按用户设置分别控制 KRKR / Artemis。
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { injectEngineCutout(activity); }
            @Override public void onActivityResumed(Activity activity) { injectEngineCutout(activity); }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private void injectEngineCutout(Activity activity) {
        try {
            boolean isKrkr = activity instanceof Kirikiroid134 || activity instanceof Kirikiroid139;
            boolean isArtemis = activity instanceof ArtemisActivityV1 || activity instanceof ArtemisActivityV2 || activity instanceof ArtemisActivityV3;
            if (!isKrkr && !isArtemis) return;
            SharedPreferences sp = getSharedPreferences("yukihub_prefs", MODE_PRIVATE);
            boolean allow = sp.getBoolean(isKrkr ? MainActivity.KEY_KR_DRAW_CUTOUT : MainActivity.KEY_ARTEMIS_DRAW_CUTOUT, true);
            CutoutCompat.setCutoutMode(activity.getWindow(), allow);
        } catch (Throwable ignored) { }
    }
}