import io, re
import xml.etree.ElementTree as ET

P = "/data/user/0/com.ai.assistance.operit/files/workspace/d29a12a2-17bb-423d-815e-b95f5344d606/tpl/live_update_progress/manifest.xml"

# 1. XML 合法性
try:
    ET.parse(P)
    print("XML OK")
except Exception as e:
    print("XML FAIL:", e)

s = io.open(P, encoding="utf-8").read()

# 2. 取关键变量
def var(name):
    m = re.search(r'<Var name="' + name + r'"[^>]*expression="([^"]*)"', s)
    return m.group(1) if m else None

for n in ["icon_size", "icon_x", "icon_y", "content_x", "content_pad",
          "content_pad_right", "text_x", "text_w", "title_size",
          "text_size", "subtext_size", "text_area_y", "title_gap",
          "text_gap", "bar_y"]:
    print("  %-18s = %s" % (n, var(n)))

# 3. 在 976x596 下换算
W, H = 976, 596
sc = H / 213
def num(expr):
    try:
        e = expr.replace("#scale", str(sc)).replace("#view_width", str(W))
        e = e.replace("#view_height", str(H))
        e = e.replace("#content_pad_right", str(20 * sc))
        e = e.replace("#content_pad", str(20 * sc))
        e = e.replace("#icon_size", str(34 * sc))
        e = e.replace("#text_x", str((103 + 20) * sc))
        return round(eval(e), 1)
    except Exception as ex:
        return "?"

print()
print("976x596 实际坐标:")
for n in ["content_x", "text_x", "text_w", "icon_size", "icon_x", "icon_y",
          "text_area_y", "bar_y", "title_size", "subtext_size", "text_size"]:
    print("  %-14s %s px" % (n, num(var(n))))

icx = num(var("icon_x"))
print()
print("图标右边缘 =", round(icx + 34 * sc, 1), "/ 屏宽", W)
print("图标与文字是否重叠: 图标 x=%s, 文字右边界=%s" %
      (icx, round(num(var("text_x")) + num(var("text_w")), 1)))