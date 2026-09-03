#!/usr/bin/env python3
"""Validate the cross-process packaged-client evidence produced by CI."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

EXPECTED_STORAGE_LEAF = "127.0.0.1_25565"


def fail(message: str) -> None:
    raise SystemExit(message)


def load_result(report_dir: Path, name: str) -> dict[str, Any]:
    path = report_dir / name
    if not path.is_file():
        fail(f"missing packaged-client evidence: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid packaged-client evidence in {path}: {exception}")
    if not isinstance(data, dict):
        fail(f"packaged-client evidence is not an object: {path}")
    return data


def require_true(data: dict[str, Any], key: str, source: str) -> None:
    if data.get(key) is not True:
        fail(f"{source}: {key} was not true: {data.get(key)!r}")


def require_positive(data: dict[str, Any], key: str, source: str) -> None:
    value = data.get(key)
    if not isinstance(value, int) or value <= 0:
        fail(f"{source}: {key} was not positive: {value!r}")


def validate_common(data: dict[str, Any], phase: str, source: str) -> None:
    if data.get("phase") != phase:
        fail(f"{source}: expected phase {phase!r}, got {data.get('phase')!r}")
    for key in ("success", "voxyLoaded", "embeddiumLoaded", "backendInitialized", "backendReady"):
        require_true(data, key, source)


def main() -> None:
    report_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("ci/packaged-runtime-smoke")
    title = load_result(report_dir, "packaged-title.json")
    write = load_result(report_dir, "packaged-world-write.json")
    reopen = load_result(report_dir, "packaged-world-reopen.json")
    before = load_result(report_dir, "persistence-before-reopen.json")
    after = load_result(report_dir, "persistence-after-reopen.json")

    validate_common(title, "title", "packaged-title.json")
    validate_common(write, "world-write", "packaged-world-write.json")
    validate_common(reopen, "world-reopen", "packaged-world-reopen.json")

    for source, data in (("packaged-world-write.json", write), ("packaged-world-reopen.json", reopen)):
        for key in ("worldJoined", "instanceCreated", "renderSystemCreated"):
            require_true(data, key, source)
        require_positive(data, "successfulRenderPasses", source)

        raw_path = data.get("storageBasePath")
        if not isinstance(raw_path, str) or not raw_path:
            fail(f"{source}: storageBasePath was empty")
        storage_path = Path(raw_path)
        if storage_path.name != EXPECTED_STORAGE_LEAF:
            fail(
                f"{source}: direct-connect storage leaf was {storage_path.name!r}; "
                f"expected {EXPECTED_STORAGE_LEAF!r}"
            )
        if "UNKNOWN" in storage_path.parts:
            fail(f"{source}: direct-connect persistence used the shared UNKNOWN path")

    if write["storageBasePath"] != reopen["storageBasePath"]:
        fail("storageBasePath changed across the full client restart")

    require_positive(write, "successfulSectionSaves", "packaged-world-write.json")
    require_positive(write, "currentPersistenceBytes", "packaged-world-write.json")
    require_positive(reopen, "initialPersistenceBytes", "packaged-world-reopen.json")
    require_positive(reopen, "successfulSectionLoads", "packaged-world-reopen.json")
    require_positive(before, "nonemptyFileCount", "persistence-before-reopen.json")
    require_positive(before, "totalBytes", "persistence-before-reopen.json")
    require_positive(after, "nonemptyFileCount", "persistence-after-reopen.json")
    require_positive(after, "totalBytes", "persistence-after-reopen.json")

    print("packaged-client evidence satisfies the Forge 1.19.2 release contract")


if __name__ == "__main__":
    main()
