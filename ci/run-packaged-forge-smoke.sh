#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="$ROOT_DIR/ci/packaged-runtime-smoke"
PROJECT_DIR="$ROOT_DIR/build/packaged-smoke-project"
VOXY_JAR="$(find "$ROOT_DIR/build/libs" -maxdepth 1 -type f -name '*-all.jar' -print -quit)"

mkdir -p "$REPORT_DIR"
test -n "$VOXY_JAR"
test -f "$VOXY_JAR"

if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "xvfb-run is required for packaged Forge client smoke tests" >&2
    exit 2
fi
if ! command -v setsid >/dev/null 2>&1; then
    echo "setsid is required for isolated packaged Forge server process groups" >&2
    exit 2
fi

rm -rf "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR"
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

def smokePhase = providers.gradleProperty('smokePhase').orNull
def smokeRunDir = file(providers.gradleProperty('smokeRunDir').orElse('run-client').get())
def serverRunDir = file(providers.gradleProperty('serverRunDir').orElse('run-server').get())
def voxyJar = file(providers.gradleProperty('voxyJar').orElse('missing-voxy.jar').get())
def includeOculus = providers.gradleProperty('includeOculus').isPresent()

minecraft {
    mappings channel: 'official', version: '1.19.2'
    runs {
        client {
            workingDirectory smokeRunDir
            property 'forge.logging.console.level', 'debug'
            property 'voxy.smoke.phase', smokePhase ?: 'title'
            if (includeOculus) {
                property 'voxy.smoke.requireShaders', 'true'
            }
            args '--width', '854', '--height', '480'
            if (smokePhase == 'world') {
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
    maven {
        name = 'Modrinth'
        url = 'https://api.modrinth.com/maven'
        content { includeGroup 'maven.modrinth' }
    }
}

configurations {
    smokeMods
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.19.2-43.5.2'
    smokeMods('org.embeddedt:embeddium-1.19.2:0.3.32-beta.90+mc1.19.2') {
        transitive = false
    }
    if (includeOculus) {
        smokeMods('maven.modrinth:GchcoXML:Mw8aFpWF') {
            transitive = false
        }
    }
}

tasks.register('prepareSmokeMods', Sync) {
    into new File(smokeRunDir, 'mods')
    from voxyJar
    from configurations.smokeMods
}

tasks.named('runClient').configure {
    dependsOn tasks.named('prepareSmokeMods')
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}
GRADLE

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

write_server_config() {
    local run_dir="$ROOT_DIR/run-packaged-server"
    rm -rf "$run_dir"
    mkdir -p "$run_dir"
    printf 'eula=true\n' > "$run_dir/eula.txt"
    cat > "$run_dir/server.properties" <<'PROPERTIES'
online-mode=false
server-port=25565
server-ip=127.0.0.1
level-name=voxy-packaged-world
level-seed=424242
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=2
motd=Packaged Voxy smoke server
PROPERTIES
}

start_clean_server() {
    write_server_config
    rm -f "$REPORT_DIR/server.log"
    setsid --wait "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" --no-daemon \
        --project-cache-dir "$ROOT_DIR/build/packaged-server-project-cache" \
        runServer -PserverRunDir="$ROOT_DIR/run-packaged-server" \
        > "$REPORT_DIR/server.log" 2>&1 &
    server_pid=$!

    local ready=false
    for _ in $(seq 1 300); do
        if grep -Eq 'Done \([0-9.]+s\)!|Done \(' "$REPORT_DIR/server.log" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$server_pid" >/dev/null 2>&1; then
            echo "clean Forge server exited before readiness" >&2
            tail -n 250 "$REPORT_DIR/server.log" >&2 || true
            return 10
        fi
        sleep 1
    done
    if [[ "$ready" != true ]]; then
        echo "clean Forge server did not become ready" >&2
        tail -n 250 "$REPORT_DIR/server.log" >&2 || true
        return 11
    fi
}

run_packaged_client() {
    local name="$1"
    local phase="$2"
    local run_dir="$3"
    shift 3

    rm -f "$run_dir/voxy-smoke-${phase}.json"
    set +e
    timeout 720s xvfb-run -a -s '-screen 0 1280x720x24' \
        env LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.6 \
        "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" --no-daemon \
            --project-cache-dir "$ROOT_DIR/build/${name}-project-cache" \
            runClient \
            -PvoxyJar="$VOXY_JAR" \
            -PsmokeRunDir="$run_dir" \
            -PsmokePhase="$phase" \
            "$@" \
            > "$REPORT_DIR/${name}.log" 2>&1
    local status=$?
    set -e

    if [[ "$status" -ne 0 ]]; then
        echo "packaged client '$name' exited with status $status" >&2
        tail -n 300 "$REPORT_DIR/${name}.log" >&2 || true
        return "$status"
    fi
    test -f "$run_dir/voxy-smoke-${phase}.json"
    grep -Fq '"success": true' "$run_dir/voxy-smoke-${phase}.json"
    if grep -Eqi \
        'MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError|DuplicateModsFoundException' \
        "$REPORT_DIR/${name}.log"; then
        grep -Ein \
            'MixinApplyError|InvalidMixinException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError|DuplicateModsFoundException' \
            "$REPORT_DIR/${name}.log" | tail -n 120 >&2 || true
        return 14
    fi
}

# Installed-jar title launch with no source-set Voxy mod.
rm -rf "$ROOT_DIR/run-packaged-client"
mkdir -p "$ROOT_DIR/run-packaged-client"
run_packaged_client packaged-title title "$ROOT_DIR/run-packaged-client"
cp "$ROOT_DIR/run-packaged-client/voxy-smoke-title.json" "$REPORT_DIR/title.json"

# The server intentionally has no Voxy, Embeddium, or Oculus installed. This
# verifies the packaged client is correctly declared as client-compatible.
start_clean_server
run_packaged_client packaged-world world "$ROOT_DIR/run-packaged-client"
cp "$ROOT_DIR/run-packaged-client/voxy-smoke-world.json" "$REPORT_DIR/world.json"
stop_server

# Installed-jar Oculus path with a real enabled shader pack.
rm -rf "$ROOT_DIR/run-packaged-client-shaders"
mkdir -p \
    "$ROOT_DIR/run-packaged-client-shaders/config" \
    "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders"
cat > "$ROOT_DIR/run-packaged-client-shaders/config/iris.properties" <<'PROPERTIES'
shaderPack=VoxySmoke
enableShaders=true
PROPERTIES
cp "$ROOT_DIR/run-packaged-client-shaders/config/iris.properties" \
    "$ROOT_DIR/run-packaged-client-shaders/config/oculus.properties"
cp "$ROOT_DIR/run-packaged-client-shaders/config/iris.properties" \
    "$ROOT_DIR/run-packaged-client-shaders/optionsshaders.txt"

cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_basic.vsh" <<'SHADER'
#version 120
varying vec4 color;
void main() { gl_Position = ftransform(); color = gl_Color; }
SHADER
cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_basic.fsh" <<'SHADER'
#version 120
varying vec4 color;
void main() { gl_FragData[0] = color; }
SHADER
cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_terrain.vsh" <<'SHADER'
#version 120
varying vec2 texcoord;
varying vec4 color;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    color = gl_Color;
}
SHADER
cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/gbuffers_terrain.fsh" <<'SHADER'
#version 120
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 color;
void main() { gl_FragData[0] = texture2D(texture, texcoord) * color; }
SHADER
cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/final.vsh" <<'SHADER'
#version 120
varying vec2 texcoord;
void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }
SHADER
cat > "$ROOT_DIR/run-packaged-client-shaders/shaderpacks/VoxySmoke/shaders/final.fsh" <<'SHADER'
#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() { gl_FragData[0] = texture2D(colortex0, texcoord); }
SHADER

start_clean_server
run_packaged_client packaged-shader-world world \
    "$ROOT_DIR/run-packaged-client-shaders" -PincludeOculus=true
cp "$ROOT_DIR/run-packaged-client-shaders/voxy-smoke-world.json" \
    "$REPORT_DIR/shader-world.json"
stop_server

printf 'packaged Forge artifact smoke tests passed\n' > "$REPORT_DIR/SUCCESS"
