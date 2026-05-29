package org.cocos2dx.lib;

import java.io.File;
import java.io.FileInputStream;

public class GameControllerUtils {
    public static void ensureDirectoryExist(String path) {
        File f = new File(path);
        if (!f.exists()) f.mkdirs();
    }
    public static String readJsonFile(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[fis.available()];
            fis.read(data);
            fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}