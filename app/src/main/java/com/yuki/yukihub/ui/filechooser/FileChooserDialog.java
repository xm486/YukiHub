package com.yuki.yukihub.ui.filechooser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.yuki.yukihub.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

/**
 * 应用内文件浏览器对话框。
 * 支持：目录选择、文件选择（含 MIME 过滤）、多存储根浏览。
 * SD 卡通过 SAF 回调让调用方启动原生选择器。
 */
public class FileChooserDialog {

    public enum Mode { FILE, DIRECTORY, FILE_OR_DIR }

    private final Context context;
    private Mode mode = Mode.FILE;
    private String[] mimeFilters;
    private String[] extFilters;
    private String title = "选择文件";
    private String startPath;
    private boolean showHidden = false;
    private OnFileSelectedListener listener;
    private OnSafRequestListener safListener;

    private AlertDialog dialog;
    private TextView tvCurrentPath;
    private LinearLayout pathEditBar;
    private EditText etPath;
    private ListView listView;
    private FileAdapter adapter;
    private File currentDir;
    private final Stack<File> backStack = new Stack<>();
    private ProgressBar progressBar;
    private boolean browsingRoots = false;

    private static final int C_BG = 0xFF141B2D;
    private static final int C_SURFACE = 0xFF1C2538;
    private static final int C_BORDER = 0xFF2A3A52;
    private static final int C_TEXT = 0xFFF0F4FA;
    private static final int C_TEXT_MUTED = 0xFF7E8CA0;
    private static final int C_TEXT_DIM = 0xFF4E5E7C;
    private static final int C_ACCENT = 0xFF6C9FFF;
    private static final int C_SELECTED = 0x278AB4FF;

    public FileChooserDialog(Context context) {
        this.context = context;
        this.startPath = null;
    }

    public FileChooserDialog setMode(Mode mode) { this.mode = mode; return this; }
    public FileChooserDialog setTitle(String title) { this.title = title; return this; }
    public FileChooserDialog setStartPath(String path) { this.startPath = path; return this; }
    public FileChooserDialog setMimeFilter(String... mimes) { this.mimeFilters = mimes; return this; }
    public FileChooserDialog setExtFilter(String... exts) { this.extFilters = exts; return this; }
    public FileChooserDialog setShowHidden(boolean show) { this.showHidden = show; return this; }
    public FileChooserDialog setOnFileSelectedListener(OnFileSelectedListener l) { this.listener = l; return this; }
    public FileChooserDialog setOnSafRequestListener(OnSafRequestListener l) { this.safListener = l; return this; }

    // --- 存储根 ---
    private List<StorageRoot> detectStorageRoots() {
        List<StorageRoot> roots = new ArrayList<>();
        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        File internalDir = new File(internalPath);
        if (internalDir.isDirectory() && internalDir.canRead()) {
            roots.add(new StorageRoot("内部存储", internalPath));
        }
        File appInternal = context.getFilesDir();
        if (appInternal != null) {
            roots.add(new StorageRoot("应用内部", appInternal.getAbsolutePath()));
        }
        return roots;
    }

