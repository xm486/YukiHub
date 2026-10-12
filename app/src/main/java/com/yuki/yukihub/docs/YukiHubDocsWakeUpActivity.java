package com.yuki.yukihub.docs;

import android.app.Activity;
import android.os.Bundle;

/**
 * 空壳 Activity，只为让第三方文件管理器（MT 管理器等）能「看见」YukiHub。
 *
 * <p>Android 11+ 有包可见性限制：别的 App 默认查不到我们，
 * 也就不会在「添加本地存储」里列出 {@link YukiHubDocumentsProvider}。
 * 声明一个 exported 的 LAUNCHER 入口后，对方通过 &lt;queries&gt; 就能发现本包，
 * 进而看到我们注册的 DocumentsProvider。
 *
 * <p>本 Activity 不显示任何界面，被启动后立即结束（noHistory + NoDisplay 主题）。
 */
public class YukiHubDocsWakeUpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}