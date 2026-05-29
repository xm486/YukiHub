package bridge;

import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class NativeBridge {
    private static final List<RandomAccessFile> OPEN_FILES = new ArrayList<>();

    private NativeBridge() { }

    public static native boolean initialize(String so);
    public static native boolean launch(String so, String path, boolean useMaps);
    public static native void interceptor(String prefix);
    public static native void relocate();
    public static native boolean write(String path, byte[] data);

    public static synchronized int open(String path, int mode) {
        try {
            String javaMode = toJavaMode(mode);
            RandomAccessFile raf = new RandomAccessFile(new File(normalizeFilePath(path)), javaMode);
            OPEN_FILES.add(raf);
            int fd = getFd(raf);
            Log.i("NativeBridge", "open " + fd + " " + javaMode + " " + path);
            return fd;
        } catch (Throwable t) {
            Log.e("NativeBridge", "open failed mode=" + mode + " path=" + path, t);
            return -1;
        }
    }

    private static String normalizeFilePath(String path) {
        if (path == null) return path;
        if (path.startsWith("file://")) return path.substring("file://".length());
        return path;
    }

    private static String toJavaMode(int mode) {
        int accessMode = mode & OsConstants.O_ACCMODE;
        if (accessMode == OsConstants.O_RDONLY) return "r";
        return "rw";
    }

    private static int getFd(RandomAccessFile raf) throws Exception {
        FileDescriptor fd = raf.getFD();
        Method method = FileDescriptor.class.getDeclaredMethod("getInt$");
        method.setAccessible(true);
        return (Integer) method.invoke(fd);
    }
}
