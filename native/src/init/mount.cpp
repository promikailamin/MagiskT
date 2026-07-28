/**
 * Mount operations for magiskinit.
 *
 * This file handles all filesystem mounting during the init replacement phase:
 * - Enumerates block devices from /sys/dev/block
 * - Finds partitions by name (system, vendor, preinit, etc.)
 * - Mounts the preinit data directory from a dedicated partition
 * - Mounts the system root partition (system/vroot/APP) for system-as-root
 * - Sets up a tmpfs work directory at the magisk tmp location
 * - Handles AVD (Android Virtual Device) emulator hacks for legacy SAR
 *
 * The mount_list vector tracks all mounts so magiskd can clean up later.
 */
#include <set>
#include <sys/mount.h>
#include <sys/sysmacros.h>
#include <libgen.h>

#include <base.hpp>
#include <consts.hpp>

#include "init.hpp"

using namespace std;

/** Metadata for a single block device, collected from sysfs uevent + dm info. */
struct devinfo {
    int major;                  ///< Device major number
    int minor;                  ///< Device minor number
    char devname[32];           ///< Kernel device name (e.g. "sda1")
    char partname[32];          ///< Partition name from uevent PARTNAME
    char dmname[32];            ///< Device-mapper name (if any), e.g. "system"
    char devpath[PATH_MAX];     ///< Full /dev path resolved via realpath
};

static vector<devinfo> dev_list;

/**
 * True when running on a legacy system-as-root AVD (Android Virtual Device)
 * emulator (API 28). Triggers special hacks in mount_system_root and
 * patch_ro_root to work around missing two-stage init.
 */
bool avd_hack = false;

/**
 * Parse a single uevent file from /sys/dev/block/<major:minor>/uevent
 * to extract the device's major, minor, kernel devname, and partition name.
 * Fields not present in the uevent file remain as "\0".
 */
static void parse_device(devinfo *dev, const char *uevent) {
    dev->partname[0] = '\0';
    dev->devpath[0] = '\0';
    dev->dmname[0] = '\0';
    dev->devname[0] = '\0';
    parse_prop_file(uevent, [=](Utf8CStr key, Utf8CStr value) -> bool {
        if (key == "MAJOR")
            dev->major = parse_int(value);
        else if (key == "MINOR")
            dev->minor = parse_int(value);
        else if (key == "DEVNAME")
            strscpy(dev->devname, value.c_str(), sizeof(dev->devname));
        else if (key == "PARTNAME")
            strscpy(dev->partname, value.c_str(), sizeof(dev->devname));

        return true;
    });
}

/**
 * Enumerate all block devices by reading /sys/dev/block.
 * For each device, reads the uevent file for major/minor/devname/partname,
 * checks for a device-mapper name, and falls back to androidboot.partition_map
 * for partition name if none was found in uevent. Populates the global dev_list.
 */
void MagiskInit::collect_devices() const noexcept {
    char path[PATH_MAX];
    devinfo dev{};
    if (auto dir = xopen_dir("/sys/dev/block"); dir) {
        for (dirent *entry; (entry = readdir(dir.get()));) {
            if (entry->d_name == "."sv || entry->d_name == ".."sv)
                continue;
            sprintf(path, "/sys/dev/block/%s/uevent", entry->d_name);
            parse_device(&dev, path);
            // Check for device-mapper name (e.g. dm-0 → "system")
            sprintf(path, "/sys/dev/block/%s/dm/name", entry->d_name);
            if (access(path, F_OK) == 0) {
                auto name = rtrim(full_read(path));
                strscpy(dev.dmname, name.data(), sizeof(dev.dmname));
            }
            // If uevent had no PARTNAME but partition_map has a mapping for
            // this devname, use the mapped name as a fallback.
            if (auto it = std::ranges::find_if(config.partition_map, [&](const auto &i) {
                return i.key == dev.devname;
            }); dev.partname[0] == '\0' && it != config.partition_map.end()) {
                strscpy(dev.partname, it->value.data(), sizeof(dev.partname));
            }
            // Resolve the full /dev/block/<major>:<minor> path
            sprintf(path, "/sys/dev/block/%s", entry->d_name);
            xrealpath(path, dev.devpath, sizeof(dev.devpath));
            dev_list.push_back(dev);
        }
    }
}

/**
 * Find a block device by partition/dm/dev name.
 * Tries up to 3 times with 10ms delays to handle devices that appear asynchronously.
 * Returns the device number (dev_t) on success, or 0 if not found.
 *
 * Matching order: partname → dmname → devname → devpath suffix.
 */
