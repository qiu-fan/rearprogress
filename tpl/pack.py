import zipfile, os, sys

base = "/data/user/0/com.ai.assistance.operit/files/workspace/d29a12a2-17bb-423d-815e-b95f5344d606/tpl"
src = os.path.join(base, "live_update_progress")
out = os.path.join(base, "lup_ui.zip")

if os.path.exists(out):
    os.remove(out)

z = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
z.write(os.path.join(src, "manifest.xml"), "manifest.xml")
adir = os.path.join(src, "assets")
for root, dirs, files in os.walk(adir):
    for f in files:
        p = os.path.join(root, f)
        z.write(p, os.path.relpath(p, src))
z.close()

z2 = zipfile.ZipFile(out)
print("size", os.path.getsize(out))
print("entries", [i.filename for i in z2.infolist()])
xml = z2.read("manifest.xml").decode("utf-8")
print("xml_len", len(xml))
print("has_subtext_size", "subtext_size" in xml)
print("semibold_on_sub", xml.count("mipro-semibold"))
print("custom_mark_left", "CUSTOM-V1" in xml)