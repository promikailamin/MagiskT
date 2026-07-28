#include <unistd.h>
#include <sched.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <cstdint>
#include <string>
#include <vector>
#include <set>
#include <unordered_map>

#include <sys/mman.h>

#include <lsplt.hpp>

#include <base.hpp>

#include "zygisk.hpp"
#include "module.hpp"

using namespace std;

// ── Mountinfo parser (minimal, single-use) ──────────────────────────────────

struct MountEntry {
    int id;
    int parent;
    string root;
    string mount_point;
    string fs_type;
    string source;
    unordered_map<string, string> options;
};

static vector<MountEntry> parse_mountinfo() {
    vector<MountEntry> entries;
    FILE *fp = fopen("/proc/self/mountinfo", "re");
    if (!fp) return entries;

    char *line = nullptr;
    size_t len = 0;
    while (getline(&line, &len, fp) > 0) {
        int id, parent, major, minor;
        char root[4096], mount_point[4096], opts[4096], fs_type[256], source[256], super_opts[4096];
        int n = sscanf(line, "%d %d %d:%d %4095s %4095s %4095s %*s %255s %255s %4095s",
                       &id, &parent, &major, &minor, root, mount_point, opts, fs_type, source, super_opts);
        if (n >= 10) {
            MountEntry e;
            e.id = id;
            e.parent = parent;
            e.root = root;
            e.mount_point = mount_point;
            e.fs_type = fs_type;
            e.source = source;

            char *save = nullptr;
            char *tok = strtok_r(opts, ",", &save);
            while (tok) {
                char *eq = strchr(tok, '=');
                if (eq) {
                    *eq = '\0';
                    e.options[tok] = eq + 1;
                }
                tok = strtok_r(nullptr, ",", &save);
            }
            entries.push_back(std::move(e));
        }
    }
    free(line);
    fclose(fp);
    return entries;
}

// ── Unmount Magisk/KSU/APatch-related mounts ────────────────────────────────

static const set<string> mountdir_list = {"/data/adb", "/debug_ramdisk"};
static const set<string> fsname_list = {"KSU", "APatch", "magisk", "worker"};

static void doUnmount() {
    auto entries = parse_mountinfo();
    // Unmount in reverse order (children first)
    for (auto it = entries.rbegin(); it != entries.rend(); ++it) {
        const auto &mp = it->mount_point;
        const auto &root = it->root;
        const auto &type = it->fs_type;
        const auto &src = it->source;
        bool should = false;

        for (const auto &dir : mountdir_list) {
            if (root.starts_with(dir) || mp.starts_with(dir)) {
                should = true;
                break;
            }
            if (type == "overlay") {
                auto it_lower = it->options.find("lowerdir");
                auto it_upper = it->options.find("upperdir");
                auto it_work = it->options.find("workdir");
                if ((it_lower != it->options.end() && it_lower->second.starts_with(dir)) ||
                    (it_upper != it->options.end() && it_upper->second.starts_with(dir)) ||
                    (it_work != it->options.end() && it_work->second.starts_with(dir))) {
                    should = true;
                    break;
                }
            }
        }
        if (!should && (type == "overlay" || type == "tmpfs") && fsname_list.count(src))
            should = true;

        if (should) {
            if (umount2(mp.c_str(), MNT_DETACH) == 0)
                LOGD("hideroot: unmounted %s\n", mp.c_str());
            else
                LOGW("hideroot: umount2(%s) failed: %s\n", mp.c_str(), strerror(errno));
        }
    }
}

// ── Remount /data with correct errors= behavior ─────────────────────────────

