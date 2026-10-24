#!/usr/bin/env python3
"""Validate every fixture in contracts/fixtures/index.json against contracts/schemas, and check
that every payload in tests/fixtures/contract_invalid/ is REJECTED by its schema.

Usage:  python scripts/validate_contracts.py
Needs:  pip install "jsonschema>=4.18"
Exit code 0 = all fixtures valid, 1 = at least one failure.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
except ImportError:  # pragma: no cover
    sys.exit('Missing dependency: pip install "jsonschema>=4.18"')

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "contracts" / "schemas"
FIXTURES = ROOT / "contracts" / "fixtures"
INVALID = ROOT / "tests" / "fixtures" / "contract_invalid"
BASE = "https://avn.local/contracts/"


def load_registry() -> Registry:
    resources = []
    for path in sorted(SCHEMAS.glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        resources.append((BASE + path.name, Resource.from_contents(schema)))
    return Registry().with_resources(resources)


def validator_for(ref: str, registry: Registry) -> Draft202012Validator:
    return Draft202012Validator({"$ref": BASE + ref}, registry=registry)


def main() -> int:
    registry = load_registry()
    for path in sorted(SCHEMAS.glob("*.schema.json")):
        Draft202012Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))

    index = json.loads((FIXTURES / "index.json").read_text(encoding="utf-8"))
    envelope = validator_for("envelope.schema.json", registry)
    listed = set()
    failures = 0

    for entry in index["fixtures"]:
        rel = entry["fixture"]
        listed.add(rel)
        doc = json.loads((FIXTURES / rel).read_text(encoding="utf-8"))
        errors = []
        if entry.get("envelope"):
            errors += [f"envelope: {e.message}" for e in envelope.iter_errors(doc)]
            target = doc.get("data")
        else:
            target = doc
        schema = entry["schema"]
        if schema != "envelope-only":
            if target is None:
                errors.append("data is null but a data schema is declared")
            else:
                errors += [f"{schema}: {e.message} at /{'/'.join(map(str, e.path))}"
                           for e in validator_for(schema, registry).iter_errors(target)]
        status = "ok  " if not errors else "FAIL"
        print(f"{status} {rel}")
        for err in errors:
            print(f"      {err}")
        failures += bool(errors)

    unlisted = sorted(
        str(p.relative_to(FIXTURES)).replace("\\", "/")
        for p in FIXTURES.rglob("*.json")
        if p.name != "index.json"
    )
    for rel in unlisted:
        if rel not in listed:
            print(f"FAIL {rel}\n      not listed in contracts/fixtures/index.json")
            failures += 1

    negatives = json.loads((INVALID / "index.json").read_text(encoding="utf-8"))["fixtures"]
    listed_neg = set()
    for entry in negatives:
        rel = entry["fixture"]
        listed_neg.add(rel)
        doc = json.loads((INVALID / rel).read_text(encoding="utf-8"))
        rejected = any(True for _ in validator_for(entry["schema"], registry).iter_errors(doc))
        print(f"{'ok  ' if rejected else 'FAIL'} invalid/{rel}")
        if not rejected:
            print(f"      should be rejected ({entry['why']}) but the schema accepts it")
        failures += not rejected
    for path in sorted(INVALID.glob("*.json")):
        if path.name != "index.json" and path.name not in listed_neg:
            print(f"FAIL invalid/{path.name}\n      not listed in {INVALID.name}/index.json")
            failures += 1

    total = len(index["fixtures"]) + len(negatives)
    print(f"\n{total} fixtures checked ({len(negatives)} must-reject), {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
