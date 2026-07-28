/**
 * SELinux policy loading and compilation.
 * Loads precompiled policies from vendor/ODM, validates with SHA256 checksums,
 * or compiles CIL source files on-the-fly using libcil. Writes policy
 * binary to selinuxfs via memory-buffered output to avoid partial writes.
 */
#include "include/sepolicy.hpp"

#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cil/cil.h>

#include <base.hpp>
#include <flags.h>

using namespace std;

#define SHALEN 64

/**
 * Compare the SHA256 hash of two files by reading their first 64 bytes.
 * Both files are expected to contain the hex SHA256 hash as their content.
 * @return true if both files exist and have matching hashes
 */
static bool cmp_sha256(const char *a, const char *b) {
    char id_a[SHALEN] = {0};
    char id_b[SHALEN] = {0};

    // Read first 64 bytes (SHA256 hex digest) from file a
    if (int fd = xopen(a, O_RDONLY | O_CLOEXEC); fd >= 0) {
        xread(fd, id_a, SHALEN);
        close(fd);
    } else {
        return false;
    }

    // Read first 64 bytes from file b
    if (int fd = xopen(b, O_RDONLY | O_CLOEXEC); fd >= 0) {
        xread(fd, id_b, SHALEN);
        close(fd);
    } else {
        return false;
    }
    LOGD("%s=[%.*s]\n", a, SHALEN, id_a);
    LOGD("%s=[%.*s]\n", b, SHALEN, id_b);
    return memcmp(id_a, id_b, SHALEN) == 0;
}

/**
 * Verify that a precompiled policy file is still valid by checking
 * SHA256 hashes against all available partition mapping files.
 *
 * Checks plat_and_mapping, plat_sepolicy_and_mapping, product,
 * and system_ext mapping SHA files. All existing ones must match.
 * @return true if all checks pass (at least one must exist)
 */
static bool check_precompiled(const char *precompiled) {
    bool ok = false;
    const char *actual_sha;
    char compiled_sha[128];

    // Check plat_and_mapping_sepolicy.cil.sha256
    actual_sha = PLAT_POLICY_DIR "plat_and_mapping_sepolicy.cil.sha256";
    if (access(actual_sha, R_OK) == 0) {
        ok = true;
        sprintf(compiled_sha, "%s.plat_and_mapping.sha256", precompiled);
        if (!cmp_sha256(actual_sha, compiled_sha))
            return false;
    }

    // Check plat_sepolicy_and_mapping.sha256
    actual_sha = PLAT_POLICY_DIR "plat_sepolicy_and_mapping.sha256";
    if (access(actual_sha, R_OK) == 0) {
        ok = true;
        sprintf(compiled_sha, "%s.plat_sepolicy_and_mapping.sha256", precompiled);
        if (!cmp_sha256(actual_sha, compiled_sha))
            return false;
    }

    // Check product_sepolicy_and_mapping.sha256
    actual_sha = PROD_POLICY_DIR "product_sepolicy_and_mapping.sha256";
    if (access(actual_sha, R_OK) == 0) {
        ok = true;
        sprintf(compiled_sha, "%s.product_sepolicy_and_mapping.sha256", precompiled);
        if (!cmp_sha256(actual_sha, compiled_sha) != 0)
            return false;
    }

    // Check system_ext_sepolicy_and_mapping.sha256
    actual_sha = SYSEXT_POLICY_DIR "system_ext_sepolicy_and_mapping.sha256";
    if (access(actual_sha, R_OK) == 0) {
        ok = true;
        sprintf(compiled_sha, "%s.system_ext_sepolicy_and_mapping.sha256", precompiled);
        if (!cmp_sha256(actual_sha, compiled_sha) != 0)
            return false;
    }

    return ok;
}

/**
 * Load a CIL source file into a cil_db for compilation.
 * Uses mmap for efficient file loading.
 */
static void load_cil(struct cil_db *db, const char *file) {
    mmap_data d(file);
    cil_add_file(db, file, (const char *) d.data(), d.size());
    LOGD("cil_add [%s]\n", file);
}

