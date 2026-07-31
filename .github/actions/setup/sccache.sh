#!/usr/bin/env bash

# Pin the sccache version so the on-disk cache format stays stable across runs.
# This file is part of the sccache cache key in action.yml, so bumping the
# version here automatically invalidates old caches.
set -euo pipefail

SCCACHE_VER="v0.17.0"
INSTALL_DIR="${SCCACHE_INSTALL_DIR:-$HOME/.local/bin}"

install_from_gh() {
  local variant="$1"
  local exe="$2"
  local url="https://github.com/mozilla/sccache/releases/download/${SCCACHE_VER}/sccache-${SCCACHE_VER}-${variant}.tar.gz"
  local archive="$(mktemp)"
  local extract_dir="$(mktemp -d)"
  trap 'rm -rf "$archive" "$extract_dir"' EXIT

  curl -fsSL -o "$archive" "$url"
  test -s "$archive"
  tar -xzf "$archive" -C "$extract_dir"
  mkdir -p "$INSTALL_DIR"
  cp "$extract_dir/sccache-${SCCACHE_VER}-${variant}/$exe" "$INSTALL_DIR/$exe"
  chmod +x "$INSTALL_DIR/$exe"
  "$INSTALL_DIR/$exe" --version
  echo "$INSTALL_DIR" >> "$GITHUB_PATH"
}

case "$RUNNER_OS" in
  macOS)
    case "$(uname -m)" in
      arm64) install_from_gh aarch64-apple-darwin sccache ;;
      x86_64) install_from_gh x86_64-apple-darwin sccache ;;
      *) echo "::error::Unsupported macOS arch: $(uname -m)"; exit 1 ;;
    esac
    ;;
  Linux) install_from_gh x86_64-unknown-linux-musl sccache ;;
  Windows) install_from_gh x86_64-pc-windows-msvc sccache.exe ;;
  *)
    echo "::error::Unsupported runner OS: ${RUNNER_OS:-<unset>}"
    exit 1
    ;;
esac
