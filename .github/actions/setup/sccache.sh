#!/usr/bin/env bash

# Pin the sccache version so the on-disk cache format stays stable across runs.
# Bump this when upgrading; the cache key in action.yml uses hashFiles() of the
# source, so changing the compiler wrapper here won't invalidate old caches.
SCCACHE_VER="v0.17.0"

# $1=variant
# $2=install_dir
# $3=exe
install_from_gh() {
  local url="https://github.com/mozilla/sccache/releases/download/${SCCACHE_VER}/sccache-${SCCACHE_VER}-$1.tar.gz"
  local dest="$2/$3"
  curl -L "$url" | tar xz -O --wildcards "*/$3" > $dest
  chmod +x $dest
}

if [ $RUNNER_OS = "macOS" ]; then
  case "$(uname -m)" in
    arm64) install_from_gh aarch64-apple-darwin /usr/local/bin sccache ;;
    x86_64) install_from_gh x86_64-apple-darwin /usr/local/bin sccache ;;
  esac
elif [ $RUNNER_OS = "Linux" ]; then
  install_from_gh x86_64-unknown-linux-musl /usr/local/bin sccache
elif [ $RUNNER_OS = "Windows" ]; then
  install_from_gh x86_64-pc-windows-msvc $USERPROFILE/.cargo/bin sccache.exe
fi
