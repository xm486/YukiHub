package org.tvp.kirikiri2;

import android.content.DialogInterface;

public final class e implements DialogInterface.OnClickListener {
    public final int f19627a;
    public final f f19628b;
    public e(f fVar, int i) { this.f19628b = fVar; this.f19627a = i; }
    @Override public void onClick(DialogInterface dialog, int which) {
        if (f19627a == 0) f19628b.b(0);
        else if (f19627a == 1) f19628b.b(1);
        else f19628b.b(2);
    }
}