"""Tests for scripts/check_contract_drift.py (AVN-266). Run: python -m pytest scripts/tests -q"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_contract_drift as drift  # noqa: E402

ROOT = drift.ROOT


def real_inputs():
    schemas = drift.load_schemas()
    kotlin = drift.parse_kotlin([p.read_text(encoding="utf-8") for p in sorted(drift.KOTLIN_DIR.glob("*.kt"))])
    python = drift.parse_python([p.read_text(encoding="utf-8") for p in drift.PY_FILES])
    return schemas, kotlin, python


# ------------------------------------------------------------------ model drift


def test_repo_models_match_schemas():
    report = drift.check_models(*real_inputs())
    assert report.problems == []
    assert report.checked >= 50


def test_kotlin_parser_reads_serial_names_and_enums():
    src = '''
    /** a comment with data class Fake(val nope: Int) */
    @Serializable
    data class Thing(
        val id: String,
        @SerialName("distance_band") val distanceBand: DistanceBand?,
        val nested: Map<String, List<Int>> = emptyMap(),
    )
    enum class Side { @SerialName("left") LEFT, @SerialName("right") RIGHT }
    '''
    classes, enums = drift.parse_kotlin([src])
    assert classes["Thing"] == {"id", "distance_band", "nested"}
    assert "Fake" not in classes
    assert enums["Side"] == {"left", "right"}


def test_python_parser_reads_aliases_literals_and_strenum():
    src = '''
from enum import StrEnum
from typing import Literal
Side = Literal["left", "right"]
class Code(StrEnum):
    a = "a"
    b = "b"
class M(BaseModel):
    model_config = ConfigDict(extra="forbid")
    side: Side | None
    kind: Literal["x", "y"]
    pkg: str = Field(alias="package")
'''
    classes, enums = drift.parse_python([src])
    assert classes["M"] == {"side", "kind", "package"}
    assert enums["Side"] == {"left", "right"}
    assert enums["M.kind"] == {"x", "y"}
    assert enums["Code"] == {"a", "b"}


def test_renamed_kotlin_field_is_reported():
    schemas, (kt_classes, kt_enums), python = real_inputs()
    kt_classes = dict(kt_classes)
    kt_classes["Event"] = (kt_classes["Event"] - {"distance_band"}) | {"distance_m"}
    report = drift.check_models(schemas, (kt_classes, kt_enums), python)
    text = "\n".join(report.problems)
    assert "Kotlin Event is missing ['distance_band']" in text
    assert "['distance_m']" in text


def test_extra_python_enum_value_is_reported():
    schemas, kotlin, (py_classes, py_enums) = real_inputs()
    py_enums = dict(py_enums)
    py_enums["Side"] = py_enums["Side"] | {"behind"}
    report = drift.check_models(schemas, kotlin, (py_classes, py_enums))
    assert any("Python Side has values not in the schema ['behind']" in p for p in report.problems)


def test_missing_model_is_reported():
    schemas, (kt_classes, kt_enums), python = real_inputs()
    kt_classes = {k: v for k, v in kt_classes.items() if k != "RouteStep"}
    report = drift.check_models(schemas, (kt_classes, kt_enums), python)
    assert any("Kotlin RouteStep type not found" in p for p in report.problems)


# ------------------------------------------------------------------ freeze lock


@pytest.fixture()
def repo(tmp_path: Path) -> Path:
    """A throwaway copy of the frozen files, the decisions folder and the real lock."""
    for rel in ["docs/api-contract.md", "contracts/schemas", "contracts/fixtures", "docs/decisions"]:
        src, dst = ROOT / rel, tmp_path / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        (shutil.copytree if src.is_dir() else shutil.copy)(src, dst)
    shutil.copy(ROOT / "contracts" / "contract.lock.json", tmp_path / "contracts" / "contract.lock.json")
    return tmp_path


# Any version above the committed lock, so these tests do not depend on the current contract version.
NEXT_VERSION = "99.0.0"


def bump_doc(repo: Path, new: str) -> None:
    doc = repo / "docs" / "api-contract.md"
    old = drift.contract_version(doc.read_text(encoding="utf-8"))
    doc.write_text(doc.read_text(encoding="utf-8").replace(f"**Version {old}", f"**Version {new}", 1), encoding="utf-8")


def test_committed_lock_matches_repo():
    assert drift.check_lock(ROOT) == []


def test_changed_fixture_fails_lock(repo: Path):
    fx = repo / "contracts" / "fixtures" / "api" / "ask.ok.json"
    fx.write_text(fx.read_text(encoding="utf-8").replace("}", ', "x": 1}', 1), encoding="utf-8")
    problems = drift.check_lock(repo)
    assert any("ask.ok.json: changed since contract" in p for p in problems)


def test_new_and_deleted_frozen_files_fail_lock(repo: Path):
    (repo / "contracts" / "fixtures" / "api" / "new.json").write_text("{}", encoding="utf-8")
    (repo / "contracts" / "schemas" / "event.schema.json").unlink()
    problems = "\n".join(drift.check_lock(repo))
    assert "new.json: new frozen file not in the lock" in problems
    assert "event.schema.json: frozen file deleted" in problems


def test_crlf_checkout_hashes_the_same(repo: Path):
    schema = repo / "contracts" / "schemas" / "common.schema.json"
    schema.write_bytes(schema.read_bytes().replace(b"\n", b"\r\n"))
    assert drift.check_lock(repo) == []


def test_relock_refused_without_version_bump(repo: Path):
    (repo / "contracts" / "fixtures" / "api" / "new.json").write_text("{}", encoding="utf-8")
    ok, msg = drift.relock(repo)
    assert not ok and "version is still" in msg


def test_relock_refused_without_decision(repo: Path):
    (repo / "contracts" / "fixtures" / "api" / "new.json").write_text("{}", encoding="utf-8")
    bump_doc(repo, NEXT_VERSION)
    ok, msg = drift.relock(repo)
    assert not ok and "Contract impact" in msg


def test_relock_accepted_with_bump_and_decision(repo: Path):
    (repo / "contracts" / "fixtures" / "api" / "new.json").write_text("{}", encoding="utf-8")
    bump_doc(repo, NEXT_VERSION)
    (repo / "docs" / "decisions" / "TD-099-test.md").write_text(
        f"# TD-099\nContract impact: [x] yes, contract version 1.1.0 -> {NEXT_VERSION}\n", encoding="utf-8"
    )
    ok, msg = drift.relock(repo)
    assert ok, msg
    assert json.loads((repo / "contracts" / "contract.lock.json").read_text())["version"] == NEXT_VERSION
    assert drift.check_lock(repo) == []


def test_doc_version_must_match_lock(repo: Path):
    bump_doc(repo, "9.9.9")
    problems = "\n".join(drift.check_lock(repo))
    assert "says 9.9.9 but the lock is" in problems


def test_lock_hashes_are_prefixed_so_the_secret_scan_ignores_them():
    # A bare hex digest next to "api..." keys trips gitleaks' generic-api-key rule in CI.
    lock = json.loads((ROOT / "contracts" / "contract.lock.json").read_text(encoding="utf-8"))
    assert all(v.startswith("sha256:") and len(v) == 71 for v in lock["files"].values())
