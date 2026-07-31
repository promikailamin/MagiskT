package pro.magisk.core.signing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Assorted ZIP format helpers for locating and modifying ZIP End of Central Directory records.
 *
 * <p>Used by the APK Signature Scheme v2 implementation to locate the Central Directory
 * and rewrite the Central Directory offset after inserting the APK Signing Block.
 *
 * <p>NOTE: Most helper methods operating on {@code ByteBuffer} instances expect that the byte
 * order of these buffers is little-endian.
 */
public abstract class ZipUtils {

    private static final int ZIP_EOCD_REC_MIN_SIZE = 22;
    private static final int ZIP_EOCD_REC_SIG = 0x06054b50;
    private static final int ZIP_EOCD_CENTRAL_DIR_SIZE_FIELD_OFFSET = 12;
    private static final int ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET = 16;
    private static final int ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET = 20;

    private static final int ZIP64_EOCD_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;

    private static final int UINT16_MAX_VALUE = 0xffff;

    private ZipUtils() {
    }

    /**
     * Returns the position at which ZIP End of Central Directory record starts in the provided
     * buffer or {@code -1} if the record is not present.
     *
     * <p>NOTE: Byte order of {@code zipContents} must be little-endian.
     */
    public static int find_zip_end_of_central_directory_record(ByteBuffer zip_contents) {
        assert_byte_order_little_endian(zip_contents);

        // ZIP End of Central Directory (EOCD) record is located at the very end of the ZIP archive.
        // The record can be identified by its 4-byte signature/magic which is located at the very
        // beginning of the record. A complication is that the record is variable-length because of
        // the comment field.
        // The algorithm for locating the ZIP EOCD record is as follows. We search backwards from
        // end of the buffer for the EOCD record signature. Whenever we find a signature, we check
        // the candidate record's comment length is such that the remainder of the record takes up
        // exactly the remaining bytes in the buffer. The search is bounded because the maximum
        // size of the comment field is 65535 bytes because the field is an unsigned 16-bit number.

        int archiveSize = zip_contents.capacity();
        if (archiveSize < ZIP_EOCD_REC_MIN_SIZE) {
            return -1;
        }
        int maxCommentLength = Math.min(archiveSize - ZIP_EOCD_REC_MIN_SIZE, UINT16_MAX_VALUE);
        int eocdWithEmptyCommentStartPosition = archiveSize - ZIP_EOCD_REC_MIN_SIZE;
        for (int expectedCommentLength = 0; expectedCommentLength < maxCommentLength; expectedCommentLength++) {
            int eocdStartPos = eocdWithEmptyCommentStartPosition - expectedCommentLength;
            if (zip_contents.getInt(eocdStartPos) == ZIP_EOCD_REC_SIG) {
                int actualCommentLength = get_unsigned_int16(zip_contents, eocdStartPos + ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET);
                if (actualCommentLength == expectedCommentLength) {
                    return eocdStartPos;
                }
            }
        }

        return -1;
    }

    /**
     * Returns {@code true} if the provided buffer contains a ZIP64 End of Central Directory
     * Locator.
     *
     * <p>NOTE: Byte order of {@code zipContents} must be little-endian.
     */
    public static boolean is_zip64_end_of_central_directory_locator_present(ByteBuffer zip_contents, int zipEndOfCentralDirectoryPosition) {
        assert_byte_order_little_endian(zip_contents);

        // ZIP64 End of Central Directory Locator immediately precedes the ZIP End of Central
        // Directory Record.

        int locatorPosition = zipEndOfCentralDirectoryPosition - ZIP64_EOCD_LOCATOR_SIZE;
        if (locatorPosition < 0) {
            return false;
        }

        return zip_contents.getInt(locatorPosition) == ZIP64_EOCD_LOCATOR_SIG;
    }

    /**
     * Returns the offset of the start of the ZIP Central Directory in the archive.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static long get_zip_eocd_central_directory_offset(ByteBuffer zip_end_of_central_directory) {
        assert_byte_order_little_endian(zip_end_of_central_directory);
        return get_unsigned_int32(zip_end_of_central_directory, zip_end_of_central_directory.position() + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET);
    }

    /**
     * Sets the offset of the start of the ZIP Central Directory in the archive.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static void set_zip_eocd_central_directory_offset(ByteBuffer zip_end_of_central_directory, long offset) {
        assert_byte_order_little_endian(zip_end_of_central_directory);
        set_unsigned_int32(zip_end_of_central_directory, zip_end_of_central_directory.position() + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET, offset);
    }

    /**
     * Returns the size (in bytes) of the ZIP Central Directory.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static long get_zip_eocd_central_directory_size_bytes(ByteBuffer zip_end_of_central_directory) {
        assert_byte_order_little_endian(zip_end_of_central_directory);
        return get_unsigned_int32(zip_end_of_central_directory, zip_end_of_central_directory.position() + ZIP_EOCD_CENTRAL_DIR_SIZE_FIELD_OFFSET);
    }

    private static void assert_byte_order_little_endian(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("ByteBuffer byte order must be little endian");
        }
    }

    private static int get_unsigned_int16(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xffff;
    }

    private static long get_unsigned_int32(ByteBuffer buffer, int offset) {
        return buffer.getInt(offset) & 0xffffffffL;
    }

    private static void set_unsigned_int32(ByteBuffer buffer, int offset, long value) {
        if ((value < 0) || (value > 0xffffffffL)) {
            throw new IllegalArgumentException("uint32 value of out range: " + value);
        }
        buffer.putInt(buffer.position() + offset, (int) value);
    }
}
