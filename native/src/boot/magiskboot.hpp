/**
 * Public API header for magiskboot.
 * Exports unpack/repack/split_image_dtb functions and file format
 * detection (check_fmt). Defines file path constants and magic bytes.
 *
 * Core flow:
 *   1. check_fmt() — identify image/compression format via magic bytes
 *   2. unpack()    — decompress and dump each component to individual files
 *   3. repack()    — reassemble modified components back into a boot image
 *   4. split_image_dtb() — extract embedded DTB from a kernel image
 *   5. cleanup()   — remove temporary files
 */
#pragma once

#include <base.hpp>

/** Filenames for extracted boot image components during unpack/repack. */
#define HEADER_FILE     "header"
#define KERNEL_FILE     "kernel"
#define RAMDISK_FILE    "ramdisk.cpio"
#define VND_RAMDISK_DIR "vendor_ramdisk"
#define SECOND_FILE     "second"
#define EXTRA_FILE      "extra"
#define KER_DTB_FILE    "kernel_dtb"
#define RECV_DTBO_FILE  "recovery_dtbo"
#define DTB_FILE        "dtb"
#define BOOTCONFIG_FILE "bootconfig"
#define NEW_BOOT        "new-boot.img"

/** Check if buffer matches a magic string (excluding null terminator). */
#define BUFFER_MATCH(buf, s) (memcmp(buf, s, sizeof(s) - 1) == 0)
/** Search for a magic byte sequence within a larger buffer. */
#define BUFFER_CONTAIN(buf, sz, s) (memmem(buf, sz, s, sizeof(s) - 1) != nullptr)
/** Safe magic check: verify minimum length first, then match content. */
#define CHECKED_MATCH(s) (len >= (sizeof(s) - 1) && BUFFER_MATCH(buf, s))

/** Magic byte sequences for identifying boot image types and compression formats. */
#define BOOT_MAGIC      "ANDROID!"
#define VENDOR_BOOT_MAGIC "VNDRBOOT"
#define CHROMEOS_MAGIC  "CHROMEOS"
#define GZIP1_MAGIC     "\x1f\x8b"
#define GZIP2_MAGIC     "\x1f\x9e"
#define LZOP_MAGIC      "\x89""LZO"
#define XZ_MAGIC        "\xfd""7zXZ"
#define BZIP_MAGIC      "BZh"
#define LZ4_LEG_MAGIC   "\x02\x21\x4c\x18"
#define LZ41_MAGIC      "\x03\x21\x4c\x18"
#define LZ42_MAGIC      "\x04\x22\x4d\x18"
#define MTK_MAGIC       "\x88\x16\x88\x58"
#define DTB_MAGIC       "\xd0\x0d\xfe\xed"
#define LG_BUMP_MAGIC   "\x41\xa9\xe4\x67\x74\x4d\x1d\x1b\xa4\x29\xf2\xec\xea\x65\x52\x79"
#define DHTB_MAGIC      "\x44\x48\x54\x42\x01\x00\x00\x00"
#define SEANDROID_MAGIC "SEANDROIDENFORCE"
#define TEGRABLOB_MAGIC "-SIGNED-BY-SIGNBLOB-"
#define NOOKHD_RL_MAGIC "Red Loader"
#define NOOKHD_GL_MAGIC "Green Loader"
#define NOOKHD_GR_MAGIC "Green Recovery"
#define NOOKHD_EB_MAGIC "eMMC boot.img+secondloader"
#define NOOKHD_ER_MAGIC "eMMC recovery.img+secondloader"
#define NOOKHD_PRE_HEADER_SZ 1048576
#define ACCLAIM_MAGIC   "BauwksBoot"
#define ACCLAIM_PRE_HEADER_SZ 262144
#define AMONET_MICROLOADER_MAGIC "microloader"
#define AMONET_MICROLOADER_SZ 1024
#define AVB_FOOTER_MAGIC "AVBf"
#define AVB_MAGIC "AVB0"
#define ZIMAGE_MAGIC "\x18\x28\x6f\x01"

enum class FileFormat : uint8_t;

/**
 * Unpack a boot image into its constituent files.
 *
 * @param image       Path to the boot image file.
 * @param skip_decomp If true, write compressed payloads as-is instead of decompressing.
 * @param hdr         If true, dump the header fields to a property file.
 * @return 0 on success, 1 on error, 2 if ChromeOS-signed, 3 if vendor boot.
 */
int unpack(Utf8CStr image, bool skip_decomp = false, bool hdr = false);

/**
 * Repack a boot image from previously unpacked component files.
 *
 * @param src_img   Path to the original boot image (used for reference layout/format).
 * @param out_img   Path for the output boot image.
 * @param skip_comp If true, write uncompressed payloads instead of recompressing.
 */
void repack(Utf8CStr src_img, Utf8CStr out_img, bool skip_comp = false);

/**
 * Split a kernel+DTB image into separate kernel and kernel_dtb files.
 *
 * @param filename    Path to the combined image file.
 * @param skip_decomp If true, write kernel as-is instead of decompressing.
 * @return 0 on success, 1 if no DTB found.
 */
int split_image_dtb(Utf8CStr filename, bool skip_decomp = false);

/** Remove all temporary files created during unpack/repack. */
void cleanup();

/**
 * Identify the format of a data buffer by checking magic bytes.
 *
 * @param buf Pointer to the data buffer.
 * @param len Length of the buffer in bytes.
 * @return The detected FileFormat enum value (UNKNOWN if no match).
 */
FileFormat check_fmt(const void *buf, size_t len);

/** Overload accepting a Rust slice. */
static inline FileFormat check_fmt(rust::Slice<const uint8_t> bytes) {
    return check_fmt(bytes.data(), bytes.size());
}
