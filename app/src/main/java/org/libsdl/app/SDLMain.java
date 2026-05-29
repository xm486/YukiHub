package org.libsdl.app;

import android.os.Process;
import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
class SDLMain implements Runnable {
    @Override // java.lang.Runnable
    public void run() {
        String mainSharedObject = SDLActivity.mSingleton.getMainSharedObject();
        String mainFunction = SDLActivity.mSingleton.getMainFunction();
        String[] arguments = SDLActivity.mSingleton.getArguments();
        try {
            Process.setThreadPriority(-4);
        } catch (Exception e8) {
            Log.v("SDL", "modify thread properties failed " + e8.toString());
        }
        Log.v("SDL", "Running main function " + mainFunction + " from library " + mainSharedObject);
        SDLActivity.nativeRunMain(mainSharedObject, mainFunction, arguments);
        Log.v("SDL", "Finished main function");
        SDLActivity sDLActivity = SDLActivity.mSingleton;
        if (sDLActivity == null || sDLActivity.isFinishing()) {
            return;
        }
        SDLActivity.mSDLThread = null;
        SDLActivity.mSingleton.finish();
    }
}
