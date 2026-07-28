/**
 * Root directory patching for magiskinit.
 *
 * This is the core of magiskinit's init.rc manipulation and root filesystem
 * setup. It handles:
 * - XZ decompression of embedded magisk/stub/init binaries
 * - Patching init.rc (removing vaultkeeper, flash_recovery, zygote injection)
 * - Patching init.zygote*.rc for magisk --zygote-restart hooks
 * - Fissiond binary patching and CPU isolated hijacking
 * - overlay.d loading (custom rc scripts from boot image)
 * - sbin directory recreation (bind-mount or symlink)
 * - Read-only root (SAR) setup via patch_ro_root()
 * - Read-write root setup via patch_rw_root()
 * - Post-init proxy main (magisk_proxy_main) for final rootfs patching
 */
#include <sys/mount.h>
#include <libgen.h>

#include <sepolicy.hpp>
#include <consts.hpp>
#include <base.hpp>
#include <xz.h>

#include "init.hpp"

using namespace std;

static vector<string> rc_list;

#define NEW_INITRC_DIR  "/system/etc/init/hw"
#define INIT_RC         "init.rc"

/**
 * Decompress an XZ-compressed buffer and write the output to a file descriptor.
 * Uses the libxz decompressor with dynamic allocation.
 *
 * @param fd    Open file descriptor to write decoded data to
 * @param bytes The compressed XZ data slice
 * @return true on success, false on decompression error
 */
static bool unxz(int fd, rust::Slice<const uint8_t> bytes) {
    uint8_t out[8192];
    xz_crc32_init();
    size_t size = bytes.size();
    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
    run_finally finally([&] { xz_dec_end(dec); });
    struct xz_buf b = {
        .in = bytes.data(),
        .in_pos = 0,
        .in_size = size,
        .out = out,
        .out_pos = 0,
        .out_size = sizeof(out)
    };
    enum xz_ret ret;
    do {
        ret = xz_dec_run(dec, &b);
        if (ret != XZ_OK && ret != XZ_STREAM_END)
            return false;
        write(fd, out, b.out_pos);
        b.out_pos = 0;
    } while (b.in_pos != size);
    return true;
}

/**
 * Patch init.rc and init.zygote*.rc files.
 *
 * Modifications to init.rc:
 *  - Removes "start vaultkeeper" (Samsung security)
 *  - Replaces "service flash_recovery" with a no-op
 *  - Invalidates "persist.sys.zygote.early" (Samsung early zygote)
 *  - Appends custom overlay.d rc scripts and Magisk rc scripts
 *
 * Modifications to init.zygote*.rc:
 *  - Injects "onrestart exec ... --zygote-restart" into the zygote service
 *    so that magiskd is notified on zygote restart.
 *
 * If writable is true, patches are done in-place on the source directory.
 * Otherwise, patched files are written to a ROOTOVL overlay directory.
 *
 * @return true if init.fission_host.rc exists (fissiond present), else false
 */
