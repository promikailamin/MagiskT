/**
 * Extracts a boot image from either an OTA payload ZIP, a factory
 * image ZIP, or a nested inner image ZIP.
 *
 * The boot image is written to a local [outFile] and may be
 * compressed with STORED or DEFLATED methods.
 */
package pro.magisk.core.tasks

import pro.magisk.core.utils.DataSourceChannel
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class ExtractImage(
    private val out_file: File,
    private val console: MutableList<String>,
    private val logs: MutableList<String>,
) {
    /**
     * Process the given [DataSourceChannel] (backed by a ZIP).
     *
     * Determines automatically whether the archive is an OTA package
     * (contains `payload.bin`) or a factory image, then extracts the
     * boot image to [outFile].
     */
    @Throws(IOException::class)
    fun consume(channel: DataSourceChannel) {
        ZipFile.builder()
            .setSeekableByteChannel(channel)
            .setIgnoreLocalFileHeader(true)
            .get().use { zip_file ->
                val payload = zip_file.getEntry("payload.bin")
                if (payload != null) {
                    console.add("- Processing as OTA package")

                    zip_file.getEntry("META-INF/com/android/metadata")?.let { entry ->
                        zip_file.getInputStream(entry).use {
                            val meta = it.bufferedReader().readText()
                            logs.add(meta)

                            console.add("- OTA metadata:")
                            meta.lines().forEach { line ->
                                if (line.startsWith("post-")) {
                                    console.add("  ${line.substringAfter('-')}")
                                }
                            }
                        }
                    }
                    zip_file.getRawInputStream(payload)
                    extract_from_o_t_a_package(payload, channel, out_file)
                } else {
                    extract_from_factory_image(zip_file, channel, out_file)
                }
            }
    }

    @Throws(IOException::class)
    private fun extract_from_o_t_a_package(
        payload: ZipArchiveEntry,
        channel: DataSourceChannel,
        out_file: File,
    ) {
        if (payload.method != ZipMethod.STORED.code) {
            throw IOException("payload.bin is compressed, expected STORED method")
        }

        channel.slice(payload.dataOffset, payload.size).use { payloadChannel ->
            Payload(payloadChannel).extract(out_file, { console.add(it) }, { logs.add(it) })
        }
    }

    /** Walk a factory image ZIP looking for a boot image entry. */
    @Throws(IOException::class)
    private fun extract_from_factory_image(
        zip_file: ZipFile,
        channel: DataSourceChannel,
        out_file: File
    ) {
        console.add("- Processing as factory image package")

        find_boot_image_zip_entry(zip_file)?.let { entry ->
            return extract_image_file(zip_file, entry, channel, out_file)
        }

        val image_zip_entry = zip_file.entries.asSequence().find { entry ->
            val file_name = entry.name.substringAfterLast('/')
            file_name.startsWith("image-") && file_name.endsWith(".zip")
        }
        if (image_zip_entry != null) {
            zip_file.getRawInputStream(image_zip_entry)
            return extract_from_inner_image_zip(image_zip_entry, channel, out_file)
        }

        throw IOException("inner image ZIP not found in factory image package")
    }

    /** Find the `init_boot.img` or `boot.img` entry in a ZIP. */
    private fun find_boot_image_zip_entry(zip_file: ZipFile): ZipArchiveEntry? {
        return zip_file.entries.asSequence().find {
            it.name.substringAfterLast('/') == "init_boot.img"
        } ?: zip_file.entries.asSequence().find {
            it.name.substringAfterLast('/') == "boot.img"
        }
    }

    /** Extract from an inner image ZIP nested inside the factory package. */
    @Throws(IOException::class)
    private fun extract_from_inner_image_zip(
        entry: ZipArchiveEntry,
        channel: DataSourceChannel,
        out_file: File
    ) {
        logs.add("Found inner image ZIP: ${entry.name}")

        if (entry.method != ZipMethod.STORED.code) {
            throw IOException("image ZIP is compressed, expected STORED method")
        }

        channel.slice(entry.dataOffset, entry.size).use { innerZipChannel ->
            ZipFile.builder()
                .setSeekableByteChannel(innerZipChannel)
                .setIgnoreLocalFileHeader(true)
                .get().use { innerZipFile ->
                    val target_entry = find_boot_image_zip_entry(innerZipFile)
                        ?: throw IOException("boot image not found in inner image ZIP")
                    return extract_image_file(innerZipFile, target_entry, innerZipChannel, out_file)
                }
        }
    }

    /** Extract a single boot image entry from a ZIP to [outFile]. */
    @Throws(IOException::class)
    private fun extract_image_file(
        zip_file: ZipFile,
        entry: ZipArchiveEntry,
        channel: DataSourceChannel,
        out_file: File,
    ) {
        console.add("- Found boot image entry: ${entry.name} (${entry.size} bytes)")
        console.add("- Downloading")

        zip_file.getRawInputStream(entry)
        when (entry.method) {
            ZipMethod.STORED.code -> {
                FileChannel.open(
                    out_file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { file_channel ->
                    val mapped = file_channel.map(FileChannel.MapMode.READ_WRITE, 0, entry.size)
                    val source_channel = channel.slice(entry.dataOffset, entry.size)
                    source_channel.read(mapped)
                }
            }

            ZipMethod.DEFLATED.code -> {
                InflaterInputStream(
                    channel.stream_read(entry.dataOffset, entry.size),
                    Inflater(true),
                    16 * 1024
                ).use { input ->
                    FileOutputStream(out_file).use { out ->
                        input.copyTo(out)
                    }
                }
            }

            else -> throw IOException("unsupported method: ${entry.method}")
        }
    }
}
