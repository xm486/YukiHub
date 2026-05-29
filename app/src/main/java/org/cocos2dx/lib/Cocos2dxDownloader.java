package org.cocos2dx.lib;

public class Cocos2dxDownloader {
    public static native void nativeOnProgress(int id, int taskId, long dl, long dlnow, long dltotal);
    public static native void nativeOnFinish(int id, int taskId, int errCode, String errStr, byte[] data);
}