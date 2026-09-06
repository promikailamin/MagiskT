/**
 * DenyList internal header defining request/response enums,
 * CLI entry points, and shared declarations (logcat thread, proc_context_match).
 */
#pragma once

#include <string_view>

#define ISOLATED_MAGIC "isolated"
// Pseudo-process name used to mark packages that are locked (persist across uninstall)
#define LOCKED_MAGIC "*locked"

/** IPC request codes sent from the client to the daemon's denylist handler. */
namespace DenyRequest {
enum : int {
    ENFORCE,
    DISABLE,
    ADD,
    REMOVE,
    LIST,
    STATUS,
    LOCK,

    END
};
}

/** IPC response codes sent back from the daemon's denylist handler. */
namespace DenyResponse {
enum : int {
    OK,
    ENFORCED,
    NOT_ENFORCED,
    ITEM_EXIST,
    ITEM_NOT_EXIST,
    INVALID_PKG,
    NO_NS,
    ERROR,
    ITEM_LOCKED,

    END
};
}

/** Enable denylist enforcement. */
int enable_deny();
/** Disable denylist enforcement. */
int disable_deny();
/** Read (pkg, proc) from an IPC client and add to the denylist. */
int add_list(int client);
/** Read (pkg, proc) from an IPC client and remove from the denylist. */
int rm_list(int client);
/** Read (pkg, locked) from an IPC client and set the lock flag of a package. */
int set_locked(int client);
/** Write the full denylist to an IPC client. */
void ls_list(int client);

/** Check if a process has the given SELinux context prefix. */
bool proc_context_match(int pid, std::string_view context);
/** Thread entry for the logcat monitoring thread. */
void *logcat(void *arg);
/** Flag to signal logcat thread to exit. */
extern bool logcat_exit;
