/**
 * Desugaring helper that provides backward-compatible access to {@link java.util.zip.ZipEntry}
 * time methods and disables compression-feature checks in Apache Commons Compress.
 *
 * <p>Methods like {@link ZipEntry#getLastModifiedTime()} were added in API 26. This class provides
 * static fallbacks that return the DOS timestamp on older platforms.
 *
 * <p>{@link #checkRequestedFeatures} is a no-op replacement for
 * {@link ZipUtil#checkRequestedFeatures}. It is called by the ASM-instrumented
 * {@link ZipArchiveOutputStream#copyFromZipInputStream} to bypass unsupported-compression-method
 * checks when copying raw ZIP entries.
 */
package pro.magisk.core.utils;

import android.os.Build;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;

import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;

public class Desugar {
    /** Returns {@link ZipEntry#getLastModifiedTime()} on API 26+, falls back to DOS timestamp. */
    public static FileTime getLastModifiedTime(ZipEntry entry) {
        if (Build.VERSION.SDK_INT >= 26) {
            return entry.getLastModifiedTime();
        } else {
            return FileTime.fromMillis(entry.getTime());
        }
    }

    /** Returns {@link ZipEntry#getLastAccessTime()} on API 26+, or {@code null} on older platforms. */
    public static FileTime getLastAccessTime(ZipEntry entry) {
        if (Build.VERSION.SDK_INT >= 26) {
            return entry.getLastAccessTime();
        } else {
            return null;
        }
    }

    /** Returns {@link ZipEntry#getCreationTime()} on API 26+, or {@code null} on older platforms. */
    public static FileTime getCreationTime(ZipEntry entry) {
        if (Build.VERSION.SDK_INT >= 26) {
            return entry.getCreationTime();
        } else {
            return null;
        }
    }

    /**
     * Within {@link ZipArchiveOutputStream#copyFromZipInputStream}, we redirect the method call
     * {@link ZipUtil#checkRequestedFeatures} to this method. This is safe because the only usage
     * of copyFromZipInputStream is in {@link ZipArchiveOutputStream#addRawArchiveEntry},
     * which does not need to actually understand the content of the zip entry. By removing
     * this feature check, we can modify zip files using unsupported compression methods.
     */
    public static void checkRequestedFeatures(final ZipArchiveEntry ze) {
        // No-op
    }
}
