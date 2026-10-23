import io

P = "/data/user/0/com.ai.assistance.operit/files/workspace/d29a12a2-17bb-423d-815e-b95f5344d606/tpl/live_update_progress/manifest.xml"
s = io.open(P, encoding="utf-8").read()
orig = s

def rep(old, new, tag):
    global s
    if old not in s:
        print("MISS [" + tag + "]")
        return False
    s = s.replace(old, new, 1)
    print("OK   [" + tag + "]")
    return True

# ============ 1. 图标移到右上角（原来在左上角跟文字打架） ============
rep(
    '  <Var name="icon_x" type="number" expression="#text_x"/>',
    '  <Var name="icon_x" type="number" expression="(#view_width - #content_pad_right - #icon_size)"/>\n'
    '  <Var name="icon_y" type="number" expression="(14 * #scale)"/>',
    "icon -> 右上角")

# 原来的 icon_y 单独一行也要去掉重复定义
rep(
    '  <Var name="icon_y" type="number" expression="(18 * #scale)"/>\n',
    '',
    "remove old icon_y")

# 图标略小一点，右上角更克制
rep(
    '  <Var name="icon_size" type="number" expression="(40 * #scale)"/>',
    '  <Var name="icon_size" type="number" expression="(34 * #scale)"/>',
    "icon 40 -> 34")

# ============ 2. 文字整体上移，给工具名腾地方 ============
# 原来 text_area_y=68*scale（190px），上移到 30*scale（84px）
rep(
    '  <Var name="text_area_y" type="number" expression="(68 * #scale)"/>',
    '  <Var name="text_area_y" type="number" expression="(26 * #scale)"/>',
    "text_area_y 68 -> 26")

# ============ 3. 进度条跟着上移，底部留出手指区 ============
rep(
    '  <Var name="bar_y" type="number" expression="(178 * #scale)"/>',
    '  <Var name="bar_y" type="number" expression="(168 * #scale)"/>',
    "bar_y 178 -> 168")

# ============ 4. 字号微调：工具名再放大，正文略缩 ============
# 工具名 20 -> 24（视觉主体更突出）
rep(
    '  <Var name="subtext_size" type="number" expression="(20 * #scale)"/>',
    '  <Var name="subtext_size" type="number" expression="(24 * #scale)"/>',
    "subtext 20 -> 24")

# 状态行 18 -> 16（退成辅角，不与工具名争）
rep(
    '  <Var name="title_size" type="number" expression="(18 * #scale)"/>',
    '  <Var name="title_size" type="number" expression="(16 * #scale)"/>',
    "title 18 -> 16")

# 参数 14 -> 13
rep(
    '  <Var name="text_size" type="number" expression="(14 * #scale)"/>',
    '  <Var name="text_size" type="number" expression="(13 * #scale)"/>',
    "text 14 -> 13")

# ============ 5. 行距调整 ============
rep(
    '  <Var name="title_gap" type="number" expression="(2 * #scale)"/>',
    '  <Var name="title_gap" type="number" expression="(6 * #scale)"/>',
    "title_gap 2 -> 6")
rep(
    '  <Var name="text_gap" type="number" expression="(0 * #scale)"/>',
    '  <Var name="text_gap" type="number" expression="(3 * #scale)"/>',
    "text_gap 0 -> 3")

# ============ 6. 状态行给个淡一点的颜色，层次更清楚 ============
rep(
    '<Text x="#text_x" y="#text_area_y" size="#title_size" color="#FFFFFF" textExp="@lu_title" width="#text_w" name="title" fontFamily="mipro-semibold" multiLine="true" lineClamp="#title_max_lines" ellipsis="true"/>',
    '<Text x="#text_x" y="#text_area_y" size="#title_size" color="#B3FFFFFF" textExp="@lu_title" width="#text_w" name="title" fontFamily="mipro-medium" multiLine="true" lineClamp="#title_max_lines" ellipsis="true"/>',
    "title 弱化为 #B3 medium")

# ============ 7. Large Icon 位置：跟着进度条走，别压在文字上 ============
rep(
    '  <Var name="large_icon_y" type="number" expression="(142 * #scale)"/>',
    '  <Var name="large_icon_y" type="number" expression="(150 * #scale)"/>',
    "large_icon_y 142 -> 150")

if s != orig:
    io.open(P, "w", encoding="utf-8").write(s)
    print("\nWROTE")
else:
    print("\nNO CHANGE")