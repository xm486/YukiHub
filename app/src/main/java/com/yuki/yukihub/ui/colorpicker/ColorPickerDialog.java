package com.yuki.yukihub.ui.colorpicker;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ui.DynamicTheme;
import com.yuki.yukihub.ui.ThemeColorExtractor;

/**
 * 手机横屏友好的主题颜色选择器。
 * 单色：左侧调色盘，右侧大预览。
 * 渐变：起始色/结束色两个大触控目标，点选后用同一个调色盘编辑。
 */
public class ColorPickerDialog {
    public enum ColorMode { SINGLE_COLOR, GRADIENT_COLOR }
    private enum EditingTarget { SINGLE, GRADIENT_START, GRADIENT_END }

    private final Context context;
    private AlertDialog dialog;
    private ColorPickerView colorPicker;
    private RadioGroup modeRadioGroup;
    private LinearLayout gradientTargets;
    private TextView gradientStartButton;
    private TextView gradientEndButton;
    private View previewView;
    private TextView colorValueText;
    private TextView alphaHintText;
    private Button applyButton;
    private Button cancelButton;

    private ColorMode currentMode = ColorMode.SINGLE_COLOR;
    private EditingTarget editingTarget = EditingTarget.SINGLE;
    private int selectedColor = 0xFF8AB4FF;
    private int gradientColor1 = 0xFF8AB4FF;
    private int gradientColor2 = 0xFFFF8AB3;
    private float gradientAngle = 0f;

    private OnColorSelectedListener listener;
    private OnColorPreviewListener previewListener;

    public ColorPickerDialog(Context context) {
        this.context = context;
        createDialog();
    }

