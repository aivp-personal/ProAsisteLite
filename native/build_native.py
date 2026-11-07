"""Builds app/src/main/jniLibs from FalkorDB's official arm64 image. Needs Docker. Run again only to change versions.

Usage: python native/build_native.py

The images are pinned by digest and pulled without a tag, so no local image tag changes. Nothing from the image runs here.
"""
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile

from patch_glibc import patch

FALKORDB_IMAGE = "falkordb/falkordb@sha256:d7b669995d4f0490527529903b521a6026711ca0dadd8102ec9539c5ccaed342"  # 6.0.4, arm64
TOOLCHAIN_IMAGE = "debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2"
CONTAINER = "proasistelite-native-export"

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(HERE, "..", "app", "src", "main", "jniLibs", "arm64-v8a")

# Packaged name -> name inside the image. Android installs only files named lib*.so; FalkorServer links back to the real names.
FILES = {
    "libfalkor_loader.so": "ld-linux-aarch64.so.1",
    "libfalkor_redis.so": "redis-server",
    "libfalkor_module.so": "falkordb.so",
    "libfalkor_c.so": "libc.so.6",
    "libfalkor_m.so": "libm.so.6",
    "libfalkor_gcc.so": "libgcc_s.so.1",
    "libfalkor_stdcpp.so": "libstdc++.so.6",
    "libfalkor_ssl.so": "libssl.so.3",
    "libfalkor_crypto.so": "libcrypto.so.3",
    "libfalkor_z.so": "libz.so.1",
    "libfalkor_zstd.so": "libzstd.so.1",
}


def run(*command):
    subprocess.run(command, check=True)


def export_image(tar_path):
    run("docker", "pull", "--platform", "linux/arm64", FALKORDB_IMAGE)
    run("docker", "create", "--platform", "linux/arm64", "--name", CONTAINER, FALKORDB_IMAGE)
    try:
        run("docker", "export", CONTAINER, "-o", tar_path)
    finally:
        run("docker", "rm", CONTAINER)
    run("docker", "rmi", FALKORDB_IMAGE)


def extract_files(tar_path, staging):
    with tarfile.open(tar_path) as image:
        members = {}
        for member in image.getmembers():
            if member.isfile() or member.issym() or member.islnk():
                members.setdefault(os.path.basename(member.name), []).append(member)
        for packaged, name in FILES.items():
            found = members.get(name)
            if not found:
                sys.exit(f"{name} isn't in the image")
            source = image.extractfile(found[0])
            if source is None:
                sys.exit(f"Can't read {name}")
            with open(os.path.join(staging, packaged), "wb") as target:
                shutil.copyfileobj(source, target)


def compile_and_strip(staging):
    """Compiles the launcher and strips debug symbols from everything else, in a throwaway container."""
    shutil.copy(os.path.join(HERE, "launcher.c"), staging)
    script = (
        "apt-get update -qq >/dev/null && "
        "apt-get install -y -qq --no-install-recommends gcc-aarch64-linux-gnu libc6-dev-arm64-cross >/dev/null 2>&1 && "
        "aarch64-linux-gnu-gcc -static -nostdlib -ffreestanding -fno-stack-protector -fno-pie -no-pie -O1 -Wall "
        "-o /w/libfalkor_launcher.so /w/launcher.c && "
        "for f in /w/libfalkor_*.so; do "
        '[ "$f" = /w/libfalkor_loader.so ] || aarch64-linux-gnu-strip --strip-unneeded "$f"; done'
    )
    run("docker", "run", "--rm", "-v", f"{staging}:/w", TOOLCHAIN_IMAGE, "sh", "-c", script)
    os.remove(os.path.join(staging, "launcher.c"))


def main():
    with tempfile.TemporaryDirectory() as work:
        tar_path = os.path.join(work, "image.tar")
        staging = os.path.join(work, "staging")
        os.mkdir(staging)
        export_image(tar_path)
        extract_files(tar_path, staging)
        compile_and_strip(staging)
        library = os.path.join(staging, "libfalkor_c.so")
        patch(library, library)
        shutil.rmtree(OUTPUT, ignore_errors=True)
        os.makedirs(OUTPUT)
        for name in sorted(os.listdir(staging)):
            shutil.copy(os.path.join(staging, name), OUTPUT)
            print(f"{name}: {os.path.getsize(os.path.join(OUTPUT, name)) / 1_000_000:.1f} MB")


if __name__ == "__main__":
    main()
