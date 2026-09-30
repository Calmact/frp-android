"""Preprocess bundled frp AARs for DexClassLoader loading.

jahen/frp releases ship frp.aar whose classes.jar contains raw .class files,
but FrpLibraryManager loads the library through DexClassLoader, which only
accepts dex. This script converts the .class entries to a single classes.dex
(via the Android build-tools `d8`) and repacks classes.jar so it contains
classes.dex, matching what the app expects ("Found classes.dex inside
classes.jar! Extracting...").

Usage: python3 preprocess_aar.py <d8> <android.jar> <aar paths...>
"""
import glob
import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile


def process_aar(d8, android_jar, aar_path, workdir):
    print(f"== processing {aar_path}")
    aar = zipfile.ZipFile(aar_path)
    classes_jar_bytes = aar.read("classes.jar")
    cj = zipfile.ZipFile(io.BytesIO(classes_jar_bytes))
    class_entries = [n for n in cj.namelist() if n.endswith(".class")]
    if not class_entries:
        print("   already contains no .class entries, skip")
        return False
    dex_entries = [n for n in cj.namelist() if n.endswith(".dex")]
    if dex_entries:
        print("   classes.jar already contains dex, skip")
        return False

    tmp = os.path.join(workdir, os.path.basename(aar_path) + ".dir")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    cj.extractall(tmp)
    class_files = []
    for root, _, files in os.walk(tmp):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    print(f"   {len(class_files)} .class files -> d8")

    out_dir = tmp + ".dexout"
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    cmd = [d8, "--release", "--min-api", "19", "--lib", android_jar,
           "--output", out_dir] + class_files
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("   d8 failed:", res.stdout, res.stderr)
        sys.exit(1)
    dex_file = os.path.join(out_dir, "classes.dex")
    if not os.path.exists(dex_file):
        found = glob.glob(out_dir + "/*.dex")
        if not found:
            print("   no dex produced!")
            sys.exit(1)
        dex_file = found[0]
    print(f"   dex produced: {dex_file} ({os.path.getsize(dex_file)} bytes)")

    # repack classes.jar containing only classes.dex
    new_cj = io.BytesIO()
    with zipfile.ZipFile(new_cj, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")

    # repack aar with the new classes.jar, other entries unchanged
    new_aar = io.BytesIO()
    with zipfile.ZipFile(new_aar, "w", zipfile.ZIP_DEFLATED) as z:
        for entry in aar.namelist():
            if entry == "classes.jar":
                z.writestr(entry, new_cj.getvalue())
            else:
                z.writestr(entry, aar.read(entry))
    aar.close()
    with open(aar_path, "wb") as f:
        f.write(new_aar.getvalue())
    print(f"   repacked {aar_path} ({os.path.getsize(aar_path)} bytes)")
    return True


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    d8, android_jar = sys.argv[1], sys.argv[2]
    patterns = sys.argv[3:]
    paths = []
    for p in patterns:
        paths.extend(glob.glob(p))
    with tempfile.TemporaryDirectory() as workdir:
        for aar_path in paths:
            process_aar(d8, android_jar, aar_path, workdir)


if __name__ == "__main__":
    main()
