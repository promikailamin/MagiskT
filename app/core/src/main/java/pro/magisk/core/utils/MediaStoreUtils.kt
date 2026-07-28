/**
 * Utilities for interacting with [MediaStore] on Android 10+ and falling
 * back to raw file I/O on older releases. Handles inserting, querying,
 * and deleting files in the Downloads collection, and provides extension
 * properties on [Uri] for display name and streaming.
 */
package pro.magisk.core.utils

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import androidx.core.net.toUri
import pro.magisk.core.AppContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@Suppress("DEPRECATION")
object MediaStoreUtils {

    private val cr get() = AppContext.contentResolver

    private fun relativePath(name: String) =
        if (name.isEmpty()) Environment.DIRECTORY_DOWNLOADS
        else Environment.DIRECTORY_DOWNLOADS + File.separator + name

    fun fullPath(name: String): String =
        File(Environment.getExternalStorageDirectory(), relativePath(name)).canonicalPath

    private val downloadPath get() = relativePath("")

    /** Insert a new file into MediaStore Downloads (API 30+). */
    @RequiresApi(api = 30)
    @Throws(IOException::class)
    private fun insertFile(displayName: String): MediaStoreFile {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, downloadPath)
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)

        val fileUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Can't insert $displayName.")

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        cr.query(fileUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idIndex)
                val data = cursor.getString(dataColumn)
                return MediaStoreFile(id, data)
            }
        }

        throw IOException("Can't insert $displayName.")
    }

    /** Look up an existing file in MediaStore by display name (API 29+). */
    @RequiresApi(api = 29)
    private fun queryFile(displayName: String): UriFile? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} == ?"
        val selectionArgs = arrayOf(displayName)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val query = cr.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder)
        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val data = cursor.getString(dataColumn)
                if (data.endsWith(downloadPath + File.separator + displayName)) {
                    return MediaStoreFile(id, data)
                }
            }
        }
        return null
    }

    /**
     * Obtain a [UriFile] for the given display name. Uses MediaStore
     * on API 30+ and falls back to raw file I/O on older versions.
     */
    @Throws(IOException::class)
    fun getFile(displayName: String): UriFile {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val parent = File(Environment.getExternalStorageDirectory(), downloadPath)
            parent.mkdirs()
            LegacyUriFile(File(parent, displayName))
        } else {
            queryFile(displayName) ?: insertFile(displayName)
        }
    }

    /** Open the content URI for reading. */
    fun Uri.inputStream() = cr.openInputStream(this) ?: throw FileNotFoundException()

    /** Open the content URI for writing ("rwt" = read-write truncate). */
    fun Uri.outputStream() = cr.openOutputStream(this, "rwt") ?: throw FileNotFoundException()

    /** Open a file descriptor for reading. */
    fun Uri.openFd() = cr.openFileDescriptor(this, "r") ?: throw FileNotFoundException()

    /** Resolve the display name of a content or file URI. */
    val Uri.displayName: String get() {
        if (scheme == "file") {
            return toFile().name
        }
        require(scheme == "content") { "Uri lacks 'content' scheme: $this" }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        cr.query(this, projection, null, null, null)?.use { cursor ->
            val displayNameColumn = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                return cursor.getString(displayNameColumn)
            }
        }
        return this.toString()
    }

    /** Minimal abstraction over an on-disk file reference. */
    interface UriFile {
        val uri: Uri
        fun delete(): Boolean
    }

    /** [UriFile] backed by a raw [File] (pre-Android 11). */
    private class LegacyUriFile(private val file: File) : UriFile {
        override val uri = file.toUri()
        override fun delete() = file.delete()
        override fun toString() = file.toString()
    }

    /** [UriFile] backed by a MediaStore entry (API 29+). */
    @RequiresApi(api = 29)
    private class MediaStoreFile(private val id: Long, private val data: String) : UriFile {
        override val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        override fun toString() = data
        override fun delete(): Boolean {
            val selection = "${MediaStore.MediaColumns._ID} == ?"
            val selectionArgs = arrayOf(id.toString())
            return cr.delete(uri, selection, selectionArgs) == 1
        }
    }
}
