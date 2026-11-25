#!/usr/bin/env python3
"""Fetch every model in models/manifest.json, verify its SHA-256, and optionally install it.

Weights are never committed (WS01-04). A fresh clone gets them one of three ways, in order:
  1. already present in models/ with the right SHA-256 -> kept;
  2. --from DIR: copied from a folder a teammate shares (USB, Drive sync) -> verified;
  3. the entry's "url" (https only, e.g. a GitHub Release asset) -> downloaded -> verified.
Anything that fails verification is deleted, never installed.

Manifest fields: name, version, file, sha256, license, source (required); url, input, output,
labels (optional); "required": false for reference/rollback copies a fresh clone does not need;
"android_asset": false for files that must not be copied into the app.

Usage:
  python scripts/fetch_models.py                    # fetch + verify into models/
  python scripts/fetch_models.py --android         # also copy app models into Android assets
  python scripts/fetch_models.py --from ~/avn-models
  python scripts/fetch_models.py --check           # verify only, no downloads, no copies
  python scripts/fetch_models.py --history         # fail if any weight file is in Git history
Works on Windows, macOS and Linux (standard library only).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
REQUIRED_FIELDS = ("name", "version", "file", "sha256", "license", "source")
WEIGHT_SUFFIXES = (".onnx", ".tflite", ".pt", ".pth", ".ckpt", ".h5", ".safetensors", ".bin")
_SHA = re.compile(r"^[0-9a-f]{64}$")
DOWNLOAD_TIMEOUT_S = 60


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest(manifest: dict) -> list[str]:
    """Problems with the manifest itself (missing fields, bad hashes, unsafe paths or URLs)."""
    problems, names = [], set()
    for i, entry in enumerate(manifest.get("models", [])):
        label = entry.get("name", f"entry {i}")
        for field in REQUIRED_FIELDS:
            if not entry.get(field):
                problems.append(f"{label}: missing '{field}'")
        if entry.get("sha256") and not _SHA.match(entry["sha256"]):
            problems.append(f"{label}: sha256 must be 64 lowercase hex characters")
        file = entry.get("file", "")
        if file and (Path(file).is_absolute() or ".." in Path(file).parts or " " in file):
            problems.append(f"{label}: 'file' must be a plain relative path inside models/")
        url = entry.get("url")
        if url and not url.startswith("https://"):
            problems.append(f"{label}: url must be https")
        if label in names:
            problems.append(f"{label}: duplicate name")
        names.add(label)
    return problems


def _verified(path: Path, expected: str) -> bool:
    return path.is_file() and sha256(path) == expected


def _install(src_tmp: Path, target: Path, expected: str, label: str) -> bool:
    actual = sha256(src_tmp)
    if actual != expected:
        print(f"FAIL  {label}: sha256 {actual[:12]}... does not match the manifest; file discarded")
        src_tmp.unlink(missing_ok=True)
        return False
    src_tmp.replace(target)
    return True


def fetch_one(entry: dict, models_dir: Path, source_dir: Path | None, check_only: bool) -> str:
    """Return 'ok', 'fetched', 'missing' or 'failed' for one manifest entry."""
    name, expected = entry["name"], entry["sha256"]
    target = models_dir / entry["file"]
    if _verified(target, expected):
        print(f"ok    {name} ({entry['file']})")
        return "ok"
    if target.exists():
        print(f"BAD   {name}: {entry['file']} exists but its SHA-256 does not match")
        if check_only:
            return "failed"
    if check_only:
        print(f"MISS  {name}: {entry['file']} not present")
        return "missing"

    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".part")

    if source_dir is not None:
        for candidate in (source_dir / entry["file"], source_dir / Path(entry["file"]).name):
            if candidate.is_file():
                shutil.copyfile(candidate, tmp)
                if _install(tmp, target, expected, name):
                    print(f"ok    {name} copied from {candidate.parent} and verified")
                    return "fetched"
                return "failed"

    url = entry.get("url")
    if not url:
        print(f"MISS  {name}: no url in the manifest. Get {entry['file']} from a teammate "
              "(--from DIR) or follow models/README.md.")
        return "missing"
    print(f"get   {name} from {url}")
    try:
        # URLs come from the reviewed manifest and must be https (validate_manifest).
        with urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT_S) as resp, \
                tmp.open("wb") as out:
            shutil.copyfileobj(resp, out, 1 << 20)
    except (OSError, ValueError) as exc:
        tmp.unlink(missing_ok=True)
        print(f"FAIL  {name}: download failed ({type(exc).__name__}: {exc})")
        return "failed"
    if _install(tmp, target, expected, name):
        print(f"ok    {name} downloaded and verified")
        return "fetched"
    return "failed"


def weights_in_history(root: Path = ROOT) -> list[str]:
    """Paths of model-weight files in any commit reachable from any ref."""
    out = subprocess.run(
        ["git", "-C", str(root), "rev-list", "--objects", "--all"],
        capture_output=True, text=True, check=True,
    ).stdout
    found = set()
    for line in out.splitlines():
        _, _, path = line.partition(" ")
        if path.lower().endswith(WEIGHT_SUFFIXES):
            found.add(path)
    return sorted(found)


def main(argv: list[str] | None = None, models_dir: Path = MODELS, assets_dir: Path = ASSETS) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--android", action="store_true", help="copy app models into Android assets")
    parser.add_argument("--from", dest="source", type=Path, help="folder holding the model files")
    parser.add_argument("--check", action="store_true", help="verify only; no downloads or copies")
    parser.add_argument("--history", action="store_true", help="fail if weights are in Git history")
    args = parser.parse_args(argv)

    if args.history:
        found = weights_in_history()
        for path in found:
            print(f"FAIL  weight file in Git history: {path}")
        print(f"{len(found)} weight file(s) in Git history")
        return 1 if found else 0

    manifest = json.loads((models_dir / "manifest.json").read_text(encoding="utf-8"))
    problems = validate_manifest(manifest)
    for p in problems:
        print(f"FAIL  manifest: {p}")
    if problems:
        return 1
    entries = manifest.get("models", [])
    if not entries:
        print("models/manifest.json lists no models yet. Nothing to fetch.")
        return 0

    source = args.source.expanduser() if args.source else None
    failures = 0
    for entry in entries:
        required = entry.get("required", True)
        result = fetch_one(entry, models_dir, source, args.check)
        if result in ("missing", "failed") and required:
            failures += 1
        elif result == "missing":
            print(f"      ({entry['name']} is optional: not needed to build or run the app)")
        if args.android and not args.check and result in ("ok", "fetched") \
                and entry.get("android_asset", True):
            dest = assets_dir / Path(entry["file"]).name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(models_dir / entry["file"], dest)
            print(f"copy  {Path(entry['file']).name} -> android assets")

    required_count = sum(1 for e in entries if e.get("required", True))
    print(f"\n{required_count} required model(s), {failures} missing or failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
