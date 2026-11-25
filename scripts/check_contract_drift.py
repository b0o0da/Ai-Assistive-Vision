#!/usr/bin/env python3
"""Contract drift check (WS25-14, AVN-266).

Two checks, both run in CI next to validate_contracts.py:

1. Model drift: the Kotlin models (android/contracts) and the Pydantic models (backend/app/schemas,
   backend/app/core/envelope.py) must use exactly the field names and enum values of
   contracts/schemas. Both sides are read as source text (Kotlin by regex, Python by ast), so this
   needs no Gradle, no Pydantic and no install beyond the standard library.

2. Freeze lock: contracts/contract.lock.json holds the contract version and a SHA-256 of every
   frozen file (docs/api-contract.md, contracts/schemas/*.json, contracts/fixtures/**/*.json).
   Any change to those files fails CI until the lock is regenerated with --relock, and --relock
   refuses unless docs/api-contract.md carries a higher version AND a Technical Decision in
   docs/decisions/ ticks "Contract impact" for that version (docs/api-contract.md section 7).

Usage:
    python scripts/check_contract_drift.py            # check both, exit 1 on drift
    python scripts/check_contract_drift.py --relock   # after a TD + version bump
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "contracts" / "schemas"
FIXTURES = ROOT / "contracts" / "fixtures"
CONTRACT_DOC = ROOT / "docs" / "api-contract.md"
DECISIONS = ROOT / "docs" / "decisions"
LOCK = ROOT / "contracts" / "contract.lock.json"
KOTLIN_DIR = ROOT / "android" / "contracts" / "src" / "main" / "kotlin" / "avn" / "contracts"
PY_FILES = [
    ROOT / "backend" / "app" / "schemas" / "common.py",
    ROOT / "backend" / "app" / "schemas" / "event.py",
    ROOT / "backend" / "app" / "schemas" / "api.py",
    ROOT / "backend" / "app" / "core" / "envelope.py",
]

# Schema object -> (Kotlin class or None, Pydantic class or None). None = not mirrored on that side.
OBJECTS: dict[str, tuple[str | None, str | None]] = {
    "event.schema.json#": ("Event", "Event"),
    "envelope.schema.json#": ("ApiEnvelope", "Envelope"),
    "envelope.schema.json#/properties/error/oneOf/1": ("ErrorInfo", "ErrorInfo"),
    "common.schema.json#/$defs/Context": (None, "Context"),
    "common.schema.json#/$defs/LatLon": ("LatLon", "LatLon"),
    "android.schema.json#/$defs/Transcript": ("Transcript", None),
    "android.schema.json#/$defs/SpeechRequest": ("SpeechRequest", None),
    "android.schema.json#/$defs/ActionPlan": ("ActionPlan", "ActionPlan"),
    "android.schema.json#/$defs/PlanStepItem": ("PlanStepItem", "PlanStepItem"),
    "android.schema.json#/$defs/NodeInfo": ("NodeInfo", None),
    "android.schema.json#/$defs/ScreenSnapshot": ("ScreenSnapshot", None),
    "android.schema.json#/$defs/ScreenSnapshot/properties/flags": ("ScreenFlags", None),
    "api.schema.json#/$defs/HealthResponse": (None, "HealthResponse"),
    "api.schema.json#/$defs/DescribeRequest": (None, "DescribeRequest"),
    "api.schema.json#/$defs/DescribeResponse": (None, "DescribeResponse"),
    "api.schema.json#/$defs/AskRequest": (None, "AskRequest"),
    "api.schema.json#/$defs/AskResponse": (None, "AskResponse"),
    "api.schema.json#/$defs/ScreenSummarizeRequest": (None, "ScreenSummarizeRequest"),
    "api.schema.json#/$defs/ScreenSummarizeResponse": (None, "ScreenSummarizeResponse"),
    "api.schema.json#/$defs/IntentRequest": (None, "IntentRequest"),
    "api.schema.json#/$defs/FacesEnrollRequest": (None, "FacesEnrollRequest"),
    "api.schema.json#/$defs/FacesEnrollResponse": (None, "FacesEnrollResponse"),
    "api.schema.json#/$defs/FacesMatchRequest": (None, "FacesMatchRequest"),
    "api.schema.json#/$defs/FacesMatchResponse": (None, "FacesMatchResponse"),
    "api.schema.json#/$defs/RouteRequestQuery": (None, "RouteRequestQuery"),
    "api.schema.json#/$defs/RouteStep": ("RouteStep", "RouteStep"),
    "api.schema.json#/$defs/RouteResponse": ("RouteResponse", "RouteResponse"),
    "api.schema.json#/$defs/SosRequest": (None, "SosRequest"),
    "api.schema.json#/$defs/SosResponse": (None, "SosResponse"),
}

# Schema enum -> (Kotlin enum class or None, Python Literal alias / "Class.field" / StrEnum or None).
ENUMS: dict[str, tuple[str | None, str | None]] = {
    "common.schema.json#/$defs/Source": ("Source", "Source"),
    "common.schema.json#/$defs/EventType": ("EventType", "EventType"),
    "common.schema.json#/$defs/Side": ("Side", "Side"),
    "common.schema.json#/$defs/DistanceBand": ("DistanceBand", "DistanceBand"),
    "common.schema.json#/$defs/Level": ("Level", None),
    "common.schema.json#/$defs/ScreenType": ("ScreenType", "ScreenType"),
    "common.schema.json#/$defs/PlanStep": ("PlanStep", "PlanStep"),
    "common.schema.json#/$defs/Language": (None, "Language"),
    "common.schema.json#/$defs/Context/properties/mode": (None, "Context.mode"),
    "api.schema.json#/$defs/RouteStep/properties/maneuver": ("Maneuver", "RouteStep.maneuver"),
    "api.schema.json#/$defs/HealthResponse/properties/status": (None, "HealthResponse.status"),
    "envelope.schema.json#/properties/error/oneOf/1/properties/code": ("ErrorCode", "ErrorCode"),
}


# ---------------------------------------------------------------- schema side


def load_schemas(schema_dir: Path = SCHEMAS) -> dict[str, dict]:
    return {p.name: json.loads(p.read_text(encoding="utf-8")) for p in schema_dir.glob("*.schema.json")}


def resolve(schemas: dict[str, dict], ref: str) -> dict:
    name, _, pointer = ref.partition("#")
    node = schemas[name]
    for part in [p for p in pointer.split("/") if p]:
        node = node[int(part)] if isinstance(node, list) else node[part]
    return node


def schema_fields(schemas: dict[str, dict], ref: str) -> set[str]:
    return set(resolve(schemas, ref).get("properties", {}))


def schema_enum(schemas: dict[str, dict], ref: str) -> set[str]:
    node = resolve(schemas, ref)
    if "enum" in node:
        return set(node["enum"])
    raise KeyError(f"{ref} has no enum")


# ---------------------------------------------------------------- Kotlin side

_KT_DATA = re.compile(r"data\s+class\s+(\w+)\s*\(")
_KT_ENUM = re.compile(r"enum\s+class\s+(\w+)\s*\{")
_KT_PARAM = re.compile(r'(?:@SerialName\(\s*"([^"]+)"\s*\)\s*)?va[lr]\s+(\w+)\s*:')
_KT_SERIAL = re.compile(r'@SerialName\(\s*"([^"]+)"\s*\)\s*(\w+)')


def _balanced(text: str, start: int, open_ch: str, close_ch: str) -> str:
    depth = 0
    for i in range(start, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return text[start + 1 : i]
    raise ValueError("unbalanced Kotlin source")


def _strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def parse_kotlin(sources: list[str]) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Return ({data class: wire field names}, {enum class: wire values})."""
    classes: dict[str, set[str]] = {}
    enums: dict[str, set[str]] = {}
    for raw in sources:
        src = _strip_comments(raw)
        for m in _KT_DATA.finditer(src):
            body = _balanced(src, m.end() - 1, "(", ")")
            classes[m.group(1)] = {serial or name for serial, name in _KT_PARAM.findall(body)}
        for m in _KT_ENUM.finditer(src):
            body = _balanced(src, m.end() - 1, "{", "}")
            enums[m.group(1)] = {serial for serial, _ in _KT_SERIAL.findall(body)}
    return classes, enums


