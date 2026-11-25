"""Tests for scripts/fetch_models.py (WS01-04, AVN-30). Run: python -m pytest scripts/tests -q"""
from __future__ import annotations

import hashlib
import http.server
import json
import sys
import threading
from functools import partial
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_models as fm  # noqa: E402

PAYLOAD = b"fake onnx bytes for tests"
SHA = hashlib.sha256(PAYLOAD).hexdigest()


def entry(**over) -> dict:
    base = {"name": "det", "version": "1", "file": "det.onnx", "sha256": SHA,
            "license": "MIT", "source": "test", "url": None}
    return {**base, **over}


def write_manifest(models: Path, *entries: dict) -> None:
    models.mkdir(parents=True, exist_ok=True)
    (models / "manifest.json").write_text(json.dumps({"version": 1, "models": list(entries)}))


@pytest.fixture()
def server(tmp_path: Path):
    """Serves tmp_path/www over http://127.0.0.1 (no network)."""
    www = tmp_path / "www"
    www.mkdir()
    handler = partial(http.server.SimpleHTTPRequestHandler, directory=str(www))
    handler.log_message = lambda *a, **k: None
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield www, f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


# ------------------------------------------------------------------ manifest validation


def test_repo_manifest_is_valid():
    manifest = json.loads((fm.MODELS / "manifest.json").read_text(encoding="utf-8"))
    assert fm.validate_manifest(manifest) == []


def test_repo_manifest_app_models_match_android_configs():
    manifest = json.loads((fm.MODELS / "manifest.json").read_text(encoding="utf-8"))
    shipped = {Path(e["file"]).name for e in manifest["models"] if e.get("android_asset", True)}
    config_dir = fm.ROOT / "android" / "app" / "src" / "main" / "resources" / "config"
    wanted = {json.loads(p.read_text())["modelAsset"] for p in config_dir.glob("detector*.json")}
    assert wanted <= shipped


@pytest.mark.parametrize(("bad", "msg"), [
    ({"sha256": "XYZ"}, "64 lowercase hex"),
    ({"file": "../escape.onnx"}, "plain relative path"),
    ({"file": "a b.onnx"}, "plain relative path"),
    ({"url": "http://example.com/x.onnx"}, "https"),
    ({"license": ""}, "missing 'license'"),
])
def test_bad_manifest_entries_are_reported(bad, msg):
    problems = fm.validate_manifest({"models": [entry(**bad)]})
    assert any(msg in p for p in problems), problems


def test_duplicate_names_are_reported():
    assert any("duplicate" in p for p in fm.validate_manifest({"models": [entry(), entry()]}))


# ------------------------------------------------------------------ fetching


def test_present_and_verified_file_is_kept(tmp_path: Path):
    models = tmp_path / "models"
    write_manifest(models, entry())
    (models / "det.onnx").write_bytes(PAYLOAD)
    assert fm.fetch_one(entry(), models, None, check_only=False) == "ok"


def test_download_verifies_and_installs(tmp_path: Path, server):
    www, base = server
    (www / "det.onnx").write_bytes(PAYLOAD)
    models = tmp_path / "models"
    result = fm.fetch_one(entry(url=f"{base}/det.onnx", file="sub/det.onnx"), models, None, False)
    assert result == "fetched"
    assert (models / "sub" / "det.onnx").read_bytes() == PAYLOAD  # parent folder created
    assert not list(models.rglob("*.part"))


def test_download_with_wrong_hash_is_discarded(tmp_path: Path, server):
    www, base = server
    (www / "det.onnx").write_bytes(b"<html>not a model</html>")
    models = tmp_path / "models"
    assert fm.fetch_one(entry(url=f"{base}/det.onnx"), models, None, False) == "failed"
    assert not (models / "det.onnx").exists()
    assert not list(models.rglob("*.part"))


def test_download_error_is_soft(tmp_path: Path, server):
    _, base = server
    models = tmp_path / "models"
    assert fm.fetch_one(entry(url=f"{base}/missing.onnx"), models, None, False) == "failed"
    assert not list(models.rglob("*.part"))


def test_copy_from_shared_folder(tmp_path: Path):
    shared = tmp_path / "shared"
    shared.mkdir()
    (shared / "det.onnx").write_bytes(PAYLOAD)
    models = tmp_path / "models"
    e = entry(file="nested/det.onnx")  # found by bare file name in the shared folder
    assert fm.fetch_one(e, models, shared, False) == "fetched"
    assert (models / "nested" / "det.onnx").read_bytes() == PAYLOAD


def test_check_mode_never_downloads(tmp_path: Path, server):
    www, base = server
    (www / "det.onnx").write_bytes(PAYLOAD)
    models = tmp_path / "models"
    assert fm.fetch_one(entry(url=f"{base}/det.onnx"), models, None, True) == "missing"
    assert not (models / "det.onnx").exists()


def test_corrupt_local_file_is_replaced(tmp_path: Path):
    shared = tmp_path / "shared"
    shared.mkdir()
    (shared / "det.onnx").write_bytes(PAYLOAD)
    models = tmp_path / "models"
    models.mkdir()
    (models / "det.onnx").write_bytes(b"truncated")
    assert fm.fetch_one(entry(), models, shared, False) == "fetched"
    assert (models / "det.onnx").read_bytes() == PAYLOAD


# ------------------------------------------------------------------ main: required, android


def test_optional_missing_models_do_not_fail(tmp_path: Path):
    models, assets = tmp_path / "models", tmp_path / "assets"
    write_manifest(models, entry(), entry(name="backup", file="b.onnx", required=False))
    (models / "det.onnx").write_bytes(PAYLOAD)
    assert fm.main([], models_dir=models, assets_dir=assets) == 0


def test_required_missing_model_fails(tmp_path: Path):
    models = tmp_path / "models"
    write_manifest(models, entry())
    assert fm.main(["--check"], models_dir=models, assets_dir=tmp_path / "a") == 1


def test_android_copies_only_app_models(tmp_path: Path):
    models, assets = tmp_path / "models", tmp_path / "assets"
    backup = entry(name="backup", file="backup/det.onnx", android_asset=False, required=False)
    write_manifest(models, entry(), backup)
    (models / "det.onnx").write_bytes(PAYLOAD)
    (models / "backup").mkdir()
    (models / "backup" / "det.onnx").write_bytes(PAYLOAD)
    assert fm.main(["--android"], models_dir=models, assets_dir=assets) == 0
    assert [p.name for p in assets.iterdir()] == ["det.onnx"]


def test_invalid_manifest_stops_before_fetching(tmp_path: Path):
    models = tmp_path / "models"
    write_manifest(models, entry(sha256="nope"))
    assert fm.main([], models_dir=models, assets_dir=tmp_path / "a") == 1


def test_repo_history_has_no_weights():
    assert fm.weights_in_history() == []