static bool patch_rc_scripts(const char *src_path, const char *tmp_path, bool writable) {
    auto src_dir = xopen_dir(src_path);
    if (!src_dir) return false;
    int src_fd = dirfd(src_dir.get());

    // If writable, modify files directly in src_path.
    // Otherwise, write to ROOTOVL<src_path> for overlay mounting later.
    auto dest_dir = writable ? [&] {
        return xopen_dir(src_path);
    }() : [&] {
        char buf[PATH_MAX] = {};
        ssprintf(buf, sizeof(buf), ROOTOVL "%s", src_path);
        xmkdirs(buf, 0755);
        return xopen_dir(buf);
    }();
    if (!dest_dir) return false;
    int dest_fd = dirfd(dest_dir.get());

    // ── Patch init.rc ──────────────────────────────────────────────────────
    {
        owned_fd src_rc = xopenat(src_fd, INIT_RC, O_RDONLY | O_CLOEXEC, 0);
        if (src_rc < 0) return false;
        if (writable) unlinkat(src_fd, INIT_RC, 0);
        auto dest_rc = xopen_file(
                xopenat(dest_fd, INIT_RC, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0), "we");
        if (!dest_rc) return false;
        LOGD("Patching " INIT_RC " in %s\n", src_path);
        file_readline(src_rc, [&dest_rc](Utf8CStr line) -> bool {
            // Prevent vaultkeeper from starting (Samsung security daemon)
            if (line.sv().contains("start vaultkeeper")) {
                LOGD("Remove vaultkeeper\n");
                return true;
            }
            // Replace flash_recovery service with a no-op (prevents OTA revert)
            if (line.sv().starts_with("service flash_recovery")) {
                LOGD("Remove flash_recovery\n");
                fprintf(dest_rc.get(), "service flash_recovery /system/bin/true\n");
                return true;
            }
            // Samsung's persist.sys.zygote.early causes zygote to start
            // before post-fs-data, breaking Magisk's module mounts.
            if (line.sv().starts_with("on property:persist.sys.zygote.early=")) {
                LOGD("Invalidate persist.sys.zygote.early\n");
                fprintf(dest_rc.get(), "on property:persist.sys.zygote.early.xxxxx=true\n");
                return true;
            }
            // Unmodified line — write as-is
            fprintf(dest_rc.get(), "%s", line.c_str());
            return true;
        });

        fprintf(dest_rc.get(), "\n");

        // Append custom overlay.d rc scripts (with MAGISKTMP substitution)
        for (auto &script : rc_list) {
            replace_all(script, "${MAGISKTMP}", tmp_path);
            fprintf(dest_rc.get(), "\n%s\n", script.data());
        }
        rc_list.clear();

        // Append Magisk's built-in rc fragments (daemon start, etc.)
        rust::inject_magisk_rc(fileno(dest_rc.get()), tmp_path);

        fclone_attr(src_rc, fileno(dest_rc.get()));
    }

    // ── Patch init.zygote*.rc ──────────────────────────────────────────────
    for (dirent *entry; (entry = readdir(src_dir.get()));) {
        {
            auto name = std::string_view(entry->d_name);
            if (!name.starts_with("init.zygote") || !name.ends_with(".rc")) continue;
        }
        owned_fd src_rc = xopenat(src_fd, entry->d_name, O_RDONLY | O_CLOEXEC, 0);
        if (src_rc < 0) continue;
        if (writable) unlinkat(src_fd, entry->d_name, 0);
        auto dest_rc = xopen_file(
                xopenat(dest_fd, entry->d_name, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0), "we");
        if (!dest_rc) continue;
        LOGD("Patching %s in %s\n", entry->d_name, src_path);
        file_readline(src_rc, [&dest_rc, &tmp_path](Utf8CStr line) -> bool {
            // Inject onrestart hook into the zygote service definition
            if (line.sv().starts_with("service zygote ")) {
                LOGD("Inject zygote restart\n");
                fprintf(dest_rc.get(), "%s", line.c_str());
                fprintf(dest_rc.get(),
                        "    onrestart exec " MAGISK_PROC_CON " 0 0 -- %s/magisk --zygote-restart\n", tmp_path);
                return true;
            }
            fprintf(dest_rc.get(), "%s", line.c_str());
            return true;
        });
        fclone_attr(src_rc, fileno(dest_rc.get()));
    }

    // Return true if fissiond is present (indicates need for fissiond patching)
    return faccessat(src_fd, "init.fission_host.rc", F_OK, 0) == 0;
}

/**
 * Patch the fissiond binary and hijack the /sys/devices/system/cpu/isolated
 * interface.
 *
 * fissiond is a OnePlus-specific daemon for system fission (multiple system
 * images). Magisk patches its "ro.build.system.fission_single_os" property
 * string reference so the daemon behaves correctly with Magisk's overlay.
 *
 * The cpu/isolated hijack: A FIFO is bind-mounted over the sysfs "isolated"
 * file. A forked child reads the original content, binds the magisk tmp into
 * a proper location, mounts overlay, then writes the content back through
 * the FIFO. This tricks the kernel into accepting our overlay without
 * triggering isolation constraints.
 */
