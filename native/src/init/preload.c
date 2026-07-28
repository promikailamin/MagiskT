/**
 * Preload library for init replacement (magiskinit).
 *
 * This shared library is loaded into the stock init process via LD_PRELOAD.
 * It intercepts the security_load_policy() call to capture the SELinux
 * policy before it is loaded into the kernel. The intercepted policy is
 * written to a pre-arranged file (/dev/sepolicy) so that magiskinit can
 * read, patch, and re-insert it with Magisk's rules added.
 *
 * Flow:
 *  1. Constructor runs: unsetenv("LD_PRELOAD"), unlink preload lib path
 *  2. When stock init calls security_load_policy(), this interceptor:
 *     a. Writes the policy data to /dev/sepolicy
 *     b. Blocks on /dev/ack until magiskinit signals it's done patching
 *     c. Returns 0 (success) so init continues
 *  3. magiskinit reads /dev/sepolicy, patches it, writes it back via
 *     the kernel interface, then writes to /dev/ack to unblock init.
 */
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>

#include "init.hpp"

/**
 * Constructor: runs automatically when the library is loaded via LD_PRELOAD.
 *
 * Cleans up the preload environment variables and removes the preload library
 * file so that subsequent exec() calls (e.g. when init re-execs) don't
 * reload this library unintentionally.
 */
__attribute__((constructor))
static void preload_init() {
    unsetenv("LD_PRELOAD");
    unlink(PRELOAD_LIB);
}

/**
 * Intercept security_load_policy() — the SELinux policy loading function.
 *
 * Instead of letting the stock init load the policy directly, this function:
 * 1. Writes the raw policy blob to /dev/sepolicy (PRELOAD_POLICY)
 * 2. Blocks reading from /dev/ack (PRELOAD_ACK) until magiskinit signals
 *    that it has finished patching the policy with Magisk rules
 * 3. Returns 0 to the caller, which makes init believe the policy was
 *    loaded successfully (the interceptor + magiskinit handle the real
 *    loading through the kernel interface afterwards)
 *
 * @param data Pointer to the SELinux policy binary blob
 * @param len  Size of the policy blob in bytes
 * @return 0 on success, -1 on failure
 */
int security_load_policy(void *data, size_t len) {
    int policy = open(PRELOAD_POLICY, O_WRONLY | O_CREAT, 0644);
    if (policy < 0) return -1;

    write(policy, data, len);
    close(policy);

    // Wait for magiskinit to finish patching the policy
    int ack = open(PRELOAD_ACK, O_RDONLY);
    char c;
    read(ack, &c, 1);
    close(ack);

    return 0;
}
