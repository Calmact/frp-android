"""Dump gomobile bridge API surface (go/*, frpclib/*) from an apk's dex files."""
import sys
from androguard.misc import AnalyzeDex

target_substrings = ("Lgo/", "Lfrpclib/")

for dex_path in sys.argv[1:]:
    print(f"===== {dex_path} =====")
    try:
        _, d, _ = AnalyzeDex(dex_path)
    except Exception as e:
        print("analyze failed:", e)
        continue
    for m in d.get_classes():
        name = m.get_name()
        if not name.startswith(target_substrings):
            continue
        cname = name[1:-1].replace("/", ".")
        print(f"class {cname}")
        for f in m.get_fields():
            print(f"  field {f.get_descriptor()} {f.get_name()}")
        for meth in m.get_methods():
            print(f"  method {meth.get_descriptor()} {meth.get_name()}{meth.get_descriptor()}")
