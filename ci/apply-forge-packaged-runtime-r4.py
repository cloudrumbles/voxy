from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing {label} anchor")
    return text.replace(old, new, 1)


mods_path = ROOT / "src/main/resources/META-INF/mods.toml"
mods = mods_path.read_text(encoding="utf-8")
if 'displayTest="IGNORE_SERVER_VERSION"' not in mods:
    candidates = [
        'description="${mod_description}"\n',
        'description = "${mod_description}"\n',
    ]
    for candidate in candidates:
        if candidate in mods:
            mods = mods.replace(candidate, candidate + 'displayTest="IGNORE_SERVER_VERSION"\n', 1)
            break
    else:
        # Keep the value inside the first [[mods]] table even if the template
        # formatting differs from the MDK default.
        table = "[[mods]]\n"
        if table not in mods:
            raise RuntimeError("missing [[mods]] table")
        mods = mods.replace(table, table + 'displayTest="IGNORE_SERVER_VERSION"\n', 1)
mods_path.write_text(mods, encoding="utf-8")

workflow_path = ROOT / ".github/workflows/forge-1.19.2-ci.yml"
workflow = workflow_path.read_text(encoding="utf-8")
workflow = workflow.replace("timeout-minutes: 90", "timeout-minutes: 120", 1)
if "Packaged Forge artifact smoke tests" not in workflow:
    anchor = '''      - name: Forge runtime smoke tests
        shell: bash
        run: bash ci/run-forge-smoke.sh
'''
    addition = anchor + '''
      - name: Packaged Forge artifact smoke tests
        shell: bash
        run: bash ci/run-packaged-forge-smoke.sh
'''
    workflow = replace_once(workflow, anchor, addition, "Forge runtime smoke step")
if "ci/packaged-runtime-smoke/" not in workflow:
    runtime_path = "            ci/runtime-smoke/\n"
    workflow = replace_once(
        workflow,
        runtime_path,
        runtime_path + "            ci/packaged-runtime-smoke/\n",
        "runtime artifact path",
    )
workflow_path.write_text(workflow, encoding="utf-8")

porting_path = ROOT / "PORTING.md"
porting = porting_path.read_text(encoding="utf-8")
marker = '''

## Installed-artifact validation

CI builds a separate empty Forge 1.19.2 userdev environment and loads only the
reobfuscated Voxy artifact plus its declared client dependencies from the
`mods` directory. It reaches the title screen, connects to a Forge server that
does not have Voxy installed, creates a live Voxy renderer, and repeats the
world launch with Oculus and an enabled shader pack. The Forge metadata uses
`IGNORE_SERVER_VERSION` so the client-only renderer remains compatible with
ordinary servers. Runtime contract revision: forge-1.19.2-r4.
'''
if "forge-1.19.2-r4." not in porting:
    porting_path.write_text(porting.rstrip() + marker, encoding="utf-8")
