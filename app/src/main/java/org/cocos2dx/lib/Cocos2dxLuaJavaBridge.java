package org.cocos2dx.lib;

public class Cocos2dxLuaJavaBridge {
    public static native int callLuaFunctionWithString(int functionId, String value);
    public static native int callLuaGlobalFunctionWithString(String functionName, String value);
    public static native int retainLuaFunction(int functionId);
    public static native int releaseLuaFunction(int functionId);
}