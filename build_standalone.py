#!/usr/bin/env python3
"""Builds the standalone jar (no Little LUMI required) — `java -jar dist\\niah-game-world.jar`.

Compiles every src\\djmax\\*.java EXCEPT the handful that only exist to talk to the Little LUMI
SDK (DjmaxPlugin, LittleLumiGameHost, LittleLumiMascotIntegration, LumiPrefsAdapter) — see
GameHost's own class doc for why the split is drawn exactly there. Needs a JDK 25 on PATH (or
--javac/$JAVAC/$JAVA_HOME), same as tools\\build_plugin.py in the Little LUMI Mod SDK.

    python build_standalone.py                 -> dist\\niah-game-world.jar
    python build_standalone.py --out some\\dir  -> some\\dir\\niah-game-world.jar
"""
import argparse
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src" / "djmax"
MAIN_CLASS = "djmax.StandaloneMain"
JAR_NAME = "niah-game-world.jar"

# Only these touch the Little LUMI SDK (com.group_finity.mascot.lumi.plugin) — everything else in
# src\djmax compiles and runs with no SDK jar on the classpath at all.
LUMI_ONLY_FILES = {
    "DjmaxPlugin.java",
    "LittleLumiGameHost.java",
    "LittleLumiMascotIntegration.java",
    "LumiPrefsAdapter.java",
}


def find_javac(explicit: str | None) -> str:
    if explicit:
        return explicit
    import os
    env_javac = os.environ.get("JAVAC")
    if env_javac:
        return env_javac
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("javac.exe" if sys.platform.startswith("win") else "javac")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("javac")
    if found:
        return found
    raise SystemExit("javac not found — pass --javac, or set $JAVAC / $JAVA_HOME")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(ROOT / "dist"), help="folder for the jar (default: dist\\)")
    ap.add_argument("--javac", default=None)
    args = ap.parse_args()

    javac = find_javac(args.javac)
    sources = sorted(p for p in SRC.glob("*.java") if p.name not in LUMI_ONLY_FILES)
    if not sources:
        raise SystemExit(f"no sources found under {SRC}")

    build_dir = ROOT / "build_standalone"
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True)

    cmd = [javac, "-d", str(build_dir), "-encoding", "UTF-8"] + [str(p) for p in sources]
    result = subprocess.run(cmd)
    if result.returncode != 0:
        raise SystemExit(f"javac failed (exit {result.returncode})")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    jar_path = out_dir / JAR_NAME

    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as zf:
        manifest = f"Manifest-Version: 1.0\nMain-Class: {MAIN_CLASS}\n"
        zf.writestr("META-INF/MANIFEST.MF", manifest)
        for path in sorted(build_dir.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(build_dir).as_posix())

    shutil.rmtree(build_dir)
    print(f"built: {jar_path}  ({len(sources)} source(s))")
    print(f"run:   java -jar \"{jar_path}\"")


if __name__ == "__main__":
    main()
