#!/usr/bin/env python3
"""Require that a packaged smoke run exercised the requested Oculus shader path."""

from __future__ import annotations

import json
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def load(path: Path) -> dict:
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"cannot read shader validation result {path}: {exception}")
    if not isinstance(result, dict):
        fail(f"shader validation result is not an object: {path}")
    return result


def require_true(result: dict, key: str, source: Path) -> None:
    if result.get(key) is not True:
        fail(f"{source.name}: {key} was not true: {result.get(key)!r}")


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: validate-shader-results.py <report-dir> <expected-pack-filename>")

    report_dir = Path(sys.argv[1])
    expected_pack = sys.argv[2]
    if not expected_pack:
        fail("expected shader-pack filename is empty")

    title = load(report_dir / "packaged-title.json")
    write = load(report_dir / "packaged-world-write.json")
    reopen = load(report_dir / "packaged-world-reopen.json")

    for source, result in (
        (report_dir / "packaged-title.json", title),
        (report_dir / "packaged-world-write.json", write),
        (report_dir / "packaged-world-reopen.json", reopen),
    ):
        require_true(result, "success", source)
        require_true(result, "shaderRequired", source)
        require_true(result, "oculusLoaded", source)
        require_true(result, "shaderPackEnabled", source)
        if result.get("expectedShaderPack") != expected_pack:
            fail(
                f"{source.name}: expectedShaderPack was {result.get('expectedShaderPack')!r}; "
                f"expected {expected_pack!r}"
            )

    for source, result in (
        (report_dir / "packaged-world-write.json", write),
        (report_dir / "packaged-world-reopen.json", reopen),
    ):
        require_true(result, "shaderPipelineCreated", source)
        require_true(result, "shaderPatchLoaded", source)
        if not isinstance(result.get("successfulRenderPasses"), int) or result["successfulRenderPasses"] <= 0:
            fail(f"{source.name}: no completed Voxy terrain pass under the shader pack")

    print(f"packaged client exercised the Voxy Oculus pipeline with {expected_pack}")


if __name__ == "__main__":
    main()
