package com.operit.rearprogress;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 布局参数调节页（软布局）—— 独立页面，不影响主页面日志。
 *
 * 功能：
 *   1. 显示/编辑背屏布局参数（scale 单位）
 *   2. 应用到模板（替换 manifest.xml 的 Var 表达式 → 打包 → 部署 → 重启副屏中心）
 *   3. 备份/恢复布局模板
 */
public class LayoutConfigActivity extends Activity {

    /** 模板路径 */
    private static final String LAYOUT_TPL = "/data/local/tmp/rp_tpl/live_update_big_text";
    private static final String LAYOUT_BACKUP = "/sdcard/Download/RearProgress_layout_backup.zip";
    private static final String LAYOUT_ZIP = "/data/local/tmp/rp_layout.zip";
    private static final String UNZIP_DIR = "/data/local/tmp/rp_layout_dir";

    /** 布局参数键名、默认值、说明 */
    private static final String[][] PARAMS = {
            {"content_x",       "103",   "内容区左边界(避开摄像头)"},
            {"content_pad",     "20",    "内容区内边距"},
            {"area_top",        "12",    "顶部安全边距"},
            {"area_bottom_sub", "1.8",   "底部边距(整体垂直居中微调)"},
            {"agent_offset",    "11.8",  "A Agent蓝字位置"},
            {"base_shift",    "2",    "整体上移量(scale)"},
            {"agent_shift",   "10.9",  "长内容上移量(仅无标记,scale)"},
            {"head_offset",     "2",     "B 工具名相对A偏移"},
            {"token_offset",    "5.2",   "token行(B下C上)上移"},
            {"body_offset",     "0",     "C 消息偏移"},
            {"chart_offset",    "7.4",   "D 图表+任务时间偏移"},
            {"title_gap",       "6",     "行间距"},
            {"title_size",      "18",    "标题字号"},
            {"text_size",       "14",    "正文字号"},
    };

    /** 输入框引用（key → EditText） */
    private final Map<String, EditText> inputs = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 根布局：ScrollView 包 LinearLayout
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int statusBarH = 0;
        try {
            statusBarH = getResources().getDimensionPixelSize(
                    getResources().getIdentifier("status_bar_height", "dimen", "android"));
        } catch (Throwable ignored) {}
        root.setPadding(20, statusBarH + 16, 20, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("布局参数（背屏）");
        tvTitle.setTextSize(17);
        tvTitle.setPadding(0, 0, 0, 8);
        root.addView(tvTitle);

        // 提示
        TextView tvTip = new TextView(this);
        tvTip.setText("参数为 scale 单位，实际像素 = 值 × scale\nscale = view_height / 213");
        tvTip.setTextSize(11);
        tvTip.setTextColor(Color.GRAY);
        tvTip.setPadding(0, 0, 0, 12);
        root.addView(tvTip);

        // 参数输入区
        for (String[] p : PARAMS) {
            String key = p[0], def = p[1], desc = p[2];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 4, 0, 4);

            TextView tv = new TextView(this);
            tv.setText(key);
            tv.setTextSize(12);
            tv.setWidth(260);
            row.addView(tv);

            EditText et = new EditText(this);
            et.setTextSize(12);
            et.setSingleLine(true);
            et.setText(def);
            et.setWidth(160);
            et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            inputs.put(key, et);
            row.addView(et);

            TextView td = new TextView(this);
            td.setText("  " + desc);
            td.setTextSize(10);
            td.setTextColor(Color.GRAY);
            row.addView(td);

            root.addView(row);
        }

