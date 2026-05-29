package com.bytedance.shadowhook;

public final class ShadowHook {
    public static final int MODE_UNIQUE = 0;
    public static final int MODE_SHARED = 1;
    public static final int MODE_AUTOMATIC = 2;
    public static final int MODE_DISABLED = 3;

    private ShadowHook() {}

    public static void init() {}
    public static void init(int mode, boolean debug) {}
    public static void init(Config config) {}
    public static void setDebug(boolean debug) {}
    public static String getVersion() { return "stub"; }
    public static String getRecords(int itemFlags) { return ""; }
    public static void dumpRecords(String pathname, int itemFlags) {}

    public static class Config {
        public int mode = MODE_AUTOMATIC;
        public boolean debug = false;
        public boolean recordable = false;
        public Config setMode(int mode) { this.mode = mode; return this; }
        public Config setDebug(boolean debug) { this.debug = debug; return this; }
        public Config setRecordable(boolean recordable) { this.recordable = recordable; return this; }
    }
}