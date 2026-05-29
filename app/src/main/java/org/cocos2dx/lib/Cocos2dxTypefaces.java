package org.cocos2dx.lib;

import android.content.Context;
import android.graphics.Typeface;
import java.util.HashMap;

public class Cocos2dxTypefaces {
    private static final HashMap<String, Typeface> cache = new HashMap<>();
    public static Typeface get(Context context, String name) {
        synchronized (cache) {
            if (cache.containsKey(name)) return cache.get(name);
            Typeface tf;
            try { tf = Typeface.createFromAsset(context.getAssets(), name); }
            catch (Throwable t) { tf = Typeface.create(name, Typeface.NORMAL); }
            cache.put(name, tf);
            return tf;
        }
    }
}