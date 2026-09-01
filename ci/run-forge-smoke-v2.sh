#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="$ROOT_DIR/ci/runtime-smoke"
mkdir -p "$REPORT_DIR"

if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "xvfb-run is required for the Forge client smoke tests" >&2
    exit 2
fi
if ! command -v setsid >/dev/null 2>&1; then
    echo "setsid is required for isolated Forge server process groups" >&2
    exit 2
fi

write_server_config() {
    rm -rf run-smoke-server
    mkdir -p run-smoke-server
    printf 'eula=true\n' > run-smoke-server/eula.txt
    cat > run-smoke-server/server.properties <<'PROPERTIES'
online-mode=false
server-port=25565
server-ip=127.0.0.1
level-name=voxy-smoke-world
level-seed=8675309
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=2
motd=Voxy Forge 1.19.2 smoke test
PROPERTIES
}

server_pid=""
stop_server() {
    if [[ -z "$server_pid" ]]; then
        return
    fi

    kill -TERM -- "-$server_pid" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            server_pid=""
            return
        fi
        sleep 0.5
    done
    kill -KILL -- "-$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
    server_pid=""
}
trap stop_server EXIT

start_server() {
    local name="$1"
    write_server_config
    rm -f "$REPORT_DIR/${name}-server.log"

    setsid --wait ./gradlew --no-daemon \
        --project-cache-dir "build/${name}-server-project-cache" \
        runServer -PvoxySmokePhase=world \
        > "$REPORT_DIR/${name}-server.log" 2>&1 &
    server_pid=$!

    local ready=false
    for _ in $(seq 1 300); do
        if grep -Eq 'Done \([0-9.]+s\)!|Done \(' "$REPORT_DIR/${name}-server.log" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            echo "Forge smoke server exited before readiness" >&2
            tail -n 200 "$REPORT_DIR/${name}-server.log" >&2 || true
            return 10
        fi
        sleep 1
    done

    if [[ "$ready" != true ]]; then
        echo "Forge smoke server did not become ready" >&2
        tail -n 200 "$REPORT_DIR/${name}-server.log" >&2 || true
        return 11
    fi
}

run_client() {
    local name="$1"
    local phase="$2"
    local result_dir="$3"
    shift 3

    rm -f "$result_dir/voxy-smoke-${phase}.json"
    set +e
    timeout 600s xvfb-run -a -s '-screen 0 1280x720x24' \
        env LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.6 \
        ./gradlew --no-daemon \
            --project-cache-dir "build/${name}-client-project-cache" \
            runClient -PvoxySmokePhase="$phase" "$@" \
            > "$REPORT_DIR/${name}-client.log" 2>&1
    local status=$?
    set -e

    if [[ "$status" -ne 0 ]]; then
        echo "Forge smoke client '$name' exited with status $status" >&2
        tail -n 250 "$REPORT_DIR/${name}-client.log" >&2 || true
        return "$status"
    fi
    if [[ ! -f "$result_dir/voxy-smoke-${phase}.json" ]]; then
        echo "Forge smoke client '$name' did not produce its result record" >&2
        return 12
    fi
    if ! grep -Fq '"success": true' "$result_dir/voxy-smoke-${phase}.json"; then
        cat "$result_dir/voxy-smoke-${phase}.json" >&2
        return 13
    fi
}

reject_linkage_failures() {
    local log="$1"
    if grep -Eqi \
        'MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError' \
        "$log"; then
        echo "linkage or mixin failure detected in $log" >&2
        grep -Ein \
            'MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError' \
            "$log" | tail -n 100 >&2 || true
        return 1
    fi
}

snapshot_persistence() {
    local run_dir="$1"
    local output="$2"
    python3 - "$run_dir" "$output" <<'PYTHON'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
output = Path(sys.argv[2])
files = sorted(
    path for path in root.rglob('*')
    if path.is_file()
    and ('voxy' in path.name.lower() or '.voxy' in path.parts)
    and not path.name.startswith('voxy-smoke-')
    and path.as_posix() != f'{root.as_posix()}/config/voxy-config.json'
    and 'logs' not in path.parts
)
nonempty = [path for path in files if path.stat().st_size > 0]
record = {
    'root': root.as_posix(),
    'file_count': len(files),
    'nonempty_file_count': len(nonempty),
    'total_bytes': sum(path.stat().st_size for path in nonempty),
    'files': [
        {'path': path.relative_to(root).as_posix(), 'bytes': path.stat().st_size}
        for path in nonempty
    ],
}
output.write_text(json.dumps(record, indent=2) + '\n')
if record['nonempty_file_count'] == 0 or record['total_bytes'] == 0:
    raise SystemExit('Voxy did not create a nonempty persistence store')
PYTHON
}