/**
 * Load a policy binary from an in-memory byte buffer.
 * Uses PF_USE_MEMORY to read directly from the buffer without file I/O.
 * @return SePolicy wrapping the parsed policydb, or an empty SePolicy on failure
 */
SePolicy SePolicy::from_data(rust::Slice<const uint8_t> data) noexcept {
    LOGD("Load policy from data\n");

    policy_file_t pf;
    policy_file_init(&pf);
    // Point the policy file reader directly at our memory buffer
    pf.data = (char *) data.data();
    pf.len = data.size();
    pf.type = PF_USE_MEMORY;

    auto db = static_cast<policydb_t *>(malloc(sizeof(policydb_t)));
    if (policydb_init(db) || policydb_read(db, &pf, 0)) {
        LOGE("Fail to load policy from data\n");
        free(db);
        return {};
    }

    return {std::make_unique<sepol_impl>(db)};
}

/**
 * Load a policy binary from a file on disk using stdio.
 * @return SePolicy wrapping the parsed policydb, or an empty SePolicy on failure
 */
SePolicy SePolicy::from_file(::Utf8CStr file) noexcept {
    LOGD("Load policy from: %.*s\n", static_cast<int>(file.size()), file.data());

    policy_file_t pf;
    policy_file_init(&pf);
    auto fp = xopen_file(file.data(), "re");
    pf.fp = fp.get();
    pf.type = PF_USE_STDIO;

    auto db = static_cast<policydb_t *>(malloc(sizeof(policydb_t)));
    if (policydb_init(db) || policydb_read(db, &pf, 0)) {
        LOGE("Fail to load policy from %.*s\n", static_cast<int>(file.size()), file.data());
        free(db);
        return {};
    }

    return {std::make_unique<sepol_impl>(db)};
}

/**
 * Compile CIL source files from all partitions (plat, system_ext, product,
 * vendor, odm) into a single policydb binary.
 *
 * Loads mapping version files, platform CIL, mapping CIL, compat CIL,
 * and per-partition sepolicy CIL files. Compiles with libcil then
 * builds the final policydb.
 * @return SePolicy wrapping the compiled policydb, or empty on failure
 */
SePolicy SePolicy::compile_split() noexcept {
    char path[128], plat_ver[10];
    cil_db_t *db = nullptr;
    sepol_policydb_t *pdb = nullptr;
    FILE *f;
    int policy_ver;
    const char *cil_file;

#if MAGISK_DEBUG
    // Enable CIL info-level logging in debug builds
    cil_set_log_level(CIL_INFO);
#endif

    // Route libcil log messages through Magisk's logging system
    cil_set_log_handler(+[](int lvl, const char *msg) {
        if (lvl == CIL_ERR) {
            LOGE("cil: %s", msg);
        } else if (lvl == CIL_WARN) {
            LOGW("cil: %s", msg);
        } else if (lvl == CIL_INFO) {
            LOGI("cil: %s", msg);
        } else {
            LOGD("cil: %s", msg);
        }
    });

    cil_db_init(&db);
    run_finally fin([db_ptr = &db]{ cil_db_destroy(db_ptr); });

    // Configure the CIL database
    cil_set_mls(db, 1);                        // Enable MLS (Multi-Level Security)
    cil_set_multiple_decls(db, 1);             // Allow multiple declarations
    cil_set_disable_neverallow(db, 1);         // Disable neverallow checking (Magisk needs this)
    cil_set_target_platform(db, SEPOL_TARGET_SELINUX);
    cil_set_attrs_expand_generated(db, 1);

    // Read kernel's expected policy version from selinuxfs
    f = xfopen(SELINUX_VERSION, "re");
    fscanf(f, "%d", &policy_ver);
    fclose(f);
    cil_set_policy_version(db, policy_ver);

    // Read platform_sepolicy version for mapping file selection
    f = xfopen(VEND_POLICY_DIR "plat_sepolicy_vers.txt", "re");
    fscanf(f, "%s", plat_ver);
    fclose(f);

    // === Platform (system) CIL files ===
    load_cil(db, SPLIT_PLAT_CIL);

    sprintf(path, PLAT_POLICY_DIR "mapping/%s.cil", plat_ver);
    load_cil(db, path);

    sprintf(path, PLAT_POLICY_DIR "mapping/%s.compat.cil", plat_ver);
    if (access(path, R_OK) == 0)
        load_cil(db, path);

    // === system_ext CIL files ===
    sprintf(path, SYSEXT_POLICY_DIR "mapping/%s.cil", plat_ver);
    if (access(path, R_OK) == 0)
        load_cil(db, path);

    sprintf(path, SYSEXT_POLICY_DIR "mapping/%s.compat.cil", plat_ver);
    if (access(path, R_OK) == 0)
        load_cil(db, path);

    cil_file = SYSEXT_POLICY_DIR "system_ext_sepolicy.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    // === Product CIL files ===
    sprintf(path, PROD_POLICY_DIR "mapping/%s.cil", plat_ver);
    if (access(path, R_OK) == 0)
        load_cil(db, path);

    cil_file = PROD_POLICY_DIR "product_sepolicy.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    // === Vendor CIL files ===
    cil_file = VEND_POLICY_DIR "nonplat_sepolicy.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    cil_file = VEND_POLICY_DIR "plat_pub_versioned.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    cil_file = VEND_POLICY_DIR "vendor_sepolicy.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    // === ODM CIL files ===
    cil_file = ODM_POLICY_DIR "odm_sepolicy.cil";
    if (access(cil_file, R_OK) == 0)
        load_cil(db, cil_file);

    // Compile CIL and build the policydb
    if (cil_compile(db))
        return {};
    if (cil_build_policydb(db, &pdb))
        return {};
    return {std::make_unique<sepol_impl>(&pdb->p)};
}

