#!/system/bin/sh
F=/data/data/io.github.hyperisland/shared_prefs/FlutterSharedPreferences.xml

# XML 转义：& -> &amp;  " -> "
# island_custom 内层 JSON 的 " 在 XML 里是 "，在 JSON 里是 \"
NEW_CONFIG='    <string name="flutter.pref_app_config_com.ai.assistance.operit">{"channels":{"settings":{"agent_rear_progress":{"focus":"off","icon":"app_icon","first_float":"on","island_outer_glow":"follow_dynamic","status_bar_icon":1,"timeout":"0","island_custom":"{\\"island_left_expr\\":\\"Agent\\",\\"island_right_expr\\":\\"${title}\\"}"}}}}</string>'

sed -i "s|</map>|${NEW_CONFIG}\n</map>|" $F

echo "=== 验证 ==="
grep 'pref_app_config_com.ai.assistance.operit' $F | head -c 800