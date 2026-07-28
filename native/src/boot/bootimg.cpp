/**
 * Boot image parsing, unpacking, and repacking implementation.
 * Handles AOSP boot image headers (v0–v4), vendor boot images,
 * MediaTek/Nook/ChromeOS special headers, AVB signing, DTB extraction,
 * and zImage kernel handling.
 *
 * Core flow:
 *   1. check_fmt() — identify image/compression format via magic bytes
 *   2. boot_img constructor — memory-map, scan for formats, call parse_hdr + parse_image
 *   3. parse_hdr() — identify header version, instantiate dyn_img_hdr subclass
 *   4. parse_image() — locate all component blocks (kernel, ramdisk, ...), detect embedded DTB, AVB, etc.
 *   5. unpack() — decompress and dump each component to individual files
 *   6. repack() — reread modified component files, recompress, reassemble, patch header
 */
#include <bit>
#include <functional>
#include <memory>
#include <span>

#include <base.hpp>

#include "boot-rs.hpp"
#include "bootimg.hpp"
#include "magiskboot.hpp"

using namespace std;

/** Padding width for header field labels in print() output. */
#define PADDING 15
#define SHA256_DIGEST_SIZE 32
#define SHA_DIGEST_SIZE 20

#define RETURN_OK       0
#define RETURN_ERROR    1
#define RETURN_CHROMEOS 2
#define RETURN_VENDOR   3

/** Decompress a buffer and write the result to a file descriptor. */
static void decompress(FileFormat type, int fd, const void *in, size_t size) {
    decompress_bytes(type, byte_view { in, size }, fd);
}

/**
 * Compress a buffer and write to fd, returning the compressed size.
 * Measures byte count by comparing lseek position before/after compression.
 */
static off_t compress_len(FileFormat type, byte_view in, int fd) {
    auto prev = lseek(fd, 0, SEEK_CUR);
    compress_bytes(type, in, fd);
    auto now = lseek(fd, 0, SEEK_CUR);
    return now - prev;
}

/** Write a buffer to a new file (truncates if exists). No-op if size == 0. */
static void dump(const void *buf, size_t size, const char *filename) {
    if (size == 0)
        return;
    int fd = creat(filename, 0644);
    xwrite(fd, buf, size);
    close(fd);
}

/**
 * Append the entire contents of a file to an open fd using sendfile.
 * @return The number of bytes copied.
 */
static size_t restore(int fd, const char *filename) {
    int ifd = xopen(filename, O_RDONLY);
    size_t size = lseek(ifd, 0, SEEK_END);
    lseek(ifd, 0, SEEK_SET);
    xsendfile(fd, ifd, nullptr, size);
    close(ifd);
    return size;
}

/** Check if an environment variable is set to the literal string "true". */
static bool check_env(const char *name) {
    const char *val = getenv(name);
    return val != nullptr && val == "true"sv;
}

/**
 * Heuristic detection of LZMA compressed data from the LZMA header properties.
 * LZMA stream starts with:
 *   0     : lc/lp/pb properties byte
 *   1–4   : dictionary size (must be a power of 2)
 *   5–12  : all 0xFF (LZMA marker)
 */
static bool guess_lzma(const uint8_t *buf, size_t len) {
    if (len <= 13) return false;
    if (memcmp(buf, "\x5d", 1) != 0) return false;
    uint32_t dict_sz = 0;
    memcpy(&dict_sz, buf + 1, sizeof(dict_sz));
    if (dict_sz == 0 || (dict_sz & (dict_sz - 1)) != 0) return false;
    if (memcmp(buf + 5, "\xff\xff\xff\xff\xff\xff\xff\xff", 8) != 0) return false;
    return true;
}

/**
 * Identify the format of a buffer by matching known magic byte sequences.
 * Checks are ordered: image wrappers (ChromeOS, AOSP, vendor) first,
 * then compression formats, then special headers (MTK, DTB, DHTB, BLOB, zImage).
 */
FileFormat check_fmt(const void *buf, size_t len) {
    if (CHECKED_MATCH(CHROMEOS_MAGIC)) {
        return FileFormat::CHROMEOS;
    } else if (CHECKED_MATCH(BOOT_MAGIC)) {
        return FileFormat::AOSP;
    } else if (CHECKED_MATCH(VENDOR_BOOT_MAGIC)) {
        return FileFormat::AOSP_VENDOR;
    } else if (CHECKED_MATCH(GZIP1_MAGIC) || CHECKED_MATCH(GZIP2_MAGIC)) {
        return FileFormat::GZIP;
    } else if (CHECKED_MATCH(LZOP_MAGIC)) {
        return FileFormat::LZOP;
    } else if (CHECKED_MATCH(XZ_MAGIC)) {
        return FileFormat::XZ;
    } else if (guess_lzma(static_cast<const uint8_t *>(buf), len)) {
        return FileFormat::LZMA;
    } else if (CHECKED_MATCH(BZIP_MAGIC)) {
        return FileFormat::BZIP2;
    } else if (CHECKED_MATCH(LZ41_MAGIC) || CHECKED_MATCH(LZ42_MAGIC)) {
        return FileFormat::LZ4;
    } else if (CHECKED_MATCH(LZ4_LEG_MAGIC)) {
        return FileFormat::LZ4_LEGACY;
    } else if (CHECKED_MATCH(MTK_MAGIC)) {
        return FileFormat::MTK;
    } else if (CHECKED_MATCH(DTB_MAGIC)) {
        return FileFormat::DTB;
    } else if (CHECKED_MATCH(DHTB_MAGIC)) {
        return FileFormat::DHTB;
    } else if (CHECKED_MATCH(TEGRABLOB_MAGIC)) {
        return FileFormat::BLOB;
    } else if (len >= 0x28 && memcmp(&((char *)buf)[0x24], ZIMAGE_MAGIC, 4) == 0) {
        return FileFormat::ZIMAGE;
    } else {
        return FileFormat::UNKNOWN;
    }
}

/**
 * Print a human-readable summary of all boot image header fields to stderr.
 * Shows only fields relevant to the detected header version.
 */
