package com.operit.rearprogress;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * RearProgress 控制面板 —— 调试信息 + 快捷操作。
 *
 * 注意：这是 Xposed 模块的 UI 入口（宿主进程），权限有限：
 *   - 读文件 /data/local/tmp/* 需要 root（模块本身没 root，靠 su 兜底）
 *   - am/pm 命令通过 su 执行
 */
public class MainActivity extends Activity {

    private static final String TAG = "RearProgress";
    private static final String ENABLED_FILE = "/data/local/tmp/agent_progress.enabled";
    private static final String PROGRESS_FILE = "/data/local/tmp/agent_progress.txt";
    private static final String RESULT_FILE = "/data/local/tmp/rp_cmd_result.txt";
    private static final String OPERIT_PKG = "com.ai.assistance.operit";

    /** AI 远程命令广播 action */
    private static final String ACTION_CMD = "com.operit.rearprogress.CMD";

    private TextView tvStatus;
    private TextView tvLog;
    private ScrollView logScroll;
    /** 模拟发布输入框 */
    private EditText etTool, etToken, etBody;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean refreshing = true;
    private boolean receiverRegistered = false;
    /** 日志过滤：0=全部 1=仅工具 2=仅错误 */
    private int logFilter = 0;
    /** 自动滚动日志到底部 */
    private boolean autoScroll = true;
    /** 日志行数上限（防止 OOM） */
    private static final int LOG_MAX_LINES = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 去掉窗口标题栏（避免占用顶部空间遮挡按钮）
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        // ===== 根布局：LinearLayout（固定头 + 日志占满剩余） =====
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // top 加大，避开状态栏高度（NO_TITLE 后内容会顶到状态栏）
        int statusBarH = 0;
        try {
            statusBarH = getResources().getDimensionPixelSize(
                    getResources().getIdentifier("status_bar_height", "dimen", "android"));
        } catch (Throwable ignored) {}
        root.setPadding(20, statusBarH + 24, 20, 24);
        setContentView(root);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("RearProgress 控制面板");
        tvTitle.setTextSize(19);
        tvTitle.setPadding(0, 0, 0, 8);
        root.addView(tvTitle);

        // ===== 操作区（固定高度） =====
        root.addView(sectionLabel("操作"));
        root.addView(btnRow(
                makeButton("重启 Operit", v -> restartOperit()),
                makeButton("强停", v -> execSu("am force-stop " + OPERIT_PKG, "已强停")),
                makeButton("重启背屏", v -> restartSubScreenCenter())));
        root.addView(btnRow(
                makeButton("开通知", v -> { execSu("touch " + ENABLED_FILE, "已开"); refreshStatus(); }),
                makeButton("清通知", v -> { execSu("rm -f " + ENABLED_FILE, "已清"); refreshStatus(); })));
        root.addView(btnRow(
                makeButton("清进度", v -> { execSu("rm -f " + PROGRESS_FILE, "已清"); refreshStatus(); }),
                makeButton("清日志", v -> { execSuSilent("logcat -c"); tvLog.setText("（日志已清空）"); })));

        // ===== 测试区（含模拟发布页，核心调试） =====
        root.addView(sectionLabel("测试"));
        root.addView(btnRow(
                makeButton("测进度(纯)", v -> testProgress(false)),
                makeButton("测进度(JSON)", v -> testProgress(true)),
                makeButton("测重启广播", v -> sendCmd("restart_operit")),
                makeButton("测状态", v -> sendCmd("status"))));

