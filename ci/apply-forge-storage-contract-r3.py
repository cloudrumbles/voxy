from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing {label} anchor")
    return text.replace(old, new, 1)


build_path = ROOT / "build.gradle"
build = build_path.read_text(encoding="utf-8")

if "lwjglVoxyNatives" not in build:
    build = replace_once(
        build,
        "dependencies {\n",
        "configurations {\n    lwjglVoxyNatives\n}\n\ndependencies {\n",
        "dependencies block",
    )

native_dependencies = '''
    // Voxy uses LWJGL modules that vanilla Minecraft does not ship. The Java
    // bindings are compile-only because their classes are packaged separately;
    // these classifier jars contribute the actual native libraries to the
    // distributable and to native-loading tests.
    lwjglVoxyNatives 'org.lwjgl:lwjgl-lmdb:3.3.1:natives-windows'
    lwjglVoxyNatives 'org.lwjgl:lwjgl-lmdb:3.3.1:natives-linux'
    lwjglVoxyNatives 'org.lwjgl:lwjgl-zstd:3.3.1:natives-windows'
    lwjglVoxyNatives 'org.lwjgl:lwjgl-zstd:3.3.1:natives-linux'
    testRuntimeOnly 'org.lwjgl:lwjgl-lmdb:3.3.1:natives-linux'
    testRuntimeOnly 'org.lwjgl:lwjgl-zstd:3.3.1:natives-linux'
'''
if "lwjglVoxyNatives 'org.lwjgl:lwjgl-lmdb:3.3.1:natives-windows'" not in build:
    anchor = "    compileOnly 'org.lwjgl:lwjgl-zstd:3.3.1'\n"
    build = replace_once(build, anchor, anchor + native_dependencies, "LWJGL compile dependencies")

build = build.replace(
    "org.rocksdb:rocksdbjni:[7.10.2,8.0)",
    "org.rocksdb:rocksdbjni:[10.2.1,11.0)",
)
build = build.replace(
    "version { strictly '[7.10.2,8.0)'; prefer '7.10.2' }",
    "version { strictly '[10.2.1,11.0)'; prefer '10.2.1' }",
)
build = build.replace(
    "org.xerial:sqlite-jdbc:[3.42.0.0,4.0)",
    "org.xerial:sqlite-jdbc:[3.49.1.0,4.0)",
)
build = build.replace(
    "version { strictly '[3.42.0.0,4.0)'; prefer '3.42.0.0' }",
    "version { strictly '[3.49.1.0,4.0)'; prefer '3.49.1.0' }",
)

native_from = '''
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.lwjglVoxyNatives.collect { zipTree(it) } }) {
        exclude 'META-INF/MANIFEST.MF'
        exclude 'META-INF/*.SF', 'META-INF/*.RSA', 'META-INF/*.DSA'
        exclude 'module-info.class', 'META-INF/versions/**/module-info.class'
        exclude 'META-INF/LICENSE*', 'META-INF/NOTICE*'
    }
'''

jarjar_anchor = "tasks.named('jarJar').configure {\n    enabled = true\n"
if "configurations.lwjglVoxyNatives.collect" not in build.split("tasks.named('jarJar').configure", 1)[-1].split("}\n\n", 1)[0]:
    build = replace_once(
        build,
        jarjar_anchor,
        jarjar_anchor + native_from,
        "jarJar task",
    )

jar_anchor = "jar {\n"
jar_region = build.split(jar_anchor, 1)[-1]
if "configurations.lwjglVoxyNatives.collect" not in jar_region:
    build = replace_once(build, jar_anchor, jar_anchor + native_from, "jar task")

build_path.write_text(build, encoding="utf-8")

# A real durable round trip catches JNI/linkage drift and tests reopening, not
# merely classpath presence.
test_path = ROOT / "src/test/java/me/cortex/voxy/common/storage/NativeStorageDependencyTest.java"
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package me.cortex.voxy.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class NativeStorageDependencyTest {
    @Test
    void rocksDbRoundTripSurvivesReopen(@TempDir Path temporaryDirectory) throws Exception {
        RocksDB.loadLibrary();
        byte[] key = "section-key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "voxy-section-payload".getBytes(StandardCharsets.UTF_8);
        String databasePath = temporaryDirectory.resolve("rocks").toString();

        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB database = RocksDB.open(options, databasePath)) {
            database.put(key, value);
            assertArrayEquals(value, database.get(key));
        }

        try (Options options = new Options().setCreateIfMissing(false);
             RocksDB database = RocksDB.open(options, databasePath)) {
            assertArrayEquals(value, database.get(key));
        }
    }

    @Test
    void sqliteRoundTripUsesBundledDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.executeUpdate("create table sections (id integer primary key, payload text not null)");
            statement.executeUpdate("insert into sections(payload) values ('voxy')");
            try (var result = statement.executeQuery("select payload from sections")) {
                assertEquals(true, result.next());
                assertEquals("voxy", result.getString(1));
            }
        }
    }
}
''', encoding="utf-8")

workflow_path = ROOT / ".github/workflows/forge-1.19.2-ci.yml"
workflow = workflow_path.read_text(encoding="utf-8")
if "liblwjgl_lmdb" not in workflow:
    renderer_check = "          jar tf \"$jar_file\" | grep -q '^me/cortex/voxy/client/core/VoxyRenderSystem.class$'\n"
    native_checks = renderer_check + '''          jar tf "$jar_file" | grep -Eq '(^|/)liblwjgl_lmdb\\.so$|(^|/)lwjgl_lmdb\\.dll$'
          jar tf "$jar_file" | grep -Eq '(^|/)liblwjgl_zstd\\.so$|(^|/)lwjgl_zstd\\.dll$'
          jar tf "$jar_file" | grep -qx 'META-INF/jarjar/metadata.json'
          unzip -p "$jar_file" META-INF/jarjar/metadata.json | grep -F 'rocksdbjni'
          unzip -p "$jar_file" META-INF/jarjar/metadata.json | grep -F '10.2.1'
          unzip -p "$jar_file" META-INF/jarjar/metadata.json | grep -F 'sqlite-jdbc'
          unzip -p "$jar_file" META-INF/jarjar/metadata.json | grep -F '3.49.1.0'
'''
    workflow = replace_once(workflow, renderer_check, native_checks, "renderer class package check")
workflow_path.write_text(workflow, encoding="utf-8")

porting_path = ROOT / "PORTING.md"
porting = porting_path.read_text(encoding="utf-8")
marker = '''

## Storage and native parity

The Forge artifact uses the Voxy 0.2.14 RocksDB 10.2.1 and SQLite 3.49.1.0
lines. Windows and Linux x86_64 native binaries for LWJGL LMDB and Zstd are
embedded into the distributable, and CI performs RocksDB reopen and SQLite
round-trip tests before launch validation. Runtime contract revision:
forge-1.19.2-r3.
'''
if "forge-1.19.2-r3." not in porting:
    porting_path.write_text(porting.rstrip() + marker, encoding="utf-8")