# ---------------------------------------------------------------- Python side


def _literal_values(node: ast.AST | None, aliases: dict[str, set[str]]) -> set[str] | None:
    """Values of Literal[...] inside an annotation, following Optional/union and known aliases."""
    if node is None:
        return None
    if isinstance(node, ast.Name) and node.id in aliases:
        return aliases[node.id]
    if isinstance(node, ast.Subscript) and getattr(node.value, "id", None) == "Literal":
        elts = node.slice.elts if isinstance(node.slice, ast.Tuple) else [node.slice]
        return {e.value for e in elts if isinstance(e, ast.Constant)}
    if isinstance(node, ast.BinOp):  # X | None
        return _literal_values(node.left, aliases) or _literal_values(node.right, aliases)
    if isinstance(node, ast.Subscript) and getattr(node.value, "id", None) == "Annotated":
        first = node.slice.elts[0] if isinstance(node.slice, ast.Tuple) else node.slice
        return _literal_values(first, aliases)
    return None


def _alias_of(value: ast.expr | None) -> str | None:
    """Field(alias="x") on an assignment, if any."""
    if isinstance(value, ast.Call):
        for kw in value.keywords:
            if kw.arg in ("alias", "serialization_alias") and isinstance(kw.value, ast.Constant):
                return kw.value.value
    return None


