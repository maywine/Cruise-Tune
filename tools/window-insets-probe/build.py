#!/usr/bin/env python3
"""Build a tiny standalone diagnostic APK using an existing SDK/JDK; no downloads."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--toolchain", type=Path, default=Path.home() / ".cache/cruise-tune-toolchain/paths.json")
    p.add_argument("--output", type=Path, default=ROOT / "outputs/car-bottom-bar/CruiseTune-WindowProbe-1.0.apk")
    args = p.parse_args()
    paths = json.loads(args.toolchain.read_text())
    java, sdk = Path(paths["java"]), Path(paths["sdk"])
    android = sdk / "platforms/android-36/android.jar"
    bt = sdk / "build-tools/35.0.0"
    env = dict(os.environ, JAVA_HOME=str(java))
    env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    workparent = ROOT / "outputs/car-bottom-bar"
    workparent.mkdir(parents=True, exist_ok=True)

    def run(*cmd):
        subprocess.run([str(c) for c in cmd], check=True, env=env)

    # Keep the test-only key in ignored outputs. This app is unrelated to player signing.
    key = workparent / "window-probe-debug.keystore"
    if not key.exists():
        run(java / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "probe", "-dname", "CN=Cruise Tune Window Probe", "-keyalg", "RSA", "-validity", "3650")
    with tempfile.TemporaryDirectory(prefix="window-probe-", dir=workparent) as tmp:
        work = Path(tmp)
        classes, dex = work / "classes", work / "dex"
        classes.mkdir(); dex.mkdir()
        run(java / "bin/javac", "-encoding", "UTF-8", "--release", "8", "-classpath", android,
            "-d", classes, HERE / "ProbeActivity.java")
        with zipfile.ZipFile(work / "classes.jar", "w") as jar:
            for f in classes.rglob("*.class"):
                jar.write(f, f.relative_to(classes))
        run(bt / "d8", "--lib", android, "--min-api", "23", "--output", dex, work / "classes.jar")
        run(bt / "aapt", "package", "-f", "-M", HERE / "AndroidManifest.xml", "-I", android, "-F", work / "unsigned.apk")
        with zipfile.ZipFile(work / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as apk:
            apk.write(dex / "classes.dex", "classes.dex")
        run(bt / "zipalign", "-f", "4", work / "unsigned.apk", work / "aligned.apk")
        run(bt / "apksigner", "sign", "--ks", key, "--ks-key-alias", "probe", "--ks-pass", "pass:android",
            "--key-pass", "pass:android", "--out", args.output, work / "aligned.apk")
        run(bt / "apksigner", "verify", args.output)
    print(args.output)


if __name__ == "__main__":
    main()