    private void createDialog() {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null);
        initViews(dialogView);
        dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(true)
                .create();
    }

    private void initViews(View root) {
        modeRadioGroup = root.findViewById(R.id.radioColorMode);
        colorPicker = root.findViewById(R.id.colorPicker);
        gradientTargets = root.findViewById(R.id.gradientTargets);
        gradientStartButton = root.findViewById(R.id.btnGradientStart);
        gradientEndButton = root.findViewById(R.id.btnGradientEnd);
        previewView = root.findViewById(R.id.colorPreview);
        colorValueText = root.findViewById(R.id.tvColorValue);
        alphaHintText = root.findViewById(R.id.tvAlphaHint);
        applyButton = root.findViewById(R.id.btnApply);
        cancelButton = root.findViewById(R.id.btnCancel);

        modeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioSingleColor) {
                switchToSingleMode();
            } else if (checkedId == R.id.radioGradient) {
                switchToGradientMode();
            }
        });

        gradientStartButton.setOnClickListener(v -> setEditingTarget(EditingTarget.GRADIENT_START));
        gradientEndButton.setOnClickListener(v -> setEditingTarget(EditingTarget.GRADIENT_END));

        colorPicker.setOnColorChangeListener((color, hue, saturation, value, alpha) -> {
            if (editingTarget == EditingTarget.GRADIENT_START) {
                gradientColor1 = color;
            } else if (editingTarget == EditingTarget.GRADIENT_END) {
                gradientColor2 = color;
            } else {
                selectedColor = color;
                gradientColor1 = color;
            }
            updatePreview();
            notifyPreview();
        });

        applyButton.setOnClickListener(v -> {
            if (listener != null) {
                if (currentMode == ColorMode.SINGLE_COLOR) {
                    listener.onColorSelected(selectedColor, selectedColor, 0f);
                } else {
                    listener.onColorSelected(gradientColor1, gradientColor2, gradientAngle);
                }
            }
            dialog.dismiss();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        switchToSingleMode();
    }

    private void switchToSingleMode() {
        currentMode = ColorMode.SINGLE_COLOR;
        editingTarget = EditingTarget.SINGLE;
        if (gradientTargets != null) gradientTargets.setVisibility(View.GONE);
        colorPicker.setColor(selectedColor);
        updatePreview();
        notifyPreview();
    }

    private void switchToGradientMode() {
        currentMode = ColorMode.GRADIENT_COLOR;
        if (gradientColor1 == gradientColor2) gradientColor2 = shiftHue(gradientColor1, 0.33f);
        if (gradientTargets != null) gradientTargets.setVisibility(View.VISIBLE);
        setEditingTarget(EditingTarget.GRADIENT_START);
        updatePreview();
        notifyPreview();
    }

    private void setEditingTarget(EditingTarget target) {
        editingTarget = target;
        if (target == EditingTarget.GRADIENT_START) {
            colorPicker.setColor(gradientColor1);
        } else if (target == EditingTarget.GRADIENT_END) {
            colorPicker.setColor(gradientColor2);
        } else {
            colorPicker.setColor(selectedColor);
        }
        updateTargetButtons();
        updatePreview();
    }

    private void updateTargetButtons() {
        if (gradientStartButton == null || gradientEndButton == null) return;
        gradientStartButton.setBackground(makeChipBg(gradientColor1, editingTarget == EditingTarget.GRADIENT_START));
        gradientEndButton.setBackground(makeChipBg(gradientColor2, editingTarget == EditingTarget.GRADIENT_END));
        gradientStartButton.setTextColor(contrastText(gradientColor1));
        gradientEndButton.setTextColor(contrastText(gradientColor2));
    }

    private void updatePreview() {
        if (previewView == null) return;
        if (currentMode == ColorMode.SINGLE_COLOR) {
            previewView.setBackground(makePreviewBg(selectedColor));
            setColorText(selectedColor, null);
        } else {
            GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{gradientColor1, gradientColor2});
            gd.setCornerRadius(dp(12));
            gd.setStroke(dp(2), 0xFFFFFFFF);
            previewView.setBackground(gd);
            setColorText(gradientColor1, gradientColor2);
        }
        updateTargetButtons();
    }

    private void setColorText(int color1, Integer color2) {
        int alpha = Color.alpha(editingTarget == EditingTarget.GRADIENT_END ? gradientColor2 : color1);
        int percent = Math.round(alpha / 255f * 100f);
        if (alphaHintText != null) alphaHintText.setText("强度/透明度：" + percent + "%");
        if (colorValueText == null) return;
        if (color2 == null) {
            colorValueText.setText(String.format("#%08X", color1));
        } else {
            colorValueText.setText(String.format("#%08X  →  #%08X", color1, color2));
        }
    }

    private void notifyPreview() {
        if (previewListener == null) return;
        if (currentMode == ColorMode.SINGLE_COLOR) {
            previewListener.onColorPreview(selectedColor, selectedColor, 0f, false);
        } else {
            previewListener.onColorPreview(gradientColor1, gradientColor2, gradientAngle, true);
        }
    }

    private GradientDrawable makePreviewBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(2), 0xFFFFFFFF);
        return gd;
    }

    private GradientDrawable makeChipBg(int color, boolean active) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor((active ? 0xFF000000 : 0xCC000000) | (color & 0x00FFFFFF));
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(active ? 2 : 1), active ? 0xFFFFFFFF : 0x66FFFFFF);
        return gd;
    }

    private int contrastText(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum > 150 ? 0xFF071221 : 0xFFFFFFFF;
    }

    public void show() {
        dialog.show();
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawableResource(R.drawable.bg_dialog);
            DynamicTheme dt = DynamicTheme.getInstance();
            if (dt.isEnabled() && dt.getColors() != null) {
                window.setBackgroundDrawable(tintDialog(dt.getColors()));
            }
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.min((int) (dm.widthPixels * 0.58f), dp(620));
            int height = Math.min((int) (dm.heightPixels * 0.88f), dp(430));
            window.setLayout(Math.max(dp(520), width), Math.max(dp(320), height));
        }
    }

    public void setOnDismissListener(android.content.DialogInterface.OnDismissListener listener) {
        if (dialog != null) dialog.setOnDismissListener(listener);
    }

    public void setInitialColor(int color) {
        selectedColor = color;
        gradientColor1 = color;
        if (colorPicker != null) colorPicker.setColor(color);
        updatePreview();
    }

    public void setInitialGradient(int color1, int color2, float angle) {
        gradientColor1 = color1;
        gradientColor2 = color2;
        gradientAngle = angle;
        updatePreview();
    }

    public void setOnColorSelectedListener(OnColorSelectedListener listener) { this.listener = listener; }
    public void setOnColorPreviewListener(OnColorPreviewListener listener) { this.previewListener = listener; }
    public ColorMode getCurrentMode() { return currentMode; }

    public interface OnColorSelectedListener { void onColorSelected(int color1, int color2, float gradientAngle); }
    public interface OnColorPreviewListener { void onColorPreview(int color1, int color2, float gradientAngle, boolean gradientMode); }

    private GradientDrawable tintDialog(ThemeColorExtractor.ThemeColors c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor((0xF0 << 24) | (c.card & 0x00FFFFFF));
        d.setStroke(dp(1), (0x5E << 24) | (c.primary & 0x00FFFFFF));
        d.setCornerRadius(dp(12));
        return d;
    }

    private int dp(float v) { return (int) (v * context.getResources().getDisplayMetrics().density); }

    public static ThemeColorExtractor.ThemeColors generateThemeFromColor(int baseColor) {
        return ThemeColorExtractor.DEFAULT;
    }

    private static int shiftHue(int color, float offset) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + offset * 360f) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }
}