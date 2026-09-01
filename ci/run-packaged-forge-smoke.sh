#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="$ROOT_DIR/ci/packaged-runtime-smoke"
PROJECT_DIR="$ROOT_DIR/build/packaged-smoke-project"
CLIENT_RUN_DIR="$ROOT_DIR/run-packaged-client"
SERVER_RUN_DIR="$ROOT_DIR/run-packaged-server"

rm -rf "$REPORT_DIR" "$PROJECT_DIR" "$CLIENT_RUN_DIR" "$SERVER_RUN_DIR"
mkdir -p "$REPORT_DIR" "$PROJECT_DIR" "$CLIENT_RUN_DIR"

mapfile -t voxy_jars < <(find "$ROOT_DIR/build/libs" -maxdepth 1 -type f -name '*-all.jar' -print | sort)
if [[ "${#voxy_jars[@]}" -ne 1 ]]; then
    printf 'expected exactly one packaged Voxy jar, found %s\n' "${#voxy_jars[@]}" >&2
    printf '%s\n' "${voxy_jars[@]:-}" >&2
    exit 2
fi
VOXY_JAR="${voxy_jars[0]}"

for command in xvfb-run setsid jar unzip python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "$command is required for packaged Forge client validation" >&2
        exit 2
    fi
done

jar tf "$VOXY_JAR" > "$REPORT_DIR/jar-entries.txt"
required_entries=(
    'META-INF/mods.toml'
    'META-INF/jarjar/metadata.json'
    'client.voxy.mixins.json'
    'client.voxy.refmap.json'
    'META-INF/accesstransformer.cfg'
    'me/cortex/voxy/client/VoxyMod.class'
    'me/cortex/voxy/client/VoxyClientSmoke.class'
    'me/cortex/voxy/client/core/VoxyRenderSystem.class'
)
for entry in "${required_entries[@]}"; do
    if ! grep -Fxq "$entry" "$REPORT_DIR/jar-entries.txt"; then
        echo "packaged jar is missing $entry" >&2
        exit 3
    fi
done

unzip -p "$VOXY_JAR" META-INF/mods.toml > "$REPORT_DIR/packaged-mods.toml"
grep -Eq 'modId[[:space:]]*=[[:space:]]*"voxy"' "$REPORT_DIR/packaged-mods.toml"
grep -Eq 'displayTest[[:space:]]*=[[:space:]]*"IGNORE_SERVER_VERSION"' "$REPORT_DIR/packaged-mods.toml"
sha256sum "$VOXY_JAR" > "$REPORT_DIR/voxy-jar.sha256"

cat > "$PROJECT_DIR/settings.gradle" <<'GRADLE'
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
        mavenCentral()
    }
}
rootProject.name = 'voxy-packaged-smoke'
GRADLE

