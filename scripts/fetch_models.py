#!/usr/bin/env python3
"""Download every model listed in models/manifest.json and verify its SHA-256.

Usage:
  python scripts/fetch_models.py            # download into models/
  python scripts/fetch_models.py --android  # also copy into android/app/src/main/assets/
Works on Windows, macOS and Linux (standard library only).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android", action="store_true", help="copy models into the app assets")
    args = parser.parse_args()

    manifest = json.loads((MODELS / "manifest.json").read_text(encoding="utf-8"))
    entries = manifest.get("models", [])
    if not entries:
        print("models/manifest.json lists no models yet. Nothing to fetch.")
        return 0

    failures = 0
    for entry in entries:
        target = MODELS / entry["file"]
        if target.exists() and sha256(target) == entry["sha256"]:
            print(f"ok    {entry['name']} ({entry['file']}) already present")
        else:
            url = entry.get("url")
            if not url:
                print(
                    f"MISS  {entry['name']}: {target} is absent or has the wrong SHA-256. "
                    "Follow the model source/export instructions in models/README.md."
                )
                failures += 1
                continue
            print(f"get   {entry['name']} from {url}")
            tmp = target.with_suffix(target.suffix + ".part")
            urllib.request.urlretrieve(url, tmp)  # noqa: S310 (URLs come from the reviewed manifest)
            actual = sha256(tmp)
            if actual != entry["sha256"]:
                print(f"FAIL  {entry['name']}: sha256 {actual} does not match the manifest")
                tmp.unlink(missing_ok=True)
                failures += 1
                continue
            tmp.replace(target)
            print(f"ok    {entry['name']} verified")
        if args.android:
            ASSETS.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, ASSETS / entry["file"])
            print(f"copy  {entry['file']} -> android assets")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
