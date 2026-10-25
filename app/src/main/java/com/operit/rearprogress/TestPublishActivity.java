package com.operit.rearprogress;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 模拟发布测试页 —— 模拟 Hook 推送（token/工具名/内容），
 * 通过 su 调 cmd notification post 发一条与 Hook 相同 extras 的通知，
 * 用于背屏 MAML 模板/灵动岛渲染调试。
 */
public class TestPublishActivity extends Activity {

    private static final String TAG = "RearProgress";
    /** 测试通知 tag（用于清除） */
    private static final String TEST_TAG = "rp_test";

    private EditText etTool, etToken, etBody;
    private TextView tvResult;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int statusBarH = 0;
        try {
            statusBarH = getResources().getDimensionPixelSize(
                    getResources().getIdentifier("status_bar_height", "dimen", "android"));
        } catch (Throwable ignored) {}
        root.setPadding(20, statusBarH + 24, 20, 24);
        setContentView(root);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("模拟发布（测试页）");
        tvTitle.setTextSize(19);
        tvTitle.setPadding(0, 0, 0, 8);
        root.addView(tvTitle);

        // 说明
        TextView tvTip = new TextView(this);
        tvTip.setText("模拟 Hook 推送：直接发通知（带 lu_* extras），测背屏/灵动岛渲染。\n"
                + "工具名→lu_title  token→lu_sub_text  内容→lu_big_text");
        tvTip.setTextSize(12);
        tvTip.setPadding(0, 0, 0, 8);
        root.addView(tvTip);

        // 输入框
        etTool = MainActivity.makeInput(this, "工具名，如 Shell(root)");
        etToken = MainActivity.makeInput(this, "token 副标题，如 ↑1.05M ↓398");
        etBody = MainActivity.makeInput(this, "内容（参数摘要），多行一条");
        root.addView(label("工具名（主标题）"));
        root.addView(etTool);
        root.addView(label("token（副标题）"));
        root.addView(etToken);
        root.addView(label("内容（消息体）"));
        root.addView(etBody);

        // 按钮行
        root.addView(btnRow(
                makeButton("发布", v -> publishTest()),
                makeButton("模拟有token", v -> { etToken.setText("↑897K ↓123"); publishTest(); }),
                makeButton("模拟无token", v -> { etToken.setText("↑0 ↓0"); publishTest(); })));
        root.addView(btnRow(
                makeButton("清除输入", v -> {
                    etTool.setText(""); etToken.setText(""); etBody.setText("");
                }),
                makeButton("清除测试通知", v -> {
                    execSuSilent("cmd notification cancel " + TEST_TAG);
                    Toast.makeText(this, "已清除测试通知", Toast.LENGTH_SHORT).show();
                })));

        // 结果反馈
        tvResult = new TextView(this);
        tvResult.setTextSize(12);
        tvResult.setPadding(0, 12, 0, 0);
        root.addView(tvResult);
    }

    /** 小标题 */
    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setPadding(0, 6, 0, 2);
        return tv;
    }

    private Button makeButton(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 12, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout btnRow(Button... btns) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : btns) row.addView(b);
        return row;
    }

    /**
     * 模拟 Hook 推送：通过 su 调 cmd notification post（携带与 Hook 相同的 lu_* extras）。
     * 面板进程没有通知权限 → 必须走 su。
     */
    private void publishTest() {
        final String tool = etTool.getText().toString().trim().isEmpty()
                ? "Shell(root)" : etTool.getText().toString().trim();
        final String token = etToken.getText().toString().trim().isEmpty()
                ? "↑0 ↓0" : etToken.getText().toString().trim();
        final String body = etBody.getText().toString().trim();

        tvResult.setText("发布中…");
        new Thread(() -> {
            try {
                // 与 Hook 相同的 extras（副屏中心加 lu_ 前缀 → 模板读 lu_*）
                String extras = "--es lu_title '" + esc(tool) + "'"
                        + " --es lu_sub_text '" + esc(token) + "'"
                        + " --es lu_big_text '" + esc(body) + "'"
                        + " --es lu_hit_rate '99.50%'"
                        + " --es lu_token_in '" + esc(token) + "'"
                        + " --es lu_token_out ''"
                        + " --es lu_token_path 'M0.0,50 L120.0,30 L300.0,10 L480.0,40 L630.0,20'"
                        + " --es lu_task_time '12:00-12:30 (30m0s)'"
                        + " --es lu_time_range '30m0s'";
                String cmd = "cmd notification post -S bigtext -t '" + esc(tool) + "' '" + esc(body)
                        + "' " + TEST_TAG + " " + extras;
                String out = execSuCapture(cmd);
                final String msg = out.isEmpty() ? "已发布: " + tool : "已发布: " + tool + " (" + out.trim() + ")";
                runOnUiThread(() -> tvResult.setText(msg));
            } catch (Throwable t) {
                Log.w(TAG, "publishTest failed", t);
                runOnUiThread(() -> tvResult.setText("发布失败: " + t.getMessage()));
            }
        }).start();
    }

    /** 通过 su 执行并捕获输出 */
    private static String execSuCapture(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    /** 静默执行（su） */
    static void execSuSilent(String cmd) {
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
            } catch (Exception ignored) {}
        }).start();
    }

    /** shell 单引号转义 */
    static String esc(String s) {
        return (s == null) ? "" : s.replace("'", "'\\''");
    }
}