cat > "$PROJECT_DIR/build.gradle" <<'GRADLE'
buildscript {
    repositories {
        mavenLocal()
        maven { url = 'https://mirrors.huaweicloud.com/repository/maven/' }
        mavenCentral()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
    dependencies {
        classpath 'net.minecraftforge.gradle:ForgeGradle:6.0.54'
    }
}

apply plugin: 'net.minecraftforge.gradle'

version = '1.0.0'
group = 'me.cortex.voxy.smoke'

def smokePhase = providers.gradleProperty('smokePhase').orElse('title').get()
def smokeRunDir = file(providers.gradleProperty('smokeRunDir').orElse('run-client').get())
def serverRunDir = file(providers.gradleProperty('serverRunDir').orElse('run-server').get())
def voxyJar = file(providers.gradleProperty('voxyJar').orElse('missing-voxy.jar').get())
def isWorldPhase = smokePhase.startsWith('world-')

minecraft {
    mappings channel: 'official', version: '1.19.2'
    runs {
        client {
            workingDirectory smokeRunDir
            property 'forge.logging.console.level', 'debug'
            property 'voxy.smoke.phase', smokePhase
            args '--width', '854', '--height', '480'
            if (isWorldPhase) {
                args '--server', '127.0.0.1', '--port', '25565'
            }
        }
        server {
            workingDirectory serverRunDir
            property 'forge.logging.console.level', 'debug'
        }
    }
}

repositories {
    mavenLocal()
    maven { url = 'https://mirrors.huaweicloud.com/repository/maven/' }
    mavenCentral()
    maven { url = 'https://maven.minecraftforge.net/' }
    maven { url = 'https://maven.blamejared.com/' }
}

configurations {
    smokeMods
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.19.2-43.5.2'
    smokeMods('org.embeddedt:embeddium-1.19.2:0.3.32-beta.90+mc1.19.2') {
        transitive = false
    }
}

tasks.register('prepareSmokeMods', Sync) {
    into new File(smokeRunDir, 'mods')
    from voxyJar
    from configurations.smokeMods
}

// ForgeGradle registers run tasks after project evaluation. Configure them
// lazily, and materialise its source-less default-mod coordinates immediately
// before the child JVM is spawned; prepareRuns can otherwise remove empty
// build directories created by the outer shell.
tasks.matching { it.name == 'runClient' }.configureEach {
    dependsOn tasks.named('prepareSmokeMods')
}
tasks.matching { it.name == 'runClient' || it.name == 'runServer' }.configureEach {
    doFirst {
        new File(project.buildDir, 'resources/main').mkdirs()
        new File(project.buildDir, 'classes/java/main').mkdirs()
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}
GRADLE

server_pid=""
server_input_fd_open=false
SERVER_FIFO="$REPORT_DIR/server.stdin"

stop_server() {
    if [[ -z "$server_pid" ]]; then
        return
    fi

    if [[ "$server_input_fd_open" == true ]]; then
        printf 'stop\n' >&9 2>/dev/null || true
    fi

    for _ in $(seq 1 90); do
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            wait "$server_pid" >/dev/null 2>&1 || true
            server_pid=""
            if [[ "$server_input_fd_open" == true ]]; then
                exec 9>&-
                server_input_fd_open=false
            fi
            return
        fi
        sleep 1
    done

    kill -TERM -- "-$server_pid" >/dev/null 2>&1 || true
    sleep 3
    kill -KILL -- "-$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
    server_pid=""
    if [[ "$server_input_fd_open" == true ]]; then
        exec 9>&-
        server_input_fd_open=false
    fi
}
trap stop_server EXIT

write_server_config() {
    rm -rf "$SERVER_RUN_DIR"
    mkdir -p "$SERVER_RUN_DIR"
    printf 'eula=true\n' > "$SERVER_RUN_DIR/eula.txt"
    cat > "$SERVER_RUN_DIR/server.properties" <<'PROPERTIES'
online-mode=false
server-port=25565
server-ip=127.0.0.1
level-name=voxy-packaged-world
level-seed=424242
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=5
simulation-distance=5
max-players=2
motd=Packaged Voxy Forge 1.19.2 validation server
PROPERTIES
}

start_clean_server() {
    write_server_config
    rm -f "$SERVER_FIFO" "$REPORT_DIR/server.log"
    mkfifo "$SERVER_FIFO"
    exec 9<> "$SERVER_FIFO"
    server_input_fd_open=true

    setsid --wait "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" --no-daemon \
        --project-cache-dir "$ROOT_DIR/build/packaged-server-project-cache" \
        runServer -PserverRunDir="$SERVER_RUN_DIR" \
        < "$SERVER_FIFO" > "$REPORT_DIR/server.log" 2>&1 &
    server_pid=$!

    local ready=false
    for _ in $(seq 1 360); do
        if grep -Eq 'Done \([0-9.]+s\)!|Done \(' "$REPORT_DIR/server.log" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            echo "clean Forge server exited before readiness" >&2
            tail -n 300 "$REPORT_DIR/server.log" >&2 || true
            return 10
        fi
        sleep 1
    done

    if [[ "$ready" != true ]]; then
        echo "clean Forge server did not become ready" >&2
        tail -n 300 "$REPORT_DIR/server.log" >&2 || true
        return 11
    fi

    if find "$SERVER_RUN_DIR/mods" -maxdepth 1 -type f -iname '*voxy*' -print -quit 2>/dev/null | grep -q .; then
        echo "the validation server unexpectedly contains Voxy" >&2
        return 12
    fi
}

reject_runtime_failures() {
    local log="$1"
    local pattern='MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|AbstractMethodError|UnsatisfiedLinkError|LinkageError|DuplicateModsFoundException|The game crashed|Minecraft Crash Report|Exception caught during firing event|Caught exception from Voxy'
    if grep -Eqi "$pattern" "$log"; then
        echo "runtime linkage, mixin, or crash failure detected in $log" >&2
        grep -Ein "$pattern" "$log" | tail -n 160 >&2 || true
        return 1
    fi
}

validate_result() {
    local result="$1"
    local expected_phase="$2"
    python3 - "$result" "$expected_phase" <<'PYTHON'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
phase = sys.argv[2]
if not path.is_file():
    raise SystemExit(f"missing smoke result: {path}")

data = json.loads(path.read_text())
if data.get("phase") != phase:
    raise SystemExit(f"wrong smoke phase: {data.get('phase')!r}, expected {phase!r}")
if data.get("success") is not True:
    raise SystemExit(f"smoke phase failed: {data}")
for key in ("voxyLoaded", "embeddiumLoaded", "backendInitialized", "backendReady"):
    if data.get(key) is not True:
        raise SystemExit(f"{key} was not true: {data}")

if phase.startswith("world-"):
    for key in ("worldJoined", "instanceCreated", "renderSystemCreated"):
        if data.get(key) is not True:
            raise SystemExit(f"{key} was not true: {data}")
    if not data.get("storageBasePath"):
        raise SystemExit(f"storageBasePath was empty: {data}")

if phase == "world-write":
    if data.get("successfulSectionSaves", 0) <= 0:
        raise SystemExit(f"no section save completed: {data}")
    if data.get("currentPersistenceBytes", 0) <= 0:
        raise SystemExit(f"persistence remained empty: {data}")

if phase == "world-reopen":
    if data.get("initialPersistenceBytes", 0) <= 0:
        raise SystemExit(f"no persistence existed before reopen: {data}")
    if data.get("successfulSectionLoads", 0) <= 0:
        raise SystemExit(f"no persisted section was loaded: {data}")
PYTHON
}

run_packaged_client() {
    local name="$1"
    local phase="$2"
    local result="$CLIENT_RUN_DIR/voxy-smoke-${phase}.json"

    rm -f "$result"
    set +e
    timeout --signal=TERM --kill-after=30s 660s \
        xvfb-run -a -s '-screen 0 1280x720x24' \
        env \
            LIBGL_ALWAYS_SOFTWARE=1 \
            GALLIUM_DRIVER=llvmpipe \
            MESA_GL_VERSION_OVERRIDE=4.6 \
            "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" --no-daemon \
                --project-cache-dir "$ROOT_DIR/build/packaged-client-project-cache" \
                runClient \
                -PvoxyJar="$VOXY_JAR" \
                -PsmokeRunDir="$CLIENT_RUN_DIR" \
                -PsmokePhase="$phase" \
                > "$REPORT_DIR/${name}.log" 2>&1
    local status=$?
    set -e

    if [[ "$status" -ne 0 ]]; then
        echo "packaged client '$name' exited with status $status" >&2
        tail -n 350 "$REPORT_DIR/${name}.log" >&2 || true
        return "$status"
    fi

    reject_runtime_failures "$REPORT_DIR/${name}.log"
    validate_result "$result" "$phase"
    cp "$result" "$REPORT_DIR/${name}.json"
}

snapshot_persistence() {
    local output="$1"
    python3 - "$CLIENT_RUN_DIR/.voxy/saves" "$output" <<'PYTHON'
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1])
output = Path(sys.argv[2])
files = sorted(path for path in root.rglob("*") if path.is_file()) if root.is_dir() else []
entries = []
for path in files:
    size = path.stat().st_size
    if size <= 0:
        continue
    digest = hashlib.sha256(path.read_bytes()).hexdigest() if size <= 8 * 1024 * 1024 else None
    entries.append({
        "path": path.relative_to(root).as_posix(),
        "bytes": size,
        "sha256": digest,
    })
record = {
    "root": root.as_posix(),
    "nonemptyFileCount": len(entries),
    "totalBytes": sum(entry["bytes"] for entry in entries),
    "files": entries,
}
output.write_text(json.dumps(record, indent=2) + "\n")
if record["nonemptyFileCount"] <= 0 or record["totalBytes"] <= 0:
    raise SystemExit("Voxy did not create a nonempty persistence store")
PYTHON
}

