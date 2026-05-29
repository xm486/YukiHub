package org.tvp.kirikiri2;

import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import org.cocos2dx.lib.Cocos2dxActivity;

public final class g implements Runnable {
    public int f19633a;
    public int f19634b;
    public int f19635c;
    public int f19636d;
    @Override public void run() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(f19635c, f19636d + 15);
        lp.leftMargin = f19633a;
        lp.topMargin = f19634b;
        View view = KR2Activity.mTextEdit;
        if (view == null) {
            a v = new a(KR2Activity.sInstance);
            v.setFocusableInTouchMode(true);
            v.setFocusable(true);
            v.setOnKeyListener(v);
            KR2Activity.mTextEdit = v;
            KR2Activity.sInstance.mFrameLayout.addView(KR2Activity.mTextEdit, lp);
        } else {
            view.setLayoutParams(lp);
        }
        KR2Activity.mTextEdit.setVisibility(View.VISIBLE);
        KR2Activity.mTextEdit.requestFocus();
        ((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).showSoftInput(KR2Activity.mTextEdit, 0);
    }
}