#!/usr/bin/env python3
import argparse
import glob
import multiprocessing
import os
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

# --------------------
# Terminal colors
# --------------------
no_color = False

def color_print(code, s):
    if no_color:
        print(s)
    else:
        s = s.replace("\n", f"\033[0m\n{code}")
        print(f"{code}{s}\033[0m")

def error(s):
    color_print("\033[41;39m", f"\n! {s}\n")
    sys.exit(1)

def header(s):
    color_print("\033[44;39m", f"\n{s}\n")

def vprint(s):
    if args.verbose > 0:
        print(s)

# --------------------
# OS Detection
# --------------------
os_name = platform.system().lower()
is_windows = False

if os_name not in ["linux", "darwin"]:
    is_windows = True
    os_name = "windows"

EXE_EXT = ".exe" if is_windows else ""

if is_windows:
    try:
        import colorama
        colorama.init()
    except ImportError:
        no_color = True

if not sys.version_info >= (3, 8):
    error("Requires Python 3.8+")

cpu_count = multiprocessing.cpu_count()

# --------------------
# ABIs and targets
# --------------------
support_abis = {
    "armeabi-v7a": "thumbv7neon-linux-androideabi",
    "x86": "i686-linux-android",
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
}

abi_alias = {
    "arm": "armeabi-v7a",
    "arm32": "armeabi-v7a",
    "arm64": "arm64-v8a",
    "x64": "x86_64",
}

default_abis = set(support_abis.keys())

support_targets = {
    "magisk",
    "magiskinit",
    "magiskboot",
    "magiskpolicy",
    "resetprop"
}

default_targets = support_targets - {"resetprop"}
rust_targets = default_targets.copy()

ondk_version = "r29.5"

config = {}
args: argparse.Namespace
build_abis: dict[str, str]
force_out = False

# --------------------
# Helper functions
# --------------------
def mv(src: Path, dst: Path):
    try:
        shutil.move(src, dst)
        vprint(f"mv {src} -> {dst}")
    except:
        pass

def cp(src: Path, dst: Path):
    try:
        shutil.copyfile(src, dst)
        vprint(f"cp {src} -> {dst}")
    except:
        pass

def rm(file: Path):
    try:
        os.remove(file)
        vprint(f"rm {file}")
    except FileNotFoundError:
        pass

def rm_on_error(func, path, _):
    try:
        os.chmod(path, stat.S_IWRITE)
        os.unlink(path)
    except FileNotFoundError:
        pass

def rm_rf(path: Path):
    vprint(f"rm -rf {path}")
    shutil.rmtree(path, ignore_errors=False, onerror=rm_on_error)

def execv(cmds: list, env=None):
    out = None if force_out or args.verbose > 0 else subprocess.DEVNULL
    return subprocess.run(cmds, stdout=out, env=env, shell=is_windows)

def cmd_out(cmds: list):
    return subprocess.run(
        cmds,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        shell=is_windows
    ).stdout.strip().decode("utf-8")

# --------------------
# Git helpers
# --------------------
def is_git_repo():
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            shell=is_windows
        )

        return (
            proc.returncode == 0 and
            proc.stdout.strip() == "true"
        )
    except:
        return False

def get_git_commit():
    try:
        commit = cmd_out(["git", "rev-parse", "--short", "HEAD"])

        if commit:
            return commit
    except:
        pass

    return "local"

# --------------------
# Path setup
# --------------------
def ensure_paths():
    global sdk_path
    global ndk_root
    global ndk_path
    global rust_sysroot
    global ndk_build
    global adb_path

    if "sdk_path" in globals():
        return

    try:
        sdk_path = Path(os.environ["ANDROID_HOME"])
    except KeyError:
        try:
            sdk_path = Path(os.environ["ANDROID_SDK_ROOT"])
        except KeyError:
            error("Please set ANDROID_HOME or ANDROID_SDK_ROOT")

    ndk_root = sdk_path / "ndk"
    ndk_path = ndk_root / "magisk"

    ndk_build = ndk_path / "ndk-build"

    rust_sysroot = (
        ndk_path /
        "toolchains" /
        "rust"
    )

    adb_path = (
        sdk_path /
        "platform-tools" /
        "adb"
    )