compare_reopen_results() {
    python3 - \
        "$REPORT_DIR/packaged-world-write.json" \
        "$REPORT_DIR/packaged-world-reopen.json" \
        "$REPORT_DIR/persistence-before-reopen.json" \
        "$REPORT_DIR/persistence-after-reopen.json" <<'PYTHON'
from pathlib import Path
import json
import sys

write = json.loads(Path(sys.argv[1]).read_text())
reopen = json.loads(Path(sys.argv[2]).read_text())
before = json.loads(Path(sys.argv[3]).read_text())
after = json.loads(Path(sys.argv[4]).read_text())

if write["storageBasePath"] != reopen["storageBasePath"]:
    raise SystemExit("storage base path changed across client restart")
if before["nonemptyFileCount"] <= 0 or before["totalBytes"] <= 0:
    raise SystemExit("persistence was empty before client restart")
if after["nonemptyFileCount"] <= 0 or after["totalBytes"] <= 0:
    raise SystemExit("persistence was empty after client restart")
if reopen["successfulSectionLoads"] <= 0:
    raise SystemExit("the second client did not deserialize a persisted section")
PYTHON
}

# Phase 1: prove Forge discovers and initializes the packaged jar itself.
run_packaged_client packaged-title title

# Phases 2 and 3: connect to a clean Forge server, write real Voxy section
# persistence, fully exit, then launch the packaged client again and require a
# successful persisted-section read from the same storage path.
start_clean_server
run_packaged_client packaged-world-write world-write
snapshot_persistence "$REPORT_DIR/persistence-before-reopen.json"
run_packaged_client packaged-world-reopen world-reopen
snapshot_persistence "$REPORT_DIR/persistence-after-reopen.json"
compare_reopen_results
stop_server

printf 'packaged Forge 1.19.2 client validation passed\n' > "$REPORT_DIR/SUCCESS"
