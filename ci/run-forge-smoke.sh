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
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" >/dev/null 2>&1 || true
        wait "$server_pid" >/dev/null 2>&1 || true
        server_pid=""
    fi
}
trap stop_server EXIT

start_server() {
    local name="$1"
    write_server_config
    rm -f "$REPORT_DIR/${name}-server.log"
    (
        set -o pipefail
        ./gradlew --no-daemon \
            --project-cache-dir "build/${name}-server-project-cache" \
            runServer -PvoxySmokePhase=world \
            2>&1 | tee "$REPORT_DIR/${name}-server.log"
    ) &
    server_pid=$!

    local ready=false
    for _ in $(seq 1 300); do
        if grep -Eq 'Done \([0-9.]+s\)!|Done \(' "$REPORT_DIR/${name}-server.log" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            echo "Forge smoke server exited before readiness" >&2
            return 10
        fi
        sleep 1
    done
    [[ "$ready" == true ]]
}

run_client() {
    local name="$1"
    local phase="$2"
    local result_dir="$3"
    shift 3

    rm -f "$result_dir/voxy-smoke-${phase}.json"
    set -o pipefail
    timeout 600s xvfb-run -a -s '-screen 0 1280x720x24' \
        env LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.6 \
        ./gradlew --no-daemon \
            --project-cache-dir "build/${name}-client-project-cache" \
            runClient -PvoxySmokePhase="$phase" "$@" \
        2>&1 | tee "$REPORT_DIR/${name}-client.log"
    local status=${PIPESTATUS[0]}
    [[ "$status" -eq 0 ]]
    [[ -f "$result_dir/voxy-smoke-${phase}.json" ]]
    grep -Fq '"success": true' "$result_dir/voxy-smoke-${phase}.json"
}

reject_linkage_failures() {
    local log="$1"
    if grep -Eqi \
        'MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError' \
        "$log"; then
        echo "linkage or mixin failure detected in $log" >&2
        return 1
    fi
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

python3 - <<'PYTHON'
from pathlib import Path

root = Path('run-smoke-client')
paths = sorted(
    path for path in root.rglob('*')
    if path.is_file()
    and ('voxy' in path.name.lower() or '.voxy' in path.parts)
    and not path.name.startswith('voxy-smoke-')
    and path.as_posix() != 'run-smoke-client/config/voxy-config.json'
    and 'logs' not in path.parts
)
if not paths:
    raise SystemExit('Voxy did not create persistent world data')
with Path('ci/runtime-smoke/persistence-first.tsv').open('w') as output:
    for path in paths:
        size = path.stat().st_size
        if size <= 0:
            raise SystemExit(f'Voxy created an empty persistence file: {path}')
        output.write(f'{size}\t{path.as_posix()}\n')
PYTHON

run_client core-world-second world run-smoke-client
cp run-smoke-client/voxy-smoke-world.json "$REPORT_DIR/core-world-second.json"
reject_linkage_failures "$REPORT_DIR/core-world-second-client.log"

python3 - <<'PYTHON'
from pathlib import Path

snapshot = Path('ci/runtime-smoke/persistence-first.tsv').read_text().splitlines()
if not snapshot:
    raise SystemExit('Voxy persistence manifest was empty')
for line in snapshot:
    _, filename = line.split('\t', 1)
    path = Path(filename)
    if not path.is_file():
        raise SystemExit(f'Voxy persistence file disappeared after restart: {path}')
    if path.stat().st_size <= 0:
        raise SystemExit(f'Voxy persistence file became empty after restart: {path}')
PYTHON
stop_server

# Phase 3: install Oculus, enable a minimal shader pack, join the same kind of
# Forge world, and require Voxy's renderer to remain live inside that pipeline.
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