void MagiskInit::patch_fissiond(const char *tmp_path) noexcept {
    {
        LOGD("Patching fissiond\n");
        mmap_data fissiond("/system/bin/fissiond", false);
        // Patch the property string reference in the fissiond binary
        for (size_t off : fissiond.patch(
                "ro.build.system.fission_single_os",
                "ro.build.system.xxxxxxxxxxxxxxxxx"))
        {
            LOGD("Patch @ %08zX [ro.build.system.fission_single_os] -> "
                 "[ro.build.system.xxxxxxxxxxxxxxxxx]\n", off);
        }
        // Write the patched binary to the overlay
        mkdirs(ROOTOVL "/system/bin", 0755);
        if (auto target_fissiond = xopen_file(ROOTOVL "/system/bin/fissiond", "we")) {
            fwrite(fissiond.data(), 1, fissiond.size(), target_fissiond.get());
            clone_attr("/system/bin/fissiond", ROOTOVL "/system/bin/fissiond");
        }
    }
    LOGD("hijack isolated\n");
    // Open the original cpu/isolated file before bind-mounting over it
    auto hijack = xopen_file("/sys/devices/system/cpu/isolated", "re");
    // Replace the sysfs file with a FIFO we control
    mkfifo(INTLROOT "/isolated", 0777);
    xmount(INTLROOT "/isolated", "/sys/devices/system/cpu/isolated", nullptr, MS_BIND, nullptr);
    if (!xfork()) {
        // Child process: read original content, do setup, feed content through FIFO
        auto dest = xopen_file(INTLROOT "/isolated", "we");
        LOGD("hijacked isolated\n");
        xumount2("/sys/devices/system/cpu/isolated", MNT_DETACH);
        unlink(INTLROOT "/isolated");
        string content = full_read(fileno(hijack.get()));
        {
            // Bind the magisk tmp into /dev/cells/cell2<tmp_path> and mount overlay
            string target = "/dev/cells/cell2"s + tmp_path;
            xmkdirs(target.data(), 0);
            xmount(tmp_path, target.data(), nullptr, MS_BIND | MS_REC, nullptr);
            mount_overlay("/dev/cells/cell2");
        }
        // Write original content back so the kernel reads what it expects
        fprintf(dest.get(), "%s", content.data());
        exit(0);
    }
}

/**
 * Load custom .rc files from an overlay.d directory.
 *
 * Scans the given overlay directory for .rc files (excluding init.rc,
 * which is explicitly deleted to prevent overwriting). Each discovered
 * .rc file is read into the global rc_list for later injection into
 * the patched init.rc.
 *
 * Files that already exist at /<name> are logged as replacements
 * (they will shadow the originals), while new ones are loaded and removed
 * from the overlay directory.
 */
static void load_overlay_rc(const char *overlay) {
    auto dir = open_dir(overlay);
    if (!dir) return;

    int dfd = dirfd(dir.get());
    // Never allow overlay.d to replace init.rc itself
    unlinkat(dfd, INIT_RC, 0);

    // Buffer: '/' + name + '\0'
    char buf[NAME_MAX + 2];
    buf[0] = '/';
    for (dirent *entry; (entry = xreaddir(dir.get()));) {
        if (!string_view(entry->d_name).ends_with(".rc")) {
            continue;
        }
        strscpy(buf + 1, entry->d_name, sizeof(buf) - 1);
        if (access(buf, F_OK) == 0) {
            // Script already exists in the real root — it will be overlaid
            LOGD("Replace rc script [%s]\n", entry->d_name);
        } else {
            // New script; load it for injection into init.rc
            LOGD("Found rc script [%s]\n", entry->d_name);
            int rc = xopenat(dfd, entry->d_name, O_RDONLY | O_CLOEXEC);
            rc_list.push_back(full_read(rc));
            close(rc);
            unlinkat(dfd, entry->d_name, 0);
        }
    }
}

