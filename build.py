#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


def archive(destination, entries):
    with ZipFile(destination, "w", ZIP_DEFLATED) as output:
        for path, name in entries:
            output.write(path, name)


def main():
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Build an importable AM++ plugin ZIP.")
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--ampp-sdk", required=True, type=Path)
    parser.add_argument("--d8", required=True, type=Path, help="Android Build Tools lib/d8.jar")
    parser.add_argument("--ecj", type=Path, help="Optional Eclipse compiler JAR")
    args = parser.parse_args()
    for path in (args.android_jar, args.ampp_sdk, args.d8, args.ecj):
        if path is not None and not path.is_file():
            parser.error(f"File not found: {path}")

    manifest = root / "plugin.json"
    metadata = json.loads(manifest.read_text(encoding="utf-8"))
    version = metadata["versionName"]
    compilation_classpath = os.pathsep.join(map(str, (args.android_jar, args.ampp_sdk)))
    sources = sorted((root / "src").rglob("*.java"))
    assets = sorted((root / "assets").rglob("*"))
    output_dir = root / "build"
    output_dir.mkdir(exist_ok=True)
    destination = output_dir / f"ampp-native-session-{version}.zip"

    with tempfile.TemporaryDirectory(dir=output_dir) as temporary:
        temp = Path(temporary)
        classes = temp / "classes"
        classes.mkdir()
        compiler = ["javac"] if args.ecj is None else ["java", "-jar", str(args.ecj), "-proc:none"]
        subprocess.run(compiler + [
            "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-classpath", compilation_classpath, "-d", str(classes), *map(str, sources),
        ], check=True)
        compiled = temp / "compiled.jar"
        archive(compiled, ((path, path.relative_to(classes).as_posix())
                           for path in sorted(classes.rglob("*.class"))))

        dex = temp / "dex"
        dex.mkdir()
        subprocess.run([
            "java", "-cp", str(args.d8), "com.android.tools.r8.D8", "--release",
            "--min-api", str(metadata["minAndroidApi"]), "--lib", str(args.android_jar),
            "--classpath", str(args.ampp_sdk), "--output", str(dex), str(compiled),
        ], check=True)
        code = temp / "code.jar"
        archive(code, ((path, path.name) for path in sorted(dex.glob("classes*.dex"))))
        plugin = temp / destination.name
        archive(plugin, [(manifest, "plugin.json"), (code, "code.jar"), *(
            (path, path.relative_to(root).as_posix()) for path in assets if path.is_file()
        )])
        plugin.replace(destination)

    print(destination)


if __name__ == "__main__":
    main()