uint64_t MagiskInit::find_block(const char *partname) const noexcept {
    if (dev_list.empty())
        collect_devices();

    for (int tries = 0; tries < 3; ++tries) {
        for (auto &dev : dev_list) {
            const char *name;
            if (strcasecmp(dev.partname, partname) == 0)
                name = dev.partname;
            else if (strcasecmp(dev.dmname, partname) == 0)
                name = dev.dmname;
            else if (strcasecmp(dev.devname, partname) == 0)
                name = dev.devname;
            else if (std::string_view(dev.devpath).ends_with("/"s + partname))
                name = dev.devpath;
            else
                continue;

            LOGD("Found %s: [%s] (%d, %d)\n", name, dev.devname, dev.major, dev.minor);
            return makedev(dev.major, dev.minor);
        }
        // Some devices (e.g. dm-verity) appear asynchronously; wait and retry
        usleep(10000);
        dev_list.clear();
        collect_devices();
    }

    // The requested partname does not exist
    return 0;
}

/**
 * Mount the preinit data partition (where Magisk stores modules, config).
 *
 * Strategy:
 * 1. Look up the preinit block device by name.
 * 2. If already mounted elsewhere, bind-mount it to MIRRDIR.
 * 3. Otherwise, try ext4 → f2fs (read-only) to avoid kernel crashes from
 *    buggy drivers. magiskd will remount writable later.
 * 4. Find the preinit directory on the partition, bind-mount it to PREINITMIRR,
 *    then detach the temporary MIRRDIR mount.
 */
void MagiskInit::mount_preinit_dir() noexcept {
    if (preinit_dev.empty()) return;
    auto dev = find_block(preinit_dev.c_str());
    if (dev == 0) {
        LOGE("Cannot find preinit %s, abort!\n", preinit_dev.c_str());
        return;
    }
    xmknod(PREINITDEV, S_IFBLK | 0600, dev);
    xmkdir(MIRRDIR, 0);
    bool mounted = false;
    // Check if the device is already mounted somewhere else in the system
    std::string mnt_point;
    if (rust::is_device_mounted(dev, mnt_point)) {
        // Already mounted elsewhere, just bind mount to MIRRDIR
        xmount(mnt_point.data(), MIRRDIR, nullptr, MS_BIND, nullptr);
        mounted = true;
    }

    // Mount the block device read-only first (safer with buggy kernel drivers).
    // magiskd will later create a writable symlink at PREINITMIRR.
    if (mounted || mount(PREINITDEV, MIRRDIR, "ext4", MS_RDONLY, nullptr) == 0 ||
        mount(PREINITDEV, MIRRDIR, "f2fs", MS_RDONLY, nullptr) == 0) {
        string preinit_dir = resolve_preinit_dir(MIRRDIR);
        // Bind-mount the actual preinit data directory to the final location
        xmkdirs(PREINITMIRR, 0);
        if (access(preinit_dir.data(), F_OK)) {
            LOGW("empty preinit: %s\n", preinit_dir.data());
        } else {
            LOGD("preinit: %s\n", preinit_dir.data());
            xmount(preinit_dir.data(), PREINITMIRR, nullptr, MS_BIND, nullptr);
        }
        // Detach the temporary mirror mount — we only needed it for the bind
        xumount2(MIRRDIR, MNT_DETACH);
    } else {
        PLOGE("Mount preinit %s", preinit_dev.c_str());
        // Keep the block device node; it may be formatted later by recovery/init
    }
}

/**
 * Mount the system root partition for system-as-root (SAR) devices.
 *
 * Tries multiple partition names in order:
 *   1. "vroot" – legacy dm-verity virtual root
 *   2. "APP"   – NVIDIA Tegra naming scheme
 *   3. "system<slot>" – standard A/B system partition
 *
 * If rootwait is set in the kernel cmdline, keeps polling forever.
 * After mounting, performs switch_root to /system_root, sets up a writable
 * tmpfs at /dev, and optionally applies the AVD emulator vendor hack.
 *
 * @return true if two-stage init (Android 10+), false if legacy SAR
 */
