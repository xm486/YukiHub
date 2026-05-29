package org.tvp.kirikiri2;

import android.app.AlertDialog;
import android.widget.EditText;

public final class f {
    public String f19629a;
    public String f19630b;
    public String[] f19631c;
    public EditText f19632d;

    public AlertDialog.Builder a() {
        AlertDialog.Builder b = new AlertDialog.Builder(KR2Activity.sInstance)
                .setTitle(f19629a).setMessage(f19630b).setCancelable(false);
        String[] arr = f19631c != null ? f19631c : new String[]{"OK"};
        if (arr.length >= 1) b = b.setPositiveButton(arr[0], new e(this, 0));
        if (arr.length >= 2) b = b.setNeutralButton(arr[1], new e(this, 1));
        if (arr.length >= 3) b = b.setNegativeButton(arr[2], new e(this, 2));
        return b;
    }

    public void b(int which) {
        if (f19632d != null) KR2Activity.onMessageBoxText(f19632d.getText().toString());
        KR2Activity.onMessageBoxOK(which);
    }
}