void dyn_img_hdr::print() const {
    uint32_t ver = header_version();
    fprintf(stderr, "%-*s [%u]\n", PADDING, "HEADER_VER", ver);
    if (!is_vendor())
        fprintf(stderr, "%-*s [%u]\n", PADDING, "KERNEL_SZ", kernel_size());
    fprintf(stderr, "%-*s [%u]\n", PADDING, "RAMDISK_SZ", ramdisk_size());
    if (ver < 3)
        fprintf(stderr, "%-*s [%u]\n", PADDING, "SECOND_SZ", second_size());
    if (ver == 0)
        fprintf(stderr, "%-*s [%u]\n", PADDING, "EXTRA_SZ", extra_size());
    if (ver == 1 || ver == 2)
        fprintf(stderr, "%-*s [%u]\n", PADDING, "RECOV_DTBO_SZ", recovery_dtbo_size());
    if (ver == 2 || is_vendor())
        fprintf(stderr, "%-*s [%u]\n", PADDING, "DTB_SZ", dtb_size());
    if (ver == 4 && is_vendor())
        fprintf(stderr, "%-*s [%u]\n", PADDING, "BOOTCONFIG_SZ", bootconfig_size());

    // Decode os_version: 21-bit version + 11-bit patch level
    if (uint32_t os_ver = os_version()) {
        int a,b,c,y,m = 0;
        int version = os_ver >> 11;
        int patch_level = os_ver & 0x7ff;

        a = (version >> 14) & 0x7f;
        b = (version >> 7) & 0x7f;
        c = version & 0x7f;
        fprintf(stderr, "%-*s [%d.%d.%d]\n", PADDING, "OS_VERSION", a, b, c);

        y = (patch_level >> 4) + 2000;
        m = patch_level & 0xf;
        fprintf(stderr, "%-*s [%d-%02d]\n", PADDING, "OS_PATCH_LEVEL", y, m);
    }

    fprintf(stderr, "%-*s [%u]\n", PADDING, "PAGESIZE", page_size());
    if (const char *n = name()) {
        fprintf(stderr, "%-*s [%s]\n", PADDING, "NAME", n);
    }
    fprintf(stderr, "%-*s [%.*s%.*s]\n", PADDING, "CMDLINE",
            BOOT_ARGS_SIZE, cmdline(), BOOT_EXTRA_ARGS_SIZE, extra_cmdline());
    if (const char *checksum = id()) {
        fprintf(stderr, "%-*s [", PADDING, "CHECKSUM");
        for (int i = 0; i < SHA256_DIGEST_SIZE; ++i)
            fprintf(stderr, "%02hhx", checksum[i]);
        fprintf(stderr, "]\n");
    }
}

/**
 * Dump boot header fields to the "header" property file (key=value format).
 * Used to preserve name, cmdline, os_version, and os_patch_level for repack.
 */
void dyn_img_hdr::dump_hdr_file() const {
    FILE *fp = xfopen(HEADER_FILE, "w");
    if (name())
        fprintf(fp, "name=%s\n", name());
    fprintf(fp, "cmdline=%.*s%.*s\n", BOOT_ARGS_SIZE, cmdline(), BOOT_EXTRA_ARGS_SIZE, extra_cmdline());
    uint32_t ver = os_version();
    if (ver) {
        int a, b, c, y, m;
        int version, patch_level;
        version = ver >> 11;
        patch_level = ver & 0x7ff;

        a = (version >> 14) & 0x7f;
        b = (version >> 7) & 0x7f;
        c = version & 0x7f;
        fprintf(fp, "os_version=%d.%d.%d\n", a, b, c);

        y = (patch_level >> 4) + 2000;
        m = patch_level & 0xf;
        fprintf(fp, "os_patch_level=%d-%02d\n", y, m);
    }
    fclose(fp);
}

/**
 * Load boot header fields from the "header" property file (written by dump_hdr_file).
 * Parses name, cmdline (may split across cmdline+extra_cmdline),
 * os_version, and os_patch_level.
 */
void dyn_img_hdr::load_hdr_file() {
    parse_prop_file(HEADER_FILE, [=, this](Utf8CStr key, Utf8CStr value) -> bool {
        if (key == "name" && name()) {
            // name is fixed at 16 bytes; truncate if needed
            memset(name(), 0, 16);
            memcpy(name(), value.data(), value.length() > 15 ? 15 : value.length());
        } else if (key == "cmdline") {
            // cmdline + extra_cmdline are contiguous in memory; split at BOOT_ARGS_SIZE
            memset(cmdline(), 0, BOOT_ARGS_SIZE);
            memset(extra_cmdline(), 0, BOOT_EXTRA_ARGS_SIZE);
            if (value.length() > BOOT_ARGS_SIZE) {
                memcpy(cmdline(), value.data(), BOOT_ARGS_SIZE);
                auto len = std::min(value.length() - BOOT_ARGS_SIZE, (size_t) BOOT_EXTRA_ARGS_SIZE);
                memcpy(extra_cmdline(), value.data() + BOOT_ARGS_SIZE, len);
            } else {
                memcpy(cmdline(), value.data(), value.length());
            }
        } else if (key == "os_version") {
            // Preserve the patch_level bits, replace the version bits
            int patch_level = os_version() & 0x7ff;
            int a, b, c;
            sscanf(value.data(), "%d.%d.%d", &a, &b, &c);
            os_version() = (((a << 14) | (b << 7) | c) << 11) | patch_level;
        } else if (key == "os_patch_level") {
            // Preserve the version bits, replace the patch_level bits
            int os_ver = os_version() >> 11;
            int y, m;
            sscanf(value.data(), "%d-%d", &y, &m);
            y -= 2000;
            os_version() = (os_ver << 11) | (y << 4) | m;
        }
        return true;
    });
}

/**
 * Construct a boot_img by memory-mapping the given image file and scanning for
 * known boot image/header formats. The scan walks through the mapped image byte
 * by byte, looking for recognizable magic sequences:
 *
 *   - ChromeOS → set CHROMEOS_FLAG, skip 64K blocksignature
 *   - DHTB     → set DHTB + SEANDROID flags, skip DHTB header
 *   - BLOB     → set BLOB flag, skip BLOB header
 *   - AOSP / AOSP_VENDOR → call parse_image to fully decode
 *
 * If no recognized header is found, exits with RETURN_ERROR.
 */
boot_img::boot_img(const char *image) :
map(image), k_fmt(FileFormat::UNKNOWN), r_fmt(FileFormat::UNKNOWN), e_fmt(FileFormat::UNKNOWN) {
    fprintf(stderr, "Parsing boot image: [%s]\n", image);
    for (const uint8_t *addr = map.data(); addr < map.data() + map.size(); ++addr) {
        FileFormat fmt = check_fmt(addr, map.size());
        switch (fmt) {
        case FileFormat::CHROMEOS:
            // chromeos require external signing
            flags[CHROMEOS_FLAG] = true;
            addr += 65535;
            break;
        case FileFormat::DHTB:
            flags[DHTB_FLAG] = true;
            flags[SEANDROID_FLAG] = true;
            fprintf(stderr, "DHTB_HDR\n");
            addr += sizeof(dhtb_hdr) - 1;
            break;
        case FileFormat::BLOB:
            flags[BLOB_FLAG] = true;
            fprintf(stderr, "TEGRA_BLOB\n");
            addr += sizeof(blob_hdr) - 1;
            break;
        case FileFormat::AOSP:
        case FileFormat::AOSP_VENDOR:
            if (parse_image(addr, fmt))
                return;
            // fallthrough
        default:
            break;
        }
    }
    exit(RETURN_ERROR);
}

boot_img::~boot_img() {
    delete hdr;
}

/**
 * Minimal Flattened Device Tree (FDT) header for on-disk DTB detection.
 * Uses a custom fdt32_t type that byteswaps big-endian to host endianness
 * on read via operator uint32_t().
 */
