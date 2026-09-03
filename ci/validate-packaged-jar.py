#!/usr/bin/env python3
"""Validate the exact runtime contents of the distributable Forge JAR."""

from __future__ import annotations

import io
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any

EXPECTED_EMBEDDIUM_VERSION = "0.3.32-beta.90+mc1.19.2"
EXPECTED_JARJAR_VERSIONS = {
    ("io.github.llamalad7", "mixinextras-forge"): "0.5.5",
    ("org.lwjgl", "lwjgl-lmdb"): "3.3.1",
    ("com.github.luben", "zstd-jni"): "1.5.7-16",
    ("org.rocksdb", "rocksdbjni"): "10.2.1",
    ("redis.clients", "jedis"): "5.1.0",
    ("org.apache.commons", "commons-pool2"): "2.12.0",
    ("at.yawk.lz4", "lz4-java"): "1.8.1",
    ("org.tukaani", "xz"): "1.10",
    ("org.xerial", "sqlite-jdbc"): "3.49.1.0",
}

REQUIRED_LMDB_NATIVES = {
    "linux/x64/org/lwjgl/lmdb/liblwjgl_lmdb.so",
    "windows/x64/org/lwjgl/lmdb/lwjgl_lmdb.dll",
}


def fail(message: str) -> None:
    raise SystemExit(message)


def load_metadata(archive: zipfile.ZipFile) -> dict[str, Any]:
    try:
        raw = archive.read("META-INF/jarjar/metadata.json")
    except KeyError:
        fail("distributable JAR is missing META-INF/jarjar/metadata.json")
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exception:
        fail(f"invalid JarJar metadata: {exception}")
    if not isinstance(metadata, dict) or not isinstance(metadata.get("jars"), list):
        fail("JarJar metadata does not contain a jars array")
    return metadata


def validate_embeddium_contract(archive: zipfile.ZipFile) -> None:
    try:
        mods_toml = archive.read("META-INF/mods.toml").decode("utf-8")
    except KeyError:
        fail("distributable JAR is missing META-INF/mods.toml")
    except UnicodeDecodeError as exception:
        fail(f"META-INF/mods.toml is not UTF-8: {exception}")

    dependency_blocks = re.findall(
        r"\[\[dependencies\.voxy\]\](.*?)(?=\[\[dependencies\.voxy\]\]|\[\[mixins\]\]|\Z)",
        mods_toml,
        flags=re.DOTALL,
    )
    embeddium_blocks = [
        block for block in dependency_blocks
        if re.search(r'^\s*modId\s*=\s*"embeddium"\s*$', block, flags=re.MULTILINE)
    ]
    if len(embeddium_blocks) != 1:
        fail(f"expected one Embeddium dependency block, found {len(embeddium_blocks)}")

    block = embeddium_blocks[0]
    expected_range = f"[{EXPECTED_EMBEDDIUM_VERSION}]"
    match = re.search(r'^\s*versionRange\s*=\s*"([^"]+)"\s*$', block, flags=re.MULTILINE)
    if match is None or match.group(1) != expected_range:
        actual = None if match is None else match.group(1)
        fail(f"Embeddium version range was {actual!r}; expected {expected_range!r}")
    if not re.search(r'^\s*mandatory\s*=\s*true\s*$', block, flags=re.MULTILINE):
        fail("Embeddium dependency is not mandatory")
    if not re.search(r'^\s*side\s*=\s*"CLIENT"\s*$', block, flags=re.MULTILINE):
        fail("Embeddium dependency is not client-side")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate-packaged-jar.py <voxy-all.jar>")

    jar_path = Path(sys.argv[1])
    if not jar_path.is_file():
        fail(f"distributable JAR does not exist: {jar_path}")

    try:
        archive = zipfile.ZipFile(jar_path)
    except (OSError, zipfile.BadZipFile) as exception:
        fail(f"cannot open distributable JAR: {exception}")

    with archive:
        names = set(archive.namelist())
        validate_embeddium_contract(archive)
        metadata = load_metadata(archive)
        actual: dict[tuple[str, str], dict[str, Any]] = {}

        for entry in metadata["jars"]:
            if not isinstance(entry, dict):
                fail(f"invalid JarJar entry: {entry!r}")
            identifier = entry.get("identifier")
            version = entry.get("version")
            if not isinstance(identifier, dict) or not isinstance(version, dict):
                fail(f"invalid JarJar entry: {entry!r}")

            key = (identifier.get("group"), identifier.get("artifact"))
            if not all(isinstance(part, str) and part for part in key):
                fail(f"invalid JarJar identifier: {identifier!r}")
            if key in actual:
                fail(f"duplicate JarJar identifier: {key[0]}:{key[1]}")
            actual[key] = entry

        missing = set(EXPECTED_JARJAR_VERSIONS) - set(actual)
        unexpected = set(actual) - set(EXPECTED_JARJAR_VERSIONS)
        if missing:
            fail("missing pinned JarJar dependencies: " + ", ".join(
                f"{group}:{artifact}" for group, artifact in sorted(missing)))
        if unexpected:
            fail("unexpected JarJar dependencies: " + ", ".join(
                f"{group}:{artifact}" for group, artifact in sorted(unexpected)))

        for key, expected_version in EXPECTED_JARJAR_VERSIONS.items():
            entry = actual[key]
            version = entry["version"]
            artifact_version = version.get("artifactVersion")
            version_range = version.get("range")
            expected_range = f"[{expected_version}]"
            if artifact_version != expected_version:
                fail(
                    f"{key[0]}:{key[1]} resolved {artifact_version!r}; "
                    f"expected {expected_version!r}"
                )
            if version_range != expected_range:
                fail(
                    f"{key[0]}:{key[1]} advertises range {version_range!r}; "
                    f"expected exact range {expected_range!r}"
                )

            nested_path = entry.get("path")
            if not isinstance(nested_path, str) or nested_path not in names:
                fail(f"JarJar metadata points at a missing nested JAR: {nested_path!r}")
            if archive.getinfo(nested_path).file_size <= 0:
                fail(f"nested JAR is empty: {nested_path}")

            try:
                with zipfile.ZipFile(io.BytesIO(archive.read(nested_path))) as nested:
                    corrupt_entry = nested.testzip()
            except zipfile.BadZipFile as exception:
                fail(f"nested JAR is corrupt: {nested_path}: {exception}")
            if corrupt_entry is not None:
                fail(f"nested JAR contains a corrupt entry: {nested_path}!/{corrupt_entry}")

        missing_natives = REQUIRED_LMDB_NATIVES - names
        if missing_natives:
            fail("distributable JAR is missing LMDB natives: " + ", ".join(sorted(missing_natives)))
        for native in REQUIRED_LMDB_NATIVES:
            if archive.getinfo(native).file_size <= 0:
                fail(f"LMDB native is empty: {native}")

        if any(name.startswith("META-INF/jarjar/lwjgl-lmdb-")
               and name != "META-INF/jarjar/lwjgl-lmdb-3.3.1.jar"
               for name in names):
            fail("distributable JAR contains a non-3.3.1 LWJGL LMDB binding")

    print("distributable JAR contains the exact validated runtime dependency set")


if __name__ == "__main__":
    main()
