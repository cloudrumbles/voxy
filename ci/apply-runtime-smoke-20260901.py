from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing {label} anchor")
    return text.replace(old, new, 1)


write(
    "src/main/java/me/cortex/voxy/client/VoxyClientSmoke.java",
    r'''package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in launch probe used by CI. Normal installations never register these
 * listeners because {@code voxy.smoke.phase} is unset.
 */
public final class VoxyClientSmoke {
    private static final String PHASE = System.getProperty("voxy.smoke.phase", "")
            .trim().toLowerCase(Locale.ROOT);
    private static final boolean REQUIRE_SHADERS = Boolean.getBoolean("voxy.smoke.requireShaders");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static boolean completed;
    private static int ticks;
    private static int worldReadyTicks;

    private VoxyClientSmoke() {
    }

    public static void register() {
        if (!PHASE.isEmpty() && REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.addListener(VoxyClientSmoke::onClientTick);
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (completed || event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ticks++;

        if ("title".equals(PHASE) && minecraft.screen instanceof TitleScreen) {
            succeed("title", "title screen ticked");
            return;
        }

        if ("world".equals(PHASE) && minecraft.level != null && minecraft.player != null) {
            worldReadyTicks++;
            IGetVoxyRenderSystem rendererAccess = (IGetVoxyRenderSystem) (Object) minecraft.levelRenderer;
            if (worldReadyTicks >= 200
                    && VoxyCommon.getInstance() != null
                    && rendererAccess.voxy$getRenderSystem() != null
                    && shadersReady()) {
                succeed("world", REQUIRE_SHADERS
                        ? "joined server with live Voxy renderer and enabled shader pack"
                        : "joined server with live Voxy renderer");
                return;
            }
        } else {
            worldReadyTicks = 0;
        }

        int timeoutTicks = "world".equals(PHASE) ? 2400 : 600;
        if (ticks >= timeoutTicks) {
            fail("timed out in phase " + PHASE
                    + "; level=" + (minecraft.level != null)
                    + "; player=" + (minecraft.player != null)
                    + "; instance=" + (VoxyCommon.getInstance() != null)
                    + "; shadersReady=" + shadersReady());
        }
    }

    private static boolean shadersReady() {
        return !REQUIRE_SHADERS || IrisUtil.irisShaderPackEnabled();
    }

    private static void succeed(String phase, String detail) {
        if (!completed) {
            completed = true;
            writeResult(phase, true, detail);
            Minecraft.getInstance().stop();
        }
    }

    private static void fail(String detail) {
        if (!completed) {
            completed = true;
            writeResult(PHASE.isEmpty() ? "unknown" : PHASE, false, detail);
            Runtime.getRuntime().halt(2);
        }
    }

    private static void writeResult(String phase, boolean success, String detail) {
        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
        Path result = gameDirectory.resolve("voxy-smoke-" + phase + ".json");
        String json = "{\n"
                + "  \"success\": " + success + ",\n"
                + "  \"phase\": \"" + escape(phase) + "\",\n"
                + "  \"detail\": \"" + escape(detail) + "\",\n"
                + "  \"timestamp\": \"" + Instant.now() + "\"\n"
                + "}\n";
        try {
            Files.createDirectories(gameDirectory);
            Files.writeString(result, json);
        } catch (IOException exception) {
            exception.printStackTrace();
            Runtime.getRuntime().halt(3);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
''',
)

# Register the opt-in smoke controller from the client-only Forge bootstrap.
mod_path = ROOT / "src/main/java/me/cortex/voxy/client/VoxyMod.java"
mod = mod_path.read_text(encoding="utf-8")
if "VoxyClientSmoke.register();" not in mod:
    mod = replace_once(
        mod,
        "        private static void initialize() {\n",
        "        private static void initialize() {\n            VoxyClientSmoke.register();\n",
        "VoxyMod client initialize",
    )
    mod_path.write_text(mod, encoding="utf-8")

# Add opt-in ForgeGradle run settings and an Oculus runtime only for the shader
# smoke configuration. Production dependency semantics remain unchanged.
build_path = ROOT / "build.gradle"
build = build_path.read_text(encoding="utf-8")
all_definitions = """def voxySmokePhase = providers.gradleProperty('voxySmokePhase').orNull
def voxySmokeShaders = project.hasProperty('voxySmokeShaders')
def voxySmokeOculus = project.hasProperty('voxySmokeOculus') || voxySmokeShaders
def voxyClientRunDirectory = voxySmokePhase == null ? 'run' : (voxySmokeShaders ? 'run-smoke-client-shaders' : (voxySmokeOculus ? 'run-smoke-client-oculus' : 'run-smoke-client'))
def voxyServerRunDirectory = voxySmokePhase == 'world' ? 'run-smoke-server' : 'run'
"""
if "def voxySmokePhase" not in build:
    build = replace_once(
        build,
        "base {\n    archivesName = project.archives_base_name\n}\n",
        "base {\n    archivesName = project.archives_base_name\n}\n\n" + all_definitions,
        "Gradle base",
    )