struct [[gnu::packed]] fdt_header {
    /** Big-endian 32-bit integer that auto-swaps to host endianness. */
    struct fdt32_t {
        uint32_t byte0: 8;
        uint32_t byte1: 8;
        uint32_t byte2: 8;
        uint32_t byte3: 8;

        constexpr operator uint32_t() const {
            return bit_cast<uint32_t>(fdt32_t {
                .byte0 = byte3,
                .byte1 = byte2,
                .byte2 = byte1,
                .byte3 = byte0
            });
        }
    };

    /** FDT node header: tag followed by variable-length name. */
    struct node_header {
        fdt32_t tag;
        char name[0];
    };

    fdt32_t magic;             /* magic word FDT_MAGIC */
    fdt32_t totalsize;         /* total size of DT block */
    fdt32_t off_dt_struct;     /* offset to structure */
    fdt32_t off_dt_strings;    /* offset to strings */
    fdt32_t off_mem_rsvmap;    /* offset to memory reserve map */
    fdt32_t version;           /* format version */
    fdt32_t last_comp_version; /* last compatible version */

    /* version 2 fields below */
    fdt32_t boot_cpuid_phys;  /* Which physical CPU id we're booting on */
    /* version 3 fields below */
    fdt32_t size_dt_strings;  /* size of the strings block */
    /* version 17 fields below */
    fdt32_t size_dt_struct;   /* size of the structure block */
};

/**
 * Scan a buffer for a valid Flattened Device Tree (DTB) blob embedded within
 * a kernel image. Validates the FDT header with sanity checks:
 *   - totalsize must not exceed remaining buffer and must exceed minimum header
 *   - off_dt_struct must be within bounds
 *   - first node tag must be FDT_BEGIN_NODE (0x00000001)
 *
 * @return Byte offset from buf to the DTB start, or -1 if not found.
 */
static int find_dtb_offset(const uint8_t *buf, unsigned sz) {
    const uint8_t * const end = buf + sz;

    // Scan for DTB_MAGIC at fdt_header-aligned positions
    for (auto curr = buf; curr < end; curr += sizeof(fdt_header)) {
        curr = static_cast<uint8_t*>(memmem(curr, end - curr, DTB_MAGIC, sizeof(fdt_header::fdt32_t)));
        if (curr == nullptr)
            return -1;

        auto fdt_hdr = reinterpret_cast<const fdt_header *>(curr);

        // Check that fdt_header.totalsize does not overflow kernel image size or is empty dtb
        // https://github.com/torvalds/linux/commit/7b937cc243e5b1df8780a0aa743ce800df6c68d1
        uint32_t totalsize = fdt_hdr->totalsize;
        if (totalsize > end - curr || totalsize <= 0x48)
            continue;

        // Check that fdt_header.off_dt_struct does not overflow kernel image size
        uint32_t off_dt_struct = fdt_hdr->off_dt_struct;
        if (off_dt_struct > end - curr)
            continue;

        // Check that fdt_node_header.tag of first node is FDT_BEGIN_NODE
        auto fdt_node_hdr = reinterpret_cast<const fdt_header::node_header *>(curr + off_dt_struct);
        if (fdt_node_hdr->tag != 0x1u)
            continue;

        return curr - buf;
    }
    return -1;
}

/**
 * Extended format detection: if the buffer appears to be LZ4_LEGACY, scan the
 * block structure to detect LZ4_LG (large-block LZ4). LZ4_LG has a block
 * whose declared size would exceed the remaining buffer, while LZ4_LEGACY
 * always has blocks that fit exactly.
 */
static FileFormat check_fmt_lg(const uint8_t *buf, unsigned sz) {
    FileFormat fmt = check_fmt(buf, sz);
    if (fmt == FileFormat::LZ4_LEGACY) {
        // Walk LZ4 block headers: if any block claims more data than available, it's LZ4_LG
        uint32_t off = 4;
        uint32_t block_sz;
        while (off + sizeof(block_sz) <= sz) {
            memcpy(&block_sz, buf + off, sizeof(block_sz));
            off += sizeof(block_sz);
            if (off + block_sz > sz)
                return FileFormat::LZ4_LG;
            off += block_sz;
        }
    }
    return fmt;
}

#define CMD_MATCH(s) BUFFER_MATCH(h->cmdline, s)

/**
 * Parse the boot image header at addr and instantiate the appropriate
 * dyn_img_hdr subclass. Detects:
 *   - Vendor boot images (v3/v4)
 *   - Samsung PXA headers (detected via unrealistically large page_size)
 *   - Amonet microloader (header is shifted by 1024 bytes)
 *   - Nook HD (1 MiB pre-header shift)
 *   - Accclaim (256 KiB pre-header shift)
 *   - Standard AOSP headers (v0–v4)
 *
 * @return The adjusted addr (after stripping any pre-header).
 */
const uint8_t *boot_img::parse_hdr(const uint8_t *addr, FileFormat type) {
    if (type == FileFormat::AOSP_VENDOR) {
        fprintf(stderr, "VENDOR_BOOT_HDR\n");
        auto h = reinterpret_cast<const boot_img_hdr_vnd_v3*>(addr);
        switch (h->header_version) {
        case 4:
            hdr = new dyn_img_vnd_v4(addr);
            break;
        default:
            hdr = new dyn_img_vnd_v3(addr);
            break;
        }
        return addr;
    }

    auto h = reinterpret_cast<const boot_img_hdr_v0*>(addr);

    // Samsung PXA headers have page_size ≥ 0x02000000 (unrealistically large)
    if (h->page_size >= 0x02000000) {
        fprintf(stderr, "PXA_BOOT_HDR\n");
        hdr = new dyn_img_pxa(addr);
        return addr;
    }

    // Lambda to create the correct AOSP header subclass based on header_version
    auto make_aosp_hdr = [](const uint8_t *ptr, ssize_t size = -1) -> dyn_img_hdr * {
        auto h = reinterpret_cast<const boot_img_hdr_v0*>(ptr);
        if (memcmp(h->magic, BOOT_MAGIC, BOOT_MAGIC_SIZE) != 0)
            return nullptr;

        switch (h->header_version) {
        case 1:
            return new dyn_img_v1(ptr, size);
        case 2:
            return new dyn_img_v2(ptr, size);
        case 3:
            return new dyn_img_v3(ptr, size);
        case 4:
            return new dyn_img_v4(ptr, size);
        default:
            return new dyn_img_v0(ptr, size);
        }
    };

    // For NOOKHD and ACCLAIM, the entire boot image is shifted by a fixed offset.
    // For AMONET, the header itself is internally shifted by a fixed offset.

    // Check for Amonet: microloader magic followed immediately by BOOT_MAGIC
    if (BUFFER_CONTAIN(addr, AMONET_MICROLOADER_SZ, AMONET_MICROLOADER_MAGIC) &&
        BUFFER_MATCH(addr + AMONET_MICROLOADER_SZ, BOOT_MAGIC)) {
        flags[AMONET_FLAG] = true;
        fprintf(stderr, "AMONET_MICROLOADER\n");

        // The real header is shifted by AMONET_MICROLOADER_SZ
        h = reinterpret_cast<const boot_img_hdr_v0*>(addr + AMONET_MICROLOADER_SZ);
        auto real_hdr_sz = h->page_size - AMONET_MICROLOADER_SZ;
        hdr = make_aosp_hdr(addr + AMONET_MICROLOADER_SZ, real_hdr_sz);
        return addr;
    }

    // Check for Nook HD pre-header (identified by cmdline magic)
    if (CMD_MATCH(NOOKHD_RL_MAGIC) ||
        CMD_MATCH(NOOKHD_GL_MAGIC) ||
        CMD_MATCH(NOOKHD_GR_MAGIC) ||
        CMD_MATCH(NOOKHD_EB_MAGIC) ||
        CMD_MATCH(NOOKHD_ER_MAGIC)) {
        flags[NOOKHD_FLAG] = true;
        fprintf(stderr, "NOOKHD_LOADER\n");
        addr += NOOKHD_PRE_HEADER_SZ;
    } else if (BUFFER_MATCH(h->name, ACCLAIM_MAGIC)) {
        // Check for Barnes & Noble Nook Accclaim pre-header (identified by name field)
        flags[ACCLAIM_FLAG] = true;
        fprintf(stderr, "ACCLAIM_LOADER\n");
        addr += ACCLAIM_PRE_HEADER_SZ;
    }

    hdr = make_aosp_hdr(addr);
    return addr;
}