# --------------------
# NDK setup
# --------------------
def setup_ndk():
    ensure_paths()

    url = (
        f"https://github.com/topjohnwu/ondk/releases/download/"
        f"{ondk_version}/ondk-{ondk_version}-{os_name}.tar.xz"
    )

    ndk_archive = url.split("/")[-1]

    ondk_path = (
        ndk_root /
        f"ondk-{ondk_version}"
    )

    header(f"* Downloading and extracting {ndk_archive}")

    rm_rf(ondk_path)

    try:
        with urllib.request.urlopen(url) as response:
            with tarfile.open(mode="r|xz", fileobj=response) as tar:
                if hasattr(tarfile, "data_filter"):
                    tar.extractall(ndk_root, filter="tar")
                else:
                    tar.extractall(ndk_root)

    except Exception as e:
        error(f"NDK download/extract failed: {e}")

    rm_rf(ndk_path)
    mv(ondk_path, ndk_path)

    ondk_ver_file = ndk_path / "ONDK_VERSION"

    if (
        not ondk_ver_file.exists() or
        ondk_ver_file.read_text().strip() != ondk_version
    ):
        error(f"NDK version mismatch! Expected {ondk_version}")

    header("* NDK setup complete")

# --------------------
# Native Build
# --------------------
def run_ndk_build(cmds: list[str]):
    os.chdir("native")

    cmds.append("NDK_PROJECT_PATH=.")
    cmds.append("NDK_APPLICATION_MK=src/Application.mk")
    cmds.append(f"APP_ABI={' '.join(build_abis.keys())}")
    cmds.append(f"-j{cpu_count}")

    if args.verbose > 1:
        cmds.append("V=1")

    if not args.release:
        cmds.append("MAGISK_DEBUG=1")

    proc = execv([ndk_build, *cmds])

    if proc.returncode != 0:
        error("Build binary failed!")

    os.chdir("..")

def collect_ndk_build():
    for arch in build_abis.keys():
        arch_dir = Path("native", "libs", arch)
        out_dir = Path("native", "out", arch)

        out_dir.mkdir(parents=True, exist_ok=True)

        for source in arch_dir.iterdir():
            target = out_dir / source.name
            mv(source, target)

def clean_elf():
    cargo_toml = Path(
        "tools",
        "elf-cleaner",
        "Cargo.toml"
    )

    cmds = [
        "run",
        "--release",
        "--manifest-path",
        cargo_toml
    ]

    if args.verbose == 0:
        cmds.append("-q")
    elif args.verbose > 1:
        cmds.append("--verbose")

    cmds.append("--")

    cmds.extend(glob.glob("native/out/*/magisk"))
    cmds.extend(glob.glob("native/out/*/magiskpolicy"))

    run_cargo(cmds)

def build_cpp_src(targets: set[str]):
    cmds = []
    clean = False

    if "magisk" in targets:
        cmds.append("B_MAGISK=1")
        clean = True

    if "magiskpolicy" in targets:
        cmds.append("B_POLICY=1")
        clean = True

    if "magiskinit" in targets:
        cmds.append("B_PRELOAD=1")

    if "resetprop" in targets:
        cmds.append("B_PROP=1")

    if cmds:
        run_ndk_build(cmds)
        collect_ndk_build()

    cmds.clear()

    if "magiskinit" in targets:
        cmds.append("B_INIT=1")

    if "magiskboot" in targets:
        cmds.append("B_BOOT=1")

    if cmds:
        cmds.append("B_CRT0=1")

        run_ndk_build(cmds)
        collect_ndk_build()

    if clean:
        clean_elf()

def run_cargo(cmds: list):
    try:
        env = os.environ.copy()

        env["PATH"] = (
            f"{rust_sysroot / 'bin'}"
            f"{os.pathsep}"
            f"{env['PATH']}"
        )

        return execv(["cargo", *cmds], env)

    except FileNotFoundError:
        header("Cargo not found. Skipping Rust build.")

        class Dummy:
            returncode = 0

        return Dummy()

