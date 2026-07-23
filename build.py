#!/usr/bin/env python3
import argparse
import multiprocessing
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

cpu_count = multiprocessing.cpu_count()
is_windows = platform.system().lower() not in ("linux","darwin")
no_color = False
config = {}
support_abis = {
    "armeabi-v7a":"thumbv7neon-linux-androideabi",
    "arm64-v8a":"aarch64-linux-android",
    "x86":"i686-linux-android",
    "x86_64":"x86_64-linux-android",
}
build_abis = support_abis.copy()

def color_print(c,s): print(s)
def error(s):
    print(f"ERROR: {s}")
    sys.exit(1)
def header(s): print(f"\n{s}\n")
def mv(a,b):
    Path(b).parent.mkdir(parents=True,exist_ok=True)
    shutil.move(a,b)
def cp(a,b):
    Path(b).parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(a,b)
def execv(cmd,env=None):
    return subprocess.run(cmd,env=env,shell=is_windows)
def cmd_out(cmd):
    return subprocess.run(cmd,stdout=subprocess.PIPE,shell=is_windows).stdout.decode().strip()

def parse_props(f):
    p={}
    if not Path(f).exists(): return p
    for l in Path(f).read_text().splitlines():
        if "=" in l and not l.startswith("#"):
            k,v=l.split("=",1)
            p[k.strip()]=v.strip()
    return p

def set_build_abis(abis):
    global build_abis
    build_abis={k:support_abis[k] for k in abis if k in support_abis}

def ensure_paths():
    global gradlew
    gradlew=Path.cwd()/ "app"/("gradlew.bat" if is_windows else "gradlew")

def find_jdk():
    if subprocess.run("javac -version",shell=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0:
        error("JDK not found")
    return os.environ.copy()

def load_config():
    config.update({"version":"dev","versionCode":1,"outdir":Path("out")})
    config.update(parse_props("config.prop"))
    gp=parse_props(Path("app")/"gradle.properties")
    for k,v in gp.items():
        if k.startswith("magisk."):
            config[k[7:]]=v
    config["outdir"]=Path(config["outdir"])
    config["outdir"].mkdir(exist_ok=True)

def build_apk(module):
    ensure_paths()
    env=find_jdk()
    build_type="Release" if args.release else "Debug"
    os.chdir("app")
    r=execv([str(gradlew),f"{module}:assemble{build_type}",f"-PabiList={','.join(build_abis.keys())}"],env)
    os.chdir("..")
    if r.returncode!=0:
        error("Build failed")
    bt=build_type.lower()
    name=module.split(":")[-1]
    apk=Path("app",name,"build","outputs","apk",bt,f"{name}-{bt}.apk")
    out=config["outdir"]/apk.name
    if apk.exists():
        mv(apk,out)
    return out

def build_app():
    header("Building app")
    apk=build_apk(":apk")
    if apk.exists():
        new=apk.parent/apk.name.replace("apk-","app-")
        mv(apk,new)
        print(new)

def build_apkT():
    header("Building apkT")
    print(build_apk(":apkT"))

def build_stub():
    header("Building stub")
    print(build_apk(":stub"))

def build_test():
    header("Building test")
    old=args.release
    args.release=True
    print(build_apk(":test"))
    args.release=old

def build_all():
    build_app();build_apkT();build_stub();build_test()

def parse_args():
    p=argparse.ArgumentParser()
    p.add_argument("-r","--release",action="store_true")
    s=p.add_subparsers(dest="cmd")
    for n,f in [("all",build_all),("app",build_app),("apkT",build_apkT),("stub",build_stub),("test",build_test)]:
        sp=s.add_parser(n);sp.set_defaults(func=f)
    a=p.parse_args()
    if not hasattr(a,"func"): p.print_help();sys.exit(1)
    return a

args=parse_args()
load_config()
args.func()
