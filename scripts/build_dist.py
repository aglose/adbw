#!/usr/bin/env python3
"""Build the distributable files into dist/:

    dist/adbw             single-file tool: the host program plus the agent APK inside
                          (a Python zipapp; run it with python3 on macOS, Linux or Windows)
    dist/adbw-agent.apk   the agent on its own

    python3 scripts/build_dist.py            # builds the APK with Gradle first
    python3 scripts/build_dist.py --apk X    # use an already-built APK

Signing: set ADBW_KEYSTORE, ADBW_KEYSTORE_PASSWORD (and optionally ADBW_KEY_ALIAS) to sign the
APK with a stable key; see agent/app/build.gradle.kts.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipapp
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", type=Path, help="prebuilt agent APK (skips Gradle)")
    args = ap.parse_args()

    version = re.search(r'__version__ = "([^"]+)"', (ROOT / "host/adbw.py").read_text()).group(1)
    apk = args.apk
    if apk is None:
        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        subprocess.run([gradlew, ":app:assembleRelease", "-q", "--console=plain"],
                       cwd=ROOT / "agent", check=True)
        apk = ROOT / "agent/app/build/outputs/apk/release/app-release.apk"
    if not apk.is_file():
        print(f"APK not found: {apk}")
        return 1

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp)
        shutil.copy(ROOT / "host/adbw.py", src / "adbw.py")
        shutil.copy(apk, src / "agent.apk")
        (src / "__main__.py").write_text("import sys\nfrom adbw import main\nsys.exit(main())\n")
        out = dist / "adbw"
        zipapp.create_archive(src, out, interpreter="/usr/bin/env python3", compressed=True)
        out.chmod(0o755)
    shutil.copy(apk, dist / "adbw-agent.apk")
    for f in (dist / "adbw", dist / "adbw-agent.apk"):
        digest = hashlib.sha256(f.read_bytes()).hexdigest()
        print(f"{f.relative_to(ROOT)}  {f.stat().st_size:>9,} bytes  sha256 {digest}")
    print(f"adbw {version} built")
    return 0


if __name__ == "__main__":
    sys.exit(main())
