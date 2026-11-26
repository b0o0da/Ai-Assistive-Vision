"""WS09-02 tools for the EGP banknote fixture set.

The photos never go into Git (team drive only, see datasets/LICENSES.md). What is committed is
the manifest that lists them, plus these tools:

    python -m computer_vision.currency.tools build    IMAGES_DIR --source own_photo --licence project-owned \
        --consent "own notes, photographer" --collected-by NAME --checked-by NAME [--labels labels.csv] -o manifest.json
    python -m computer_vision.currency.tools validate manifest.json [--images IMAGES_DIR] [--min-per-class 20]
    python -m computer_vision.currency.tools sheet    manifest.json --images IMAGES_DIR -o contact_sheet.html
    python -m computer_vision.currency.tools import-roboflow EXPORT_DIR --map map.json -o IMAGES_DIR [--splits valid test] [--crop]
    python -m computer_vision.currency.tools autotag  IMAGES_DIR -o labels.csv

IMAGES_DIR layout: one folder per class id from classes.json, e.g. IMAGES_DIR/egp_50/front_01.jpg.
labels.csv (optional): file,lighting,angle,blur  with file relative to IMAGES_DIR.
Standard library only, so it runs anywhere Python 3.10+ does.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import json
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLASSES_FILE = HERE / "classes.json"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
LIGHTING = {"daylight", "indoor", "dim", "flash", "normal", "bright", "unknown"}
ANGLE = {"flat", "tilted", "steep", "unknown"}
BLUR = {"sharp", "slight", "blurry", "unknown"}
REQUIRED = ("file", "class", "sha256", "source", "licence", "consent", "lighting", "angle", "blur", "collected_by", "checked_by")


def load_classes(path: Path = CLASSES_FILE) -> list[str]:
    return [c["id"] for c in json.loads(Path(path).read_text(encoding="utf-8"))["classes"]]


def optional_classes(path: Path = CLASSES_FILE) -> set[str]:
    """Classes marked optional in classes.json: the per-class minimum does not apply to them."""
    return {c["id"] for c in json.loads(Path(path).read_text(encoding="utf-8"))["classes"] if c.get("optional")}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def _read_labels(path: Path | None) -> dict[str, dict[str, str]]:
    if path is None:
        return {}
    with open(path, newline="", encoding="utf-8") as f:
        return {row["file"].replace("\\", "/"): row for row in csv.DictReader(f)}


def build_manifest(images: Path, *, source: str, licence: str, consent: str, collected_by: str,
                   checked_by: str, labels: Path | None = None, classes: list[str] | None = None) -> dict:
    classes = classes or load_classes()
    extra = _read_labels(labels)
    entries = []
    for cls in classes:
        folder = Path(images) / cls
        if not folder.is_dir():
            continue
        for img in sorted(p for p in folder.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES):
            rel = f"{cls}/{img.name}"
            row = extra.get(rel, {})
            entries.append({
                "file": rel,
                "class": cls,
                "sha256": sha256(img),
                "source": source,
                "licence": licence,
                "consent": consent,
                "lighting": row.get("lighting", "unknown") or "unknown",
                "angle": row.get("angle", "unknown") or "unknown",
                "blur": row.get("blur", "unknown") or "unknown",
                "collected_by": collected_by,
                "checked_by": checked_by,
            })
    return {"version": 1, "classes_file": "computer_vision/currency/classes.json", "images": entries}


def validate(manifest: dict, *, images: Path | None = None, min_per_class: int = 20,
             classes: list[str] | None = None) -> list[str]:
    """Returns a list of problems; an empty list means the manifest meets the WS09-02 bar."""
    classes = classes or load_classes()
    problems: list[str] = []
    if manifest.get("version") != 1:
        problems.append("version must be 1")
    entries = manifest.get("images")
    if not isinstance(entries, list):
        return problems + ["images must be a list"]
    seen_sha: dict[str, str] = {}
    per_class: Counter[str] = Counter()
    variety: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for i, e in enumerate(entries):
        where = f"images[{i}] {e.get('file', '?')}"
        missing = [k for k in REQUIRED if not str(e.get(k, "")).strip()]
        if missing:
            problems.append(f"{where}: missing {', '.join(missing)}")
            continue
        if e["class"] not in classes:
            problems.append(f"{where}: unknown class {e['class']}")
        if "unknown" in e["licence"].lower():
            problems.append(f"{where}: licence must be known, not '{e['licence']}'")
        if e["checked_by"].strip().upper().startswith("PENDING"):
            problems.append(f"{where}: label check by a second person still pending")
        elif e["checked_by"].strip().lower() == e["collected_by"].strip().lower():
            problems.append(f"{where}: label must be checked by a second person")
        if e["lighting"] not in LIGHTING or e["angle"] not in ANGLE or e["blur"] not in BLUR:
            problems.append(f"{where}: lighting/angle/blur value not allowed")
        if not re.fullmatch(r"[0-9a-f]{64}", e["sha256"]):
            problems.append(f"{where}: bad sha256")
        elif e["sha256"] in seen_sha:
            problems.append(f"{where}: same image as {seen_sha[e['sha256']]}")
        else:
            seen_sha[e["sha256"]] = e["file"]
        if images is not None:
            path = Path(images) / e["file"]
            if not path.is_file():
                problems.append(f"{where}: file not found")
            elif sha256(path) != e["sha256"]:
                problems.append(f"{where}: file changed since the manifest was built")
        per_class[e["class"]] += 1
        for k in ("lighting", "angle", "blur"):
            variety[e["class"]][k].add(e[k])
    skip = optional_classes() if set(classes) == set(load_classes()) else set()
    for cls in classes:
        if cls in skip and per_class[cls] == 0:
            continue
        if per_class[cls] < min_per_class:
            problems.append(f"{cls}: {per_class[cls]} photos, need at least {min_per_class}")
        elif min_per_class > 0:
            for k in ("lighting", "angle"):
                known = variety[cls][k] - {"unknown"}
                if len(known) < 2:
                    problems.append(f"{cls}: needs at least 2 different {k} values, has {sorted(known) or 'none'}")
    return problems


def contact_sheet(manifest: dict, images: Path, out: Path) -> Path:
    """A local HTML page with every photo grouped by class, for the second-person label check."""
    by_class: dict[str, list[dict]] = defaultdict(list)
    for e in manifest["images"]:
        by_class[e["class"]].append(e)
    parts = ["<!doctype html><meta charset='utf-8'><title>EGP contact sheet</title>",
             "<style>body{font-family:sans-serif}figure{display:inline-block;margin:4px;width:160px}"
             "img{width:160px;height:110px;object-fit:cover}figcaption{font-size:11px}</style>"]
    for cls in sorted(by_class):
        parts.append(f"<h2>{html.escape(cls)} ({len(by_class[cls])})</h2>")
        for e in by_class[cls]:
            src = (Path(images) / e["file"]).resolve().as_uri()
            cap = f"{e['file']} | {e['lighting']}, {e['angle']}, {e['blur']}"
            parts.append(f"<figure><img src='{src}'><figcaption>{html.escape(cap)}</figcaption></figure>")
    Path(out).write_text("\n".join(parts), encoding="utf-8")
    return Path(out)


def _yolo_names(export: Path) -> list[str]:
    text = (export / "data.yaml").read_text(encoding="utf-8")
    inline = re.search(r"^names:\s*\[(.*)\]\s*$", text, re.M)
    if inline:
        return [n.strip().strip("'\"") for n in inline.group(1).split(",") if n.strip()]
    block = re.search(r"^names:\s*\n((?:\s+-\s*.*\n?)+)", text, re.M)
    if block:
        return [line.split("-", 1)[1].strip().strip("'\"") for line in block.group(1).splitlines() if "-" in line]
    raise ValueError("data.yaml has no names list")


def import_roboflow_yolo(export: Path, class_map: dict[str, str], out: Path, *, splits: list[str] | None = None,
                         crop: bool = False, margin: float = 0.04, min_side: int = 32) -> Counter:
    """Copies a Roboflow YOLO export into OUT/<class>/ using class_map (dataset name -> class id).

    Without crop: an image is copied only when all its boxes map to one class (others skipped).
    With crop (needs Pillow): every box becomes its own image, cut with a small margin; boxes
    smaller than min_side pixels are skipped. Use crop when images hold several notes.
    splits limits which export folders are read (e.g. ["valid", "test"] to leave out augmented train).
    """
    names = _yolo_names(Path(export))
    copied: Counter[str] = Counter()
    if crop:
        from PIL import Image  # optional dependency, only needed for cropping
    for label_file in sorted(Path(export).glob("*/labels/*.txt")):
        split = label_file.parent.parent.name
        if splits and split not in splits:
            continue
        rows = [line.split() for line in label_file.read_text().splitlines() if line.strip()]
        image = next((p for p in (label_file.parent.parent / "images").glob(label_file.stem + ".*")
                      if p.suffix.lower() in IMAGE_SUFFIXES), None)
        if image is None:
            copied["skipped"] += 1
            continue
        if not crop:
            mapped = {class_map.get(names[int(r[0])]) for r in rows if int(r[0]) < len(names)}
            if len(mapped) != 1 or None in mapped:
                copied["skipped"] += 1
                continue
            cls = mapped.pop()
            dest = Path(out) / cls
            dest.mkdir(parents=True, exist_ok=True)
            shutil.copy2(image, dest / f"{split}_{image.name}")
            copied[cls] += 1
            continue
        with Image.open(image) as im:
            w, h = im.size
            for i, r in enumerate(rows):
                cls = class_map.get(names[int(r[0])]) if int(r[0]) < len(names) else None
                cx, cy, bw, bh = (float(v) for v in r[1:5])
                x0, y0 = max(0, int((cx - bw / 2 - margin) * w)), max(0, int((cy - bh / 2 - margin) * h))
                x1, y1 = min(w, int((cx + bw / 2 + margin) * w)), min(h, int((cy + bh / 2 + margin) * h))
                # Size is judged on the box itself, not on the box plus margin.
                if cls is None or min(bw * w, bh * h) < min_side:
                    copied["skipped"] += 1
                    continue
                dest = Path(out) / cls
                dest.mkdir(parents=True, exist_ok=True)
                im.crop((x0, y0, x1, y1)).convert("RGB").save(dest / f"{split}_{image.stem}_{i}.jpg", quality=92)
                copied[cls] += 1
    return copied


def autotag(images: Path, out_csv: Path) -> int:
    """Measures lighting, angle and blur for every image under IMAGES/<class>/ (needs Pillow) and
    writes labels.csv. These are estimates from pixels and are documented as such:
    lighting = mean brightness (dim < 80, normal, bright > 170); blur = variance of the Laplacian on
    a 256 px gray copy (blurry < 60, slight < 200, sharp); angle = how far the crop's shape is from a
    flat banknote (long/short side >= 1.8 flat, >= 1.3 tilted, else steep)."""
    from PIL import Image, ImageFilter, ImageStat

    rows = ["file,lighting,angle,blur"]
    for img in sorted(p for p in Path(images).glob("*/*") if p.suffix.lower() in IMAGE_SUFFIXES):
        with Image.open(img) as im:
            gray = im.convert("L")
            gray.thumbnail((256, 256))
            brightness = ImageStat.Stat(gray).mean[0]
            lap = gray.filter(ImageFilter.Kernel((3, 3), [0, 1, 0, 1, -4, 1, 0, 1, 0], scale=1, offset=128))
            variance = ImageStat.Stat(lap).var[0]
            ratio = max(im.size) / max(1, min(im.size))
        lighting = "dim" if brightness < 80 else "bright" if brightness > 170 else "normal"
        blur = "blurry" if variance < 60 else "slight" if variance < 200 else "sharp"
        angle = "flat" if ratio >= 1.8 else "tilted" if ratio >= 1.3 else "steep"
        rows.append(f"{img.parent.name}/{img.name},{lighting},{angle},{blur}")
    Path(out_csv).write_text("\n".join(rows) + "\n", encoding="utf-8")
    return len(rows) - 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="currency-tools", description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("images", type=Path)
    for name in ("source", "licence", "consent", "collected-by", "checked-by"):
        b.add_argument(f"--{name}", required=True)
    b.add_argument("--labels", type=Path)
    b.add_argument("-o", "--out", type=Path, required=True)
    v = sub.add_parser("validate")
    v.add_argument("manifest", type=Path)
    v.add_argument("--images", type=Path)
    v.add_argument("--min-per-class", type=int, default=20)
    s = sub.add_parser("sheet")
    s.add_argument("manifest", type=Path)
    s.add_argument("--images", type=Path, required=True)
    s.add_argument("-o", "--out", type=Path, required=True)
    r = sub.add_parser("import-roboflow")
    r.add_argument("export", type=Path)
    r.add_argument("--map", type=Path, required=True, help="JSON object: dataset class name -> class id")
    r.add_argument("-o", "--out", type=Path, required=True)
    r.add_argument("--splits", nargs="*", help="export folders to read, e.g. valid test")
    r.add_argument("--crop", action="store_true", help="one image per box (needs Pillow)")
    t = sub.add_parser("autotag")
    t.add_argument("images", type=Path)
    t.add_argument("-o", "--out", type=Path, required=True)
    a = p.parse_args(argv)

    if a.cmd == "build":
        m = build_manifest(a.images, source=a.source, licence=a.licence, consent=a.consent,
                           collected_by=a.collected_by, checked_by=a.checked_by, labels=a.labels)
        a.out.write_text(json.dumps(m, indent=2) + "\n", encoding="utf-8")
        print(f"{len(m['images'])} images -> {a.out}")
        return 0
    if a.cmd == "validate":
        problems = validate(json.loads(a.manifest.read_text(encoding="utf-8")), images=a.images,
                            min_per_class=a.min_per_class)
        for line in problems:
            print("FAIL", line)
        print("OK" if not problems else f"{len(problems)} problem(s)")
        return 0 if not problems else 1
    if a.cmd == "sheet":
        print(contact_sheet(json.loads(a.manifest.read_text(encoding="utf-8")), a.images, a.out))
        return 0
    if a.cmd == "autotag":
        print(f"{autotag(a.images, a.out)} images tagged -> {a.out}")
        return 0
    counts = import_roboflow_yolo(a.export, json.loads(a.map.read_text(encoding="utf-8")), a.out,
                                  splits=a.splits, crop=a.crop)
    print(dict(counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
