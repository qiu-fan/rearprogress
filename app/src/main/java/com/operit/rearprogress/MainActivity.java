package com.operit.rearprogress;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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

import org.json.JSONObject;

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
import com.operit.rearprogress.BuildConfig;

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
        // 申请通知权限（测试按钮要发通知，未授权时系统会静默丢弃）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Throwable ignored) {}
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

        // 标题 + 右上角版本/提交编号
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, 8);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("RearProgress 控制面板");
        tvTitle.setTextSize(19);
        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvVer = new TextView(this);
        String ver = BuildConfig.VERSION_NAME + "  " + BuildConfig.GIT_COMMIT;
        tvVer.setText(ver);
        tvVer.setTextSize(12);
        tvVer.setTextColor(0xFF888888);
        titleRow.addView(tvVer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(titleRow);

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

        // —— 模拟发布：跳到独立测试页 ——
        root.addView(btnRow(
                makeButton("→ 模拟发布（测试页）", v ->
                        startActivity(new Intent(this, TestPublishActivity.class)))));

        // —— 布局参数：跳到独立布局调节页 ——
        root.addView(btnRow(
                makeButton("→ 布局参数（背屏）", v ->
                        startActivity(new Intent(this, LayoutConfigActivity.class)))));

        // —— 测试通道：模拟"其他软件"的通知，验证背屏 Agent 标签过滤 ——
        root.addView(btnRow(
                makeButton("发测试通知(BigText)", v -> sendForeignTestNotification(true)),
                makeButton("发测试通知(普通)", v -> sendForeignTestNotification(false))));
        // —— 模拟 Operit：依次"调用"10 个工具（走 recordTool + pushState） ——
        root.addView(btnRow(
                makeButton("模拟10个工具", v -> simulateTenTools())));

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
        long stamp = System.currentTimeMillis() % 10000;
        if (json) {
            // JSON 里带上变化的时间戳，否则连续点击会被 sLastTitle 去重吞掉
            content = "{\"title\":\"测试任务" + stamp + "\",\"tool\":\"Python\",\"body\":\"执行 test.py\\nAll passed\",\"percent\":75}";
        } else {
            content = "测试进度 " + stamp;
        }
        execSuSilent("echo '" + content.replace("'", "'\\''") + "' > " + PROGRESS_FILE);
        Toast.makeText(this, "已上传进度(" + (json ? "JSON" : "纯") + ")", Toast.LENGTH_SHORT).show();
    }
/**
     * 模拟 Operit 依次调用 10 个工具。
     * 做法：只往 /data/local/tmp/agent_progress.sim 写指令文件，
     * 由 Operit 进程内的 hook 轮询读取并执行 —— 走的是和真实 hook 完全相同的
     * 链路（同一个包、同一个 channel、同一条卡片），通知栏/岛/背屏表现完全一致。
     */
    private void simulateTenTools() {
        final String[] tools = {
                "Shell(root)|echo 'hello 来自模拟工具 1'",
                "GrepCode|pattern=AgentProgress path=rearprogress",
                "ReadPart|start_line=1 end_line=50 path=FocusNotify.java",
                "EditFile|path=LayoutConfigActivity.java +12 -3",
                "Proxy|tool_name=super_admin:shell command=ls /data/local/tmp",
                "WriteFile|path=tpl/layout_config.json +8 -2",
                "QueryMemory|query=背屏布局参数 hit=87.5%",
                "TokenHook|token_in=980K token_out=2.1K hit=98.2%",
                "CreateFile|path=test/mock.md content=模拟数据",
                "VisitWeb|url=https://example.com 已就绪 模拟第10个"
        };
        new Thread(() -> {
            try {
                // 确保开关文件存在（否则 Operit 侧 pushState 会直接 return）
                execSuSilent("touch " + ENABLED_FILE);
                StringBuilder sb = new StringBuilder();
                for (String t : tools) sb.append(t).append('\n');
                // 写到临时文件再 mv，避免 Operit 侧读到半截内容
                String tmp = "/data/local/tmp/.sim.tmp";
                String escaped = sb.toString().replace("'", "'\\''");
                execSuSilent("printf '%s' '" + escaped + "' > " + tmp
                        + " && mv -f " + tmp + " /data/local/tmp/agent_progress.sim");
                runOnUiThread(() -> Toast.makeText(this,
                        "已下发 10 个模拟工具，Operit 侧开始执行",
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "下发失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 发送一条"其他软件"风格的测试通知（独立 channel，无 Agent 标记）。
     * 用于验证背屏模板的 Agent 标签过滤：非 Agent 通知不应显示蓝色 Agent。
     * bigText=true 时用 BigTextStyle（模拟常见 App 的大文本通知），否则普通文本。
     */
    private void sendForeignTestNotification(boolean bigText) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "foreign_test_channel";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        channelId, "其他软件测试", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, channelId);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("微信");
            b.setContentText("收到新消息：测试一下背屏显示");
            b.setSmallIcon(android.R.drawable.stat_notify_chat);
            b.setAutoCancel(true);
            if (bigText) {
                b.setStyle(new Notification.BigTextStyle()
                        .bigText("这是来自「微信」的模拟通知内容，用于验证其他应用的通知是否会被误打 Agent 标签。\n第二行：路径 /storage/emulated/0/Download/test.txt 1234567890")
                        .setBigContentTitle("微信")
                        .setSummaryText("新消息"));
            }
            nm.notify(9911, b.build());
            Toast.makeText(this, "已发测试通知(" + (bigText ? "BigText" : "普通") + ")", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "发测试通知失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
    static EditText makeInput(Activity a, String hint) {
        EditText et = new EditText(a);
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

    /** shell 单引号转义 */
    static String esc(String s) {
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
                        case "simulate10":
                            simulateTenTools();
                            writeResult("simulate10", "已开始模拟10个工具");
                            break;
                        case "foreign_notify":
                            sendForeignTestNotification(true);
                            writeResult("foreign_notify", "已发非Operit测试通知");
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