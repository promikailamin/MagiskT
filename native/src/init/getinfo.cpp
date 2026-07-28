/**
 * Boot configuration parser for magiskinit.
 *
 * Parses boot-time configuration from multiple sources:
 * - /proc/cmdline (kernel command line)
 * - /proc/bootconfig (newer Android bootconfig mechanism)
 * - Device tree (DT) files under /proc/device-tree
 * - /.backup/.magisk (Magisk config file)
 *
 * Extracts boot parameters: A/B slot, skip_initramfs (SAR mode),
 * force_normal_boot, rootwait, hardware platform, fstab suffix,
 * partition map, emulator detection, and more.
 *
 * Also handles volume-up key combo detection for recovery mode decisions
 * and Amlogic slot_suffix sanity checks.
 */
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <linux/input.h>
#include <fcntl.h>
#include <vector>

#include <base.hpp>

#include "init.hpp"

using namespace std;

template<char... cs> using chars = integer_sequence<char, cs...>;

/**
 * Extract a quoted or unquoted string from a key-value format.
 *
 * When inside quotes ("), parsing stops on any character in `breaks`.
 * When outside quotes, parsing stops on any character in `breaks` + `escapes`.
 * The `"` character is always a quote toggle; it is consumed but not appended
 * to the result.
 *
 * @tparam escapes Characters that terminate unquoted values
 * @tparam breaks  Characters that terminate both quoted and unquoted values
 * @param str  Input string view
 * @param pos  Current position (updated on return)
 * @param quoted Quote state flag (toggled when '"' is encountered)
 * @return Extracted substring
 */
template<char... escapes, char... breaks>
static string extract_quoted_str_until(chars<escapes...>, chars<breaks...>,
        string_view str, size_t &pos, bool &quoted) {
    string result;
    char match_array[] = {escapes..., breaks..., '"'};
    string_view match(match_array, std::size(match_array));
    for (size_t cur = pos;; ++cur) {
        cur = str.find_first_of(match, cur);
        if (cur == string_view::npos ||
            ((str[cur] == breaks) || ...) ||
            (!quoted && ((str[cur] == escapes) || ...))) {
            result.append(str.substr(pos, cur - pos));
            pos = cur;
            return result;
        }
        // Handle quote toggle — include everything between quotes
        if (str[cur] == '"') {
            quoted = !quoted;
            result.append(str.substr(pos, cur - pos));
            pos = cur + 1;
        }
    }
}

/**
 * Generic key-value string parser.
 *
 * Format: [delim][key][padding][eq][padding][value][delim]
 *
 * Supports:
 * - Quoted values (handles nested delimiters inside quotes)
 * - Padding characters that can appear around '='
 * - Key-only entries (no value, implied boolean)
 *
 * @tparam delim   Character separating entries (e.g. ' ' for cmdline, '\n' for bootconfig)
 * @tparam eq      Assignment character (typically '=')
 * @tparam padding Characters allowed around '=' (e.g. ' ' for bootconfig)
 * @return Vector of key-value pairs
 */
template<char delim, char eq, char... padding>
static kv_pairs parse_impl(chars<padding...>, string_view str) {
    kv_pairs kv;
    char skip_array[] = {eq, padding...};
    string_view skip(skip_array, std::size(skip_array));
    bool quoted = false;
    for (size_t pos = 0u; pos < str.size(); pos = str.find_first_not_of(delim, pos)) {
        auto key = extract_quoted_str_until(
                chars<padding..., delim>{}, chars<eq>{}, str, pos, quoted);
        // Skip '=' and any padding to get to the value
        pos = str.find_first_not_of(skip, pos);
        if (pos == string_view::npos || str[pos] == delim) {
            // Key with no value (boolean flag like "skip_initramfs")
            kv.emplace_back(key, "");
            continue;
        }
        auto value = extract_quoted_str_until(chars<delim>{}, chars<>{}, str, pos, quoted);
        kv.emplace_back(key, value);
    }
    return kv;
}

