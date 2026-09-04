#!/usr/bin/env python3
"""Ensure VoxyRenderSystem selects the imported Iris/Oculus pipeline."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"


def main() -> None:
    text = PATH.read_text(encoding="utf-8")
    if "new IrisVoxyRenderPipeline" in text:
        return

    pattern = re.compile(
        r"(?P<indent>^[ \t]*)(?P<lhs>(?:this\.)?[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*)"
        r"new\s+BasicVoxyRenderPipeline\((?P<args>.*?)\);",
        re.MULTILINE | re.DOTALL,
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise SystemExit(
            f"expected one BasicVoxyRenderPipeline assignment, found {len(matches)}"
        )
    match = matches[0]
    indent = match.group("indent")
    lhs = match.group("lhs")
    args = match.group("args")
    replacement = (
        f"{indent}{lhs}IrisUtil.irisShaderPackEnabled()\n"
        f"{indent}        ? new IrisVoxyRenderPipeline({args})\n"
        f"{indent}        : new BasicVoxyRenderPipeline({args});"
    )
    text = text[: match.start()] + replacement + text[match.end() :]

    if "import me.cortex.voxy.client.core.util.IrisUtil;" not in text:
        package_end = text.find("\n", text.find("package ")) + 1
        text = (
            text[:package_end]
            + "\nimport me.cortex.voxy.client.core.util.IrisUtil;\n"
            + text[package_end:]
        )

    PATH.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
