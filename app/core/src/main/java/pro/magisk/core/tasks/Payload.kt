/**
 * ChromeOS update payload parser (CrAU format, version 2).
 *
 * Reads the delta payload header and manifest, locates the `init_boot`
 * or `boot` partition, applies [InstallOperation]s (REPLACE,
 * REPLACE_BZ, REPLACE_XZ, ZERO) to reconstruct the partition image,
 * and verifies the SHA-256 hash.
 *
 * Used by [ExtractImage] to extract boot images from OTA packages.
 */
package pro.magisk.core.tasks

import chromeos_update_engine.DeltaArchiveManifest
import chromeos_update_engine.InstallOperation
import chromeos_update_engine.PartitionUpdate
import pro.magisk.core.utils.DataSourceChannel
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class Payload(private val channel: DataSourceChannel) {
    private val manifest: DeltaArchiveManifest
    private var data_base = 0L

    init {
        manifest = read_payload_header()
    }

    /**
     * Extract the boot partition image from the payload.
     *
     * @param outputFile Destination file for the extracted image.
     * @param console    Callback for progress messages.
     * @param logger     Callback for diagnostic messages.
     */
    @Throws(IOException::class)
    fun extract(outputFile: File, console: (String) -> Unit, logger: (String) -> Unit) {
        val partition = find_partition()
        console("- Found partition ${partition.partition_name}")

        val actual_hash = extract_partition(outputFile, partition, console)

        val new_partition_info = partition.new_partition_info
        if (new_partition_info?.hash == null) {
            logger("Hash verification skipped")
            return
        }

        fun to_hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        val expected_hash = new_partition_info.hash.toByteArray()
        if (!expected_hash.contentEquals(actual_hash)) {
            throw IOException(
                "Hash mismatch, expected ${to_hex(expected_hash)}, but got ${to_hex(actual_hash)}"
            )
        }
        logger("Hash verification passed")
    }

    /** Parse the payload header (magic, version, manifest, signature). */
    @Throws(IOException::class)
    private fun read_payload_header(): DeltaArchiveManifest {
        val magic_buffer = ByteBuffer.allocate(4)
        channel.read(magic_buffer)
        magic_buffer.flip()
        val magic = String(magic_buffer.array())
        if (magic != "CrAU") {
            throw IOException("Invalid payload: invalid magic")
        }

        val version_buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        channel.read(version_buffer)
        version_buffer.flip()
        val version = version_buffer.long
        if (version != 2L) {
            throw IOException("Invalid payload: unsupported version: $version")
        }

        val manifest_len_buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        channel.read(manifest_len_buffer)
        manifest_len_buffer.flip()
        val manifest_len = manifest_len_buffer.long.toInt()
        if (manifest_len == 0) {
            throw IOException("Invalid payload: manifest length is zero")
        }

        val manifest_sig_len_buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        channel.read(manifest_sig_len_buffer)
        manifest_sig_len_buffer.flip()
        val manifest_sig_len = manifest_sig_len_buffer.int
        if (manifest_sig_len == 0) {
            throw IOException("Invalid payload: manifest signature length is zero")
        }

        val manifest_buffer = ByteBuffer.allocate(manifest_len)
        channel.read(manifest_buffer)
        manifest_buffer.flip()
        val manifest = DeltaArchiveManifest.ADAPTER.decode(manifest_buffer.array())

        channel.position(channel.position() + manifest_sig_len)

        data_base = channel.position()

        return manifest
    }

    /** Find the `init_boot` or `boot` partition in the manifest. */
    @Throws(IOException::class)
    private fun find_partition(): PartitionUpdate {
        return manifest.partitions.find { it.partition_name == "init_boot" }
            ?: manifest.partitions.find { it.partition_name == "boot" }
            ?: throw IOException("boot partition not found in payload")
    }

    /** Reconstruct the partition image by applying all operations. */
    @Throws(IOException::class)
    private fun extract_partition(
        outputFile: File,
        partition: PartitionUpdate,
        console: (String) -> Unit,
    ): ByteArray {
        FileChannel.open(
            outputFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { out_channel ->
            val size = partition.new_partition_info?.size ?: 0L
            out_channel.write(ByteBuffer.allocate(1), size - 1)

            val count = partition.operations.size
            partition.operations.forEachIndexed { index, operation ->
                if (index % 5 == 0 || index == count - 1) {
                    console("- Downloading ${index + 1}/$count")
                }
                process_operation(out_channel, operation)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = out_channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
            digest.update(buffer)
            return digest.digest()
        }
    }

    /** Apply a single [InstallOperation] to the output file. */
    @Throws(IOException::class)
    private fun process_operation(out_channel: FileChannel, operation: InstallOperation) {
        val data_type = operation.type
        if (data_type == InstallOperation.Type.ZERO) {
            return
        }

        val data_buffer = ByteBuffer.allocate(operation.data_length?.toInt() ?: 0)
        channel.read(data_buffer, data_base + (operation.data_offset ?: 0L))
        data_buffer.flip()

        val dst_extent = operation.dst_extents[0]
        val out_offset = (dst_extent.start_block ?: 0L) * (manifest.block_size ?: 4096)

        when (data_type) {
            InstallOperation.Type.REPLACE -> {
                out_channel.write(data_buffer, out_offset)
            }

            InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ -> {
                val inputStream = data_buffer.array().inputStream()
                if (data_type == InstallOperation.Type.REPLACE_BZ) {
                    BZip2CompressorInputStream(inputStream)
                } else {
                    XZCompressorInputStream(inputStream)
                }.use { decompressor ->
                    val bytes = ByteArray(8192)
                    var bytes_read: Int
                    var bytes_written = 0
                    while (decompressor.read(bytes).also { bytes_read = it } != -1) {
                        val buffer = ByteBuffer.wrap(bytes, 0, bytes_read)
                        bytes_written += out_channel.write(buffer, out_offset + bytes_written)
                    }
                }
            }

            else -> throw IOException("Unsupported operation type: $data_type")
        }
    }
}