bool MagiskInit::mount_system_root() noexcept {
    LOGD("Mounting system_root\n");

    // The stub cpio (minimal initramfs) has no /dev; create it
    xmkdir("/dev", 0777);

    dev_t dev;
    do {
        // Try legacy SAR dm-verity virtual root device
        dev = find_block("vroot");
        if (dev > 0)
            goto mount_root;

        // Try NVIDIA Tegra-specific system partition name
        dev = find_block("APP");
        if (dev > 0)
            goto mount_root;

        // Standard A/B system partition (e.g. "system_a")
        char sys_part[32];
        sprintf(sys_part, "system%s", config.slot.data());
        dev = find_block(sys_part);
        if (dev > 0)
            goto mount_root;

        // Keep polling if rootwait was specified in cmdline
    } while (config.rootwait);

    // No suitable partition found and rootwait is not set
    LOGE("Cannot find root partition, abort\n");
    exit(1);

mount_root:
    xmknod("/dev/root", S_IFBLK | 0600, dev);
    xmkdir("/system_root", 0755);

    // Try ext4 first, then erofs (used on newer devices)
    if (xmount("/dev/root", "/system_root", "ext4", MS_RDONLY, nullptr)) {
        if (xmount("/dev/root", "/system_root", "erofs", MS_RDONLY, nullptr)) {
            LOGE("Cannot mount root partition, abort\n");
            exit(1);
        }
    }

    // Switch root so /system_root becomes the new /
    rust::switch_root("/system_root");

    // Replace the temporary /dev with a writable tmpfs
    xmount("tmpfs", "/dev", "tmpfs", 0, "mode=755");
    mount_list.emplace_back("/dev");

    // Detect two-stage init: /system/bin/init exists only on
    // Android 10+ where the real init is inside system.
    bool is_two_stage = access("/system/bin/init", F_OK) == 0;
    LOGD("is_two_stage: [%d]\n", is_two_stage);

    // API 28 AVD emulators use legacy SAR without two-stage init.
    // We need to manually mount vendor so the emulator can boot.
    if (!is_two_stage && config.emulator) {
        avd_hack = true;
        auto vendor_dev = find_block("vendor");
        xmkdir("/dev/block", 0755);
        xmknod("/dev/block/vde1", S_IFBLK | 0600, vendor_dev);
        xmount("/dev/block/vde1", "/vendor", "ext4", MS_RDONLY, nullptr);
    }

    return is_two_stage;
}

/**
 * Set up the Magisk temporary directory (tmpfs) at the given path.
 *
 * Operations:
 * - Creates internal directories (INTLROOT, DEVICEDIR, WORKERDIR)
 * - Mounts the preinit data partition
 * - Copies .backup/.magisk config to MAIN_CONFIG, removes backup
 * - Creates applet symlinks (magisk → magisk, magiskpolicy → supolicy)
 * - Bind-mounts the working directory to the final tmp_path
 * - Sets up a tmpfs WORKERDIR for magiskd worker processes
 * - Optionally creates an isolated devpts instance for shell functionality
 */
void MagiskInit::setup_tmp(const char *path) noexcept {
    LOGD("Setup Magisk tmp at %s\n", path);
    chdir("/data");

    xmkdir(INTLROOT, 0711);
    xmkdir(DEVICEDIR, 0711);
    xmkdir(WORKERDIR, 0);

    mount_preinit_dir();

    // Persist the Magisk config from the backup ramdisk
    cp_afc(".backup/.magisk", MAIN_CONFIG);
    rm_rf(".backup");

    // Create symlinks for each applet pointing to the magisk binary
    for (int i = 0; applet_names[i]; ++i)
        xsymlink("./magisk", applet_names[i]);
    xsymlink("./magiskpolicy", "supolicy");

    // Bind-mount the working directory to the desired tmp path
    xmount(".", path, nullptr, MS_BIND, nullptr);

    chdir(path);

    // Worker tmpfs — magiskd uses this for its own internal operations
    xmount("magisk", WORKERDIR, "tmpfs", 0, "mode=755");

    // If the kernel supports devpts newinstance, create an isolated PTY
    // namespace so that su sessions get their own PTS devices.
    if (access("/dev/pts/ptmx", F_OK) == 0) {
        xmkdirs(SHELLPTS, 0755);
        xmount("devpts", SHELLPTS, "devpts", MS_NOSUID | MS_NOEXEC, "newinstance");
        xmount(nullptr, SHELLPTS, nullptr, MS_PRIVATE, nullptr);
        // If /ptmx wasn't created inside the new instance, fall back
        if (access(SHELLPTS "/ptmx", F_OK)) {
            umount2(SHELLPTS, MNT_DETACH);
            rmdir(SHELLPTS);
        }
    }

    chdir("/");
}