def build_rust_src(targets: set[str]):
    targets = targets.copy()

    if "resetprop" in targets:
        targets.add("magisk")

    targets = targets & rust_targets

    if not targets:
        return

    os.chdir(Path("native", "src"))

    cmds = ["build", "-p", ""]

    if args.release:
        cmds.append("-r")
        profile = "release"
    else:
        profile = "debug"

    if args.verbose == 0:
        cmds.append("-q")
    elif args.verbose > 1:
        cmds.append("--verbose")

    for triple in build_abis.values():
        cmds.append("--target")
        cmds.append(triple)

    for tgt in targets:
        cmds[2] = tgt

        proc = run_cargo(cmds)

        if proc.returncode != 0:
            error("Build binary failed!")

    os.chdir(Path("..", ".."))

    native_out = Path("native", "out")
    rust_out = native_out / "rust"

    for arch, triple in build_abis.items():
        arch_out = native_out / arch

        arch_out.mkdir(mode=0o755, exist_ok=True)

        for tgt in targets:
            source = (
                rust_out /
                triple /
                profile /
                f"lib{tgt}.a"
            )

            target = arch_out / f"lib{tgt}-rs.a"

            mv(source, target)

def dump_flag_header():
    header_txt = "#pragma once\n"

    header_txt += (
        f'#define MAGISK_VERSION "{config["version"]}"\n'
    )

    header_txt += (
        f'#define MAGISK_VER_CODE {config["versionCode"]}\n'
    )

    if args.release:
        header_txt += "#define MAGISK_DEBUG 0\n"
    else:
        header_txt += "#define MAGISK_DEBUG 1\n"

    native_gen_path = Path(
        "native",
        "out",
        "generated"
    )

    native_gen_path.mkdir(parents=True, exist_ok=True)

    with open(native_gen_path / "flags.h", "w") as f:
        f.write(header_txt)

    rust_txt = (
        f'pub const MAGISK_VERSION: &str = "{config["version"]}";\n'
    )

    rust_txt += (
        f'pub const MAGISK_VER_CODE: i32 = {config["versionCode"]};\n'
    )

    with open(native_gen_path / "flags.rs", "w") as f:
        f.write(rust_txt)

def ensure_toolchain():
    ensure_paths()

    try:
        with open(
            Path(ndk_path, "ONDK_VERSION"),
            "r"
        ) as ondk_ver:

            assert (
                ondk_ver
                .read()
                .strip(" \t\r\n") == ondk_version
            )

    except:
        error(
            'Unmatched NDK. '
            'Please install/upgrade NDK with "build.py ndk"'
        )

    if sccache := shutil.which("sccache"):
        os.environ["RUSTC_WRAPPER"] = sccache
        os.environ["NDK_CCACHE"] = sccache
        os.environ["CARGO_INCREMENTAL"] = "0"

    if ccache := shutil.which("ccache"):
        os.environ["NDK_CCACHE"] = ccache

def build_native():
    ensure_toolchain()

    targets = default_targets

    header(
        f"* Building native: {' '.join(targets)}"
    )

    dump_flag_header()

    build_rust_src(targets)
    build_cpp_src(targets)

# --------------------
# Config and ABIs
# --------------------
def set_build_abis(abis: set[str]):
    global build_abis

    abis = {
        abi_alias.get(k, k)
        for k in abis
    }

    for k in abis - support_abis.keys():
        error(f"Unknown ABI: {k}")

    build_abis = {
        k: support_abis[k]
        for k in abis
        if k in support_abis
    }

def load_config():
    if is_git_repo():
        version_name = get_git_commit()
    else:
        version_name = "local"

    config["version"] = version_name
    config["versionCode"] = 67678
    config["outdir"] = Path("out")

    set_build_abis(default_abis)

# --------------------
# CLI
# --------------------
def parse_args():
    parser = argparse.ArgumentParser(
        description="Magisk build"
    )

    parser.set_defaults(func=lambda: None)

    parser.add_argument(
        "-r",
        "--release",
        action="store_true"
    )

    parser.add_argument(
        "-v",
        "--verbose",
        action="count",
        default=0
    )

    subparsers = parser.add_subparsers(
        title="actions"
    )

    all_parser = subparsers.add_parser(
        "all",
        help="build everything"
    )

    native_parser = subparsers.add_parser(
        "native",
        help="build native binaries"
    )

    ndk_parser = subparsers.add_parser(
        "ndk",
        help="setup/download Magisk NDK"
    )

    all_parser.set_defaults(func=build_native)
    native_parser.set_defaults(func=build_native)
    ndk_parser.set_defaults(func=setup_ndk)

    return parser.parse_args()

def main():
    global args
    args = parse_args()
    load_config()
    args.func()

if __name__ == "__main__":
    main()