/**
 * Load the split policy by first trying precompiled policy from ODM,
 * then vendor, falling back to compiling from CIL sources.
 * Each precompiled candidate is validated via SHA256.
 */
SePolicy SePolicy::from_split() noexcept {
    const char *odm_pre = ODM_POLICY_DIR "precompiled_sepolicy";
    const char *vend_pre = VEND_POLICY_DIR "precompiled_sepolicy";
    if (access(odm_pre, R_OK) == 0 && check_precompiled(odm_pre))
        return SePolicy::from_file(odm_pre);
    else if (access(vend_pre, R_OK) == 0 && check_precompiled(vend_pre))
        return SePolicy::from_file(vend_pre);
    else
        return SePolicy::compile_split();
}

/** Destructor: destroy the policydb and free its memory */
sepol_impl::~sepol_impl() {
    policydb_destroy(db);
    free(db);
}

/**
 * Callback for funopen: appends data to a vector<char> buffer.
 * Used by to_file to buffer the serialized policy in memory before
 * writing to selinuxfs (avoiding partial writes to the kernel).
 */
static int vec_write(void *v, const char *buf, int len) {
    auto vec = static_cast<vector<char> *>(v);
    vec->insert(vec->end(), buf, buf + len);
    return len;
}

/**
 * Serialize the policydb to a binary file.
 *
 * First dumps the entire policy into an in-memory buffer (via funopen
 * and vec_write), then writes it atomically to the output file.
 *
 * No partial writes are allowed to /sys/fs/selinux/load, thus the
 * reason why we first dump everything into memory, then directly
 * call write system call.
 *
 * @return true on success
 */
bool SePolicy::to_file(::Utf8CStr file) const noexcept {
    vector<char> out;

    // Use funopen with vec_write callback to serialize into memory
    FILE *fp = funopen(&out, nullptr, vec_write, nullptr, nullptr);
    setbuf(fp, nullptr); // Disable stdio buffering since we're writing to memory

    policy_file_t pf;
    policy_file_init(&pf);
    pf.type = PF_USE_STDIO;
    pf.fp = fp;
    if (policydb_write(impl->db, &pf)) {
        LOGE("Fail to create policy image\n");
        fclose(fp);
        return false;
    }
    fclose(fp);

    // Atomically write the complete buffer to the destination file
    int fd = xopen(file.data(), O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0)
        return false;

    // Truncate existing file content before writing
    if (struct stat st{}; xfstat(fd, &st) == 0 && st.st_size > 0) {
        ftruncate(fd, 0);
    }
    xwrite(fd, out.data(), out.size());

    close(fd);
    return true;
}