/// Parse /proc/cmdline (space-delimited, '=' separated, no padding).
static kv_pairs parse_cmdline(string_view str) {
    return parse_impl<' ', '='>(chars<>{}, str);
}

/// Parse /proc/bootconfig (newline-delimited, '=' separated, space padding allowed).
static kv_pairs parse_bootconfig(string_view str) {
    return parse_impl<'\n', '='>(chars<' '>{}, str);
}

/// Parse androidboot.partition_map (semicolon-delimited entries, comma-separated key=value).
static kv_pairs parse_partition_map(std::string_view str) {
    return parse_impl<';', ','>(chars<>{}, str);
}

/// Test if a specific EV_KEY bit is set in a bitmask array.
#define test_bit(bit, array) (array[bit / 8] & (1 << (bit % 8)))

/**
 * Check if the volume-up key combo is held during boot.
 *
 * Scans all input event devices (minor 64-95 = /dev/input/event*) for
 * KEY_VOLUMEUP capability, then polls them for up to 5 seconds.
 * If volume-up is held for ≥300ms out of 500 consecutive 10ms polls,
 * returns true — indicating the user wants to force normal boot mode
 * (disable system-as-root for recovery).
 *
 * @return true if volume-up was held sufficiently
 */
static bool check_key_combo() {
    LOGD("Running in recovery mode, waiting for key...\n");
    uint8_t bitmask[(KEY_MAX + 1) / 8];
    vector<int> events;
    constexpr const char *name = "/event";

    // Iterate over possible input event minors (13:64 to 13:95)
    for (int minor = 64; minor < 96; ++minor) {
        if (xmknod(name, S_IFCHR | 0444, makedev(13, minor)))
            continue;
        int fd = open(name, O_RDONLY | O_CLOEXEC);
        unlink(name);
        if (fd < 0)
            continue;
        memset(bitmask, 0, sizeof(bitmask));
        // Check if this device supports KEY_VOLUMEUP
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bitmask)), bitmask);
        if (test_bit(KEY_VOLUMEUP, bitmask))
            events.push_back(fd);
        else
            close(fd);
    }
    if (events.empty())
        return false;

    run_finally fin([&] { for_each(events.begin(), events.end(), close); });

    // Poll all volume-up-capable devices for ~5 seconds (500 × 10ms)
    int count = 0;
    for (int i = 0; i < 500; ++i) {
        for (const int &fd : events) {
            memset(bitmask, 0, sizeof(bitmask));
            ioctl(fd, EVIOCGKEY(sizeof(bitmask)), bitmask);
            if (test_bit(KEY_VOLUMEUP, bitmask)) {
                count++;
                break;
            }
        }
        // Require ≥300 polls (3s of held key) to confirm
        if (count >= 300) {
            LOGD("KEY_VOLUMEUP detected: disable system-as-root\n");
            return true;
        }
        usleep(10000);
    }
    return false;
}

/**
 * Apply a parsed key-value configuration to this BootConfig.
 *
 * Recognized keys (from kernel cmdline or /proc/bootconfig):
 * - androidboot.slot_suffix / androidboot.slot  → A/B slot
 * - skip_initramfs                              → legacy SAR flag
 * - androidboot.force_normal_boot               → force normal (not recovery) boot
 * - rootwait                                    → wait indefinitely for root device
 * - androidboot.android_dt_dir                  → device tree directory override
 * - androidboot.hardware / .hardware.platform   → hardware identifiers
 * - androidboot.fstab_suffix                    → fstab variant
 * - androidboot.mode                            → boot mode
 * - qemu                                        → emulator detection
 * - androidboot.partition_map                   → partition name mapping
 */