/**
 * Parse a zImage kernel to locate the compressed piggy payload.
 * zImage layout: header (0x28 bytes) + compressed piggy data + tail.
 * Steps:
 *   1. Scan past the zImage header for a known compression format magic.
 *   2. Compute the piggy end address from the zImage_hdr.end field and
 *      an offset table located near the end of the original piggy region.
 *   3. Adjust kernel pointer to point at the compressed payload and update
 *      kernel_size accordingly.
 */
void boot_img::parse_zimage() {
    z_info.hdr = reinterpret_cast<const zimage_hdr *>(kernel);

    const uint8_t* piggy = nullptr;
    // Skip 0x28 (zImage header) and scan for compression magic
    for (const uint8_t* curr = kernel + 0x28; curr < kernel + hdr->kernel_size(); curr++) {
        if (check_fmt_lg(curr, hdr->kernel_size() - (curr - kernel)) != FileFormat::UNKNOWN) {
            piggy = curr;
            break;
        }
    }

    if (piggy != nullptr) {
        fprintf(stderr, "ZIMAGE_KERNEL\n");
        z_info.hdr_sz = piggy - kernel;

        // Find the end of the compressed piggy by reading offset table
        // stored in the last 64 bytes of the piggy section
        uint32_t piggy_size = z_info.hdr->end - z_info.hdr->start;
        uint32_t piggy_end = piggy_size;
        uint32_t offsets[16];
        memcpy(offsets, kernel + piggy_size - sizeof(offsets), sizeof(offsets));
        // Offsets table: find the largest offset that is near (within 0xFF)
        // but less than piggy_size — this marks the actual end of piggy data
        for (int i = 15; i >= 0; --i) {
            if (offsets[i] > (piggy_size - 0xFF) && offsets[i] < piggy_size) {
                piggy_end = offsets[i];
                break;
            }
        }

        if (piggy_end == piggy_size) {
            fprintf(stderr, "! Could not find end of zImage piggy, keeping raw kernel\n");
        } else {
            flags[ZIMAGE_KERNEL] = true;
            z_info.tail = byte_view(kernel + piggy_end, hdr->kernel_size() - piggy_end);
            // Shift the kernel pointer past zImage header and resize to piggy payload only
            kernel += z_info.hdr_sz;
            hdr->kernel_size() = piggy_end - z_info.hdr_sz;
            k_fmt = check_fmt_lg(kernel, hdr->kernel_size());
        }
    } else {
        fprintf(stderr, "! Could not find zImage piggy, keeping raw kernel\n");
    }
}

/** Convert a vendor ramdisk type integer to a human-readable string. */
static const char *vendor_ramdisk_type(int type) {
    switch (type) {
    case VENDOR_RAMDISK_TYPE_PLATFORM:
        return "platform";
    case VENDOR_RAMDISK_TYPE_RECOVERY:
        return "recovery";
    case VENDOR_RAMDISK_TYPE_DLKM:
        return "dlkm";
    case VENDOR_RAMDISK_TYPE_NONE:
    default:
        return "none";
    }
}

/**
 * Return a span over the vendor ramdisk table entries.
 * Validates that v4_vnd->vendor_ramdisk_table_entry_size matches the expected
 * struct size before returning; exits with error if mismatch.
 */
std::span<const vendor_ramdisk_table_entry_v4> boot_img::vendor_ramdisk_tbl() const {
    if (hdr->vendor_ramdisk_table_size() == 0) {
        return {};
    }

    // v4 vendor boot contains multiple ramdisks
    using table_entry = const vendor_ramdisk_table_entry_v4;
    if (hdr->vendor_ramdisk_table_entry_size() != sizeof(table_entry)) {
        fprintf(stderr,
                "! Invalid vendor image: vendor_ramdisk_table_entry_size != %zu\n",
                sizeof(table_entry));
        exit(RETURN_ERROR);
    }
    return span(reinterpret_cast<table_entry *>(vendor_ramdisk_table), hdr->vendor_ramdisk_table_entry_num());
}


/** Assert that the current offset does not exceed the mapped region end. */
#define assert_off() \
if ((addr + off) > (map.data() + map_end)) {      \
    fprintf(stderr, "Corrupted boot image!\n");   \
    return false;                                 \
}

/**
 * Macro to compute a block pointer and advance the offset past the block,
 * then align to page boundary. Used iteratively for each boot image section.
 */
#define get_block(name)                 \
name = addr + off;                      \
off += hdr->name##_size();              \
off = align_to(off, hdr->page_size());  \
assert_off()

/**
 * Fully parse a boot image starting at addr.
 *
 * Flow:
 *   1. parse_hdr() — instantiate the correct header wrapper
 *   2. Detect SHA256 checksum usage
 *   3. Walk through all component blocks (kernel, ramdisk, second, extra,
 *      recovery_dtbo, dtb, signature, vendor_ramdisk_table, bootconfig)
 *      using hdr->*_size() and page alignment
 *   4. For kernel: detect embedded DTB (via FDT magic), detect MTK header,
 *      detect zImage wrapper
 *   5. For ramdisk: detect vendor ramdisk table (v4) or simple MTK header
 *   6. For tail: detect SEANDROID, LG BUMP, AVB1 signing, AVB footer/vbmeta
 *
 * @return true on success, false on header/bounds error
 */
