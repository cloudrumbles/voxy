#!/usr/bin/env python3
"""Add opt-in evidence hooks and optional shader-runtime installation to CI."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"cannot locate {label}")
    return text.replace(old, new, 1)


def instrument_smoke_probe() -> None:
    path = ROOT / "src/main/java/me/cortex/voxy/client/VoxyClientSmoke.java"
    text = path.read_text(encoding="utf-8")
    if "SHADER_PIPELINE_CREATED" in text:
        return

    text = replace_once(
        text,
        "import me.cortex.voxy.client.core.IGetVoxyRenderSystem;\n",
        "import me.cortex.voxy.client.core.IGetVoxyRenderSystem;\n"
        "import me.cortex.voxy.client.core.util.IrisUtil;\n",
        "VoxyClientSmoke imports",
    )
    text = replace_once(
        text,
        "    private static final AtomicLong SUCCESSFUL_RENDER_PASSES = new AtomicLong();\n",
        "    private static final AtomicLong SUCCESSFUL_RENDER_PASSES = new AtomicLong();\n"
        "    private static final AtomicBoolean SHADER_PIPELINE_CREATED = new AtomicBoolean();\n"
        "    private static final AtomicBoolean SHADER_PATCH_LOADED = new AtomicBoolean();\n"
        "    private static final boolean REQUIRE_SHADERS = Boolean.getBoolean(\"voxy.smoke.requireShaders\");\n"
        "    private static final String EXPECTED_SHADER_PACK = System.getProperty(\"voxy.smoke.expectedShaderPack\", \"\");\n",
        "smoke counters",
    )
    text = replace_once(
        text,
        "    private static void onClientTick(TickEvent.ClientTickEvent event) {\n",
        "    public static void recordShaderPipelineCreated() {\n"
        "        if (!PHASE.isEmpty()) {\n"
        "            SHADER_PIPELINE_CREATED.set(true);\n"
        "        }\n"
        "    }\n\n"
        "    public static void recordShaderPatchLoaded() {\n"
        "        if (!PHASE.isEmpty()) {\n"
        "            SHADER_PATCH_LOADED.set(true);\n"
        "        }\n"
        "    }\n\n"
        "    private static boolean shaderContractSatisfied() {\n"
        "        return !REQUIRE_SHADERS\n"
        "                || (ModList.get().isLoaded(\"oculus\")\n"
        "                && IrisUtil.irisShaderPackEnabled()\n"
        "                && SHADER_PIPELINE_CREATED.get()\n"
        "                && SHADER_PATCH_LOADED.get());\n"
        "    }\n\n"
        "    private static void onClientTick(TickEvent.ClientTickEvent event) {\n",
        "tick handler",
    )
    text = replace_once(
        text,
        "                    && VoxyClient.isRenderBackendReady()) {\n",
        "                    && VoxyClient.isRenderBackendReady()\n"
        "                    && (!REQUIRE_SHADERS || (ModList.get().isLoaded(\"oculus\")\n"
        "                    && IrisUtil.irisShaderPackEnabled()))) {\n",
        "title success condition",
    )
    text = replace_once(
        text,
        "                        && successfulRenderPasses > 0L\n                        && storageCondition) {\n",
        "                        && successfulRenderPasses > 0L\n"
        "                        && storageCondition\n"
        "                        && shaderContractSatisfied()) {\n",
        "world success condition",
    )
    text = replace_once(
        text,
        '                + "  \\\"embeddiumLoaded\\\": " + ModList.get().isLoaded("embeddium") + ",\\n"\n',
        '                + "  \\\"embeddiumLoaded\\\": " + ModList.get().isLoaded("embeddium") + ",\\n"\n'
        '                + "  \\\"oculusLoaded\\\": " + ModList.get().isLoaded("oculus") + ",\\n"\n'
        '                + "  \\\"shaderRequired\\\": " + REQUIRE_SHADERS + ",\\n"\n'
        '                + "  \\\"shaderPackEnabled\\\": " + IrisUtil.irisShaderPackEnabled() + ",\\n"\n'
        '                + "  \\\"shaderPipelineCreated\\\": " + SHADER_PIPELINE_CREATED.get() + ",\\n"\n'
        '                + "  \\\"shaderPatchLoaded\\\": " + SHADER_PATCH_LOADED.get() + ",\\n"\n'
        '                + "  \\\"expectedShaderPack\\\": \\\"" + escape(EXPECTED_SHADER_PACK) + "\\\",\\n"\n',
        "result JSON shader fields",
    )
    path.write_text(text, encoding="utf-8")


def instrument_pipeline() -> None:
    path = ROOT / "src/main/java/me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java"
    text = path.read_text(encoding="utf-8")
    if "recordShaderPipelineCreated" in text:
        return
    package_end = text.find("\n", text.find("package ")) + 1
    text = text[:package_end] + "\nimport me.cortex.voxy.client.VoxyClientSmoke;\n" + text[package_end:]
    match = re.search(r"public\s+IrisVoxyRenderPipeline\s*\([^)]*\)\s*\{", text, re.DOTALL)
    if not match:
        raise SystemExit("cannot locate IrisVoxyRenderPipeline constructor")
    text = text[:match.end()] + "\n        VoxyClientSmoke.recordShaderPipelineCreated();" + text[match.end():]
    path.write_text(text, encoding="utf-8")


def instrument_patch_parser() -> None:
    path = ROOT / "src/main/java/me/cortex/voxy/client/iris/IrisShaderPatch.java"
    text = path.read_text(encoding="utf-8")
    if "recordShaderPatchLoaded" in text:
        return
    package_end = text.find("\n", text.find("package ")) + 1
    text = text[:package_end] + "\nimport me.cortex.voxy.client.VoxyClientSmoke;\n" + text[package_end:]
    old = "        return new IrisShaderPatch(patchData, ipack);"
    new = "        VoxyClientSmoke.recordShaderPatchLoaded();\n" + old
    text = replace_once(text, old, new, "IrisShaderPatch return")
    path.write_text(text, encoding="utf-8")


def add_optional_runtime_hook() -> None:
    path = ROOT / "ci/run-packaged-forge-smoke.sh"
    text = path.read_text(encoding="utf-8")
    marker = "voxy_install_optional_shader_runtime()"
    if marker in text:
        return

    function = r'''

voxy_install_optional_shader_runtime() {
    if [[ -z "${VOXY_OCULUS_JAR:-}" && -z "${VOXY_SHADERPACK_FILE:-}" ]]; then
        return
    fi

    local repo_root client_root shader_name
    repo_root="$(git rev-parse --show-toplevel)"
    client_root="$repo_root/run-packaged-client"

    if [[ -n "${VOXY_OCULUS_JAR:-}" ]]; then
        test -s "$VOXY_OCULUS_JAR"
        mkdir -p "$client_root/mods"
        cp -f "$VOXY_OCULUS_JAR" "$client_root/mods/oculus.jar"
    fi

    if [[ -n "${VOXY_SHADERPACK_FILE:-}" ]]; then
        test -s "$VOXY_SHADERPACK_FILE"
        shader_name="${VOXY_SHADERPACK_NAME:-$(basename "$VOXY_SHADERPACK_FILE")}" 
        mkdir -p "$client_root/shaderpacks" "$client_root/config"
        cp -f "$VOXY_SHADERPACK_FILE" "$client_root/shaderpacks/$shader_name"
        cat > "$client_root/config/oculus.properties" <<EOF_OCULUS
shaderPack=$shader_name
enableShaders=true
EOF_OCULUS
        cat > "$client_root/config/iris.properties" <<EOF_IRIS
shaderPack=$shader_name
enableShaders=true
EOF_IRIS
    fi
}
'''
    insertion = text.find("\n", text.find("set -")) + 1
    if insertion <= 0:
        insertion = text.find("\n") + 1
    text = text[:insertion] + function + text[insertion:]

    lines = text.splitlines(keepends=True)
    output: list[str] = []
    heredoc_end: str | None = None
    inserted = 0
    start_pattern = re.compile(r"<<-?['\"]?([A-Za-z0-9_]+)['\"]?")
    for line in lines:
        stripped = line.strip()
        if heredoc_end is not None:
            output.append(line)
            if stripped == heredoc_end:
                heredoc_end = None
            continue
        heredoc = start_pattern.search(line)
        if heredoc:
            heredoc_end = heredoc.group(1)
            output.append(line)
            continue
        if "runClient" in line and not stripped.startswith("#"):
            indent = line[: len(line) - len(line.lstrip())]
            output.append(indent + "voxy_install_optional_shader_runtime\n")
            inserted += 1
        output.append(line)
    if inserted == 0:
        raise SystemExit("could not locate a runClient invocation in packaged smoke script")
    path.write_text("".join(output), encoding="utf-8")


def main() -> None:
    instrument_smoke_probe()
    instrument_pipeline()
    instrument_patch_parser()
    add_optional_runtime_hook()


if __name__ == "__main__":
    main()
