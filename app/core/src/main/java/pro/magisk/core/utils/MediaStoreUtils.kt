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

    private fun relative_path(name: String) =
        if (name.isEmpty()) Environment.DIRECTORY_DOWNLOADS
        else Environment.DIRECTORY_DOWNLOADS + File.separator + name

    fun full_path(name: String): String =
        File(Environment.getExternalStorageDirectory(), relative_path(name)).canonicalPath

    private val download_path get() = relative_path("")

    /** Insert a new file into MediaStore Downloads (API 30+). */
    @RequiresApi(api = 30)
    @Throws(IOException::class)
    private fun insert_file(display_name: String): MediaStoreFile {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, download_path)
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, display_name)

        val file_uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Can't insert $display_name.")

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        cr.query(file_uri, projection, null, null, null)?.use { cursor ->
            val id_index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val data_column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(id_index)
                val data = cursor.getString(data_column)
                return MediaStoreFile(id, data)
            }
        }

        throw IOException("Can't insert $display_name.")
    }

    /** Look up an existing file in MediaStore by display name (API 29+). */
    @RequiresApi(api = 29)
    private fun query_file(display_name: String): UriFile? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} == ?"
        val selection_args = arrayOf(display_name)
        val sort_order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val query = cr.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selection_args, sort_order)
        query?.use { cursor ->
            val id_column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val data_column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(id_column)
                val data = cursor.getString(data_column)
                if (data.endsWith(download_path + File.separator + display_name)) {
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
    fun get_file(display_name: String): UriFile {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val parent = File(Environment.getExternalStorageDirectory(), download_path)
            parent.mkdirs()
            LegacyUriFile(File(parent, display_name))
        } else {
            query_file(display_name) ?: insert_file(display_name)
        }
    }

    /** Open the content URI for reading. */
    fun Uri.inputStream() = cr.openInputStream(this) ?: throw FileNotFoundException()

    /** Open the content URI for writing ("rwt" = read-write truncate). */
    fun Uri.output_stream() = cr.openOutputStream(this, "rwt") ?: throw FileNotFoundException()

    /** Open a file descriptor for reading. */
    fun Uri.openFd() = cr.openFileDescriptor(this, "r") ?: throw FileNotFoundException()

    /** Resolve the display name of a content or file URI. */
    val Uri.display_name: String get() {
        if (scheme == "file") {
            return toFile().name
        }
        require(scheme == "content") { "Uri lacks 'content' scheme: $this" }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        cr.query(this, projection, null, null, null)?.use { cursor ->
            val display_name_column = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                return cursor.getString(display_name_column)
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
            val selection_args = arrayOf(id.toString())
            return cr.delete(uri, selection, selection_args) == 1
        }
    }
}