compare_persistence_snapshots() {
    local first="$1"
    local second="$2"
    python3 - "$first" "$second" <<'PYTHON'
from pathlib import Path
import json
import sys

first = json.loads(Path(sys.argv[1]).read_text())
second = json.loads(Path(sys.argv[2]).read_text())
if first['nonempty_file_count'] <= 0 or first['total_bytes'] <= 0:
    raise SystemExit('first Voxy persistence snapshot was empty')
if second['nonempty_file_count'] <= 0 or second['total_bytes'] <= 0:
    raise SystemExit('Voxy persistence was empty after reopen')

# RocksDB/LMDB are allowed to rotate WAL and manifest files while opening.
# The second live-renderer launch is the reopen test; this check protects the
# durable invariant without requiring byte-identical internal housekeeping.
first_roots = {entry['path'].split('/', 1)[0] for entry in first['files']}
second_roots = {entry['path'].split('/', 1)[0] for entry in second['files']}
if not first_roots.intersection(second_roots):
    raise SystemExit('Voxy persistence root changed across restart')
PYTHON
}

# Phase 1: the ordinary Forge client must reach the title screen.
rm -rf run-smoke-client
mkdir -p run-smoke-client
run_client core-title title run-smoke-client
cp run-smoke-client/voxy-smoke-title.json "$REPORT_DIR/core-title.json"
reject_linkage_failures "$REPORT_DIR/core-title-client.log"

# Phase 2: join a real Forge server, require a live Voxy renderer, shut down,
# then do it again against the same nonempty persistence state.
rm -rf run-smoke-client
mkdir -p run-smoke-client
start_server core-world
run_client core-world-first world run-smoke-client
cp run-smoke-client/voxy-smoke-world.json "$REPORT_DIR/core-world-first.json"
reject_linkage_failures "$REPORT_DIR/core-world-first-client.log"
snapshot_persistence run-smoke-client "$REPORT_DIR/persistence-first.json"

run_client core-world-second world run-smoke-client
cp run-smoke-client/voxy-smoke-world.json "$REPORT_DIR/core-world-second.json"
reject_linkage_failures "$REPORT_DIR/core-world-second-client.log"
snapshot_persistence run-smoke-client "$REPORT_DIR/persistence-second.json"
compare_persistence_snapshots \
    "$REPORT_DIR/persistence-first.json" \
    "$REPORT_DIR/persistence-second.json"
stop_server

# Phase 3: install Oculus, enable a minimal shader pack, join a Forge world,
# and require Voxy's renderer to remain live inside that pipeline.
rm -rf run-smoke-client-shaders
mkdir -p \
    run-smoke-client-shaders/config \
    run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders
cat > run-smoke-client-shaders/config/iris.properties <<'PROPERTIES'
shaderPack=VoxySmoke
enableShaders=true
PROPERTIES
cp run-smoke-client-shaders/config/iris.properties \
    run-smoke-client-shaders/config/oculus.properties
cp run-smoke-client-shaders/config/iris.properties \
    run-smoke-client-shaders/optionsshaders.txt

cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_basic.vsh <<'SHADER'
#version 120
varying vec4 color;
void main() {
    gl_Position = ftransform();
    color = gl_Color;
}
SHADER
cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_basic.fsh <<'SHADER'
#version 120
varying vec4 color;
void main() {
    gl_FragData[0] = color;
}
SHADER
cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_terrain.vsh <<'SHADER'
#version 120
varying vec2 texcoord;
varying vec4 color;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    color = gl_Color;
}
SHADER
cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_terrain.fsh <<'SHADER'
#version 120
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 color;
void main() {
    gl_FragData[0] = texture2D(texture, texcoord) * color;
}
SHADER
cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/final.vsh <<'SHADER'
#version 120
varying vec2 texcoord;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
SHADER
cat > run-smoke-client-shaders/shaderpacks/VoxySmoke/shaders/final.fsh <<'SHADER'
#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    gl_FragData[0] = texture2D(colortex0, texcoord);
}
SHADER

start_server shader-world
run_client shader-world world run-smoke-client-shaders -PvoxySmokeShaders=true
cp run-smoke-client-shaders/voxy-smoke-world.json "$REPORT_DIR/shader-world.json"
reject_linkage_failures "$REPORT_DIR/shader-world-client.log"
stop_server

printf 'all Forge runtime smoke phases passed\n' > "$REPORT_DIR/SUCCESS"
