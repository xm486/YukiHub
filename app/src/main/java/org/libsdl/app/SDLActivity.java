package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Hashtable;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class SDLActivity extends Activity implements View.OnSystemUiVisibilityChangeListener {
    static final int COMMAND_CHANGE_TITLE = 1;
    static final int COMMAND_CHANGE_WINDOW_STYLE = 2;
    static final int COMMAND_SET_KEEP_SCREEN_ON = 5;
    static final int COMMAND_TEXTEDIT_HIDE = 3;
    protected static final int COMMAND_USER = 32768;
    private static final int SDL_MAJOR_VERSION = 2;
    private static final int SDL_MICRO_VERSION = 3;
    private static final int SDL_MINOR_VERSION = 26;
    protected static final int SDL_ORIENTATION_LANDSCAPE = 1;
    protected static final int SDL_ORIENTATION_LANDSCAPE_FLIPPED = 2;
    protected static final int SDL_ORIENTATION_PORTRAIT = 3;
    protected static final int SDL_ORIENTATION_PORTRAIT_FLIPPED = 4;
    protected static final int SDL_ORIENTATION_UNKNOWN = 0;
    private static final int SDL_SYSTEM_CURSOR_ARROW = 0;
    private static final int SDL_SYSTEM_CURSOR_CROSSHAIR = 3;
    private static final int SDL_SYSTEM_CURSOR_HAND = 11;
    private static final int SDL_SYSTEM_CURSOR_IBEAM = 1;
    private static final int SDL_SYSTEM_CURSOR_NO = 10;
    private static final int SDL_SYSTEM_CURSOR_SIZEALL = 9;
    private static final int SDL_SYSTEM_CURSOR_SIZENESW = 6;
    private static final int SDL_SYSTEM_CURSOR_SIZENS = 8;
    private static final int SDL_SYSTEM_CURSOR_SIZENWSE = 5;
    private static final int SDL_SYSTEM_CURSOR_SIZEWE = 7;
    private static final int SDL_SYSTEM_CURSOR_WAIT = 2;
    private static final int SDL_SYSTEM_CURSOR_WAITARROW = 4;
    private static final String TAG = "SDL";
    public static boolean mBrokenLibraries;
    protected static SDLClipboardHandler mClipboardHandler;
    protected static Locale mCurrentLocale;
    public static NativeState mCurrentNativeState;
    protected static int mCurrentOrientation;
    protected static Hashtable<Integer, PointerIcon> mCursors;
    protected static boolean mFullscreenModeActive;
    protected static HIDDeviceManager mHIDDeviceManager;
    public static boolean mHasFocus;
    public static final boolean mHasMultiWindow;
    public static boolean mIsResumedCalled;
    protected static int mLastCursorID;
    protected static ViewGroup mLayout;
    protected static SDLGenericMotionListener_API12 mMotionListener;
    public static NativeState mNextNativeState;
    protected static Thread mSDLThread;
    protected static boolean mScreenKeyboardShown;
    protected static SDLActivity mSingleton;
    protected static SDLSurface mSurface;
    protected static DummyEdit mTextEdit;
    Handler commandHandler = new SDLCommandHandler();
    protected final int[] messageboxSelection = new int[1];
    private final Runnable rehideSystemUi = new Runnable() { // from class: org.libsdl.app.SDLActivity.7
        @Override // java.lang.Runnable
        public void run() {
            SDLActivity.this.getWindow().getDecorView().setSystemUiVisibility(5894);
        }
    };

    public enum NativeState {
        INIT,
        RESUMED,
        PAUSED
    }

    public static class SDLCommandHandler extends Handler {
        @Override // android.os.Handler
        public void handleMessage(Message message) {
            Window window;
            Context context = SDL.getContext();
            if (context == null) {
                Log.e(SDLActivity.TAG, "error handling message, getContext() returned null");
                return;
            }
            int i8 = message.arg1;
            if (i8 == 1) {
                if (context instanceof Activity) {
                    ((Activity) context).setTitle((String) message.obj);
                    return;
                } else {
                    Log.e(SDLActivity.TAG, "error handling message, getContext() returned no Activity");
                    return;
                }
            }
            if (i8 == 2) {
                if (!(context instanceof Activity)) {
                    Log.e(SDLActivity.TAG, "error handling message, getContext() returned no Activity");
                    return;
                }
                Window window2 = ((Activity) context).getWindow();
                if (window2 != null) {
                    Object obj = message.obj;
                    if (!(obj instanceof Integer) || ((Integer) obj).intValue() == 0) {
                        window2.getDecorView().setSystemUiVisibility(256);
                        window2.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                        window2.clearFlags(1024);
                        SDLActivity.mFullscreenModeActive = false;
                        return;
                    }
                    window2.getDecorView().setSystemUiVisibility(5894);
                    window2.addFlags(1024);
                    window2.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    SDLActivity.mFullscreenModeActive = true;
                    return;
                }
                return;
            }
            if (i8 == 3) {
                DummyEdit dummyEdit = SDLActivity.mTextEdit;
                if (dummyEdit != null) {
                    dummyEdit.setLayoutParams(new RelativeLayout.LayoutParams(0, 0));
                    ((InputMethodManager) context.getSystemService("input_method")).hideSoftInputFromWindow(SDLActivity.mTextEdit.getWindowToken(), 0);
                    SDLActivity.mScreenKeyboardShown = false;
                    SDLActivity.mSurface.requestFocus();
                    return;
                }
                return;
            }
            if (i8 != 5) {
                if (!(context instanceof SDLActivity) || ((SDLActivity) context).onUnhandledMessage(i8, message.obj)) {
                    return;
                }
                Log.e(SDLActivity.TAG, "error handling message, command is " + message.arg1);
                return;
            }
            if (!(context instanceof Activity) || (window = ((Activity) context).getWindow()) == null) {
                return;
            }
            Object obj2 = message.obj;
            if (!(obj2 instanceof Integer) || ((Integer) obj2).intValue() == 0) {
                window.clearFlags(128);
            } else {
                window.addFlags(128);
            }
        }
    }

    public static class ShowTextInputTask implements Runnable {
        static final int HEIGHT_PADDING = 15;

        /* JADX INFO: renamed from: h, reason: collision with root package name */
        public int f19621h;

        /* JADX INFO: renamed from: w, reason: collision with root package name */
        public int f19622w;

        /* JADX INFO: renamed from: x, reason: collision with root package name */
        public int f19623x;

        /* JADX INFO: renamed from: y, reason: collision with root package name */
        public int f19624y;

        public ShowTextInputTask(int i8, int i9, int i10, int i11) {
            this.f19623x = i8;
            this.f19624y = i9;
            this.f19622w = i10;
            this.f19621h = i11;
            if (i10 <= 0) {
                this.f19622w = 1;
            }
            if (i11 + 15 <= 0) {
                this.f19621h = -14;
            }
        }

        @Override // java.lang.Runnable
        public void run() {
            RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(this.f19622w, this.f19621h + 15);
            layoutParams.leftMargin = this.f19623x;
            layoutParams.topMargin = this.f19624y;
            DummyEdit dummyEdit = SDLActivity.mTextEdit;
            if (dummyEdit == null) {
                SDLActivity.mTextEdit = new DummyEdit(SDL.getContext());
                SDLActivity.mLayout.addView(SDLActivity.mTextEdit, layoutParams);
            } else {
                dummyEdit.setLayoutParams(layoutParams);
            }
            SDLActivity.mTextEdit.setVisibility(0);
            SDLActivity.mTextEdit.requestFocus();
            ((InputMethodManager) SDL.getContext().getSystemService("input_method")).showSoftInput(SDLActivity.mTextEdit, 0);
            SDLActivity.mScreenKeyboardShown = true;
        }
    }

    static {
        mHasMultiWindow = Build.VERSION.SDK_INT >= 24;
        mBrokenLibraries = true;
    }

    public static String clipboardGetText() {
        return mClipboardHandler.clipboardGetText();
    }

    public static boolean clipboardHasText() {
        return mClipboardHandler.clipboardHasText();
    }

    public static void clipboardSetText(String str) {
        mClipboardHandler.clipboardSetText(str);
    }

    public static int createCustomCursor(int[] iArr, int i8, int i9, int i10, int i11) {
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iArr, i8, i9, Bitmap.Config.ARGB_8888);
        mLastCursorID++;
        if (Build.VERSION.SDK_INT < 24) {
            return 0;
        }
        try {
            mCursors.put(Integer.valueOf(mLastCursorID), PointerIcon.create(bitmapCreateBitmap, i10, i11));
            return mLastCursorID;
        } catch (Exception unused) {
            return 0;
        }
    }

    public static void destroyCustomCursor(int i8) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                mCursors.remove(Integer.valueOf(i8));
            } catch (Exception unused) {
            }
        }
    }

    public static View getContentView() {
        return mLayout;
    }

    public static Context getContext() {
        return SDL.getContext();
    }

    public static int getCurrentOrientation() {
        Activity activity = (Activity) getContext();
        if (activity == null) {
            return 0;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == 0) {
            return 3;
        }
        if (rotation == 1) {
            return 1;
        }
        if (rotation != 2) {
            return rotation != 3 ? 0 : 2;
        }
        return 4;
    }

    public static double getDiagonal() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        Activity activity = (Activity) getContext();
        if (activity == null) {
            return 0.0d;
        }
        activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        double d8 = ((double) displayMetrics.widthPixels) / ((double) displayMetrics.xdpi);
        double d9 = ((double) displayMetrics.heightPixels) / ((double) displayMetrics.ydpi);
        return Math.sqrt((d9 * d9) + (d8 * d8));
    }

    public static DisplayMetrics getDisplayDPI() {
        return getContext().getResources().getDisplayMetrics();
    }

    public static boolean getManifestEnvironmentVariables() {
        Bundle bundle;
        try {
            if (getContext() == null || (bundle = getContext().getPackageManager().getApplicationInfo(getContext().getPackageName(), 128).metaData) == null) {
                return false;
            }
            for (String str : bundle.keySet()) {
                if (str.startsWith("SDL_ENV.")) {
                    nativeSetenv(str.substring(8), bundle.get(str).toString());
                }
            }
            return true;
        } catch (Exception e8) {
            Log.v(TAG, "exception " + e8.toString());
            return false;
        }
    }

    public static SDLGenericMotionListener_API12 getMotionListener() {
        if (mMotionListener == null) {
            int i8 = Build.VERSION.SDK_INT;
            if (i8 >= SDL_MINOR_VERSION) {
                mMotionListener = new SDLGenericMotionListener_API26();
            } else if (i8 >= 24) {
                mMotionListener = new SDLGenericMotionListener_API24();
            } else {
                mMotionListener = new SDLGenericMotionListener_API12();
            }
        }
        return mMotionListener;
    }

    public static Surface getNativeSurface() {
        SDLSurface sDLSurface = mSurface;
        if (sDLSurface == null) {
            return null;
        }
        return sDLSurface.getNativeSurface();
    }

    public static boolean handleKeyEvent(View view, int i8, KeyEvent keyEvent, InputConnection inputConnection) {
        InputDevice device;
        int deviceId = keyEvent.getDeviceId();
        int source = keyEvent.getSource();
        if (source == 0 && (device = InputDevice.getDevice(deviceId)) != null) {
            source = device.getSources();
        }
        if (SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            if (keyEvent.getAction() == 0) {
                if (SDLControllerManager.onNativePadDown(deviceId, i8) == 0) {
                    return true;
                }
            } else if (keyEvent.getAction() == 1 && SDLControllerManager.onNativePadUp(deviceId, i8) == 0) {
                return true;
            }
        }
        if ((source & 257) == 257) {
            if (keyEvent.getAction() == 0) {
                if (isTextInputEvent(keyEvent)) {
                    if (inputConnection != null) {
                        inputConnection.commitText(String.valueOf((char) keyEvent.getUnicodeChar()), 1);
                    } else {
                        SDLInputConnection.nativeCommitText(String.valueOf((char) keyEvent.getUnicodeChar()), 1);
                    }
                }
                onNativeKeyDown(i8);
                return true;
            }
            if (keyEvent.getAction() == 1) {
                onNativeKeyUp(i8);
                return true;
            }
        }
        if ((source & 8194) != 8194) {
            return false;
        }
        if (i8 != 4 && i8 != 125) {
            return false;
        }
        int action = keyEvent.getAction();
        return action == 0 || action == 1;
    }

    public static void handleNativeState() {
        NativeState nativeState = mNextNativeState;
        if (nativeState == mCurrentNativeState) {
            return;
        }
        if (nativeState == NativeState.INIT) {
            mCurrentNativeState = mNextNativeState;
            return;
        }
        if (mNextNativeState == NativeState.PAUSED) {
            if (mSDLThread != null) {
                nativePause();
            }
            SDLSurface sDLSurface = mSurface;
            if (sDLSurface != null) {
                sDLSurface.handlePause();
            }
            mCurrentNativeState = mNextNativeState;
            return;
        }
        if (mNextNativeState == NativeState.RESUMED && mSurface.mIsSurfaceReady && mHasFocus && mIsResumedCalled) {
            if (mSDLThread == null) {
                mSDLThread = new Thread(new SDLMain(), "SDLThread");
                mSurface.enableSensor(1, true);
                mSDLThread.start();
            } else {
                nativeResume();
            }
            mSurface.handleResume();
            mCurrentNativeState = mNextNativeState;
        }
    }

    public static void initTouch() {
        for (int i8 : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(i8);
            if (device != null && ((device.getSources() & 4098) == 4098 || device.isVirtual())) {
                int id = device.getId();
                if (id < 0) {
                    id--;
                }
                nativeAddTouch(id, device.getName());
            }
        }
    }

    public static void initialize() {
        mSingleton = null;
        mSurface = null;
        mTextEdit = null;
        mLayout = null;
        mClipboardHandler = null;
        mCursors = new Hashtable<>();
        mLastCursorID = 0;
        mSDLThread = null;
        mIsResumedCalled = false;
        mHasFocus = true;
        NativeState nativeState = NativeState.INIT;
        mNextNativeState = nativeState;
        mCurrentNativeState = nativeState;
    }

    public static boolean isAndroidTV() {
        if (((UiModeManager) getContext().getSystemService("uimode")).getCurrentModeType() == 4) {
            return true;
        }
        String str = Build.MANUFACTURER;
        if (str.equals("MINIX") && Build.MODEL.equals("NEO-U1")) {
            return true;
        }
        if (str.equals("Amlogic") && Build.MODEL.equals("X96-W")) {
            return true;
        }
        return str.equals("Amlogic") && Build.MODEL.startsWith("TV");
    }

    public static boolean isChromebook() {
        if (getContext() == null) {
            return false;
        }
        return getContext().getPackageManager().hasSystemFeature("org.chromium.arc.device_management");
    }

    public static boolean isDeXMode() {
        if (Build.VERSION.SDK_INT < 24 || getContext() == null) {
            return false;
        }
        try {
            Configuration configuration = getContext().getResources().getConfiguration();
            Class<?> cls = configuration.getClass();
            int enabled = cls.getField("SEM_DESKTOP_MODE_ENABLED").getInt(cls);
            int current = cls.getField("semDesktopModeEnabled").getInt(configuration);
            return enabled == current;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isScreenKeyboardShown() {
        if (mTextEdit != null && mScreenKeyboardShown) {
            return ((InputMethodManager) SDL.getContext().getSystemService("input_method")).isAcceptingText();
        }
        return false;
    }

    public static boolean isTablet() {
        return getDiagonal() >= 7.0d;
    }

    public static boolean isTextInputEvent(KeyEvent keyEvent) {
        if (keyEvent.isCtrlPressed()) {
            return false;
        }
        return keyEvent.isPrintingKey() || keyEvent.getKeyCode() == 62;
    }

    public static void manualBackButton() {
        mSingleton.pressBackButton();
    }

    public static void minimizeWindow() {
        if (mSingleton == null) {
            return;
        }
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.HOME");
        intent.setFlags(268435456);
        mSingleton.startActivity(intent);
    }

    public static native void nativeAddTouch(int i8, String str);

    public static native void nativeFocusChanged(boolean z);

    public static native String nativeGetHint(String str);

    public static native boolean nativeGetHintBoolean(String str, boolean z);

    public static native String nativeGetVersion();

    public static native void nativeLowMemory();

    public static native void nativePause();

    public static native void nativePermissionResult(int i8, boolean z);

    public static native void nativeQuit();

    public static native void nativeResume();

    public static native int nativeRunMain(String str, String str2, Object obj);

    public static native void nativeSendQuit();

    public static native void nativeSetScreenResolution(int i8, int i9, int i10, int i11, float f8);

    public static native void nativeSetenv(String str, String str2);

    public static native int nativeSetupJNI();

    public static native void onNativeAccel(float f8, float f9, float f10);

    public static native void onNativeClipboardChanged();

    public static native void onNativeDropFile(String str);

    public static native void onNativeKeyDown(int i8);

    public static native void onNativeKeyUp(int i8);

    public static native void onNativeKeyboardFocusLost();

    public static native void onNativeLocaleChanged();

    public static native void onNativeMouse(int i8, int i9, float f8, float f9, boolean z);

    public static native void onNativeOrientationChanged(int i8);

    public static native void onNativeResize();

    public static native boolean onNativeSoftReturnKey();

    public static native void onNativeSurfaceChanged();

    public static native void onNativeSurfaceCreated();

    public static native void onNativeSurfaceDestroyed();

    public static native void onNativeTouch(int i8, int i9, int i10, float f8, float f9, float f10);

    public static int openURL(String str) {
        try {
            Intent intent = new Intent("android.intent.action.VIEW");
            intent.setData(Uri.parse(str));
            intent.addFlags(1208483840);
            mSingleton.startActivity(intent);
            return 0;
        } catch (Exception unused) {
            return -1;
        }
    }

    public static void requestPermission(String str, int i8) {
        if (Build.VERSION.SDK_INT < 23) {
            nativePermissionResult(i8, true);
            return;
        }
        Activity activity = (Activity) getContext();
        if (activity.checkSelfPermission(str) != 0) {
            activity.requestPermissions(new String[]{str}, i8);
        } else {
            nativePermissionResult(i8, true);
        }
    }

    public static boolean sendMessage(int i8, int i9) {
        SDLActivity sDLActivity = mSingleton;
        if (sDLActivity == null) {
            return false;
        }
        return sDLActivity.sendCommand(i8, Integer.valueOf(i9));
    }

    public static boolean setActivityTitle(String str) {
        return mSingleton.sendCommand(1, str);
    }

    public static boolean setCustomCursor(int i8) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        try {
            mSurface.setPointerIcon((PointerIcon) mCursors.get(Integer.valueOf(i8)));
            return true;
        } catch (Exception unused) {
            return false;
        }
    }

    public static void setOrientation(int i8, int i9, boolean z, String str) {
        SDLActivity sDLActivity = mSingleton;
        if (sDLActivity != null) {
            sDLActivity.setOrientationBis(i8, i9, z, str);
        }
    }

    public static boolean setRelativeMouseEnabled(boolean z) {
        if (!z || supportsRelativeMouse()) {
            return getMotionListener().setRelativeMouseEnabled(z);
        }
        return false;
    }

    public static boolean setSystemCursor(int i8) {
        int i9 = 1004;
        switch (i8) {
            case 0:
                i9 = 1000;
                break;
            case 1:
                i9 = 1008;
                break;
            case 2:
            case 4:
                break;
            case 3:
                i9 = 1007;
                break;
            case 5:
                i9 = 1017;
                break;
            case 6:
                i9 = 1015;
                break;
            case 7:
                i9 = 1004;
                break;
            case 8:
                i9 = 1014;
                break;
            case 9:
                i9 = 1021;
                break;
            case 10:
                i9 = 1012;
                break;
            case SDL_SYSTEM_CURSOR_HAND /* 11 */:
                i9 = 1002;
                break;
            default:
                i9 = 0;
                break;
        }
        if (Build.VERSION.SDK_INT < 24) {
            return true;
        }
        try {
            mSurface.setPointerIcon(PointerIcon.getSystemIcon(SDL.getContext(), i9));
            return true;
        } catch (Exception unused) {
            return false;
        }
    }

    public static void setWindowStyle(boolean z) {
        mSingleton.sendCommand(2, Integer.valueOf(z ? 1 : 0));
    }

    public static boolean shouldMinimizeOnFocusLoss() {
        return false;
    }

    public static boolean showTextInput(int i8, int i9, int i10, int i11) {
        return mSingleton.commandHandler.post(new ShowTextInputTask(i8, i9, i10, i11));
    }

    public static int showToast(String str, int i8, int i9, int i10, int i11) {
        SDLActivity sDLActivity = mSingleton;
        if (sDLActivity == null) {
            return -1;
        }
        try {
            final String mMessage = str;
            final int mDuration = i8;
            final int mGravity = i9;
            final int mXOffset = i10;
            final int mYOffset = i11;
            sDLActivity.runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        Toast toastMakeText = Toast.makeText(SDLActivity.mSingleton, mMessage, mDuration);
                        if (mGravity >= 0) {
                            toastMakeText.setGravity(mGravity, mXOffset, mYOffset);
                        }
                        toastMakeText.show();
                    } catch (Exception e8) {
                        Log.e(SDLActivity.TAG, e8.getMessage());
                    }
                }
            });
            return 0;
        } catch (Exception unused) {
            return -1;
        }
    }

    public static boolean supportsRelativeMouse() {
        if (Build.VERSION.SDK_INT >= 27 || !isDeXMode()) {
            return getMotionListener().supportsRelativeMouse();
        }
        return false;
    }

    public SDLSurface createSDLSurface(Context context) {
        return new SDLSurface(context);
    }

    @Override // i.AbstractActivityC1223l, n1.j, android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent keyEvent) {
        int keyCode;
        if (mBrokenLibraries || (keyCode = keyEvent.getKeyCode()) == 25 || keyCode == 24 || keyCode == 27 || keyCode == 168 || keyCode == 169) {
            return false;
        }
        return super.dispatchKeyEvent(keyEvent);
    }

    public String[] getArguments() {
        return new String[0];
    }

    public String[] getLibraries() {
        return new String[]{"SDL2", "main"};
    }

    public String getMainFunction() {
        return "SDL_main";
    }

    public String getMainSharedObject() {
        SDLActivity activity = mSingleton == null ? this : mSingleton;
String[] libraries = activity.getLibraries();
return getContext().getApplicationInfo().nativeLibraryDir + "/" + (libraries.length > 0 ? "lib" + libraries[libraries.length - 1] + ".so" : "libmain.so");
    }

    public void loadLibraries() {
        for (String str : getLibraries()) {
            SDL.loadLibrary(str);
        }
    }

    public void messageboxCreateAndShow(Bundle bundle) {
        int i8;
        int i9;
        int i10;
        int[] intArray = bundle.getIntArray("colors");
        if (intArray != null) {
            i8 = intArray[0];
            i9 = intArray[1];
            int i11 = intArray[2];
            i10 = intArray[3];
            int i12 = intArray[4];
        } else {
            i8 = 0;
            i9 = 0;
            i10 = 0;
        }
        final AlertDialog alertDialogCreate = new AlertDialog.Builder(this).create();
        alertDialogCreate.setTitle(bundle.getString("title"));
        alertDialogCreate.setCancelable(false);
        alertDialogCreate.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: org.libsdl.app.SDLActivity.4
            @Override // android.content.DialogInterface.OnDismissListener
            public void onDismiss(DialogInterface dialogInterface) {
                synchronized (SDLActivity.this.messageboxSelection) {
                    SDLActivity.this.messageboxSelection.notify();
                }
            }
        });
        TextView textView = new TextView(this);
        textView.setGravity(17);
        textView.setText(bundle.getString("message"));
        if (i9 != 0) {
            textView.setTextColor(i9);
        }
        int[] intArray2 = bundle.getIntArray("buttonFlags");
        int[] intArray3 = bundle.getIntArray("buttonIds");
        String[] stringArray = bundle.getStringArray("buttonTexts");
        final SparseArray sparseArray = new SparseArray();
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(17);
        for (int i13 = 0; i13 < stringArray.length; i13++) {
            Button button = new Button(this);
            final int i14 = intArray3[i13];
            button.setOnClickListener(new View.OnClickListener() { // from class: org.libsdl.app.SDLActivity.5
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    SDLActivity.this.messageboxSelection[0] = i14;
                    alertDialogCreate.dismiss();
                }
            });
            int i15 = intArray2[i13];
            if (i15 != 0) {
                if ((i15 & 1) != 0) {
                    sparseArray.put(66, button);
                }
                if ((intArray2[i13] & 2) != 0) {
                    sparseArray.put(111, button);
                }
            }
            button.setText(stringArray[i13]);
            if (i9 != 0) {
                button.setTextColor(i9);
            }
            if (i10 != 0) {
                Drawable background = button.getBackground();
                if (background == null) {
                    button.setBackgroundColor(i10);
                } else {
                    background.setColorFilter(i10, PorterDuff.Mode.MULTIPLY);
                }
            }
            linearLayout.addView(button);
        }
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.addView(textView);
        linearLayout2.addView(linearLayout);
        if (i8 != 0) {
            linearLayout2.setBackgroundColor(i8);
        }
        alertDialogCreate.setView(linearLayout2);
        alertDialogCreate.setOnKeyListener(new DialogInterface.OnKeyListener() { // from class: org.libsdl.app.SDLActivity.6
            @Override // android.content.DialogInterface.OnKeyListener
            public boolean onKey(DialogInterface dialogInterface, int i16, KeyEvent keyEvent) {
                Button button2 = (Button) sparseArray.get(i16);
                if (button2 == null) {
                    return false;
                }
                if (keyEvent.getAction() == 1) {
                    button2.performClick();
                }
                return true;
            }
        });
        alertDialogCreate.show();
    }

    public int messageboxShowMessageBox(int i8, String str, String str2, int[] iArr, int[] iArr2, String[] strArr, int[] iArr3) {
        this.messageboxSelection[0] = -1;
        if (iArr.length != iArr2.length && iArr2.length != strArr.length) {
            return -1;
        }
        final Bundle bundle = new Bundle();
        bundle.putInt("flags", i8);
        bundle.putString("title", str);
        bundle.putString("message", str2);
        bundle.putIntArray("buttonFlags", iArr);
        bundle.putIntArray("buttonIds", iArr2);
        bundle.putStringArray("buttonTexts", strArr);
        bundle.putIntArray("colors", iArr3);
        runOnUiThread(new Runnable() { // from class: org.libsdl.app.SDLActivity.3
            @Override // java.lang.Runnable
            public void run() {
                SDLActivity.this.messageboxCreateAndShow(bundle);
            }
        });
        synchronized (this.messageboxSelection) {
            try {
                this.messageboxSelection.wait();
            } catch (InterruptedException e8) {
                e8.printStackTrace();
                return -1;
            }
        }
        return this.messageboxSelection[0];
    }

    @Override // b.AbstractActivityC0818o, android.app.Activity
    public void onBackPressed() {
        if (nativeGetHintBoolean("SDL_ANDROID_TRAP_BACK_BUTTON", false) || isFinishing()) {
            return;
        }
        super.onBackPressed();
    }

    @Override // i.AbstractActivityC1223l, b.AbstractActivityC0818o, android.app.Activity, android.content.ComponentCallbacks
    public void onConfigurationChanged(Configuration configuration) {
        Log.v(TAG, "onConfigurationChanged()");
        super.onConfigurationChanged(configuration);
        if (mBrokenLibraries) {
            return;
        }
        Locale locale = mCurrentLocale;
        if (locale == null || !locale.equals(configuration.locale)) {
            mCurrentLocale = configuration.locale;
            onNativeLocaleChanged();
        }
    }

    @Override // androidx.fragment.app.B, b.AbstractActivityC0818o, n1.j, android.app.Activity
    public void onCreate(Bundle bundle) {
        String message;
        String path;
        Log.v(TAG, "Device: " + Build.DEVICE);
        Log.v(TAG, "Model: " + Build.MODEL);
        Log.v(TAG, "onCreate()");
        super.onCreate(bundle);
        try {
            Thread.currentThread().setName("SDLActivity");
        } catch (Exception e8) {
            Log.v(TAG, "modify thread properties failed " + e8.toString());
        }
        try {
            loadLibraries();
            mBrokenLibraries = false;
            message = "";
        } catch (Exception e9) {
            System.err.println(e9.getMessage());
            mBrokenLibraries = true;
            message = e9.getMessage();
        } catch (UnsatisfiedLinkError e10) {
            System.err.println(e10.getMessage());
            mBrokenLibraries = true;
            message = e10.getMessage();
        }
        if (!mBrokenLibraries) {
            String str = String.valueOf(2) + "." + String.valueOf(SDL_MINOR_VERSION) + "." + String.valueOf(3);
            String strNativeGetVersion = nativeGetVersion();
            if (!strNativeGetVersion.equals(str)) {
                mBrokenLibraries = true;
                message = "SDL C/Java version mismatch (expected " + str + ", got " + strNativeGetVersion + ")";
            }
        }
        if (mBrokenLibraries) {
            mSingleton = this;
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("An error occurred while trying to start the application. Please try again and/or reinstall." + System.getProperty("line.separator") + System.getProperty("line.separator") + "Error: " + message);
            builder.setTitle("SDL Error");
            builder.setPositiveButton("Exit", new DialogInterface.OnClickListener() { // from class: org.libsdl.app.SDLActivity.1
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i8) {
                    SDLActivity.mSingleton.finish();
                }
            });
            builder.setCancelable(false);
            builder.create().show();
            return;
        }
        SDL.setupJNI();
        SDL.initialize();
        mSingleton = this;
        SDL.setContext(this);
        mClipboardHandler = new SDLClipboardHandler();
        mHIDDeviceManager = HIDDeviceManager.acquire(this);
        mSurface = createSDLSurface(getApplication());
        RelativeLayout relativeLayout = new RelativeLayout(this);
        mLayout = relativeLayout;
        relativeLayout.addView(mSurface);
        int currentOrientation = getCurrentOrientation();
        mCurrentOrientation = currentOrientation;
        onNativeOrientationChanged(currentOrientation);
        try {
            if (Build.VERSION.SDK_INT < 24) {
                mCurrentLocale = getContext().getResources().getConfiguration().locale;
            } else {
                mCurrentLocale = getContext().getResources().getConfiguration().getLocales().get(0);
            }
        } catch (Exception unused) {
        }
        setContentView(mLayout);
        setWindowStyle(false);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);
        Intent intent = getIntent();
        if (intent == null || intent.getData() == null || (path = intent.getData().getPath()) == null) {
            return;
        }
        Log.v(TAG, "Got filename: ".concat(path));
        onNativeDropFile(path);
    }

    @Override // i.AbstractActivityC1223l, androidx.fragment.app.B, android.app.Activity
    public void onDestroy() {
        Log.v(TAG, "onDestroy()");
        HIDDeviceManager hIDDeviceManager = mHIDDeviceManager;
        if (hIDDeviceManager != null) {
            HIDDeviceManager.release(hIDDeviceManager);
            mHIDDeviceManager = null;
        }
        if (mBrokenLibraries) {
            super.onDestroy();
            return;
        }
        if (mSDLThread != null) {
            nativeSendQuit();
            try {
                mSDLThread.join();
            } catch (Exception e8) {
                Log.v(TAG, "Problem stopping SDLThread: " + e8);
            }
        }
        nativeQuit();
        super.onDestroy();
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks
    public void onLowMemory() {
        Log.v(TAG, "onLowMemory()");
        super.onLowMemory();
        if (mBrokenLibraries) {
            return;
        }
        nativeLowMemory();
    }

    @Override // androidx.fragment.app.B, android.app.Activity
    public void onPause() {
        Log.v(TAG, "onPause()");
        super.onPause();
        HIDDeviceManager hIDDeviceManager = mHIDDeviceManager;
        if (hIDDeviceManager != null) {
            hIDDeviceManager.setFrozen(true);
        }
        if (mHasMultiWindow) {
            return;
        }
        pauseNativeThread();
    }

    @Override // androidx.fragment.app.B, b.AbstractActivityC0818o, android.app.Activity, n1.InterfaceC1599c
    public void onRequestPermissionsResult(int i8, String[] strArr, int[] iArr) {
        boolean z = false;
        if (iArr.length > 0 && iArr[0] == 0) {
            z = true;
        }
        nativePermissionResult(i8, z);
        super.onRequestPermissionsResult(i8, strArr, iArr);
    }

    @Override // androidx.fragment.app.B, android.app.Activity
    public void onResume() {
        Log.v(TAG, "onResume()");
        super.onResume();
        HIDDeviceManager hIDDeviceManager = mHIDDeviceManager;
        if (hIDDeviceManager != null) {
            hIDDeviceManager.setFrozen(false);
        }
        if (mHasMultiWindow) {
            return;
        }
        resumeNativeThread();
    }

    @Override // i.AbstractActivityC1223l, androidx.fragment.app.B, android.app.Activity
    public void onStart() {
        Log.v(TAG, "onStart()");
        super.onStart();
        if (mHasMultiWindow) {
            resumeNativeThread();
        }
    }

    @Override // i.AbstractActivityC1223l, androidx.fragment.app.B, android.app.Activity
    public void onStop() {
        Log.v(TAG, "onStop()");
        super.onStop();
        if (mHasMultiWindow) {
            pauseNativeThread();
        }
    }

    @Override // android.view.View.OnSystemUiVisibilityChangeListener
    public void onSystemUiVisibilityChange(int i8) {
        Handler handler;
        if (mFullscreenModeActive) {
            if (((i8 & 4) == 0 || (i8 & 2) == 0) && (handler = getWindow().getDecorView().getHandler()) != null) {
                handler.removeCallbacks(this.rehideSystemUi);
                handler.postDelayed(this.rehideSystemUi, 2000L);
            }
        }
    }

    public boolean onUnhandledMessage(int i8, Object obj) {
        return false;
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public void onWindowFocusChanged(boolean z) {
        super.onWindowFocusChanged(z);
        Log.v(TAG, "onWindowFocusChanged(): " + z);
        if (mBrokenLibraries) {
            return;
        }
        mHasFocus = z;
        if (z) {
            mNextNativeState = NativeState.RESUMED;
            getMotionListener().reclaimRelativeMouseModeIfNeeded();
            handleNativeState();
            nativeFocusChanged(true);
            return;
        }
        nativeFocusChanged(false);
        if (mHasMultiWindow) {
            return;
        }
        mNextNativeState = NativeState.PAUSED;
        handleNativeState();
    }

    public void pauseNativeThread() {
        mNextNativeState = NativeState.PAUSED;
        mIsResumedCalled = false;
        if (mBrokenLibraries) {
            return;
        }
        handleNativeState();
    }

    public void pressBackButton() {
        runOnUiThread(new Runnable() { // from class: org.libsdl.app.SDLActivity.2
            @Override // java.lang.Runnable
            public void run() {
                if (SDLActivity.this.isFinishing()) {
                    return;
                }
                SDLActivity.this.superOnBackPressed();
            }
        });
    }

    public void resumeNativeThread() {
        mNextNativeState = NativeState.RESUMED;
        mIsResumedCalled = true;
        if (mBrokenLibraries) {
            return;
        }
        handleNativeState();
    }

    public boolean sendCommand(int i8, Object obj) {
        Message messageObtainMessage = this.commandHandler.obtainMessage();
        messageObtainMessage.arg1 = i8;
        messageObtainMessage.obj = obj;
        boolean zSendMessage = this.commandHandler.sendMessage(messageObtainMessage);
        if (i8 == 2) {
            boolean z = false;
            if (obj instanceof Integer) {
                Display defaultDisplay = ((WindowManager) getSystemService("window")).getDefaultDisplay();
                DisplayMetrics displayMetrics = new DisplayMetrics();
                defaultDisplay.getRealMetrics(displayMetrics);
                if (displayMetrics.widthPixels == mSurface.getWidth() && displayMetrics.heightPixels == mSurface.getHeight()) {
                    z = true;
                }
                if (((Integer) obj).intValue() == 1) {
                    z = !z;
                }
            }
            if (z && getContext() != null) {
                synchronized (getContext()) {
                    try {
                        getContext().wait(500L);
                    } catch (InterruptedException e8) {
                        e8.printStackTrace();
                    }
                }
            }
        }
        return zSendMessage;
    }

    /* JADX WARN: Removed duplicated region for block: B:46:0x006e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public void setOrientationBis(int i8, int i9, boolean z, String str) {
        int i10 = (str.contains("LandscapeRight") && str.contains("LandscapeLeft")) ? 6 : str.contains("LandscapeRight") ? 0 : str.contains("LandscapeLeft") ? 8 : -1;
        int i11 = (str.contains("Portrait") && str.contains("PortraitUpsideDown")) ? 7 : str.contains("Portrait") ? 1 : str.contains("PortraitUpsideDown") ? 9 : -1;
        boolean z8 = i10 != -1;
        boolean z9 = i11 != -1;
        int i12 = 10;
        if (z9 || z8) {
            if (!z) {
                if (!z9 || !z8 ? !z8 : i8 <= i9) {
                }
                i12 = i10;
            } else if (!z9 || !z8) {
                if (!z8) {
                    i10 = i11;
                }
                i12 = i10;
            }
        } else if (!z) {
            i12 = i8 <= i9 ? 7 : 6;
        }
        StringBuilder sbT = new StringBuilder();
        sbT.append("setOrientation() requestedOrientation=").append(i12).append(" width=").append(i8).append(" height=").append(i9);
        sbT.append(" resizable=");
        sbT.append(z);
        sbT.append(" hint=");
        sbT.append(str);
        Log.v(TAG, sbT.toString());
        mSingleton.setRequestedOrientation(i12);
    }

    public void superOnBackPressed() {
        super.onBackPressed();
    }
}
