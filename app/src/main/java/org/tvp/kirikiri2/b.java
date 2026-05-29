package org.tvp.kirikiri2;

import android.os.Handler;
import android.os.Message;

public final class b extends Handler {
    @Override public void handleMessage(Message message) {
        KR2Activity.sInstance.handleMessage(message);
    }
}