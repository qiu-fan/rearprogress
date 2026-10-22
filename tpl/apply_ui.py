import io, sys

P = "/data/user/0/com.ai.assistance.operit/files/workspace/d29a12a2-17bb-423d-815e-b95f5344d606/tpl/live_update_progress/manifest.xml"
s = io.open(P, encoding="utf-8").read()
orig = s

def rep(old, new, n=1, tag=""):
    global s
    if old not in s:
        print("MISS[" + tag + "]")
        return False
    s = s.replace(old, new, n)
    print("OK  [" + tag + "]")
    return True

# ---- 1. 新增 subtext 专用字号：工具名比标题更大 ----
rep(
    '<Var name="text_size" type="number" expression="(14 * #scale)"/>',
    '<Var name="text_size" type="number" expression="(14 * #scale)"/>'
    '<Var name="subtext_size" type="number" expression="(20 * #scale)"/>',
    1, "subtext_size var")

# ---- 2. 无工具名时不给 subtext 留空行，让 title/body 紧贴 ----
# subtext_y 已经是动态的，OK

# ---- 3. 测量行：subtext 用新字号 + semibold（放大加粗的主体） ----
rep(
    '<Text x="0" y="0" size="#text_size" textExp="@lu_sub_text" width="#text_w" name="subtext_measure" fontFamily="mipro-medium" multiLine="true" lineClamp="#subtext_max_lines" ellipsis="true" alpha="0"/>',
    '<Text x="0" y="0" size="#subtext_size" textExp="@lu_sub_text" width="#text_w" name="subtext_measure" fontFamily="mipro-semibold" multiLine="true" lineClamp="#subtext_max_lines" ellipsis="true" alpha="0"/>',
    1, "subtext_measure size/bold")

# ---- 4. 自然测量行同样改用新字号（影响行数估算） ----
rep(
    '<Text x="0" y="0" size="#text_size" textExp="@lu_sub_text" name="subtext_natural" fontFamily="mipro-medium" alpha="0"/>',
    '<Text x="0" y="0" size="#subtext_size" textExp="@lu_sub_text" name="subtext_natural" fontFamily="mipro-semibold" alpha="0"/>',
    1, "subtext_natural size/bold")

# ---- 5. 显示行：工具名放大、纯白、加粗 ----
rep(
    '<Text visibility="#has_subtext" x="#text_x" y="#subtext_y" size="#text_size" color="#CCFFFFFF" textExp="@lu_sub_text" width="#text_w" name="content_subtext" fontFamily="mipro-medium" multiLine="true" lineClamp="#subtext_max_lines" ellipsis="true"/>',
    '<Text visibility="#has_subtext" x="#text_x" y="#subtext_y" size="#subtext_size" color="#FFFFFFFF" textExp="@lu_sub_text" width="#text_w" name="content_subtext" fontFamily="mipro-semibold" multiLine="true" lineClamp="#subtext_max_lines" ellipsis="true"/>',
    1, "content_subtext style")

# ---- 6. 参数行弱化：更暗的白 + 小字（保持 14） ----
rep(
    '<Text visibility="#has_text" x="#text_x" y="#text_y" size="#text_size" color="#CCFFFFFF" textExp="@lu_text" width="#text_w" name="content_text" fontFamily="mipro-medium" multiLine="true" lineClamp="#text_max_lines" ellipsis="true"/>',
    '<Text visibility="#has_text" x="#text_x" y="#text_y" size="#text_size" color="#99FFFFFF" textExp="@lu_text" width="#text_w" name="content_text" fontFamily="mipro-regular" multiLine="true" lineClamp="#text_max_lines" ellipsis="true"/>',
    1, "content_text dim")

if s != orig:
    io.open(P, "w", encoding="utf-8").write(s)
    print("WROTE")
else:
    print("NO CHANGE")
