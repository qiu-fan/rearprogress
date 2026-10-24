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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean refreshing = true;
    private boolean receiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("RearProgress 控制面板");
        tvTitle.setTextSize(22);
        tvTitle.setPadding(0, 0, 0, 16);
        root.addView(tvTitle);

        // 操作按钮区
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.addView(makeButton("强停 Operit", v -> execSu("am force-stop com.ai.assistance.operit", "已强停 Operit（需手动重启）")));
        btnRow1.addView(makeButton("清通知", v -> {
            execSu("rm -f " + ENABLED_FILE, "已清除 enabled，通知将消失");
            refreshStatus();
        }));
        root.addView(btnRow1);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.addView(makeButton("清进度残留", v -> {
            execSu("rm -f " + PROGRESS_FILE, "已清除 agent_progress.txt");
            refreshStatus();
        }));
        btnRow2.addView(makeButton("开通知", v -> {
            execSu("touch " + ENABLED_FILE, "已创建 enabled");
            refreshStatus();
        }));
        root.addView(btnRow2);

        // 第三行：重启 Operit（强停 + 拉起）
        LinearLayout btnRow3 = new LinearLayout(this);
        btnRow3.setOrientation(LinearLayout.HORIZONTAL);
        btnRow3.addView(makeButton("重启 Operit", v -> restartOperit()));
        root.addView(btnRow3);

        // 注册 AI 远程命令接收器
        registerCmdReceiver();

        // 状态显示
        TextView tvLabel = new TextView(this);
        tvLabel.setText("—— 调试状态（每 2 秒刷新）——");
        tvLabel.setPadding(0, 24, 0, 8);
        root.addView(tvLabel);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setLineSpacing(4, 1);
        root.addView(tvStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // 定时刷新
        handler.post(refreshRunnable);
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(0, 0, 16, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!refreshing) return;
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    private void refreshStatus() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();

            // 文件状态
            sb.append("enabled: ").append(exists(ENABLED_FILE) ? "✅ 存在" : "❌ 不存在").append('\n');
            sb.append("progress: ").append(exists(PROGRESS_FILE) ? "✅ 存在" : "❌ 无").append('\n');
            if (exists(PROGRESS_FILE)) {
                String content = execSuCapture("cat " + PROGRESS_FILE + " 2>/dev/null | head -3");
                if (!content.isEmpty()) sb.append("  内容: ").append(content.replace("\n", " | ")).append('\n');
            }

            // 通知状态
            String notif = execSuCapture("dumpsys notification --noredact 2>/dev/null "
                    + "| grep -A6 'id=1095189838' | grep -E 'android.title|android.subText|android.text' | head -3");
            sb.append("通知:\n");
            if (notif.isEmpty()) sb.append("  （无）\n");
            else sb.append(notif.trim()).append('\n');

            // Hook 日志（最近 8 条 RearProgress）
            String logs = execSuCapture("logcat -d -s RearProgress:V 2>/dev/null | tail -8");
            sb.append("\n—— Hook 日志 ——\n");
            sb.append(logs.isEmpty() ? "（空）" : logs.trim()).append('\n');

            // 进程
            String proc = execSuCapture("ps -A -o PID,ELAPSED,NAME 2>/dev/null | grep 'com.ai.assistance.operit' | head -1");
            sb.append("\nOperit 进程: ").append(proc.isEmpty() ? "未运行" : proc.trim()).append('\n');

            String finalText = sb.toString();
            runOnUiThread(() -> tvStatus.setText(finalText));
        }).start();
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