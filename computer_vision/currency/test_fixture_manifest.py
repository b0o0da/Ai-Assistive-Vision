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

    def test_committed_manifest_if_present_is_valid_json(self):
        committed = tools.HERE / "fixtures" / "manifest.json"
        if not committed.exists():
            self.skipTest("no manifest committed yet: photos not collected (see README)")
        m = json.loads(committed.read_text(encoding="utf-8"))
        self.assertEqual([], [p for p in tools.validate(m, min_per_class=0) if "need at least" not in p])


if __name__ == "__main__":
    unittest.main()