static void doRemount() {
    for (const auto &m : parse_mountinfo()) {
        if (m.mount_point != "/data") continue;

        // Read ext4 superblock to get errors behavior
        int fd = open(m.source.c_str(), O_RDONLY);
        if (fd < 0) break;
        unsigned char buf[8] = {};
        if (pread(fd, buf, 8, 0x400 + 0x38) != 8) { close(fd); break; }
        close(fd);

        uint16_t magic = buf[0] | (uint16_t)buf[1] << 8;
        if (magic != 0xEF53) break;

        uint16_t sb_errors_val = buf[4] | (uint16_t)buf[5] << 8;
        const char *sb_errors;
        switch (sb_errors_val) {
            case 1: sb_errors = "continue"; break;
            case 2: sb_errors = "remount-ro"; break;
            case 3: sb_errors = "panic"; break;
            default: sb_errors = nullptr;
        }
        if (!sb_errors) break;

        auto it = m.options.find("errors");
        if (it != m.options.end() && it->second == sb_errors) break;

        // Re-mount with correct errors=
        unsigned long flags = MS_REMOUNT;
        static const unordered_map<string, unsigned long> flag_map = {
            {"nosuid", MS_NOSUID}, {"nodev", MS_NODEV}, {"noexec", MS_NOEXEC},
            {"noatime", MS_NOATIME}, {"nodiratime", MS_NODIRATIME},
            {"relatime", MS_RELATIME}, {"nosymfollow", MS_NOSYMFOLLOW},
        };
        for (const auto &[k, v] : m.options) {
            auto itf = flag_map.find(k);
            if (itf != flag_map.end()) flags |= itf->second;
        }

        string data_opt = "errors=" + string(sb_errors);
        if (::mount(nullptr, "/data", nullptr, flags, data_opt.c_str()) == 0)
            LOGD("hideroot: remount /data with %s\n", data_opt.c_str());
        break;
    }
}

// ── Hide Zygisk by resetting libnativebridge.so .bss had_error ──────────────

static void doHideZygisk() {
    uintptr_t bss_start = 0, bss_end = 0;
    for (auto &map : lsplt::MapInfo::Scan()) {
        if (map.path.ends_with("/libnativebridge.so") && (map.perms & PROT_WRITE)) {
            bss_start = map.start;
            bss_end = map.end;
            break;
        }
    }
    if (bss_start == 0) return;

    // Scan for the 0x01 byte (had_error flag) in the writable section
    auto *addr = reinterpret_cast<uint8_t *>(bss_start);
    size_t size = bss_end - bss_start;
    uint8_t *found = static_cast<uint8_t *>(memchr(addr, 0x01, size));
    if (found) {
        *found = 0;
        LOGD("hideroot: libnativebridge.so had_error reset\n");
    }
}

// ── Reset modified ro.* properties ──────────────────────────────────────────

#include <api/system_properties.h>
#include <system_properties/prop_info.h>

static int g_mrprop_count = 0;

static void mrprop_callback(const prop_info *pi, void *) {
    if (strncmp(pi->name, "ro.", 3) != 0 || pi->is_long())
        return;
    uint_least32_t serial = load_const_atomic(&pi->serial, memory_order_relaxed);
    if ((serial & 0xFFFFFF) == 0) {
        size_t len = strnlen(pi->value, PROP_VALUE_MAX);
        for (size_t i = len; i < PROP_VALUE_MAX; i++) {
            if (pi->value[i] != '\0')
                goto reset;
        }
        return;
    }
reset:
    char buffer[PROP_VALUE_MAX];
    strncpy(buffer, pi->value, PROP_VALUE_MAX - 1);
    buffer[PROP_VALUE_MAX - 1] = '\0';
    size_t length = strnlen(buffer, PROP_VALUE_MAX);
    __system_property_update(const_cast<prop_info *>(pi), buffer, length);
    g_mrprop_count++;
}

static void doMrProp() {
    g_mrprop_count = 0;
    if (__system_properties_init() == -1) {
        LOGE("hideroot: __system_properties_init failed\n");
        return;
    }
    __system_property_foreach(mrprop_callback, nullptr);
    LOGD("hideroot: mrprop reset %d properties\n", g_mrprop_count);
}

// ── Main entry: called from app_specialize_pre() ────────────────────────────

void exec_hideroot() {
    LOGI("hideroot: starting operations\n");

    // Create a new mount namespace
    if (unshare(CLONE_NEWNS) != 0) {
        LOGE("hideroot: unshare(CLONE_NEWNS) failed: %s\n", strerror(errno));
        return;
    }
    // Make root slave so mounts propagate from parent
    if (mount("rootfs", "/", nullptr, MS_SLAVE | MS_REC, nullptr) != 0) {
        LOGE("hideroot: mount MS_SLAVE failed: %s\n", strerror(errno));
    }

    // Fork a child to do the heavy lifting
    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        LOGD("hideroot: child process running\n");
        doUnmount();
        doRemount();
        doHideZygisk();
        doMrProp();
        _exit(0);
    } else if (pid > 0) {
        // Parent: wait for child
        int status = 0;
        waitpid(pid, &status, 0);
        LOGD("hideroot: child exited with status %d\n", WEXITSTATUS(status));
    } else {
        LOGE("hideroot: fork failed: %s\n", strerror(errno));
    }
}
