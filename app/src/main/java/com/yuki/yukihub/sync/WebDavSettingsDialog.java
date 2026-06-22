package com.yuki.yukihub.sync;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.yuki.yukihub.R;
import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.util.AppExecutors;

/**
 * WebDAV 同步设置对话框
 */
public class WebDavSettingsDialog extends DialogFragment {
    
    private EditText etServer, etUsername, etPassword;
    private Switch swAutoSync;
    private Button btnTest, btnSave, btnSyncNow, btnLocalBackup, btnLocalImport;
    private TextView tvStatus, tvLastSync;
    private ProgressBar progressBar;
    private LinearLayout syncOptions;
    
    private SyncManager syncManager;
    
    public static WebDavSettingsDialog newInstance() {
        return new WebDavSettingsDialog();
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_YukiHub_WebDavDialog);
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_webdav_settings, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Apply dynamic theme tinting
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).tintDialogRoot(view);
        }
        
        syncManager = new SyncManager(requireContext());
        
        initViews(view);
        loadConfig();
        setupListeners();
    }
    
    private void initViews(View view) {
        etServer = view.findViewById(R.id.etWebDavServer);
        etUsername = view.findViewById(R.id.etWebDavUsername);
        etPassword = view.findViewById(R.id.etWebDavPassword);
        
        swAutoSync = view.findViewById(R.id.swAutoSync);
        
        btnTest = view.findViewById(R.id.btnTestConnection);
        btnSave = view.findViewById(R.id.btnSaveWebDav);
        btnSyncNow = view.findViewById(R.id.btnSyncNow);
        btnLocalBackup = view.findViewById(R.id.btnLocalBackup);
        btnLocalImport = view.findViewById(R.id.btnLocalImport);
        
        tvStatus = view.findViewById(R.id.tvWebDavStatus);
        tvLastSync = view.findViewById(R.id.tvLastSync);
        
        progressBar = view.findViewById(R.id.progressBar);
        syncOptions = view.findViewById(R.id.syncOptions);
    }
    
    private void loadConfig() {
        SyncManager.SyncConfig config = syncManager.getConfig();
        
        etServer.setText(config.serverUrl);
        etUsername.setText(config.username);
        etPassword.setText(config.password);
        swAutoSync.setChecked(config.autoSync);
        
        // 显示最后同步时间
        long lastSync = syncManager.getLastSyncTime();
        if (lastSync > 0) {
            tvLastSync.setText("上次同步: " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", lastSync));
            tvLastSync.setVisibility(View.VISIBLE);
        }
        
        // 如果已配置，显示同步选项
        if (syncManager.isConfigured()) {
            syncOptions.setVisibility(View.VISIBLE);
            btnSyncNow.setEnabled(true);
        }
    }
    
    private void setupListeners() {
        // 测试连接
        btnTest.setOnClickListener(v -> testConnection());
        
        // 保存配置
        btnSave.setOnClickListener(v -> saveConfig());
        
        // 本地备份/导入
        if (btnLocalBackup != null) {
            btnLocalBackup.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openLocalBackupExportFromSyncCenter();
                }
            });
        }
        if (btnLocalImport != null) {
            btnLocalImport.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openLocalBackupImportFromSyncCenter();
                }
            });
        }
        
        // 立即同步
        btnSyncNow.setOnClickListener(v -> startSync());
        
        // 服务器地址变化时更新 UI
        etServer.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                validateServerUrl();
            }
        });
    }
    
    private void testConnection() {
        String server = etServer.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showStatus("请填写完整的 WebDAV 配置", 0xFFFF9500);
            return;
        }
        
        btnTest.setEnabled(false);
        btnTest.setText("测试中...");
        showStatus("正在测试连接...", 0xFF8E9AB5);
        
        AppExecutors.runOnIo(() -> {
            // 创建临时客户端测试
            WebDavClient client = new WebDavClient(server, username, password);
            final String[] error = new String[]{null};
            boolean success;
            try {
                client.testConnectionOrThrow();
                success = true;
            } catch (Throwable t) {
                success = false;
                error[0] = t.getMessage();
            }
            final boolean finalSuccess = success;
            
            requireActivity().runOnUiThread(() -> {
                btnTest.setEnabled(true);
                btnTest.setText("测试连接");
                
                if (finalSuccess) {
                    showStatus("✓ 连接成功！", 0xFF34D158);
                    syncOptions.setVisibility(View.VISIBLE);
                } else {
                    showStatus("✗ 连接失败: " + (error[0] == null ? "请检查配置" : error[0]), 0xFFFF3B30);
                }
            });
        });
    }
    
    private void saveConfig() {
        String server = etServer.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        boolean autoSync = swAutoSync.isChecked();
        
        if (server.isEmpty()) {
            showStatus("请输入 WebDAV 服务器地址", 0xFFFF9500);
            return;
        }
        
        if (username.isEmpty()) {
            showStatus("请输入用户名", 0xFFFF9500);
            return;
        }
        
        if (password.isEmpty()) {
            showStatus("请输入密码/应用密码", 0xFFFF9500);
            return;
        }
        
        // 确保 URL 格式正确
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            server = "https://" + server;
            etServer.setText(server);
        }
        
        // 保存配置
        syncManager.saveConfig(server, username, password, autoSync);
        
        showStatus("✓ 配置已保存", 0xFF34D158);
        btnSyncNow.setEnabled(true);
        
        Toast.makeText(requireContext(), "WebDAV 配置已保存", Toast.LENGTH_SHORT).show();
    }
    
    private void startSync() {
        if (!syncManager.isConfigured()) {
            showStatus("请先保存 WebDAV 配置", 0xFFFF9500);
            return;
        }
        
        btnSyncNow.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        showStatus("正在同步...", 0xFF8E9AB5);
        
        syncManager.sync(new SyncManager.SyncListener() {
            @Override
            public void onSyncStart() {
                requireActivity().runOnUiThread(() -> {
                    showStatus("同步开始...", 0xFF8E9AB5);
                });
            }
            
            @Override
            public void onProgress(String item, boolean changed) {
                requireActivity().runOnUiThread(() -> {
                    showStatus("同步 " + item + (changed ? " ✓" : " (无变化)"), 0xFF8E9AB5);
                });
            }
            
            @Override
            public int onConflict(SyncManager.Conflict conflict) {
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final int[] decision = new int[]{SyncManager.RESOLVE_CANCEL};
                requireActivity().runOnUiThread(() -> {
                    String msg = "本地和云端都有修改，请选择处理方式。\n\n"
                            + "本地大小：" + (conflict.localBytes / 1024) + "KB\n"
                            + "云端大小：" + (conflict.remoteBytes / 1024) + "KB\n\n"
                            + "建议选择“智能合并”：会按游戏路径和游玩记录 UUID 去重合并，累计游玩时长取两边较大值；如果某设备执行过清除/重设时长，则以更新的重置时间为准。";
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("同步冲突")
                            .setMessage(msg)
                            .setPositiveButton("智能合并", (d, w) -> { decision[0] = SyncManager.RESOLVE_MERGE; latch.countDown(); })
                            .setNegativeButton("使用云端", (d, w) -> { decision[0] = SyncManager.RESOLVE_USE_REMOTE; latch.countDown(); })
                            .setNeutralButton("使用本地", (d, w) -> { decision[0] = SyncManager.RESOLVE_USE_LOCAL; latch.countDown(); })
                            .setOnCancelListener(d -> { decision[0] = SyncManager.RESOLVE_CANCEL; latch.countDown(); })
                            .show();
                });
                try { latch.await(); } catch (InterruptedException ignored) { }
                return decision[0];
            }
            
            @Override
            public void onSyncComplete(SyncManager.SyncResult result) {
                requireActivity().runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnSyncNow.setEnabled(true);
                    
                    if (result.hasChanges()) {
                        StringBuilder msg = new StringBuilder("同步完成：");
                        if (result.uploaded) msg.append(" 已上传");
                        if (result.downloaded) msg.append(" 已下载");
                        if (result.merged) msg.append(" 已合并");
                        msg.append(" · 本地").append(result.localBytes / 1024).append("KB");
                        showStatus(msg.toString(), 0xFF34D158);
                    } else if (result.cancelled) {
                        showStatus("已取消同步", 0xFFFF9500);
                    } else {
                        showStatus("✓ 已是最新 · " + (result.localBytes / 1024) + "KB", 0xFF34D158);
                    }
                    
                    // 更新最后同步时间
                    tvLastSync.setText("上次同步: " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", System.currentTimeMillis()));
                    tvLastSync.setVisibility(View.VISIBLE);

                    // 如果本地数据被云端覆盖或合并，刷新主界面让游戏库/资料立即生效。
                    if (result.downloaded || result.merged) {
                        Toast.makeText(requireContext(), "同步数据已应用，正在刷新界面", Toast.LENGTH_SHORT).show();
                        requireActivity().recreate();
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                requireActivity().runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnSyncNow.setEnabled(true);
                    showStatus("✗ 同步失败: " + error, 0xFFFF3B30);
                });
            }
        });
    }
    
    private void validateServerUrl() {
        String server = etServer.getText().toString().trim();
        if (!server.isEmpty() && !server.startsWith("http://") && !server.startsWith("https://")) {
            etServer.setText("https://" + server);
        }
    }
    
    private void showStatus(String msg, int color) {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
    }
    
    @Override
    public void onStart() {
        super.onStart();
        // 设置对话框大小
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.85f),
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }
}