package org.tvp.kirikiri2;

import android.app.AlertDialog;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.cocos2dx.lib.Cocos2dxActivity;

public final class d implements Runnable {
    public final String f19626a;
    public d(String text) { this.f19626a = text; }
    @Override public void run() {
        f fVar = KR2Activity.mDialogMessage;
        AlertDialog.Builder builder = fVar.a();
        fVar.f19632d = new EditText(KR2Activity.sInstance);
        fVar.f19632d.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        fVar.f19632d.setText(f19626a);
        builder.setView(fVar.f19632d);
        builder.create().show();
        fVar.f19632d.requestFocus();
        ((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).showSoftInput(fVar.f19632d, 0);
    }
}