/**
 * Recreate the contents of a directory under /sbin.
 *
 * Used in two modes:
 * - bind_mount=true:  creates empty dummies and bind-mounts real files over them
 *                     (for /sbin on tmpfs where we need real files)
 * - bind_mount=false: creates symbolic links pointing back to the mirror
 *                     (for post-init proxy main when rootfs is writable)
 *
 * Symlinks in the mirror are recreated as symlinks in /sbin.
 */
static void recreate_sbin(const char *mirror, bool use_bind_mount) {
    auto dp = xopen_dir(mirror);
    int src = dirfd(dp.get());
    char buf[4096];
    for (dirent *entry; (entry = xreaddir(dp.get()));) {
        string sbin_path = "/sbin/"s + entry->d_name;
        struct stat st;
        fstatat(src, entry->d_name, &st, AT_SYMLINK_NOFOLLOW);
        if (S_ISLNK(st.st_mode)) {
            // Symlinks: read target and recreate
            xreadlinkat(src, entry->d_name, buf, sizeof(buf));
            xsymlink(buf, sbin_path.data());
        } else {
            sprintf(buf, "%s/%s", mirror, entry->d_name);
            if (use_bind_mount) {
                auto mode = st.st_mode & 0777;
                // Create dummy file/dir, then bind-mount the real one over it
                if (S_ISDIR(st.st_mode))
                    xmkdir(sbin_path.data(), mode);
                else
                    close(xopen(sbin_path.data(), O_CREAT | O_WRONLY | O_CLOEXEC, mode));
                xmount(buf, sbin_path.data(), nullptr, MS_BIND, nullptr);
            } else {
                // Symlink to the mirror path
                xsymlink(buf, sbin_path.data());
            }
        }
    }
}

/**
 * Decompress and extract embedded XZ archives (magisk, stub APK, init-ld).
 *
 * These files are stored compressed in the boot image cpio and are extracted
 * at runtime to save space. The `sbin` flag adjusts the lookup path
 * (under /sbin/ when in rw-root mode, or cwd in ro-root mode).
 *
 * After decompression, the .xz source files are removed.
 */
static void extract_files(bool sbin) {
    const char *magisk_xz = sbin ? "/sbin/magisk.xz" : "magisk.xz";
    const char *stub_xz = sbin ? "/sbin/stub.xz" : "stub.xz";
    const char *init_ld_xz = sbin ? "/sbin/init-ld.xz" : "init-ld.xz";

    if (access(magisk_xz, F_OK) == 0) {
        mmap_data magisk(magisk_xz);
        unlink(magisk_xz);
        int fd = xopen("magisk", O_WRONLY | O_CREAT, 0755);
        unxz(fd, magisk);
        close(fd);
    }
    if (access(stub_xz, F_OK) == 0) {
        mmap_data stub(stub_xz);
        unlink(stub_xz);
        int fd = xopen("stub.apk", O_WRONLY | O_CREAT, 0);
        unxz(fd, stub);
        close(fd);
    }
    if (access(init_ld_xz, F_OK) == 0) {
        mmap_data init_ld(init_ld_xz);
        unlink(init_ld_xz);
        int fd = xopen("init-ld", O_WRONLY | O_CREAT, 0);
        unxz(fd, init_ld);
        close(fd);
    }
}

/**
 * Patch the root filesystem for system-as-root (read-only root) devices.
 *
 * This is the main entry point for SAR devices after switch_root.
 *
 * Steps:
 *  1. Determine tmp directory (/sbin or /debug_ramdisk)
 *  2. setup_tmp(): mount preinit, copy config, create applet symlinks, etc.
 *  3. If /sbin: recreate original sbin structure via bind-mounts
 *     Else: move debug_ramdisk back to its original location
 *  4. Rename overlay.d to ROOTOVL for the overlay filesystem
 *  5. AVD hack: patch "android,fstab" to "xxx" in /init to disable early mount
 *  6. Load overlay.d rc scripts
 *  7. Patch init.rc (and init.zygote*.rc), optionally patch fissiond
 *  8. Extract embedded XZ archives (magisk binary, stub APK, init-ld)
 *  9. Handle SELinux policy patching
 * 10. Mount the ROOTOVL overlay over /
 */