void BootConfig::set(const kv_pairs &kv) noexcept {
    for (const auto &[key, value] : kv) {
        if (key == "androidboot.slot_suffix") {
            // Amlogic devices are A-only but may incorrectly set slot_suffix="normal"
            if (value == "normal") {
                LOGW("Skip invalid androidboot.slot_suffix=[normal]\n");
                continue;
            }
            strscpy(slot.data(), value.data(), slot.size());
        } else if (key == "androidboot.slot") {
            // androidboot.slot sets just the letter (e.g. "a"); prepend '_'
            slot[0] = '_';
            strscpy(slot.data() + 1, value.data(), slot.size() - 1);
        } else if (key == "skip_initramfs") {
            skip_initramfs = true;
        } else if (key == "androidboot.force_normal_boot") {
            force_normal_boot = !value.empty() && value[0] == '1';
        } else if (key == "rootwait") {
            rootwait = true;
        } else if (key == "androidboot.android_dt_dir") {
            strscpy(dt_dir.data(), value.data(), dt_dir.size());
        } else if (key == "androidboot.hardware") {
            strscpy(hardware.data(), value.data(), hardware.size());
        } else if (key == "androidboot.hardware.platform") {
            strscpy(hardware_plat.data(), value.data(), hardware_plat.size());
        } else if (key == "androidboot.fstab_suffix") {
            strscpy(fstab_suffix.data(), value.data(), fstab_suffix.size());
        } else if (key == "androidboot.mode") {
            strscpy(boot_mode.data(), value.data(), boot_mode.size());
        } else if (key == "qemu") {
            emulator = true;
        } else if (key == "androidboot.partition_map") {
            // partition_map maps raw block device names to logical partitions.
            // Format: "vdb,metadata;vdc,userdata" → vdb→metadata, vdc→userdata.
            // See: https://android.googlesource.com/platform/system/core/+/refs/heads/android13-release/init/devices.cpp#191
            for (const auto &[k, v]: parse_partition_map(value)) {
                partition_map.emplace_back(k, v);
            }
        }
    }
}

/**
 * Macro to read a device-tree property file and store its value.
 * Strips trailing newline from the file content.
 * @param name DT property filename
 * @param key  Target array to copy value into
 */
#define read_dt(name, key)                                          \
ssprintf(file_name, sizeof(file_name), "%s/" name, dt_dir.data());  \
if (access(file_name, R_OK) == 0) {                                 \
    string data = full_read(file_name);                             \
    if (!data.empty()) {                                            \
        data.pop_back();                                            \
        strscpy(key.data(), data.data(), key.size());               \
    }                                                               \
}

/**
 * Initialize BootConfig from all available boot-time sources.
 *
 * Order of parsing:
 * 1. /proc/cmdline       (kernel command-line parameters)
 * 2. /proc/bootconfig    (new Android bootconfig format)
 * 3. /.backup/.magisk    (Magisk config — handles RECOVERYMODE)
 * 4. Device tree files   (fstab_suffix, hardware, hardware.platform)
 */
void BootConfig::init() noexcept {
    set(parse_cmdline(full_read("/proc/cmdline")));
    set(parse_bootconfig(full_read("/proc/bootconfig")));

    // Check Magisk config for forced recovery mode
    parse_prop_file("/.backup/.magisk", [&](auto key, auto value) -> bool {
        if (key == "RECOVERYMODE" && value == "true") {
            // On emulators, skip; otherwise wait for key combo
            skip_initramfs = emulator || !check_key_combo();
            return false;
        }
        return true;
    });

    // Default DT directory if not specified in cmdline
    if (dt_dir[0] == '\0')
        strscpy(dt_dir.data(), DEFAULT_DT_DIR, dt_dir.size());

    // Read device-tree properties to override cmdline values
    char file_name[128];
    read_dt("fstab_suffix", fstab_suffix)
    read_dt("hardware", hardware)
    read_dt("hardware.platform", hardware_plat)

    LOGD("Device config:\n");
    print();
}