elif "def voxySmokeShaders" not in build:
    start = build.index("def voxySmokePhase")
    end_marker = "def voxyServerRunDirectory"
    end = build.index("\n", build.index(end_marker, start)) + 1
    build = build[:start] + all_definitions + build[end:]

# Change only the first client/server run-directory occurrence.
client_start = build.index("client {")
server_start = build.index("server {", client_start)
client_region = build[client_start:server_start]
if "project.file(voxyClientRunDirectory)" not in client_region:
    client_region = client_region.replace(
        "workingDirectory project.file('run')",
        "workingDirectory project.file(voxyClientRunDirectory)",
        1,
    )
    build = build[:client_start] + client_region + build[server_start:]
    server_start = build.index("server {", client_start)
server_end = build.find("\n        }", server_start)
server_region = build[server_start:server_end if server_end >= 0 else len(build)]
if "project.file(voxyServerRunDirectory)" not in server_region:
    new_region = server_region.replace(
        "workingDirectory project.file('run')",
        "workingDirectory project.file(voxyServerRunDirectory)",
        1,
    )
    build = build[:server_start] + new_region + build[server_start + len(server_region):]

if "property 'voxy.smoke.phase'" not in build:
    client_start = build.index("client {")
    server_start = build.index("server {", client_start)
    console = "            property 'forge.logging.console.level', 'debug'\n"
    position = build.index(console, client_start, server_start) + len(console)
    smoke_config = """            if (voxySmokePhase != null) {
                property 'voxy.smoke.phase', voxySmokePhase
                if (voxySmokeShaders) {
                    property 'voxy.smoke.requireShaders', 'true'
                }
                args '--width', '854', '--height', '480'
                if (voxySmokePhase == 'world') {
                    args '--server', '127.0.0.1', '--port', '25565'
                }
            }
"""
    build = build[:position] + smoke_config + build[position:]

oculus_compile = '    compileOnly fg.deobf("maven.modrinth:GchcoXML:${oculus_compile_version}")\n'
if oculus_compile not in build:
    embeddium_candidates = [
        '    implementation fg.deobf("org.embeddedt:embeddium-1.19.2:${embeddium_version}")\n',
        '    implementation fg.deobf("org.embeddedt:embeddium-${minecraft_version}:${embeddium_version}")\n',
    ]
    for candidate in embeddium_candidates:
        if candidate in build:
            build = build.replace(candidate, candidate + "\n" + oculus_compile, 1)
            break
    else:
        raise RuntimeError("missing Embeddium dependency anchor")

oculus_runtime = '        runtimeOnly fg.deobf("maven.modrinth:GchcoXML:${oculus_compile_version}")\n'
if oculus_runtime not in build:
    build = build.replace(
        oculus_compile,
        oculus_compile
        + "    if (voxySmokeOculus) {\n"
        + oculus_runtime
        + "    }\n",
        1,
    )
build_path.write_text(build, encoding="utf-8")

# Make the ordinary branch workflow authoritative for runtime validation.
workflow_path = ROOT / ".github/workflows/forge-1.19.2-ci.yml"
workflow = workflow_path.read_text(encoding="utf-8")
workflow = workflow.replace("timeout-minutes: 45", "timeout-minutes: 90", 1)
if "Forge runtime smoke tests" not in workflow:
    runtime_steps = r'''      - name: Install software OpenGL smoke-test dependencies
        shell: bash
        run: |
          sudo apt-get update -qq
          sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb mesa-utils

      - name: Forge runtime smoke tests
        shell: bash
        run: bash ci/run-forge-smoke.sh

      - name: Upload runtime smoke reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: forge-1.19.2-runtime-smoke
          if-no-files-found: warn
          path: |
            ci/runtime-smoke/
            run-smoke-client/voxy-smoke-*.json
            run-smoke-client-shaders/voxy-smoke-*.json

'''
    anchors = [
        "      - name: Capture concise diagnostics\n",
        "      - name: Publish concise failure diagnostics\n",
        "      - name: Upload diagnostic reports on failure\n",
        "      - name: Upload distributable JAR\n",
    ]
    for anchor in anchors:
        if anchor in workflow:
            workflow = workflow.replace(anchor, runtime_steps + anchor, 1)
            break
    else:
        raise RuntimeError("missing CI insertion anchor")
workflow_path.write_text(workflow, encoding="utf-8")

porting_path = ROOT / "PORTING.md"
porting = porting_path.read_text(encoding="utf-8")
validation_note = """

## Runtime validation

The branch CI launches the actual Forge client under Mesa software OpenGL. It
must reach the title screen, join a local Forge server with a live Voxy
renderer, reopen nonempty Voxy persistence on a second launch, and repeat the
world test with Oculus and an enabled minimal shader pack. The distributable is
not published unless every phase passes.
"""
if "## Runtime validation" not in porting:
    porting_path.write_text(porting.rstrip() + validation_note, encoding="utf-8")