bool boot_img::parse_image(const uint8_t *addr, FileFormat type) {
    addr = parse_hdr(addr, type);
    if (hdr == nullptr) {
        fprintf(stderr, "Invalid boot image header!\n");
        return false;
    }

    // Detect SHA256 vs SHA1: check if bytes after SHA1+4 bytes are non-zero
    if (const char *id = hdr->id()) {
        for (int i = SHA_DIGEST_SIZE + 4; i < SHA256_DIGEST_SIZE; ++i) {
            if (id[i]) {
                flags[SHA256_FLAG] = true;
                break;
            }
        }
    }

    hdr->print();

    size_t map_end = align_to(map.size(), getpagesize());
    size_t off = hdr->hdr_space();
    get_block(kernel);
    get_block(ramdisk);
    get_block(second);
    get_block(extra);
    get_block(recovery_dtbo);
    get_block(dtb);
    get_block(signature);
    get_block(vendor_ramdisk_table);
    get_block(bootconfig);

    payload = byte_view(addr, off);
    auto tail_addr = addr + off;
    tail = byte_view(tail_addr, map.data() + map_end - tail_addr);

    // — Kernel handling —
    if (auto size = hdr->kernel_size()) {
        // Try to find an embedded DTB within the kernel image
        if (int dtb_off = find_dtb_offset(kernel, size); dtb_off > 0) {
            kernel_dtb = byte_view(kernel + dtb_off, size - dtb_off);
            hdr->kernel_size() = dtb_off;
            fprintf(stderr, "%-*s [%zu]\n", PADDING, "KERNEL_DTB_SZ", kernel_dtb.size());
        }

        k_fmt = check_fmt_lg(kernel, hdr->kernel_size());
        // Detect and strip MediaTek kernel header
        if (k_fmt == FileFormat::MTK) {
            fprintf(stderr, "MTK_KERNEL_HDR\n");
            flags[MTK_KERNEL] = true;
            k_hdr = reinterpret_cast<const mtk_hdr *>(kernel);
            fprintf(stderr, "%-*s [%u]\n", PADDING, "SIZE", k_hdr->size);
            fprintf(stderr, "%-*s [%s]\n", PADDING, "NAME", k_hdr->name);
            kernel += sizeof(mtk_hdr);
            hdr->kernel_size() -= sizeof(mtk_hdr);
            k_fmt = check_fmt_lg(kernel, hdr->kernel_size());
        }
        // Detect and parse zImage wrapper
        if (k_fmt == FileFormat::ZIMAGE) {
            parse_zimage();
        }
        fprintf(stderr, "%-*s [%s]\n", PADDING, "KERNEL_FMT", fmt2name(k_fmt));
    }

    // — Ramdisk handling —
    if (auto size = hdr->ramdisk_size()) {
        if (hdr->vendor_ramdisk_table_size()) {
            // v4 vendor boot: multiple named ramdisks; log each
            for (auto &it : vendor_ramdisk_tbl()) {
                FileFormat fmt = check_fmt_lg(ramdisk + it.ramdisk_offset, it.ramdisk_size);
                fprintf(stderr,
                        "%-*s name=[%s] type=[%s] size=[%u] fmt=[%s]\n", PADDING, "VND_RAMDISK",
                        it.ramdisk_name, vendor_ramdisk_type(it.ramdisk_type),
                        it.ramdisk_size, fmt2name(fmt));
            }
        } else {
            r_fmt = check_fmt_lg(ramdisk, size);
            // Detect and strip MediaTek ramdisk header
            if (r_fmt == FileFormat::MTK) {
                fprintf(stderr, "MTK_RAMDISK_HDR\n");
                flags[MTK_RAMDISK] = true;
                r_hdr = reinterpret_cast<const mtk_hdr *>(ramdisk);
                fprintf(stderr, "%-*s [%u]\n", PADDING, "SIZE", r_hdr->size);
                fprintf(stderr, "%-*s [%s]\n", PADDING, "NAME", r_hdr->name);
                ramdisk += sizeof(mtk_hdr);
                hdr->ramdisk_size() -= sizeof(mtk_hdr);
                r_fmt = check_fmt_lg(ramdisk, hdr->ramdisk_size());
            }
            fprintf(stderr, "%-*s [%s]\n", PADDING, "RAMDISK_FMT", fmt2name(r_fmt));
        }
    }

    // — Extra section format detection —
    if (auto size = hdr->extra_size()) {
        e_fmt = check_fmt_lg(extra, size);
        fprintf(stderr, "%-*s [%s]\n", PADDING, "EXTRA_FMT", fmt2name(e_fmt));
    }

    // — Tail analysis: detect signatures and AVB —
    if (tail.size()) {
        // Check for Samsung SEANDROID signature
        if (tail.size() >= 16 && BUFFER_MATCH(tail.data(), SEANDROID_MAGIC)) {
            fprintf(stderr, "SAMSUNG_SEANDROID\n");
            flags[SEANDROID_FLAG] = true;
        } else if (tail.size() >= 16 && BUFFER_MATCH(tail.data(), LG_BUMP_MAGIC)) {
            // Check for LG BUMP signature
            fprintf(stderr, "LG_BUMP_IMAGE\n");
            flags[LG_BUMP_FLAG] = true;
        } else if (verify()) {
            // Check for AVB1 (Android Verified Boot 1.0) signature
            fprintf(stderr, "AVB1_SIGNED\n");
            flags[AVB1_SIGNED_FLAG] = true;
        }

        // Find AVBv2 footer appended at the very end of the image
        const void *footer = tail.data() + tail.size() - sizeof(AvbFooter);
        if (BUFFER_MATCH(footer, AVB_FOOTER_MAGIC)) {
            avb_footer = static_cast<const AvbFooter*>(footer);
            // Verify that the vbmeta header referenced by the footer exists
            const void *meta = payload.data() + __builtin_bswap64(avb_footer->vbmeta_offset);
            if (BUFFER_MATCH(meta, AVB_MAGIC)) {
                fprintf(stderr, "VBMETA\n");
                flags[AVB_FLAG] = true;
                vbmeta = static_cast<const AvbVBMetaImageHeader*>(meta);
            }
        }
    }

    return true;
}

/**
 * Split a kernel+DTB combined image into separate kernel and kernel_dtb files.
 * If the image is compressed and skip_decomp is false, decompress the kernel
 * portion before writing. The DTB portion is always written raw.
 *
 * @return 0 on success, 1 if no embedded DTB is found.
 */
int split_image_dtb(Utf8CStr filename, bool skip_decomp) {
    mmap_data img(filename.c_str());

    if (int offset = find_dtb_offset(img.data(), img.size()); offset > 0) {
        size_t off = (size_t) offset;

        FileFormat fmt = check_fmt_lg(img.data(), img.size());
        if (!skip_decomp && fmt_compressed(fmt)) {
            int fd = creat(KERNEL_FILE, 0644);
            decompress(fmt, fd, img.data(), off);
            close(fd);
        } else {
            dump(img.data(), off, KERNEL_FILE);
        }
        dump(img.data() + off, img.size() - off, KER_DTB_FILE);
        return 0;
    } else {
        fprintf(stderr, "Cannot find DTB in %s\n", filename.c_str());
        return 1;
    }
}