void MagiskInit::patch_ro_root() noexcept {
    mount_list.emplace_back("/data");
    parse_config_file();

    string tmp_dir;

    // Determine where to set up the magisk tmp directory
    if (access("/sbin", F_OK) == 0) {
        tmp_dir = "/sbin";
    } else {
        // No /sbin — use debug_ramdisk, moved to /data temporarily
        tmp_dir = "/debug_ramdisk";
        xmkdir("/data/debug_ramdisk", 0);
        xmount("/debug_ramdisk", "/data/debug_ramdisk", nullptr, MS_MOVE, nullptr);
    }

    setup_tmp(tmp_dir.data());
    chdir(tmp_dir.data());

    if (tmp_dir == "/sbin") {
        // Bind-mount the real root to MIRRDIR and recreate /sbin contents
        xmkdir(MIRRDIR, 0755);
        xmount("/", MIRRDIR, nullptr, MS_BIND, nullptr);
        recreate_sbin(MIRRDIR "/sbin", true);
        xumount2(MIRRDIR, MNT_DETACH);
    } else {
        // Move /debug_ramdisk back from /data
        xmount("/data/debug_ramdisk", "/debug_ramdisk", nullptr, MS_MOVE, nullptr);
        rmdir("/data/debug_ramdisk");
    }

    // Rename overlay.d to ROOTOVL for overlayfs mounting
    xrename("overlay.d", ROOTOVL);

    extern bool avd_hack;
    // On legacy AVD emulators, patch the original /init to disable
    // early mounting of fstab, letting magiskinit handle it instead.
    if (avd_hack) {
        int src = xopen("/init", O_RDONLY | O_CLOEXEC);
        mmap_data init("/init");
        for (size_t off : init.patch("android,fstab", "xxx")) {
            LOGD("Patch @ %08zX [android,fstab] -> [xxx]\n", off);
        }
        int dest = xopen(ROOTOVL "/init", O_CREAT | O_WRONLY | O_CLOEXEC, 0);
        xwrite(dest, init.data(), init.size());
        fclone_attr(src, dest);
        close(src);
        close(dest);
    }

    // Load and prepare overlay.d rc scripts
    load_overlay_rc(ROOTOVL);
    if (access(ROOTOVL "/sbin", F_OK) == 0) {
        // Move files in overlay.d/sbin into the tmp directory
        mv_path(ROOTOVL "/sbin", ".");
    }

    // Patch init.rc. Android 11+ moved init.rc to /system/etc/init/hw/
    bool p;
    if (access(NEW_INITRC_DIR "/" INIT_RC, F_OK) == 0) {
        p = patch_rc_scripts(NEW_INITRC_DIR, tmp_dir.data(), false);
    } else {
        p = patch_rc_scripts("/", tmp_dir.data(), false);
    }
    // If fissiond is present (OnePlus), patch it and hijack cpu/isolated
    if (p) patch_fissiond(tmp_dir.data());

    // Decompress embedded magisk binary, stub APK, and linker
    extract_files(false);

    // Patch SELinux policy to allow Magisk operations
    handle_sepolicy();
    unlink("init-ld");

    // Mount the ROOTOVL overlay on top of /
    mount_overlay("/");

    chdir("/");
}

#define PRE_TMPSRC "/magisk"
#define PRE_TMPDIR PRE_TMPSRC "/tmp"

/**
 * Patch the root filesystem for read-write root devices (non-SAR).
 *
 * This path is used on legacy devices where / is already writable.
 *
 * Steps:
 *  1. Hardlink-mirror /sbin → /root for later sbin reconstruction
 *  2. Load overlay.d rc scripts and move overlay.d to /
 *  3. Patch init.rc directly (writable=true) and optionally patch fissiond
 *  4. Create a tmpfs at /magisk, set up the magisk tmp directory inside it
 *  5. Extract embedded XZ archives (magisk, stub, init-ld)
 *  6. Patch SELinux policy
 *  7. Dump magiskinit binary as /sbin/magisk so the proxy main can exec it
 *
 * The actual tmpfs migration to /sbin happens later in magisk_proxy_main.
 */
