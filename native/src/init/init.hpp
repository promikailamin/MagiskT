/**
 * MagiskInit header — public interface for the init replacement (`magiskinit`).
 *
 * Declares:
 * - Boot configuration constants (DT dir, preload paths)
 * - The kv_pairs type alias used throughout the init code
 * - Forward declarations for C++ functions callable from Rust (magisk_proxy_main,
 *   backup_init) and inline helpers that expose C preprocessor constants to Rust
 *   through the CXX bridge (split_plat_cil, preload_lib, preload_policy, preload_ack)
 *
 * The corresponding CXX bridge structs/declarations are in init-rs.hpp.
 */
#pragma once

#define DEFAULT_DT_DIR "/proc/device-tree/firmware/android"
#define REDIR_PATH "/data/magiskinit"

#define PRELOAD_LIB    "/dev/preload.so"
#define PRELOAD_POLICY "/dev/sepolicy"
#define PRELOAD_ACK    "/dev/ack"

#ifdef __cplusplus

#include <base.hpp>
#include <sepolicy.hpp>

/// Vector of key-value string pairs used for parsed boot config.
using kv_pairs = std::vector<std::pair<std::string, std::string>>;

#include "init-rs.hpp"

/// Secondary entry point: post-init rootfs patching executed after
/// the real init process replaces magiskinit's PID.
int magisk_proxy_main(int, char *argv[]);

/// Decompress and return the path to the backup stock init
/// (/.backup/init), used by the Rust side for restoring the
/// original init after magiskinit's work is done.
Utf8CStr backup_init();

// ── Constant-exposing helpers for the Rust/CXX bridge ───────────────────────

/// Return the path to the split sepolicy CIL file (SPLIT_PLAT_CIL).
static inline Utf8CStr split_plat_cil() {
    return SPLIT_PLAT_CIL;
};

/// Return the preload shared library path (PRELOAD_LIB).
static inline Utf8CStr preload_lib() {
    return PRELOAD_LIB;
}

/// Return the preload sepolicy output path (PRELOAD_POLICY).
static inline Utf8CStr preload_policy() {
    return PRELOAD_POLICY;
}

/// Return the preload acknowledgement path (PRELOAD_ACK).
static inline Utf8CStr preload_ack() {
    return PRELOAD_ACK;
}


#endif
