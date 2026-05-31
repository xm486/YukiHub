package com.yuki.yukihub.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AppExecutors {
    private AppExecutors() {}

    private static final int IO_POOL_SIZE = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 6));

    private static final ExecutorService ioExecutor = Executors.newFixedThreadPool(IO_POOL_SIZE, r -> {
        Thread t = new Thread(r, "YukiHub-IO");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ExecutorService singleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "YukiHub-Single");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YukiHub-Scheduled");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static ExecutorService io() {
        return ioExecutor;
    }

    public static ExecutorService single() {
        return singleExecutor;
    }

    public static ScheduledExecutorService scheduled() {
        return scheduledExecutor;
    }

    public static void runOnIo(Runnable command) {
        ioExecutor.execute(command);
    }

    public static void runOnSingle(Runnable command) {
        singleExecutor.execute(command);
    }

    public static ScheduledFuture<?> schedule(Runnable command, long delayMs) {
        return scheduledExecutor.schedule(command, delayMs, TimeUnit.MILLISECONDS);
    }
}
