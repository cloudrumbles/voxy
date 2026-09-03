#!/usr/bin/env python3
"""Validate the cross-process packaged-client evidence produced by CI."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

# Minecraft/Netty may preserve the configured numeric loopback address or
# expose its canonical localhost name. Both identify the same validation
# endpoint; the contract is stable server isolation, not DNS spelling.
EXPECTED_STORAGE_LEAVES = frozenset({"127.0.0.1_25565", "localhost_25565"})


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


def validate_persistence_manifest(data: dict[str, Any], source: str, storage_leaf: str) -> None:
    require_positive(data, "nonemptyFileCount", source)
    require_positive(data, "totalBytes", source)

    files = data.get("files")
    if not isinstance(files, list) or not files:
        fail(f"{source}: files was empty or invalid")
    if len(files) != data["nonemptyFileCount"]:
        fail(f"{source}: file count does not match nonemptyFileCount")

    for entry in files:
        if not isinstance(entry, dict):
            fail(f"{source}: invalid file entry: {entry!r}")
        relative_path = entry.get("path")
        if not isinstance(relative_path, str) or not relative_path:
            fail(f"{source}: invalid persisted path: {relative_path!r}")
        path = Path(relative_path)
        if not path.parts or path.parts[0] != storage_leaf:
            fail(
                f"{source}: persisted file escaped the validated server directory: "
                f"{relative_path!r}"
            )
        size = entry.get("bytes")
        if not isinstance(size, int) or size <= 0:
            fail(f"{source}: non-positive persisted file size: {entry!r}")


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

    storage_paths: list[Path] = []
    for source, data in (("packaged-world-write.json", write), ("packaged-world-reopen.json", reopen)):
        for key in ("worldJoined", "instanceCreated", "renderSystemCreated"):
            require_true(data, key, source)
        require_positive(data, "successfulRenderPasses", source)

        raw_path = data.get("storageBasePath")
        if not isinstance(raw_path, str) or not raw_path:
            fail(f"{source}: storageBasePath was empty")
        storage_path = Path(raw_path)
        storage_paths.append(storage_path)
        if storage_path.name not in EXPECTED_STORAGE_LEAVES:
            fail(
                f"{source}: direct-connect storage leaf was {storage_path.name!r}; "
                f"expected one of {sorted(EXPECTED_STORAGE_LEAVES)!r}"
            )
        if storage_path.parent.name != "saves" or storage_path.parent.parent.name != ".voxy":
            fail(f"{source}: storage path was outside the expected .voxy/saves root")
        if "UNKNOWN" in storage_path.parts:
            fail(f"{source}: direct-connect persistence used the shared UNKNOWN path")

    if storage_paths[0] != storage_paths[1]:
        fail("storageBasePath changed across the full client restart")
    storage_leaf = storage_paths[0].name

    require_positive(write, "successfulSectionSaves", "packaged-world-write.json")
    require_positive(write, "currentPersistenceBytes", "packaged-world-write.json")
    require_positive(reopen, "initialPersistenceBytes", "packaged-world-reopen.json")
    require_positive(reopen, "successfulSectionLoads", "packaged-world-reopen.json")

    validate_persistence_manifest(before, "persistence-before-reopen.json", storage_leaf)
    validate_persistence_manifest(after, "persistence-after-reopen.json", storage_leaf)

    if reopen["initialPersistenceBytes"] != before["totalBytes"]:
        fail(
            "world-reopen did not observe the exact persistence snapshot created "
            "by the first client process"
        )

    print("packaged-client evidence satisfies the Forge 1.19.2 release contract")


if __name__ == "__main__":
    main()
