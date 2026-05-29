package org.cocos2dx.lib;

import java.util.concurrent.CountDownLatch;

/* JADX INFO: loaded from: classes.dex */
class ShouldStartLoadingWorker implements Runnable {
    private CountDownLatch mLatch;
    private boolean[] mResult;
    private final String mUrlString;
    private final int mViewTag;

    public ShouldStartLoadingWorker(CountDownLatch countDownLatch, boolean[] zArr, int i8, String str) {
        this.mLatch = countDownLatch;
        this.mResult = zArr;
        this.mViewTag = i8;
        this.mUrlString = str;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.mResult[0] = Cocos2dxWebViewHelper._shouldStartLoading(this.mViewTag, this.mUrlString);
        this.mLatch.countDown();
    }
}
