from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing {label} anchor")
    return text.replace(old, new, 1)


build_path = ROOT / "build.gradle"
build = build_path.read_text(encoding="utf-8")

if "config 'common.voxy.mixins.json'" not in build:
    build = replace_once(
        build,
        "    config 'client.voxy.mixins.json'\n",
        "    config 'client.voxy.mixins.json'\n    config 'common.voxy.mixins.json'\n",
        "MixinGradle client config",
    )

if "'MixinConfigs'" not in build:
    candidates = [
        "                'Implementation-Vendor'   : 'Cortex'\n",
        "                'Implementation-Vendor'   : 'Cortex',\n",
    ]
    for candidate in candidates:
        if candidate in build:
            replacement = (
                "                'Implementation-Vendor'   : 'Cortex',\n"
                "                'MixinConfigs'            : 'client.voxy.mixins.json,common.voxy.mixins.json'\n"
            )
            build = build.replace(candidate, replacement, 1)
            break
    else:
        raise RuntimeError("missing jar manifest anchor")

build_path.write_text(build, encoding="utf-8")

mods_path = ROOT / "src/main/resources/META-INF/mods.toml"
mods = mods_path.read_text(encoding="utf-8")
if 'modId="embeddium"' not in mods:
    mods = mods.rstrip() + '''

[[dependencies.voxy]]
modId="embeddium"
mandatory=true
versionRange="[0.3.31,)"
ordering="AFTER"
side="CLIENT"
'''
if 'modId="oculus"' not in mods:
    mods = mods.rstrip() + '''

[[dependencies.voxy]]
modId="oculus"
mandatory=false
versionRange="[1.6.9,)"
ordering="AFTER"
side="CLIENT"
'''
mods_path.write_text(mods.rstrip() + "\n", encoding="utf-8")

workflow_path = ROOT / ".github/workflows/forge-1.19.2-ci.yml"
workflow = workflow_path.read_text(encoding="utf-8")
client_check = "          jar tf \"$jar_file\" | grep -qx 'client.voxy.mixins.json'\n"
if "grep -qx 'common.voxy.mixins.json'" not in workflow:
    workflow = replace_once(
        workflow,
        client_check,
        client_check + "          jar tf \"$jar_file\" | grep -qx 'common.voxy.mixins.json'\n",
        "client mixin package check",
    )
if "META-INF/MANIFEST.MF" not in workflow or "common.voxy.mixins.json'" not in workflow.split("META-INF/MANIFEST.MF")[-1]:
    access_check = "          jar tf \"$jar_file\" | grep -qx 'META-INF/accesstransformer.cfg'\n"
    manifest_checks = (
        access_check
        + "          unzip -p \"$jar_file\" META-INF/MANIFEST.MF | tr -d '\\r' | grep -F 'client.voxy.mixins.json'\n"
        + "          unzip -p \"$jar_file\" META-INF/MANIFEST.MF | tr -d '\\r' | grep -F 'common.voxy.mixins.json'\n"
    )
    workflow = replace_once(workflow, access_check, manifest_checks, "access transformer package check")
workflow_path.write_text(workflow, encoding="utf-8")

porting_path = ROOT / "PORTING.md"
porting = porting_path.read_text(encoding="utf-8")
marker = '''

## Forge runtime contract

The distributable declares Embeddium as a required client dependency and
Oculus as optional. Both the client and common Mixin configurations are listed
explicitly in the JAR manifest and are asserted by CI after Forge
reobfuscation. Runtime contract revision: forge-1.19.2-r2.
'''
if "Runtime contract revision: forge-1.19.2-r2." not in porting:
    porting_path.write_text(porting.rstrip() + marker, encoding="utf-8")