        // 操作按钮
        root.addView(btnRow(
                makeButton("应用到模板", v -> applyLayout()),
                makeButton("恢复默认", v -> restoreDefaults())));
        root.addView(btnRow(
                makeButton("备份到SD卡", v -> backup()),
                makeButton("从备份恢复", v -> restoreFromBackup())));
    }

    // ---------------------------------------------------------------- 按钮辅助

    private android.widget.Button makeButton(String text, android.view.View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setPadding(8, 4, 8, 4);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout btnRow(android.widget.Button... btns) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (android.widget.Button b : btns) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(2, 2, 2, 2);
            row.addView(b, lp);
        }
        return row;
    }

    // ---------------------------------------------------------------- 核心逻辑

    /** 应用布局参数到模板 */
    private void applyLayout() {
        new Thread(() -> {
            try {
                // 1. 拉取模板
                execSuSilent("cp " + LAYOUT_TPL + " " + LAYOUT_ZIP);
                // 2. 解压
                execSuSilent("rm -rf " + UNZIP_DIR + " && mkdir -p " + UNZIP_DIR);
                execSuSilent("cd " + UNZIP_DIR + " && unzip -o " + LAYOUT_ZIP + " manifest.xml");
                File mf = new File(UNZIP_DIR + "/manifest.xml");
                if (!mf.exists()) throw new Exception("模板解压失败");

                // 3. 读 manifest.xml
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(mf), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                String xml = sb.toString();

                // 4. 替换参数
                for (String[] p : PARAMS) {
                    String key = p[0];
                    EditText et = inputs.get(key);
                    if (et == null) continue;
                    final String[] val = {""};
                    runOnUiThread(() -> val[0] = et.getText().toString().trim());
                    Thread.sleep(50); // 等待 UI 线程回填
                    if (val[0].isEmpty()) continue;

                    Pattern pat = Pattern.compile(
                            "(<Var\\s+name=\"" + Pattern.quote(key) +
                            "\"[^>]*?expression=\")([^\"]*)(\")");
                    Matcher m = pat.matcher(xml);
                    if (m.find()) {
                        String newExpr;
                        if (key.equals("area_bottom_sub")) {
                            newExpr = "((213 - 20 - 12 - " + val[0] + ") * #scale)";
                        } else {
                            newExpr = "(" + val[0] + " * #scale)";
                        }
                        xml = m.replaceFirst(Matcher.quoteReplacement(
                                m.group(1) + newExpr + m.group(3)));
                    }
                }

                // 5. 写回
                try (OutputStream os = new FileOutputStream(mf)) {
                    os.write(xml.getBytes("UTF-8"));
                }

                // 6. 打包部署
                execSuSilent("cd " + UNZIP_DIR + " && rm -f " + LAYOUT_ZIP + " && zip -q " + LAYOUT_ZIP + " manifest.xml");
                execSuSilent("cp " + LAYOUT_ZIP + " " + LAYOUT_TPL);
                // 7. 重启副屏中心
                execSuSilent("am force-stop com.xiaomi.subscreencenter");

                runOnUiThread(() -> Toast.makeText(this,
                        "布局已应用，副屏中心重启中…", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "应用失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 恢复默认值 */
    private void restoreDefaults() {
        for (String[] p : PARAMS) {
            EditText et = inputs.get(p[0]);
            if (et != null) et.setText(p[1]);
        }
        Toast.makeText(this, "已恢复默认值（点应用生效）", Toast.LENGTH_SHORT).show();
    }

    /** 备份到 SD 卡 */
    private void backup() {
        new Thread(() -> {
            try {
                execSuSilent("cp " + LAYOUT_TPL + " " + LAYOUT_BACKUP);
                runOnUiThread(() -> Toast.makeText(this,
                        "已备份到: " + LAYOUT_BACKUP, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "备份失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 从备份恢复 */
    private void restoreFromBackup() {
        new Thread(() -> {
            try {
                if (!new File(LAYOUT_BACKUP).exists()) throw new Exception("备份文件不存在");
                execSuSilent("cp " + LAYOUT_BACKUP + " " + LAYOUT_TPL);
                execSuSilent("am force-stop com.xiaomi.subscreencenter");
                runOnUiThread(() -> Toast.makeText(this,
                        "已恢复备份，副屏中心重启中…", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "恢复失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 静默执行 su 命令 */
    private void execSuSilent(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception ignored) {}
    }
}
