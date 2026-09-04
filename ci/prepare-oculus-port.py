#!/usr/bin/env python3
"""Adapt the imported legacy Iris integration to optional Forge/Oculus loading."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java"


def update_java_sources() -> list[str]:
    changed: list[str] = []
    candidates = [
        JAVA / "me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java",
        JAVA / "me/cortex/voxy/client/core/util/IrisUtil.java",
        *sorted((JAVA / "me/cortex/voxy/client/iris").glob("*.java")),
        *sorted((JAVA / "me/cortex/voxy/client/mixin/iris").glob("*.java")),
    ]
    for path in candidates:
        if not path.is_file():
            continue
        original = path.read_text(encoding="utf-8")
        text = original.replace("net.irisshaders.iris", "net.coderbot.iris")
        text = text.replace(
            "import net.fabricmc.loader.api.FabricLoader;",
            "import me.cortex.voxy.commonImpl.ForgePlatform;",
        )
        text = text.replace(
            'FabricLoader.getInstance().isModLoaded("iris")',
            'ForgePlatform.isModLoaded("oculus")',
        )
        text = text.replace(
            'FabricLoader.getInstance().isModLoaded("oculus")',
            'ForgePlatform.isModLoaded("oculus")',
        )
        if text != original:
            path.write_text(text, encoding="utf-8")
            changed.append(str(path.relative_to(ROOT)))
    return changed


def configure_mixin_plugin() -> bool:
    path = JAVA / "me/cortex/voxy/client/mixin/ClientVoxyMixinPlugin.java"
    text = path.read_text(encoding="utf-8")
    if 'mixinClassName.contains(".iris.")' in text:
        return False
    anchor = "    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {\n"
    replacement = anchor + (
        '        if (mixinClassName.contains(".iris.")) {\n'
        '            return ForgePlatform.isModLoaded("oculus");\n'
        '        }\n'
    )
    if anchor not in text:
        raise SystemExit("could not locate ClientVoxyMixinPlugin.shouldApplyMixin")
    path.write_text(text.replace(anchor, replacement, 1), encoding="utf-8")
    return True


def configure_mixins() -> list[str]:
    path = ROOT / "src/main/resources/client.voxy.mixins.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    client = data.setdefault("client", [])
    added: list[str] = []
    source_root = JAVA / "me/cortex/voxy/client/mixin/iris"
    for source in sorted(source_root.glob("*.java")):
        mixin = f"iris.{source.stem}"
        if mixin not in client:
            client.append(mixin)
            added.append(mixin)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return added


def validate_no_fabric_runtime_imports() -> None:
    offenders: list[str] = []
    roots = [
        JAVA / "me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java",
        JAVA / "me/cortex/voxy/client/core/util/IrisUtil.java",
        *sorted((JAVA / "me/cortex/voxy/client/iris").glob("*.java")),
        *sorted((JAVA / "me/cortex/voxy/client/mixin/iris").glob("*.java")),
    ]
    for path in roots:
        if path.is_file() and "net.fabricmc" in path.read_text(encoding="utf-8"):
            offenders.append(str(path.relative_to(ROOT)))
    if offenders:
        raise SystemExit("legacy compatibility source still imports Fabric APIs: " + ", ".join(offenders))


def main() -> None:
    changed = update_java_sources()
    plugin_changed = configure_mixin_plugin()
    added_mixins = configure_mixins()
    validate_no_fabric_runtime_imports()
    report = {
        "changed_java": changed,
        "plugin_changed": plugin_changed,
        "added_mixins": added_mixins,
    }
    (ROOT / "OCULUS_PORT_PREPARATION.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
