"""Reconstruct the Xiaomi 14T debug APK using the extracted release patch."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile
import zlib


BASE_URL = "https://github.com/jaredgei/wow-forever-android/releases/download/v2.4.2/WoW-Forever.apk"
WRAPPER_URL = "https://downloads.gamenative.app/graphics_driver/wrapper-gamenative-20260724.tzst"
BASE_SHA = "df11abc6f0a62c9c8f32d6a7c13394c1534de526c68231a3973364fd89e6d9ae"
WRAPPER_SHA = "1a546621cf285b50a6de47aeb0ada4a298d41ac24fc5ca2cdc46673859056d37"
APK_SHA = "c6edd07166cc22b9ab60b011f07e8b2fb65d4dd8edd999efd794c7a5cd21c9f2"
APK_NAME = "WoW-Forever-Mali-side-by-side-debug.apk"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def get_input(local_path, url, expected_sha, work):
    if local_path is None:
        local_path = work / url.rsplit("/", 1)[1]
        print("Downloading", url, flush=True)
        with urllib.request.urlopen(url, timeout=90) as source, local_path.open("wb") as dest:
            shutil.copyfileobj(source, dest)
    if sha256(local_path) != expected_sha:
        raise ValueError("SHA-256 mismatch for " + str(local_path))
    return local_path


def copy_bytes(source, dest, size):
    if size < 0:
        raise ValueError("Negative size in recipe")
    while size:
        chunk = source.read(min(size, 1024 * 1024))
        if not chunk:
            raise ValueError("Input shorter than the recipe requires")
        dest.write(chunk)
        size -= len(chunk)


def assemble(folder, base, wrapper, output):
    recipe = json.loads((folder / "recipe.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(base) as members, base.open("rb") as raw_base, (folder / "changes.bin").open("rb") as changes:
        for op in recipe:
            if isinstance(op, int):
                copy_bytes(changes, output, op)
            elif op[0] == "copy":
                raw_base.seek(op[1])
                copy_bytes(raw_base, output, op[2])
            else:
                kind, name, mode = op
                if kind == "member":
                    source = members.open(name)
                elif kind == "wrapper":
                    source = wrapper.open("rb")
                else:
                    raise ValueError("Unknown input " + kind)
                with source:
                    if mode == "store":
                        shutil.copyfileobj(source, output)
                    elif mode == "deflate1":
                        encoder = zlib.compressobj(1, zlib.DEFLATED, -15)
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            output.write(encoder.compress(chunk))
                        output.write(encoder.flush())
                    else:
                        raise ValueError("Unknown compression " + mode)
        if changes.read(1):
            raise ValueError("Unused bytes in patch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--patch-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--base", type=Path, help="Use a local copy of the upstream v2.4.2 APK")
    parser.add_argument("--wrapper", type=Path, help="Use a local copy of the dated GameNative wrapper")
    parser.add_argument("--output", type=Path, default=Path(APK_NAME))
    args = parser.parse_args()
    if args.output.exists():
        if sha256(args.output) != APK_SHA:
            raise ValueError("A different file already exists at " + str(args.output))
    else:
        with tempfile.TemporaryDirectory(dir=args.output.resolve().parent) as folder:
            work = Path(folder)
            base = get_input(args.base, BASE_URL, BASE_SHA, work)
            wrapper = get_input(args.wrapper, WRAPPER_URL, WRAPPER_SHA, work)
            result = work / "result.apk"
            with result.open("wb") as output:
                assemble(args.patch_dir, base, wrapper, output)
            if sha256(result) != APK_SHA:
                raise ValueError("Result differs from the tested APK. Check the patch and your Python zlib version.")
            os.replace(result, args.output)
    print("Verified", args.output.resolve(), "SHA-256", APK_SHA)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
