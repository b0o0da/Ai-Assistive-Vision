#!/usr/bin/env python3
"""Import reusable Egyptian vehicle image candidates from Wikimedia Commons.

This fetches source images and license/attribution metadata only. It does not label images.
Uses the public MediaWiki API with a descriptive User-Agent and modest request spacing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
from pathlib import Path
from typing import Any
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
API = "https://commons.wikimedia.org/w/api.php"
DEFAULT_OUT = ROOT / "datasets/data/detection/egyptian"
CATEGORIES = {
    "tuk_tuk": "Category:Tuk-tuks in Egypt",
    "microbus": "Category:Microbuses in Cairo",
}
# Allow only licenses with clear downstream reuse rights. Keep the exact license per file.
ALLOWED_LICENSE = re.compile(
    r"^(?:CC0(?:\s|$)|Public domain(?:\s|$)|\s*Public Domain(?:\s|$)|"
    r"CC BY(?:\s|$)|CC BY-SA(?:\s|$))",
    re.IGNORECASE,
)


def get_json(params: dict[str, str]) -> dict[str, Any]:
    url = f"{API}?{urlencode(params)}"
    request = Request(url, headers={"User-Agent": "EgyptianAssistiveVisionDataset/1.0 (research project)"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def safe_name(title: str, extension: str) -> str:
    stem = Path(title.removeprefix("File:")).stem
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("._")[:120] or "image"
    return f"{stem}{extension}"


def category_files(category: str, limit: int) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    continuation: dict[str, str] = {}
    while len(result) < limit:
        params = {
            "action": "query", "format": "json", "formatversion": "2",
            "generator": "categorymembers", "gcmtitle": category,
            "gcmtype": "file", "gcmlimit": str(min(50, limit - len(result))),
            "prop": "imageinfo", "iiprop": "url|mime|extmetadata", "iiurlwidth": "1280",
        }
        params.update(continuation)
        try:
            payload = get_json(params)
        except URLError as exc:
            raise RuntimeError(
                "Could not reach Wikimedia Commons. Check internet access, then rerun this command."
            ) from exc
        if "error" in payload:
            raise RuntimeError(f"Wikimedia Commons API error: {payload['error'].get('info', 'unknown error')}")
        pages = payload.get("query", {}).get("pages", [])
        result.extend(page for page in pages if page.get("imageinfo"))
        continuation = payload.get("continue", {})
        if not continuation or not pages:
            break
    return result[:limit]


def clean_html(value: str) -> str:
    return re.sub(r"<[^>]+>", "", value or "").strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--max-per-category", type=int, default=50)
    args = parser.parse_args()
    if args.max_per_category < 1:
        parser.error("--max-per-category must be positive")

    image_dir = args.output / "images/commons"
    metadata_dir = args.output / "metadata"
    image_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = metadata_dir / "commons.jsonl"
    existing: set[str] = set()
    if manifest_path.exists():
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
                existing.add(record.get("page_url", ""))
            except json.JSONDecodeError:
                continue

    added = skipped = 0
    with manifest_path.open("a", encoding="utf-8") as manifest:
        for class_candidate, category in CATEGORIES.items():
            for page in category_files(category, args.max_per_category):
                info = page["imageinfo"][0]
                ext = info.get("extmetadata", {})
                license_name = clean_html(ext.get("LicenseShortName", {}).get("value", ""))
                if not ALLOWED_LICENSE.match(license_name):
                    skipped += 1
                    continue
                page_url = info.get("descriptionurl", "")
                if not page_url or page_url in existing:
                    skipped += 1
                    continue
                # Commons recommends thumbnails when downloading batches to reduce server load.
                image_url = info.get("thumburl") or info.get("url")
                mime = info.get("thumbmime") or info.get("mime", "")
                extension = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}.get(mime)
                if not extension or not image_url:
                    skipped += 1
                    continue

                request = Request(image_url, headers={"User-Agent": "EgyptianAssistiveVisionDataset/1.0 (research project)"})
                try:
                    with urlopen(request, timeout=45) as response:
                        content = response.read()
                except Exception as exc:  # keep processing remaining files
                    print(f"Download failed: {page.get('title')}: {exc}")
                    time.sleep(2)
                    continue
                digest = hashlib.sha256(content).hexdigest()
                filename = f"{digest[:12]}_{safe_name(page.get('title', ''), extension)}"
                destination = image_dir / filename
                if not destination.exists():
                    destination.write_bytes(content)
                record = {
                    "file": str(destination.relative_to(ROOT)),
                    "sha256": digest,
                    "page_title": page.get("title", ""),
                    "page_url": page_url,
                    "source_file_url": info["url"],
                    "download_url": image_url,
                    "candidate_class": class_candidate,
                    "source_category": category,
                    "license": license_name,
                    "license_url": ext.get("LicenseUrl", {}).get("value", ""),
                    "author": clean_html(ext.get("Artist", {}).get("value", "")),
                    "credit": clean_html(ext.get("Credit", {}).get("value", "")),
                    "attribution": clean_html(ext.get("Attribution", {}).get("value", "")),
                    "downloaded_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "annotation_status": "unlabeled",
                }
                manifest.write(json.dumps(record, ensure_ascii=False) + "\n")
                manifest.flush()
                existing.add(page_url)
                added += 1
                print(f"Imported candidate ({class_candidate}): {filename} [{license_name}]")
                time.sleep(2)
    print(f"Done: imported={added}, skipped_or_already_present={skipped}")
    print(f"Images: {image_dir}")
    print(f"Provenance: {manifest_path}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as exc:
        raise SystemExit(str(exc)) from exc