    private boolean hasExternalSdCard() {
        File storageDir = new File("/storage");
        File[] children = storageDir.listFiles();
        if (children == null) return false;
        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (File f : children) {
            String name = f.getName();
            if (name.equals("emulated") || name.equals("self")) continue;
            if (!f.isDirectory()) continue;
            try {
                if (f.getCanonicalPath().equals(new File(internalPath).getCanonicalPath())) continue;
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    private static class StorageRoot {
        final String label;
        final String path;
        StorageRoot(String label, String path) { this.label = label; this.path = path; }
    }

    // ==================== show ====================
    public void show() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(4));
        root.setBackgroundColor(C_BG);

        // 路径栏
        LinearLayout pathRow = new LinearLayout(context);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        pathRow.setBackgroundColor(C_SURFACE);

        tvCurrentPath = new TextView(context);
        tvCurrentPath.setTextColor(C_ACCENT);
        tvCurrentPath.setTextSize(13);
        tvCurrentPath.setSingleLine(true);
        tvCurrentPath.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvCurrentPath.setPadding(dp(2), 0, dp(2), 0);
        pathRow.addView(tvCurrentPath, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView ivEdit = new ImageView(context);
        ivEdit.setImageResource(android.R.drawable.ic_menu_edit);
        ivEdit.setColorFilter(C_TEXT_MUTED, android.graphics.PorterDuff.Mode.SRC_IN);
        ivEdit.setPadding(dp(4), dp(4), dp(4), dp(4));
        pathRow.addView(ivEdit, new LinearLayout.LayoutParams(dp(28), dp(28)));
        root.addView(pathRow, new LinearLayout.LayoutParams(-1, -2));

        // 路径编辑栏（隐藏）
        pathEditBar = new LinearLayout(context);
        pathEditBar.setOrientation(LinearLayout.HORIZONTAL);
        pathEditBar.setGravity(Gravity.CENTER_VERTICAL);
        pathEditBar.setVisibility(View.GONE);
        pathEditBar.setPadding(0, dp(4), 0, 0);

        etPath = new EditText(context);
        etPath.setHint("输入路径跳转…");
        etPath.setTextColor(C_TEXT);
        etPath.setHintTextColor(C_TEXT_DIM);
        etPath.setTextSize(13);
        etPath.setSingleLine(true);
        etPath.setBackgroundColor(C_SURFACE);
        etPath.setPadding(dp(8), dp(4), dp(8), dp(4));
        pathEditBar.addView(etPath, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnGo = makeSmallButton("跳转");
        btnGo.setOnClickListener(v -> {
            String p = etPath.getText().toString().trim();
            if (!p.isEmpty()) {
                File f = new File(p);
                if (f.isDirectory() && canReadDir(f)) navigateTo(f);
                else toast("路径不可访问");
            }
        });
        pathEditBar.addView(btnGo, new LinearLayout.LayoutParams(-2, -2));
        root.addView(pathEditBar, new LinearLayout.LayoutParams(-1, -2));

        pathRow.setOnClickListener(v -> {
            if (pathEditBar.getVisibility() == View.GONE) {
                pathEditBar.setVisibility(View.VISIBLE);
                if (currentDir != null) etPath.setText(currentDir.getAbsolutePath());
                etPath.requestFocus();
            } else {
                pathEditBar.setVisibility(View.GONE);
            }
        });

        // 分隔线
        View divider = new View(context);
        divider.setBackgroundColor(C_BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        // 进度条
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));

        // 文件列表
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setPadding(0, dp(4), 0, 0);
        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> {
            FileEntry entry = adapter.getItem(pos);
            if (entry == null) return;
            if (entry.isUp) {
                goUp();
            } else if (entry.isSafEntry) {
                // SD 卡条目 → 通知调用方启动 SAF
                dialog.dismiss();
                if (safListener != null) safListener.onRequestSafDirectory();
            } else if (entry.isDirectory) {
                navigateTo(entry.file);
            } else {
                onFileSelected(entry.file);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 底部按钮
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(0, dp(6), 0, 0);

        Button btnCancel = makeSmallButton("取消");
        btnCancel.setTextColor(C_TEXT_MUTED);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        Button btnConfirm = makeSmallButton(mode == Mode.DIRECTORY ? "选择此目录" : "确定");
        btnConfirm.setTextColor(C_ACCENT);
        btnConfirm.setOnClickListener(v -> {
            if (mode != Mode.FILE && currentDir != null) onFileSelected(currentDir);
        });
        if (mode == Mode.FILE) btnConfirm.setVisibility(View.GONE);

        bottomBar.addView(btnCancel, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-2, -2);
        confirmLp.leftMargin = dp(8);
        bottomBar.addView(btnConfirm, confirmLp);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));

        dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setCancelable(true)
                .create();

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(C_BG));
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                boolean isTablet = dm.widthPixels > dm.heightPixels && dm.widthPixels > dp(900);
                float rw = isTablet ? 0.72f : 0.92f;
                float rh = isTablet ? 0.88f : 0.92f;
                int w = Math.min((int)(dm.widthPixels * rw), dp(720));
                int h = Math.min((int)(dm.heightPixels * rh), dp(640));
                dialog.getWindow().setLayout(Math.max(dp(320), w), Math.max(dp(300), h));
            }
        });

        dialog.show();

        if (startPath != null) {
            File sd = new File(startPath);
            if (sd.isDirectory() && canReadDir(sd)) navigateTo(sd);
            else showRootList();
        } else {
            showRootList();
        }
    }

    private Button makeSmallButton(String text) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(C_TEXT);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setMinimumHeight(dp(32));
        btn.setMinimumWidth(dp(60));
        btn.setBackgroundColor(C_SURFACE);
        return btn;
    }

    // ==================== 根列表 ====================
    private void showRootList() {
        browsingRoots = true;
        currentDir = null;
        backStack.clear();
        tvCurrentPath.setText("选择存储位置");

        List<FileEntry> entries = new ArrayList<>();
        for (StorageRoot r : detectStorageRoots()) {
            entries.add(new FileEntry(RootType.INTERNAL, r.label, new File(r.path)));
        }

        // SD 卡 → 原生 SAF（始终可见）
        entries.add(new FileEntry(RootType.SAF, "SD卡 / 其它存储 (系统授权访问)", null));

        // Android/data
        File adDir = new File(Environment.getExternalStorageDirectory(), "Android/data");
        if (adDir.isDirectory() && adDir.listFiles() == null) {
            entries.add(new FileEntry(RootType.INTERNAL, "Android/data (需要所有文件访问权限)", adDir));
        }

        adapter.setData(entries);
        progressBar.setVisibility(View.GONE);
    }

    // ==================== 导航 ====================
    private boolean canReadDir(File dir) {
        return dir.isDirectory() && dir.listFiles() != null;
    }

    private void navigateTo(File dir) {
        if (dir == null || !dir.isDirectory()) { toast("该路径不存在"); return; }
        if (!dir.canRead() || dir.listFiles() == null) {
            toast("此目录受系统限制");
            promptAllFilesAccess();
            return;
        }
        browsingRoots = false;
        pathEditBar.setVisibility(View.GONE);
        if (currentDir != null && !currentDir.equals(dir)) backStack.push(currentDir);
        currentDir = dir;
        tvCurrentPath.setText(dir.getAbsolutePath());
        refreshList();
    }

    private void promptAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            toast("请授予「所有文件访问权限」");
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:" + context.getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    private void goUp() {
        if (browsingRoots) return;
        if (backStack.isEmpty()) { showRootList(); return; }
        currentDir = backStack.pop();
        pathEditBar.setVisibility(View.GONE);
        tvCurrentPath.setText(currentDir.getAbsolutePath());
        refreshList();
    }

    private void refreshList() {
        if (currentDir == null) return;
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File[] files = currentDir.listFiles(f ->
                    showHidden || !f.getName().startsWith("."));
            List<FileEntry> entries = new ArrayList<>();
            entries.add(new FileEntry(RootType.UP, null, null));
            if (files != null) {
                List<File> dirs = new ArrayList<>();
                List<File> regs = new ArrayList<>();
                for (File f : files) {
                    if (f.isDirectory()) dirs.add(f);
                    else if (acceptFile(f)) regs.add(f);
                }
                Collections.sort(dirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                Collections.sort(regs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File d : dirs) entries.add(new FileEntry(RootType.DIR, null, d));
                for (File r : regs) entries.add(new FileEntry(RootType.FILE, null, r));
            }
            ((android.app.Activity) context).runOnUiThread(() -> {
                adapter.setData(entries);
                progressBar.setVisibility(View.GONE);
            });
        }).start();
    }

    private boolean acceptFile(File f) {
        if (f.isDirectory()) return true;
        if (extFilters != null && extFilters.length > 0) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String e : extFilters) if (n.endsWith(e.toLowerCase(Locale.ROOT))) return true;
            return false;
        }
        if (mimeFilters != null && mimeFilters.length > 0) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String m : mimeFilters) {
                if (m.startsWith("image/")) {
                    if (n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp)$")) return true;
                } else if (m.startsWith("video/")) {
                    if (n.matches(".*\\.(mp4|mkv|avi|mov|webm|3gp)$")) return true;
                } else if (m.equals("*/*") || m.equals("application/*") || m.equals("text/*")) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void onFileSelected(File file) {
        if (listener != null) {
            if (file.isDirectory()) listener.onDirectorySelected(Uri.fromFile(file), file.getAbsolutePath());
            else listener.onFileSelected(Uri.fromFile(file), file.getAbsolutePath(), file.getName());
        }
        dialog.dismiss();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) { return (int)(v * context.getResources().getDisplayMetrics().density); }

    // ==================== 数据类 ====================
    enum RootType { UP, INTERNAL, SAF, DIR, FILE }

    private class FileEntry {
        final RootType type;
        final File file;
        final String label;
        final boolean isUp;
        final boolean isDirectory;
        final boolean isSafEntry;
        final String name;
        final String detail;

        FileEntry(RootType type, String label, File file) {
            this.type = type;
            this.file = file;
            this.label = label;
            switch (type) {
                case UP:
                    this.isUp = true; this.isDirectory = true; this.isSafEntry = false;
                    this.name = ".."; this.detail = "返回上级"; break;
                case SAF:
                    this.isUp = false; this.isDirectory = true; this.isSafEntry = true;
                    this.name = label; this.detail = "跳转系统文件选择器"; break;
                case INTERNAL:
                    this.isUp = false; this.isDirectory = true; this.isSafEntry = false;
                    this.name = label;
                    this.detail = (file != null) ? childrenCount(file) : ""; break;
                case DIR:
                    this.isUp = false; this.isDirectory = true; this.isSafEntry = false;
                    this.name = file.getName(); this.detail = childrenCount(file); break;
                case FILE: default:
                    this.isUp = false; this.isDirectory = false; this.isSafEntry = false;
                    this.name = file.getName();
                    long sz = file.length();
                    this.detail = fmtSize(sz) + "  ·  " + fmtDate(file.lastModified()); break;
            }
        }

        private String childrenCount(File d) {
            File[] c = d.listFiles();
            return (c != null ? c.length : 0) + " 项";
        }

        private String fmtSize(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024*1024) return String.format(Locale.ROOT,"%.1f KB",b/1024.0);
            if (b < 1024L*1024*1024) return String.format(Locale.ROOT,"%.1f MB",b/(1024.0*1024));
            return String.format(Locale.ROOT,"%.1f GB",b/(1024.0*1024*1024));
        }
        private String fmtDate(long ms) {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms));
        }
    }

    // ==================== Adapter ====================
    private class FileAdapter extends BaseAdapter {
        private List<FileEntry> data = new ArrayList<>();
        void setData(List<FileEntry> d) { data = d; notifyDataSetChanged(); }
        @Override public int getCount() { return data.size(); }
        @Override public FileEntry getItem(int pos) { return data.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            ViewHolder h;
            if (cv == null) {
                cv = LayoutInflater.from(context).inflate(R.layout.item_file_chooser, parent, false);
                h = new ViewHolder();
                h.icon = cv.findViewById(R.id.fileIcon);
                h.name = cv.findViewById(R.id.fileName);
                h.detail = cv.findViewById(R.id.fileDetail);
                cv.setTag(h);
            } else { h = (ViewHolder) cv.getTag(); }
            FileEntry e = data.get(pos);
            h.name.setText(e.name);
            h.detail.setText(e.detail);
            h.detail.setTextColor(e.isDirectory ? C_TEXT_MUTED : C_TEXT_DIM);

            int iconRes, nameColor;
            switch (e.type) {
                case UP:
                    iconRes = android.R.drawable.ic_menu_revert; nameColor = C_ACCENT; break;
                case SAF:
                    iconRes = android.R.drawable.ic_menu_share; nameColor = 0xFF8AB4FF; break;
                case INTERNAL:
                    iconRes = android.R.drawable.ic_menu_set_as; nameColor = C_ACCENT; break;
                case DIR:
                    iconRes = android.R.drawable.ic_menu_view; nameColor = C_TEXT; break;
                default:
                    iconRes = android.R.drawable.ic_menu_edit; nameColor = C_TEXT; break;
            }
            h.icon.setImageResource(iconRes);
            h.icon.setColorFilter(C_TEXT_MUTED, android.graphics.PorterDuff.Mode.SRC_IN);
            h.name.setTextColor(nameColor);

            boolean selected = mode == Mode.DIRECTORY && e.isDirectory
                    && e.type != RootType.UP && e.type != RootType.SAF
                    && e.file != null && e.file.equals(currentDir);
            cv.setBackgroundColor(selected ? C_SELECTED : 0x00000000);
            return cv;
        }
    }

    private static class ViewHolder {
        ImageView icon; TextView name; TextView detail;
    }

    /** 选择结果回调 */
    public interface OnFileSelectedListener {
        void onFileSelected(Uri uri, String path, String fileName);
        void onDirectorySelected(Uri uri, String path);
    }

    /** SAF 目录请求回调：点击 SD 卡条目时触发 */
    public interface OnSafRequestListener {
        void onRequestSafDirectory();
    }
}