        // —— 模拟发布页：模拟 Hook 推送通知（token/工具名/内容） ——
        root.addView(sectionLabel("模拟发布（模拟 Hook 推送）"));
        etTool = makeInput("工具名，如 Shell(root)");
        etToken = makeInput("token 副标题，如 ↑1.05M ↓398");
        etBody = makeInput("内容（参数摘要），多行一条");
        root.addView(etTool);
        root.addView(etToken);
        root.addView(etBody);
        root.addView(btnRow(
                makeButton("发布", v -> publishTest()),
                makeButton("模拟有token", v -> { etToken.setText("↑897K ↓123"); publishTest(); }),
                makeButton("模拟无token", v -> { etToken.setText("↑0 ↓0"); publishTest(); }),
                makeButton("清除", v -> {
                    etTool.setText(""); etToken.setText(""); etBody.setText("");
                    Toast.makeText(this, "已清除测试输入", Toast.LENGTH_SHORT).show();
                })));
        root.addView(btnRow(
                makeButton("清除测试通知", v -> execSuSilent("cmd notification cancel tag:rp_test"))));

        // 注册 AI 远程命令接收器
        registerCmdReceiver();

        // ===== 状态（一行） =====
        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, 8, 0, 2);
        root.addView(tvStatus);

        // ===== 日志区（核心，weight=1 占满剩余 + 内部滚动） =====
        root.addView(sectionLabel("日志（核心）"));
        root.addView(btnRow(
                makeButton("全部", v -> { logFilter = 0; refreshLog(); }),
                makeButton("仅工具", v -> { logFilter = 1; refreshLog(); }),
                makeButton("仅错误", v -> { logFilter = 2; refreshLog(); }),
                makeButton("滚动:开", v -> {
                    autoScroll = !autoScroll;
                    ((Button) v).setText(autoScroll ? "滚动:开" : "滚动:关");
                })));

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setLineSpacing(2, 1);
        tvLog.setText("（日志加载中…）");

        logScroll = new ScrollView(this);
        // weight=1：占满按钮区之外的全部剩余高度（日志能显示完整）
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(logLp);
        logScroll.addView(tvLog);
        root.addView(logScroll);

        // 定时刷新（状态 + 日志）
        handler.post(refreshRunnable);
    }

    /** 分区标题 */
    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText("—— " + text + " ——");
        tv.setTextSize(14);
        tv.setPadding(0, 16, 0, 6);
        return tv;
    }

    /** 按钮：wrap_content 宽度（不依赖 weight，避免布局坑） */
    private Button makeButton(String text, View.OnClickListener l) {
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

    /** 一行按钮（水平） */
    private LinearLayout btnRow(Button... btns) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : btns) row.addView(b);
        return row;
    }

    /** 发送命令广播 */
    private void sendCmd(String cmd) {
        sendBroadcast(new Intent(ACTION_CMD).putExtra("cmd", cmd).setPackage(getPackageName()));
        Toast.makeText(this, "已发: " + cmd, Toast.LENGTH_SHORT).show();
    }

    /** 测试上传进度（纯文字 / JSON） */
    private void testProgress(boolean json) {
        String content;
        if (json) {
            content = "{\"title\":\"测试任务\",\"tool\":\"Python\",\"body\":\"执行 test.py\\nAll passed\",\"percent\":75}";
        } else {
            content = "测试进度 " + System.currentTimeMillis() % 1000;
        }
        execSuSilent("echo '" + content.replace("'", "'\\''") + "' > " + PROGRESS_FILE);
        Toast.makeText(this, "已上传进度(" + (json ? "JSON" : "纯") + ")", Toast.LENGTH_SHORT).show();
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!refreshing) return;
            refreshStatus();
            refreshLog();
            handler.postDelayed(this, 2000);
        }
    };

    /** 简要状态（一行） */
    private void refreshStatus() {
        new Thread(() -> {
            String enabled = exists(ENABLED_FILE) ? "enabled✓" : "enabled✗";
            String proc = execSuCapture("pidof " + OPERIT_PKG);
            String p = proc.isEmpty() ? "operit✗" : "operit✓";
            String title = execSuCapture("dumpsys notification --noredact 2>/dev/null "
                    + "| grep -A2 'id=1095189838' | grep 'android.title=' | head -1 "
                    + "| sed 's/.*title=String (//;s/)//'");
            String line = enabled + "  " + p + "  title=" + (title.isEmpty() ? "?" : title.trim());
            runOnUiThread(() -> tvStatus.setText(line));
        }).start();
    }

    /** 刷新日志（核心）：按过滤显示最近 N 行，分颜色高亮 */
    private void refreshLog() {
        new Thread(() -> {
            String filterArg;
            switch (logFilter) {
                case 1: filterArg = " | grep -E 'tool-started|tool start|tool ok|tool fail|pushState|token'"; break;
                case 2: filterArg = " | grep -iE 'fail|error|exception|WARN'"; break;
                default: filterArg = ""; break;
            }
            String cmd = "logcat -d -s RearProgress:V 2>/dev/null | tail -" + LOG_MAX_LINES + filterArg;
            String logs = execSuCapture(cmd);
            if (logs.isEmpty()) logs = "（无日志）";
            final String out = logs;
            runOnUiThread(() -> {
                tvLog.setText(colorize(out));
                if (autoScroll) {
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        }).start();
    }

    /**
     * 日志分色：
     *   红   = fail/error/exception
     *   黄   = WARN
     *   绿   = tool-started / tool start / tool ok / HOOKED
     *   青   = token / pushState / finalized
     *   灰   = 其他
     */
    private android.text.SpannableString colorize(String text) {
        android.text.SpannableString span = new android.text.SpannableString(text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // 定义规则：颜色 + 关键词正则
        Object[][] rules = {
                {0xFFFF5555, "(?i)(fail|error|exception)"},
                {0xFFFFB86C, "(?i)(warn)"},
                {0xFF50FA7B, "(?i)(tool-started|tool start|tool ok|hooked|installed)"},
                {0xFF8BE9FD, "(?i)(token|pushstate|finalized|tool-exec)"},
        };
        for (Object[] rule : rules) {
            int color = (Integer) rule[0];
            String regex = (String) rule[1];
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
                while (m.find()) {
                    span.setSpan(new android.text.style.ForegroundColorSpan(color),
                            m.start(), m.end(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } catch (Throwable ignored) {}
        }
        return span;
    }

    // ---------------------------------------------------------------- 工具

    private boolean exists(String path) {
        return new File(path).exists();
    }

    /** 通过 su 执行命令，弹 Toast 反馈 */
    private void execSu(String cmd, String msg) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.w(TAG, "execSu failed", e);
                runOnUiThread(() -> Toast.makeText(this, "执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 单行输入框（带提示） */
    private EditText makeInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13);
        et.setSingleLine(false);
        et.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 4);
        et.setLayoutParams(lp);
        return et;
    }

    /**
     * 模拟 Hook 推送：直接调 FocusNotify.pushState（模拟 token/工具名/内容三段），
     * 等价于 Hook 捕获到工具调用+token 后的推送，用于背屏/灵动岛渲染调试。
     */
    private void publishTest() {
        final String tool = etTool.getText().toString().trim().isEmpty()
                ? "Shell(root)" : etTool.getText().toString().trim();
        final String token = etToken.getText().toString().trim().isEmpty()
                ? "↑0 ↓0" : etToken.getText().toString().trim();
        final String body = etBody.getText().toString().trim();
        new Thread(() -> {
            try {
                // 面板进程没有通知权限 → 用 su 直接调 cmd notification post（携带与 Hook 相同的 extras）
                String pkg = getPackageName();
                String extras = "--es lu_title '" + esc(tool) + "'"
                        + " --es lu_sub_text '" + esc(token) + "'"
                        + " --es lu_big_text '" + esc(body) + "'"
                        + " --es lu_hit_rate '99.50%'"
                        + " --es lu_token_in '" + esc(token) + "'"
                        + " --es lu_token_out ''"
                        + " --es lu_token_path 'M0.0,50 L120.0,30 L300.0,10 L480.0,40 L630.0,20'"
                        + " --es lu_task_time '12:00-12:30 (30m0s)'"
                        + " --es lu_time_range '30m0s'";
                execSuSilent("cmd notification post -S bigtext -t '" + esc(tool) + "' '" + esc(body)
                        + "' tag:rp_test " + extras);
                runOnUiThread(() -> Toast.makeText(this, "已发布: " + tool, Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                Log.w(TAG, "publishTest failed", t);
                runOnUiThread(() -> Toast.makeText(this, "发布失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** shell 单引号转义 */
    private static String esc(String s) {
        return (s == null) ? "" : s.replace("'", "'\\''");
    }

    /** 重启副屏中心（负责 MAML 背屏渲染的进程）：强停后系统会自动拉起 */
    private void restartSubScreenCenter() {
        Toast.makeText(this, "正在重启副屏中心…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                execSuSilent("am force-stop com.xiaomi.subscreencenter");
                Thread.sleep(2000);
                // 系统会自动重启（它是常驻服务）；如未拉起，兜底 startservice
                execSuSilent("am startservice -n com.xiaomi.subscreencenter/.smartassistant.SmartAssistantService >/dev/null 2>&1");
                runOnUiThread(() -> Toast.makeText(this, "副屏中心已重启", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---------------------------------------------------------------- 重启 & 远程命令

    /** 重启 Operit：强停 + 延迟拉起 */
    private void restartOperit() {
        Toast.makeText(this, "正在重启 Operit…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                execSuSilent("am force-stop " + OPERIT_PKG);
                Thread.sleep(1500);
                execSuSilent("monkey -p " + OPERIT_PKG + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1");
                writeResult("restart_operit", "已重启 Operit");
                runOnUiThread(() -> Toast.makeText(this, "Operit 已重启", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                writeResult("restart_operit", "失败: " + e.getMessage());
            }
        }).start();
    }

    /** 注册 AI 远程命令接收器 */
    private void registerCmdReceiver() {
        if (receiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String cmd = intent.getStringExtra("cmd");
                    if (cmd == null) cmd = "";
                    Log.i(TAG, "AI cmd received: " + cmd);
                    switch (cmd) {
                        case "restart_operit":
                            restartOperit();
                            break;
                        case "kill_operit":
                            execSuSilent("am force-stop " + OPERIT_PKG);
                            writeResult("kill_operit", "已强停 Operit");
                            break;
                        case "status":
                            writeResult("status", collectStatus());
                            break;
                        default:
                            writeResult(cmd, "未知命令: " + cmd);
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_CMD);
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            Log.i(TAG, "AI cmd receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "registerCmdReceiver failed", t);
        }
    }

    /** 收集简要状态（供 AI 查询） */
    private String collectStatus() {
        String enabled = exists(ENABLED_FILE) ? "enabled=yes" : "enabled=no";
        String proc = execSuCapture("pidof " + OPERIT_PKG);
        String pid = proc.isEmpty() ? "operit=stopped" : ("operit=running pid=" + proc.trim());
        String title = execSuCapture("dumpsys notification --noredact 2>/dev/null "
                + "| grep -A2 'id=1095189838' | grep 'android.title=' | head -1 | sed 's/.*title=String (//;s/)//'");
        return enabled + " " + pid + " title=" + (title.isEmpty() ? "?" : title.trim());
    }

    /** 把命令结果写文件（供 AI 读取） */
    private void writeResult(String cmd, String result) {
        try {
            String content = "cmd=" + cmd + "\nresult=" + result + "\ntime=" + System.currentTimeMillis();
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "echo " + android.util.Base64.encodeToString(content.getBytes(), android.util.Base64.NO_WRAP)
                            + " | base64 -d > " + RESULT_FILE});
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "writeResult failed", e);
        }
    }

    /** 静默执行 su 命令（不弹 Toast） */
    private void execSuSilent(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /** 通过 su 执行命令并捕获输出（失败返回空串） */
    private String execSuCapture(String cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            br.close();
            p.waitFor();
        } catch (Exception ignored) {}
        return out.toString().trim();
    }

    @Override
    protected void onDestroy() {
        refreshing = false;
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }
}