def parse_python(sources: list[str]) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Return ({model class: wire field names}, {alias / Class.field / StrEnum: values})."""
    trees = [ast.parse(s) for s in sources]
    enums: dict[str, set[str]] = {}
    for tree in trees:  # module-level Literal aliases first, so fields can refer to them
        for node in tree.body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                vals = _literal_values(node.value, enums)
                if vals is not None:
                    enums[node.targets[0].id] = vals
    classes: dict[str, set[str]] = {}
    for tree in trees:
        for node in tree.body:
            if not isinstance(node, ast.ClassDef):
                continue
            bases = {getattr(b, "id", "") for b in node.bases}
            if "StrEnum" in bases:
                enums[node.name] = {
                    s.value.value
                    for s in node.body
                    if isinstance(s, ast.Assign) and isinstance(s.value, ast.Constant)
                }
                continue
            names = set()
            for stmt in node.body:
                if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                    if stmt.target.id == "model_config":
                        continue
                    names.add(_alias_of(stmt.value) or stmt.target.id)
                    vals = _literal_values(stmt.annotation, enums)
                    if vals is not None:
                        enums[f"{node.name}.{stmt.target.id}"] = vals
            classes[node.name] = names
    return classes, enums


# ---------------------------------------------------------------- drift report


@dataclass
class Report:
    problems: list[str] = field(default_factory=list)
    checked: int = 0

    def compare(self, what: str, side: str, expected: set[str], actual: set[str] | None) -> None:
        self.checked += 1
        if actual is None:
            self.problems.append(f"{what}: {side} type not found")
            return
        missing, extra = sorted(expected - actual), sorted(actual - expected)
        if missing:
            self.problems.append(f"{what}: {side} is missing {missing}")
        if extra:
            self.problems.append(f"{what}: {side} has values not in the schema {extra}")


def check_models(
    schemas: dict[str, dict],
    kotlin: tuple[dict[str, set[str]], dict[str, set[str]]],
    python: tuple[dict[str, set[str]], dict[str, set[str]]],
) -> Report:
    report = Report()
    kt_classes, kt_enums = kotlin
    py_classes, py_enums = python
    for ref, (kt, py) in OBJECTS.items():
        expected = schema_fields(schemas, ref)
        if kt:
            report.compare(f"{ref} fields", f"Kotlin {kt}", expected, kt_classes.get(kt))
        if py:
            report.compare(f"{ref} fields", f"Pydantic {py}", expected, py_classes.get(py))
    for ref, (kt, py) in ENUMS.items():
        expected = schema_enum(schemas, ref)
        if kt:
            report.compare(f"{ref} values", f"Kotlin enum {kt}", expected, kt_enums.get(kt))
        if py:
            report.compare(f"{ref} values", f"Python {py}", expected, py_enums.get(py))
    return report


# ---------------------------------------------------------------- freeze lock

_VERSION_LINE = re.compile(r"\*\*Version\s+(\d+\.\d+\.\d+)")


def contract_version(doc_text: str) -> str:
    m = _VERSION_LINE.search(doc_text)
    if not m:
        raise ValueError("docs/api-contract.md has no '**Version X.Y.Z' line")
    return m.group(1)


def frozen_files(root: Path = ROOT) -> list[Path]:
    files = [root / "docs" / "api-contract.md"]
    files += sorted((root / "contracts" / "schemas").glob("*.json"))
    files += sorted((root / "contracts" / "fixtures").rglob("*.json"))
    return files


def file_hashes(root: Path = ROOT) -> dict[str, str]:
    out = {}
    for path in frozen_files(root):
        data = path.read_bytes().replace(b"\r\n", b"\n")  # same hash on Windows checkouts
        # "sha256:" prefix: a bare hex digest next to a name like "api..." or "...unauthorized"
        # looks like an API key to the gitleaks secret scan in CI.
        out[path.relative_to(root).as_posix()] = "sha256:" + hashlib.sha256(data).hexdigest()
    return out


def _vtuple(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in v.split("."))


def decision_for(version: str, decisions_dir: Path) -> Path | None:
    """A TD that ticks Contract impact and names the new version (e.g. '1.0.0 -> 1.1.0')."""
    pat = re.compile(r"Contract impact:.*\[x\]\s*yes.*(?:→|->)\s*" + re.escape(version) + r"\b", re.I)
    for path in sorted(decisions_dir.glob("TD-*.md")):
        if pat.search(path.read_text(encoding="utf-8")):
            return path
    return None


def check_lock(root: Path = ROOT) -> list[str]:
    lock_path = root / "contracts" / "contract.lock.json"
    if not lock_path.exists():
        return ["contracts/contract.lock.json is missing: run with --relock once to create it"]
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    now = file_hashes(root)
    problems = []
    doc_version = contract_version((root / "docs" / "api-contract.md").read_text(encoding="utf-8"))
    if doc_version != lock["version"]:
        problems.append(f"docs/api-contract.md says {doc_version} but the lock is {lock['version']}")
    for rel in sorted(set(now) | set(lock["files"])):
        if rel not in lock["files"]:
            problems.append(f"{rel}: new frozen file not in the lock")
        elif rel not in now:
            problems.append(f"{rel}: frozen file deleted")
        elif now[rel] != lock["files"][rel]:
            problems.append(f"{rel}: changed since contract {lock['version']} was locked")
    if problems:
        problems.append(
            "The contract is frozen. Follow docs/api-contract.md section 7 (TD with Contract impact "
            "ticked, version bump), then run: python scripts/check_contract_drift.py --relock"
        )
    return problems


def relock(root: Path = ROOT) -> tuple[bool, str]:
    lock_path = root / "contracts" / "contract.lock.json"
    version = contract_version((root / "docs" / "api-contract.md").read_text(encoding="utf-8"))
    hashes = file_hashes(root)
    if lock_path.exists():
        old = json.loads(lock_path.read_text(encoding="utf-8"))
        if old["files"] == hashes and old["version"] == version:
            return True, f"lock already matches contract {version}"
        if _vtuple(version) <= _vtuple(old["version"]):
            return False, (
                f"refusing to relock: frozen files changed but the version is still {version} "
                f"(locked {old['version']}). Bump the version line in docs/api-contract.md."
            )
        td = decision_for(version, root / "docs" / "decisions")
        if td is None:
            return False, (
                f"refusing to relock: no docs/decisions/TD-*.md ticks 'Contract impact: [x] yes' "
                f"for version {version}"
            )
        why = f" ({td.relative_to(root).as_posix()})"
    else:
        why = " (first lock)"
    lock_path.write_text(
        json.dumps({"version": version, "files": hashes}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return True, f"locked contract {version}{why}, {len(hashes)} files"


# ---------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--relock", action="store_true", help="rewrite contracts/contract.lock.json")
    args = parser.parse_args(argv)

    schemas = load_schemas()
    kotlin = parse_kotlin([p.read_text(encoding="utf-8") for p in sorted(KOTLIN_DIR.glob("*.kt"))])
    python = parse_python([p.read_text(encoding="utf-8") for p in PY_FILES])
    report = check_models(schemas, kotlin, python)
    for problem in report.problems:
        print(f"DRIFT {problem}")
    print(f"{report.checked} model comparisons, {len(report.problems)} drift problem(s)")
    failed = bool(report.problems)

    if args.relock:
        if failed:
            print("not relocking while the models drift from the schemas")
            return 1
        ok, msg = relock()
        print(msg)
        return 0 if ok else 1

    lock_problems = check_lock()
    for problem in lock_problems:
        print(f"LOCK  {problem}")
    if not lock_problems:
        print("lock ok: frozen contract files unchanged")
    return 1 if failed or lock_problems else 0


if __name__ == "__main__":
    sys.exit(main())
