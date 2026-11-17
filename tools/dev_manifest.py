#!/usr/bin/env python3
"""Write sytube.json for the rolling `dev-latest` prerelease.

The dev APK keeps the stable app's format (package object with per-ABI download links and one
version entry), but it has its own identity and versionCode stream (com.sytube.dev, 200000+run),
so the stable updater can never offer a dev build and vice versa.

    tools/dev_manifest.py --dist dist --out dist/sytube.json
"""
import argparse
import glob
import json
import os
import pathlib

ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
BASE = "https://github.com/Syuhadak27/SyTube/releases/download/dev-latest"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    dist = pathlib.Path(args.dist)
    name = os.environ["DEV_VERSION_NAME"]
    run_number = os.environ.get("GITHUB_RUN_NUMBER", "0")

    def find(abi):
        matches = sorted(glob.glob(str(dist / f"SyTube_*_{abi}.apk")))
        return pathlib.Path(matches[-1]) if matches else None

    package = {}
    for abi in ABIS:
        apk = find(abi)
        if apk is not None:
            package[f"downloadUrlList_{abi}"] = [f"{BASE}/{apk.name}"]
            package[f"downloadSize_{abi}"] = apk.stat().st_size

    universal = find("universal")
    if universal is None:
        raise SystemExit(f"universal APK is missing from {dist}")
    package["downloadUrlList"] = [f"{BASE}/{universal.name}"]
    package["downloadSize"] = universal.stat().st_size

    manifest = {
        "package": package,
        name: {
            "versionCode": int(os.environ["DEV_VERSION_CODE"]),
            "changelog": [f"Development build #{run_number} from the dev branch."],
        },
    }
    pathlib.Path(args.out).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