void MagiskInit::patch_rw_root() noexcept {
    mount_list.emplace_back("/data");
    parse_config_file();

    // Preserve /sbin contents by creating a hardlink mirror at /root
    mkdir("/root", 0777);
    clone_attr("/sbin", "/root");
    link_path("/sbin", "/root");

    // Load overlay.d rc scripts and move the overlay directory
    load_overlay_rc("/overlay.d");
    mv_path("/overlay.d", "/");
    rm_rf("/data/overlay.d");
    rm_rf("/.backup");

    // Patch init.rc and init.zygote*.rc in-place
    if (patch_rc_scripts("/", "/sbin", true))
        patch_fissiond("/sbin");

    // Create temporary workspace in a tmpfs
    xmkdir(PRE_TMPSRC, 0);
    xmount("tmpfs", PRE_TMPSRC, "tmpfs", 0, "mode=755");
    xmkdir(PRE_TMPDIR, 0);
    setup_tmp(PRE_TMPDIR);
    chdir(PRE_TMPDIR);

    // Decompress embedded binaries
    extract_files(true);

    handle_sepolicy();
    unlink("init-ld");

    chdir("/");

    // Copy magiskinit to /sbin/magisk for the proxy main to exec
    cp_afc(REDIR_PATH, "/sbin/magisk");
}

/**
 * Post-init proxy main: second-stage entry for read-write root devices.
 *
 * Called after the real Android init has taken over (magiskinit re-execed
 * itself into the PID namespace of init). This function:
 *
 * 1. Remounts / as read-write
 * 2. Removes the placeholder /sbin/magisk
 * 3. Migrates the PRE_TMPSRC tmpfs to /sbin via MS_MOVE
 * 4. Recreates /sbin symlinks back to the /root hardlink mirror
 * 5. Sets REMOUNT_ROOT=1 environment variable for magiskd
 * 6. Execs into /sbin/magisk (the real magisk daemon)
 */
int magisk_proxy_main(int, char *argv[]) {
    rust::setup_klog();
    LOGD("%s\n", __FUNCTION__);

    // Remount rootfs as writable for post-init patching
    xmount(nullptr, "/", nullptr, MS_REMOUNT, nullptr);

    unlink("/sbin/magisk");

    // Move the tmpfs from /magisk/tmp to /sbin
    // Make the parent mount private first so MS_MOVE works correctly
    xmount(nullptr, PRE_TMPSRC, nullptr, MS_PRIVATE, nullptr);
    xmount(PRE_TMPDIR, "/sbin", nullptr, MS_MOVE, nullptr);
    xumount2(PRE_TMPSRC, MNT_DETACH);
    rmdir(PRE_TMPDIR);
    rmdir(PRE_TMPSRC);

    // Recreate symlinks in /sbin pointing back to /root originals
    recreate_sbin("/root", false);

    // Signal magiskd to remount rootfs as rw
    setenv("REMOUNT_ROOT", "1", 1);
    execve("/sbin/magisk", argv, environ);
    return 1;
}

/**
 * Decompress the stock init binary from XZ and write it to the given path.
 * The compressed init is stored at /.backup/init.xz during installation.
 */
static void unxz_init(const char *init_xz, const char *init) {
    LOGD("unxz %s -> %s\n", init_xz, init);
    int fd = xopen(init, O_WRONLY | O_CREAT, 0777);
    unxz(fd, mmap_data{init_xz});
    close(fd);
    clone_attr(init_xz, init);
    unlink(init_xz);
}

/**
 * Get the path to the backup stock init binary.
 * Decompresses /.backup/init.xz to /.backup/init on first call if needed.
 * The Rust side uses this to restore the original init after magiskinit.
 *
 * @return path string "/.backup/init"
 */
Utf8CStr backup_init() {
    if (access("/.backup/init.xz", F_OK) == 0)
        unxz_init("/.backup/init.xz", "/.backup/init");
    return "/.backup/init";
}