/**
 * Unpack a boot image into individual component files.
 *
 * For each component (kernel, ramdisk, second, extra, recovery_dtbo, dtb,
 * bootconfig — plus embedded kernel_dtb), the function writes the data to
 * a separate file. Compressed components are decompressed unless skip_decomp
 * is set. Vendor boot images with multiple ramdisks (v4) are unpacked into
 * the vendor_ramdisk/ subdirectory.
 *
 * @param image       Path to the boot image file.
 * @param skip_decomp If true, write compressed data as-is without decompressing.
 * @param hdr         If true, also dump header fields to "header" file.
 * @return 0 on success, 1 on error, 2 if ChromeOS-signed, 3 if vendor boot.
 */
int unpack(Utf8CStr image, bool skip_decomp, bool hdr) {
    const boot_img boot(image.c_str());

    if (hdr)
        boot.hdr->dump_hdr_file();

    // Write kernel (decompressed or raw)
    if (!skip_decomp && fmt_compressed(boot.k_fmt)) {
        if (boot.hdr->kernel_size() != 0) {
            int fd = creat(KERNEL_FILE, 0644);
            decompress(boot.k_fmt, fd, boot.kernel, boot.hdr->kernel_size());
            close(fd);
        }
    } else {
        dump(boot.kernel, boot.hdr->kernel_size(), KERNEL_FILE);
    }

    // Write kernel_dtb (DTB embedded within the kernel image)
    dump(boot.kernel_dtb.data(), boot.kernel_dtb.size(), KER_DTB_FILE);

    // Write ramdisk(s)
    if (boot.hdr->vendor_ramdisk_table_size()) {
        xmkdir(VND_RAMDISK_DIR, 0755);
        owned_fd dirfd = xopen(VND_RAMDISK_DIR, O_RDONLY | O_CLOEXEC);
        for (auto &it : boot.vendor_ramdisk_tbl()) {
            char file_name[40];
            if (it.ramdisk_name[0] == '\0') {
                strscpy(file_name, RAMDISK_FILE, sizeof(file_name));
            } else {
                ssprintf(file_name, sizeof(file_name), "%s.cpio", it.ramdisk_name);
            }
            owned_fd fd = xopenat(dirfd, file_name, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644);
            FileFormat fmt = check_fmt_lg(boot.ramdisk + it.ramdisk_offset, it.ramdisk_size);
            if (!skip_decomp && fmt_compressed(fmt)) {
                decompress(fmt, fd, boot.ramdisk + it.ramdisk_offset, it.ramdisk_size);
            } else {
                xwrite(fd, boot.ramdisk + it.ramdisk_offset, it.ramdisk_size);
            }
        }
    } else if (!skip_decomp && fmt_compressed(boot.r_fmt)) {
        if (boot.hdr->ramdisk_size() != 0) {
            int fd = creat(RAMDISK_FILE, 0644);
            decompress(boot.r_fmt, fd, boot.ramdisk, boot.hdr->ramdisk_size());
            close(fd);
        }
    } else {
        dump(boot.ramdisk, boot.hdr->ramdisk_size(), RAMDISK_FILE);
    }

    // Write second stage
    dump(boot.second, boot.hdr->second_size(), SECOND_FILE);

    // Write extra (decompressed or raw)
    if (!skip_decomp && fmt_compressed(boot.e_fmt)) {
        if (boot.hdr->extra_size() != 0) {
            int fd = creat(EXTRA_FILE, 0644);
            decompress(boot.e_fmt, fd, boot.extra, boot.hdr->extra_size());
            close(fd);
        }
    } else {
        dump(boot.extra, boot.hdr->extra_size(), EXTRA_FILE);
    }

    // Write recovery DTBO/ACPIO
    dump(boot.recovery_dtbo, boot.hdr->recovery_dtbo_size(), RECV_DTBO_FILE);

    // Write device tree blob
    dump(boot.dtb, boot.hdr->dtb_size(), DTB_FILE);

    // Write bootconfig
    dump(boot.bootconfig, boot.hdr->bootconfig_size(), BOOTCONFIG_FILE);

    if (boot.flags[CHROMEOS_FLAG]) return RETURN_CHROMEOS;
    if (boot.hdr->is_vendor()) return RETURN_VENDOR;
    return RETURN_OK;
}

/** Align current file position (relative to off.header) up to the given page size by writing zeros. */
#define file_align_with(page_size) \
write_zero(fd, align_padding(lseek(fd, 0, SEEK_CUR) - off.header, page_size))

/** Align using the boot image's own page_size. */
#define file_align() file_align_with(boot.hdr->page_size())

/**
 * Repack a boot image from previously unpacked/modified component files.
 *
 * The original boot image is read for reference (layout, formats, special flags);
 * component data is re-read from individual files (kernel, ramdisk.cpio, etc.).
 * The function recompresses data using the original format (unless skip_comp),
 * reassembles the image with the correct page alignment, patches the header
 * (sizes, checksum), and updates vendor-specific structures (MTK, DHTB, BLOB,
 * AVB, SEANDROID, LG_BUMP).
 */
