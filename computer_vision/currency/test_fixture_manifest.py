"""WS09-02 (Notion test name: test_fixture_manifest_valid). Standard library only:
    python -m unittest computer_vision/currency/test_fixture_manifest.py
(pytest also collects it). Uses throwaway files in a temp folder; no real banknote photos."""

import json
import tempfile
import unittest
from pathlib import Path

from computer_vision.currency import tools

LIGHT = ["daylight", "indoor", "dim", "flash"]
ANGLES = ["flat", "tilted", "steep"]


def make_set(root: Path, per_class: int = 20, classes=None):
    classes = classes or tools.load_classes()
    rows = ["file,lighting,angle,blur"]
    for cls in classes:
        (root / cls).mkdir(parents=True)
        for i in range(per_class):
            (root / cls / f"{i:02d}.jpg").write_bytes(f"fake image {cls} {i}".encode())
            rows.append(f"{cls}/{i:02d}.jpg,{LIGHT[i % 4]},{ANGLES[i % 3]},sharp")
    (root / "labels.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    return tools.build_manifest(root, source="own_photo", licence="project-owned", consent="own notes, photographer",
                                collected_by="Marwan", checked_by="Mahmoud", labels=root / "labels.csv")


class FixtureManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "images"
        self.root.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_fixture_manifest_valid(self):
        m = make_set(self.root)
        self.assertEqual(20 * len(tools.load_classes()), len(m["images"]))
        self.assertEqual([], tools.validate(m, images=self.root))
        # The schema file is real JSON Schema and every entry has its required keys.
        schema = json.loads((tools.HERE / "manifest.schema.json").read_text(encoding="utf-8"))
        required = schema["properties"]["images"]["items"]["required"]
        self.assertEqual(sorted(required), sorted(tools.REQUIRED))
        try:
            import jsonschema
        except ImportError:
            return
        jsonschema.validate(m, schema)

    def test_too_few_photos_or_no_variety_fails(self):
        m = make_set(self.root, per_class=19)
        self.assertTrue(any("need at least 20" in p for p in tools.validate(m)))
        for e in m["images"]:
            e["lighting"] = "daylight"
        self.assertTrue(any("lighting" in p for p in tools.validate(m, min_per_class=19)))

    def test_licence_second_person_and_duplicates_are_enforced(self):
        m = make_set(self.root)
        m["images"][0]["licence"] = "UNKNOWN - flagged"
        m["images"][1]["checked_by"] = "Marwan"
        m["images"][2]["sha256"] = m["images"][3]["sha256"]
        m["images"][4]["class"] = "egp_1000"
        problems = tools.validate(m)
        for needle in ("licence must be known", "second person", "same image as", "unknown class"):
            self.assertTrue(any(needle in p for p in problems), needle)

    def test_changed_or_missing_file_is_caught(self):
        m = make_set(self.root)
        (self.root / m["images"][0]["file"]).write_bytes(b"edited")
        (self.root / m["images"][1]["file"]).unlink()
        problems = tools.validate(m, images=self.root)
        self.assertTrue(any("file changed" in p for p in problems))
        self.assertTrue(any("file not found" in p for p in problems))

    def test_contact_sheet_and_cli(self):
        m = make_set(self.root)
        out = Path(self.tmp.name) / "manifest.json"
        out.write_text(json.dumps(m), encoding="utf-8")
        self.assertEqual(0, tools.main(["validate", str(out), "--images", str(self.root)]))
        sheet = tools.contact_sheet(m, self.root, Path(self.tmp.name) / "sheet.html").read_text(encoding="utf-8")
        self.assertIn("egp_200 (20)", sheet)

    def test_import_roboflow_yolo_export(self):
        export = Path(self.tmp.name) / "export"
        for split in ("train", "valid"):
            (export / split / "images").mkdir(parents=True)
            (export / split / "labels").mkdir(parents=True)
        (export / "data.yaml").write_text("nc: 3\nnames: ['50 front', '50 back', '200 front']\n", encoding="utf-8")
        cases = {"a": "0 0.5 0.5 0.4 0.3", "b": "1 0.5 0.5 0.4 0.3", "c": "2 0.5 0.5 0.4 0.3", "mixed": "0 0.2 0.2 0.1 0.1\n2 0.7 0.7 0.1 0.1"}
        for name, label in cases.items():
            (export / "train" / "images" / f"{name}.jpg").write_bytes(name.encode())
            (export / "train" / "labels" / f"{name}.txt").write_text(label, encoding="utf-8")
        counts = tools.import_roboflow_yolo(export, {"50 front": "egp_50", "50 back": "egp_50", "200 front": "egp_200"}, self.root)
        self.assertEqual({"egp_50": 2, "egp_200": 1, "skipped": 1}, dict(counts))
        self.assertEqual(2, len(list((self.root / "egp_50").iterdir())))

    def test_crop_import_and_autotag(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow not installed")
        export = Path(self.tmp.name) / "export"
        for split in ("train", "valid"):
            (export / split / "images").mkdir(parents=True)
            (export / split / "labels").mkdir(parents=True)
        (export / "data.yaml").write_text("names: ['5Egp', '50Egp']\n", encoding="utf-8")
        img = Image.new("RGB", (640, 640), (40, 40, 40))
        img.paste((200, 30, 30), (40, 40, 360, 200))
        img.save(export / "valid" / "images" / "two.jpg")
        img.save(export / "train" / "images" / "aug.jpg")
        # two notes in one image, plus one box too small to keep
        (export / "valid" / "labels" / "two.txt").write_text(
            "0 0.31 0.19 0.5 0.25\n1 0.75 0.75 0.3 0.2\n1 0.5 0.5 0.01 0.01\n", encoding="utf-8")
        (export / "train" / "labels" / "aug.txt").write_text("0 0.5 0.5 0.4 0.3\n", encoding="utf-8")
        counts = tools.import_roboflow_yolo(export, {"5Egp": "egp_5", "50Egp": "egp_50"}, self.root,
                                            splits=["valid"], crop=True)
        self.assertEqual({"egp_5": 1, "egp_50": 1, "skipped": 1}, dict(counts))
        crop = next((self.root / "egp_5").iterdir())
        with Image.open(crop) as c:
            self.assertGreater(c.size[0], c.size[1])  # the wide note, cut out with a margin
        csv_path = Path(self.tmp.name) / "labels.csv"
        self.assertEqual(2, tools.autotag(self.root, csv_path))
        header, *rows = csv_path.read_text(encoding="utf-8").splitlines()
        self.assertEqual("file,lighting,angle,blur", header)
        for row in rows:
            _, lighting, angle, blur = row.split(",")
            self.assertIn(lighting, tools.LIGHTING)
            self.assertIn(angle, tools.ANGLE)
            self.assertIn(blur, tools.BLUR)

    def test_pending_check_and_optional_classes(self):
        m = make_set(self.root, classes=[c for c in tools.load_classes() if c not in tools.optional_classes()])
        self.assertEqual([], tools.validate(m))  # optional (polymer) classes may be empty
        m["images"][0]["checked_by"] = "PENDING second-person check"
        self.assertTrue(any("still pending" in p for p in tools.validate(m)))

    def test_committed_manifest_meets_counts_and_licence(self):
        """The committed set (Roboflow 'Egypt banknote' v1 crops, see README) must have at least 20 per
        required class, known licences, unique images and allowed tag values. The only open items allowed
        are the second-person check and angle variety, both documented in the README."""
        committed = tools.HERE / "fixtures" / "manifest.json"
        if not committed.exists():
            self.skipTest("no manifest committed yet")
        m = json.loads(committed.read_text(encoding="utf-8"))
        known_gaps = ("still pending", "different angle values")
        self.assertEqual([], [p for p in tools.validate(m) if not any(g in p for g in known_gaps)])
        counts = {c: sum(1 for e in m["images"] if e["class"] == c) for c in tools.load_classes()}
        for c in set(tools.load_classes()) - tools.optional_classes():
            self.assertGreaterEqual(counts[c], 20, c)


if __name__ == "__main__":
    unittest.main()
