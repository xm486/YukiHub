package org.cocos2dx.lib;

import android.graphics.Color;
import android.text.InputFilter;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

public class Cocos2dxEditBoxHelper {
    private static Cocos2dxActivity mCocos2dxActivity;
    private static ResizeLayout mFrameLayout;
    private static SparseArray<Cocos2dxEditBox> mEditBoxArray = new SparseArray<>();
    private static int mViewTag;

    public Cocos2dxEditBoxHelper(ResizeLayout layout) {
        mFrameLayout = layout;
        mCocos2dxActivity = (Cocos2dxActivity) Cocos2dxActivity.getContext();
    }

    private static native void editBoxEditingChanged(int tag, String text);
    private static native void editBoxEditingDidBegin(int tag);
    private static native void editBoxEditingDidEnd(int tag, String text);
    public static void __editBoxEditingChanged(int tag, String text) { editBoxEditingChanged(tag, text); }
    public static void __editBoxEditingDidBegin(int tag) { editBoxEditingDidBegin(tag); }
    public static void __editBoxEditingDidEnd(int tag, String text) { editBoxEditingDidEnd(tag, text); }

    public static int convertToSP(float size) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, mCocos2dxActivity.getResources().getDisplayMetrics()); }

    public static int createEditBox(final int x, final int y, final int w, final int h, final float fontSize) {
        final int tag = mViewTag++;
        if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> {
            Cocos2dxEditBox eb = new Cocos2dxEditBox(mCocos2dxActivity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = x; lp.topMargin = y;
            eb.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
            mEditBoxArray.put(tag, eb);
            if (mFrameLayout != null) mFrameLayout.addView(eb, lp);
        });
        return tag;
    }
    public static void removeEditBox(final int tag) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null&&mFrameLayout!=null)mFrameLayout.removeView(eb); mEditBoxArray.remove(tag); }); }
    public static void setEditBoxViewRect(final int tag, final int x, final int y, final int w, final int h) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null){ FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h); lp.leftMargin=x; lp.topMargin=y; eb.setLayoutParams(lp);} }); }
    public static void setVisible(final int tag, final boolean visible) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setVisibility(visible?android.view.View.VISIBLE:android.view.View.GONE); }); }
    public static void setText(final int tag, final String text) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setText(text); }); }
    public static void setFont(final int tag, final String font, final float size) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setTextSize(TypedValue.COMPLEX_UNIT_SP, size); }); }
    public static void setFontColor(final int tag, final int r, final int g, final int b, final int a) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setTextColor(Color.argb(a,r,g,b)); }); }
    public static void setPlaceHolderText(final int tag, final String text) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setHint(text); }); }
    public static void setPlaceHolderTextColor(final int tag, final int r, final int g, final int b, final int a) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setHintTextColor(Color.argb(a,r,g,b)); }); }
    public static void setMaxLength(final int tag, final int len) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null) eb.setFilters(new InputFilter[]{new InputFilter.LengthFilter(len)}); }); }
    public static void setInputFlag(int tag, int flag) { }
    public static void setInputMode(int tag, int mode) { }
    public static void setReturnType(int tag, int type) { }
    public static void openKeyboard(final int tag) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null){ eb.requestFocus(); ((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).showSoftInput(eb,0);} }); }
    public static void closeKeyboard(final int tag) { if (mCocos2dxActivity != null) mCocos2dxActivity.runOnUiThread(() -> { Cocos2dxEditBox eb=mEditBoxArray.get(tag); if(eb!=null)((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).hideSoftInputFromWindow(eb.getWindowToken(),0); }); }
}