void repack(Utf8CStr src_img, Utf8CStr out_img, bool skip_comp) {
    const boot_img boot(src_img.c_str());
    fprintf(stderr, "Repack to boot image: [%s]\n", out_img.c_str());

    // File offsets within the output image for each section (used later for patching)
    struct {
        uint32_t header;
        uint32_t kernel;
        uint32_t ramdisk;
        uint32_t second;
        uint32_t extra;
        uint32_t dtb;
        uint32_t tail;
        uint32_t vbmeta;
    } off{};

    // Clone the header and zero out sizes; they'll be filled as we write
    auto hdr = boot.hdr->clone();
    hdr->kernel_size() = 0;
    hdr->ramdisk_size() = 0;
    hdr->second_size() = 0;
    hdr->dtb_size() = 0;
    hdr->bootconfig_size() = 0;

    // If a header property file exists, load user modifications
    if (access(HEADER_FILE, R_OK) == 0)
        hdr->load_hdr_file();

    /***************
     * Write blocks
     ***************/

    int fd = open(out_img.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0644);

    // Write non-standard pre-headers (DHTB, BLOB, Nook HD, Accclaim)
    if (boot.flags[DHTB_FLAG]) {
        xwrite(fd, boot.map.data(), sizeof(dhtb_hdr));
    } else if (boot.flags[BLOB_FLAG]) {
        xwrite(fd, boot.map.data(), sizeof(blob_hdr));
    } else if (boot.flags[NOOKHD_FLAG]) {
        xwrite(fd, boot.map.data(), NOOKHD_PRE_HEADER_SZ);
    } else if (boot.flags[ACCLAIM_FLAG]) {
        xwrite(fd, boot.map.data(), ACCLAIM_PRE_HEADER_SZ);
    }

    // Write the AOSP boot header (padded to page_size)
    off.header = lseek(fd, 0, SEEK_CUR);
    xwrite(fd, boot.payload.data(), hdr->hdr_space());

    // — Kernel section —
    off.kernel = lseek(fd, 0, SEEK_CUR);
    if (boot.flags[MTK_KERNEL]) {
        xwrite(fd, boot.k_hdr, sizeof(mtk_hdr));
    }
    if (boot.flags[ZIMAGE_KERNEL]) {
        xwrite(fd, boot.z_info.hdr, boot.z_info.hdr_sz);
    }
    if (access(KERNEL_FILE, R_OK) == 0) {
        mmap_data m(KERNEL_FILE);
        // Recompress if the input is uncompressed and the original was compressed
        if (!skip_comp && !fmt_compressed_any(check_fmt(m.data(), m.size())) && fmt_compressed(boot.k_fmt)) {
            // Use Zopfli for zImage kernels that were originally Gzip
            auto fmt = (boot.flags[ZIMAGE_KERNEL] && boot.k_fmt == FileFormat::GZIP) ? FileFormat::ZOPFLI : boot.k_fmt;
            hdr->kernel_size() = compress_len(fmt, m, fd);
        } else {
            hdr->kernel_size() = xwrite(fd, m.data(), m.size());
        }

        // zImage kernel size must remain exactly the same to preserve the bootloader's expectations
        if (boot.flags[ZIMAGE_KERNEL]) {
            if (hdr->kernel_size() > boot.hdr->kernel_size()) {
                fprintf(stderr, "! Recompressed kernel is too large, using original kernel\n");
                ftruncate64(fd, lseek64(fd, - (off64_t) hdr->kernel_size(), SEEK_CUR));
                xwrite(fd, boot.kernel, boot.hdr->kernel_size());
            } else if (!skip_comp) {
                // Pad with zeros to maintain original size; write original vmlinux size in last 4 bytes
                uint32_t sz = m.size();
                write_zero(fd, boot.hdr->kernel_size() - hdr->kernel_size() - sizeof(sz));
                xwrite(fd, &sz, sizeof(sz));
            }

            hdr->kernel_size() = boot.hdr->kernel_size();
        }
    } else if (boot.hdr->kernel_size() != 0) {
        // No new kernel file: reuse original kernel data
        xwrite(fd, boot.kernel, boot.hdr->kernel_size());
        hdr->kernel_size() = boot.hdr->kernel_size();
    }
    if (boot.flags[ZIMAGE_KERNEL]) {
        // Append zImage tail (header and tail around the piggy payload)
        hdr->kernel_size() += boot.z_info.hdr_sz;
        hdr->kernel_size() += xwrite(fd, boot.z_info.tail.data(), boot.z_info.tail.size());
    }

    // Append kernel DTB if present
    if (access(KER_DTB_FILE, R_OK) == 0)
        hdr->kernel_size() += restore(fd, KER_DTB_FILE);
    file_align();

    // — Ramdisk section —
    off.ramdisk = lseek(fd, 0, SEEK_CUR);
    if (boot.flags[MTK_RAMDISK]) {
        xwrite(fd, boot.r_hdr, sizeof(mtk_hdr));
    }

    vector<vendor_ramdisk_table_entry_v4> ramdisk_table;

    if (boot.hdr->vendor_ramdisk_table_size()) {
        // v4 vendor boot: write multiple ramdisks from vendor_ramdisk/ directory
        ramdisk_table.assign_range(boot.vendor_ramdisk_tbl());

        owned_fd dirfd = xopen(VND_RAMDISK_DIR, O_RDONLY | O_CLOEXEC);
        uint32_t ramdisk_offset = 0;
        for (auto &it : ramdisk_table) {
            char file_name[64];
            if (it.ramdisk_name[0] == '\0') {
                strscpy(file_name, RAMDISK_FILE, sizeof(file_name));
            } else {
                ssprintf(file_name, sizeof(file_name), "%s.cpio", it.ramdisk_name);
            }
            mmap_data m(dirfd, file_name);
            FileFormat fmt = check_fmt_lg(boot.ramdisk + it.ramdisk_offset, it.ramdisk_size);
            it.ramdisk_offset = ramdisk_offset;
            if (!skip_comp && !fmt_compressed_any(check_fmt(m.data(), m.size())) && fmt_compressed(fmt)) {
                it.ramdisk_size = compress_len(fmt, m, fd);
            } else {
                it.ramdisk_size = xwrite(fd, m.data(), m.size());
            }
            ramdisk_offset += it.ramdisk_size;
        }

        hdr->ramdisk_size() = ramdisk_offset;
        file_align();
    } else if (access(RAMDISK_FILE, R_OK) == 0) {
        mmap_data m(RAMDISK_FILE);
        auto r_fmt = boot.r_fmt;
        // v4 GKI requires LZ4_LEGACY for ramdisk (to match vendor ramdisk compression)
        if (!skip_comp && !hdr->is_vendor() && hdr->header_version() == 4 && r_fmt != FileFormat::LZ4_LEGACY) {
            fprintf(stderr, "RAMDISK_FMT: [%s] -> [%s]\n", fmt2name(r_fmt), fmt2name(FileFormat::LZ4_LEGACY));
            r_fmt = FileFormat::LZ4_LEGACY;
        }
        if (!skip_comp && !fmt_compressed_any(check_fmt(m.data(), m.size())) && fmt_compressed(r_fmt)) {
            hdr->ramdisk_size() = compress_len(r_fmt, m, fd);
        } else {
            hdr->ramdisk_size() = xwrite(fd, m.data(), m.size());
        }
        file_align();
    }

    // — Second stage —
    off.second = lseek(fd, 0, SEEK_CUR);
    if (access(SECOND_FILE, R_OK) == 0) {
        hdr->second_size() = restore(fd, SECOND_FILE);
        file_align();
    }

    // — Extra section (recompress if applicable) —
    off.extra = lseek(fd, 0, SEEK_CUR);
    if (access(EXTRA_FILE, R_OK) == 0) {
        mmap_data m(EXTRA_FILE);
        if (!skip_comp && !fmt_compressed_any(check_fmt(m.data(), m.size())) && fmt_compressed(boot.e_fmt)) {
            hdr->extra_size() = compress_len(boot.e_fmt, m, fd);
        } else {
            hdr->extra_size() = xwrite(fd, m.data(), m.size());
        }
        file_align();
    }

    // — Recovery DTBO —
    if (access(RECV_DTBO_FILE, R_OK) == 0) {
        hdr->recovery_dtbo_offset() = lseek(fd, 0, SEEK_CUR);
        hdr->recovery_dtbo_size() = restore(fd, RECV_DTBO_FILE);
        file_align();
    }

    // — Device tree blob —
    off.dtb = lseek(fd, 0, SEEK_CUR);
    if (access(DTB_FILE, R_OK) == 0) {
        hdr->dtb_size() = restore(fd, DTB_FILE);
        file_align();
    }

    // — Boot signature (v4) —
    if (boot.hdr->signature_size()) {
        xwrite(fd, boot.signature, boot.hdr->signature_size());
        file_align();
    }

    // — Vendor ramdisk table (v4 vendor) —
    if (!ramdisk_table.empty()) {
        xwrite(fd, ramdisk_table.data(), sizeof(*ramdisk_table.data()) * ramdisk_table.size());
        file_align();
    }

    // — Bootconfig (v4 vendor) —
    if (access(BOOTCONFIG_FILE, R_OK) == 0) {
        hdr->bootconfig_size() = restore(fd, BOOTCONFIG_FILE);
        file_align();
    }

    // — OEM-specific trailers (SEANDROID, LG BUMP) —
    if (boot.flags[SEANDROID_FLAG]) {
        xwrite(fd, SEANDROID_MAGIC, 16);
        if (boot.flags[DHTB_FLAG]) {
            xwrite(fd, "\xFF\xFF\xFF\xFF", 4);
        }
    } else if (boot.flags[LG_BUMP_FLAG]) {
        xwrite(fd, LG_BUMP_MAGIC, 16);
    }

    off.tail = lseek(fd, 0, SEEK_CUR);
    file_align();

    // — AVBv2 vbmeta (appended after 4096-byte alignment) —
    if (boot.flags[AVB_FLAG]) {
        file_align_with(4096);
        off.vbmeta = lseek(fd, 0, SEEK_CUR);
        uint64_t vbmeta_size = __builtin_bswap64(boot.avb_footer->vbmeta_size);
        xwrite(fd, boot.vbmeta, vbmeta_size);
    }

    // Preserve original total size (for bootloader compatibility) unless ChromeOS
    if (!boot.flags[CHROMEOS_FLAG]) {
        off_t current = lseek(fd, 0, SEEK_CUR);
        if (current < boot.map.size()) {
            write_zero(fd, boot.map.size() - current);
        }
    }

    /******************
     * Patch the image
     ******************/

    uint32_t aosp_img_size = off.tail - off.header;

    // Remap the output file as writable for in-place patching
    mmap_data out(fd, lseek(fd, 0, SEEK_END), true);

    // Update MTK headers with new payload sizes
    if (boot.flags[MTK_KERNEL]) {
        auto m_hdr = reinterpret_cast<mtk_hdr *>(out.data() + off.kernel);
        m_hdr->size = hdr->kernel_size();
        hdr->kernel_size() += sizeof(mtk_hdr);
    }
    if (boot.flags[MTK_RAMDISK]) {
        auto m_hdr = reinterpret_cast<mtk_hdr *>(out.data() + off.ramdisk);
        m_hdr->size = hdr->ramdisk_size();
        hdr->ramdisk_size() += sizeof(mtk_hdr);
    }

    hdr->header_size() = hdr->hdr_size();

    // Compute and write the checksum (SHA1 or SHA256) over all component data
    if (char *id = hdr->id()) {
        auto ctx = get_sha(!boot.flags[SHA256_FLAG]);
        uint32_t size = hdr->kernel_size();
        ctx->update(byte_view(out.data() + off.kernel, size));
        ctx->update(byte_view(&size, sizeof(size)));
        size = hdr->ramdisk_size();
        ctx->update(byte_view(out.data() + off.ramdisk, size));
        ctx->update(byte_view(&size, sizeof(size)));
        size = hdr->second_size();
        ctx->update(byte_view(out.data() + off.second, size));
        ctx->update(byte_view(&size, sizeof(size)));
        size = hdr->extra_size();
        if (size) {
            ctx->update(byte_view(out.data() + off.extra, size));
            ctx->update(byte_view(&size, sizeof(size)));
        }
        uint32_t ver = hdr->header_version();
        if (ver == 1 || ver == 2) {
            size = hdr->recovery_dtbo_size();
            ctx->update(byte_view(out.data() + hdr->recovery_dtbo_offset(), size));
            ctx->update(byte_view(&size, sizeof(size)));
        }
        if (ver == 2) {
            size = hdr->dtb_size();
            ctx->update(byte_view(out.data() + off.dtb, size));
            ctx->update(byte_view(&size, sizeof(size)));
        }
        memset(id, 0, BOOT_ID_SIZE);
        ctx->finalize_into(byte_data(id, ctx->output_size()));
    }

    hdr->print();

    // Write the updated header back into the image
    if (boot.flags[AMONET_FLAG]) {
        auto real_hdr_sz = std::min(hdr->hdr_space() - AMONET_MICROLOADER_SZ, hdr->hdr_size());
        memcpy(out.data() + off.header + AMONET_MICROLOADER_SZ, hdr->raw_hdr(), real_hdr_sz);
    } else {
        memcpy(out.data() + off.header, hdr->raw_hdr(), hdr->hdr_size());
    }

    // Patch AVB footer with new image size and vbmeta offset
    if (boot.flags[AVB_FLAG]) {
        auto footer = reinterpret_cast<AvbFooter*>(out.data() + out.size() - sizeof(AvbFooter));
        memcpy(footer, boot.avb_footer, sizeof(AvbFooter));
        footer->original_image_size = __builtin_bswap64(aosp_img_size);
        footer->vbmeta_offset = __builtin_bswap64(off.vbmeta);
        if (check_env("PATCHVBMETAFLAG")) {
            auto vbmeta = reinterpret_cast<AvbVBMetaImageHeader*>(out.data() + off.vbmeta);
            vbmeta->flags = __builtin_bswap32(3);
        }
    }

    // Update DHTB checksum or BLOB header size
    if (boot.flags[DHTB_FLAG]) {
        auto d_hdr = reinterpret_cast<dhtb_hdr *>(out.data());
        d_hdr->size = aosp_img_size + 16 /* SEANDROID_MAGIC */ + 4 /* DHTB trailer */;
        sha256_hash(byte_view(out.data() + sizeof(dhtb_hdr), d_hdr->size),
                    byte_data(d_hdr->checksum, SHA256_DIGEST_SIZE));
    } else if (boot.flags[BLOB_FLAG]) {
        auto b_hdr = reinterpret_cast<blob_hdr *>(out.data());
        b_hdr->size = aosp_img_size;
    }

    // Rewrite AVB1 signature (RSA-signed hash)
    if (boot.flags[AVB1_SIGNED_FLAG]) {
        byte_view payload(out.data() + off.header, aosp_img_size);
        auto sig = sign_payload(payload);
        if (!sig.empty()) {
            lseek(fd, off.tail, SEEK_SET);
            xwrite(fd, sig.data(), sig.size());
        }
    }

    close(fd);
}

/** Remove all temporary files and directories created during unpack/repack operations. */
void cleanup() {
    unlink(HEADER_FILE);
    unlink(KERNEL_FILE);
    unlink(RAMDISK_FILE);
    unlink(SECOND_FILE);
    unlink(KER_DTB_FILE);
    unlink(EXTRA_FILE);
    unlink(RECV_DTBO_FILE);
    unlink(DTB_FILE);
    unlink(BOOTCONFIG_FILE);
    rm_rf(VND_RAMDISK_DIR);
}
