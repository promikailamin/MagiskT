/**
 * A read-only {@link SeekableByteChannel} over a slice of a {@link FileChannel} with
 * an adaptive two-tier caching strategy.
 *
 * <p>The cache switches between:
 * <ul>
 *   <li><b>Random-access cache</b> (16 KB) — centered around the read position for small,
 *       non-sequential reads.</li>
 *   <li><b>Sequential cache</b> (1 MB) — loaded forward from the current position when
 *       sequential access is detected.</li>
 * </ul>
 *
 * <p>Reads larger than 512 KB bypass the cache entirely and go directly to the backing channel.
 * Slices can be created via {@link #slice} without allocating new channel resources.
 */
package pro.magisk.core.utils;

import org.apache.commons.io.input.BoundedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

public class DataSourceChannel implements SeekableByteChannel {
    private static final int RANDOM_READ_CACHE_SIZE = 16 * 1024;
    private static final int SEQ_READ_CACHE_SIZE = 1024 * 1024;
    private static final int SEQ_READ_THRESHOLD = 1024;
    private static final int DIRECT_READ_THRESHOLD = 512 * 1024;

    private final FileChannel file_channel;
    private final long startOffset;
    private final long size;

    private long position = 0;
    private boolean open = true;

    private byte[] cache = null;
    private long cacheStart = -1;

    private DataSourceChannel(FileChannel file_channel,
                              long startOffset, long size) {
        this.file_channel = file_channel;
        this.startOffset = startOffset;
        this.size = size;
    }

    /** Wraps the entire {@link FileChannel} as a readable channel. */
    public DataSourceChannel(FileChannel file_channel) throws IOException {
        this(file_channel, 0, file_channel.size());
    }

    /**
     * Creates a logical slice of this channel without opening a new file descriptor.
     * Returns {@code this} if the slice covers the entire range.
     */
    public DataSourceChannel slice(long offset, long sliceSize) {
        if (offset == 0 && sliceSize == size) {
            return this;
        }
        if (offset < 0 || sliceSize <= 0 || offset + sliceSize >= size) {
            throw new IllegalArgumentException("Invalid slice parameters");
        }
        return new DataSourceChannel(file_channel, startOffset + offset, sliceSize);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        var bytes_read = read(dst, position);
        position += bytes_read;
        return bytes_read;
    }

    /**
     * Reads up to {@code dst.remaining()} bytes starting at the given absolute position.
     * Uses the adaptive cache for small/moderate reads and direct I/O for large reads.
     */
    public int read(ByteBuffer dst, long position) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (position < 0) {
            throw new IllegalArgumentException("Position out of bounds: " + position);
        }
        if (position >= size) return -1;

        int requestSize = dst.remaining();
        if (requestSize == 0) return 0;

        if (requestSize > DIRECT_READ_THRESHOLD) {
            return handle_large_read(dst, position);
        }

        int totalBytesRead = 0;
        if (is_cache_hit(position, 1)) {
            int bytesFromCache = read_from_cache(dst, position);
            totalBytesRead += bytesFromCache;
            position += bytesFromCache;
        }

        if (dst.hasRemaining() && position < size) {
            load_cache(position, requestSize);
            if (is_cache_hit(position, dst.remaining())) {
                totalBytesRead += read_from_cache(dst, position);
            } else {
                totalBytesRead += read_directly(dst, position);
            }
        }

        return totalBytesRead;
    }

    private int handle_large_read(ByteBuffer dst, long position) throws IOException {
        int bytesFromCache = 0;
        if (is_cache_hit(position, 1)) {
            bytesFromCache = read_from_cache(dst, position);
            position += bytesFromCache;
        }

        if (dst.hasRemaining() && position < size) {
            int directBytesRead = read_directly(dst, position);
            return bytesFromCache + directBytesRead;
        } else {
            return bytesFromCache;
        }
    }

    private void load_cache(long requestPos, int requestSize) throws IOException {
        int cacheSize;
        long cacheStart;

        var last_cache_end = cache != null ? this.cacheStart + cache.length : -1;
        if (requestSize > SEQ_READ_THRESHOLD || last_cache_end == requestPos) {
            cacheSize = SEQ_READ_CACHE_SIZE;
            cacheStart = requestPos;
        } else {
            cacheSize = RANDOM_READ_CACHE_SIZE;
            cacheStart = Math.max(0, requestPos - cacheSize / 2);
        }

        load_cache_at(cacheStart, cacheSize);
    }

    private void load_cache_at(long cacheStart, int cacheSize) throws IOException {
        long maxEnd = Math.min(cacheStart + cacheSize, size);
        cacheStart = Math.max(0, maxEnd - cacheSize);

        var buffer = ByteBuffer.allocate((int) (maxEnd - cacheStart));
        var bytes_read = read_directly(buffer, cacheStart);
        if (bytes_read != buffer.capacity()) {
            throw new IOException("Failed to fill cache.");
        }

        cache = buffer.array();
        this.cacheStart = cacheStart;

    }

    private boolean is_cache_hit(long pos, int bytesToRead) {
        if (cache == null) return false;
        long cacheEnd = cacheStart + cache.length;
        long readEnd = Math.min(pos + bytesToRead, size);
        return pos >= cacheStart && readEnd <= cacheEnd;
    }

    private int read_from_cache(ByteBuffer dst, long position) {
        long relativePos = position - cacheStart;
        int available = (int) Math.min(dst.remaining(), cache.length - relativePos);

        dst.put(cache, (int) relativePos, available);

        return available;
    }

    private int read_directly(ByteBuffer dst, long position) throws IOException {
        try (var channel = Channels.newChannel(stream_read(position, dst.remaining()))) {
            int totalBytesRead = 0;
            while (true) {
                int bytes_read = channel.read(dst);
                if (bytes_read <= 0) {
                    break;
                }
                totalBytesRead += bytes_read;
            }

            return totalBytesRead;
        }
    }

    /**
     * Opens a bounded {@link InputStream} over the specified range of data.
     * The returned stream reads from the backing {@link FileChannel} directly.
     */
    public InputStream stream_read(long position, long length) throws IOException {
        long endPosition = Math.min(position + length, size) + startOffset;
        var start_position = startOffset + position;
        var read_length = endPosition - start_position;

        if (file_channel != null) {
            file_channel.position(start_position);
            return BoundedInputStream.builder()
                    .setInputStream(Channels.newInputStream(file_channel))
                    .setMaxCount(read_length)
                    .setPropagateClose(false)
                    .get();
        }

        return null;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public DataSourceChannel position(long newPosition) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (newPosition < 0) {
            throw new IllegalArgumentException("Position out of bounds: " + newPosition);
        }
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
        cache = null;
        if (file_channel != null) {
            file_channel.close();
        }
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public DataSourceChannel truncate(long size) {
        throw new NonWritableChannelException();
    }
}
