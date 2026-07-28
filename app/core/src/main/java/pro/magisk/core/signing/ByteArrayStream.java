/**
 * Extended {@link java.io.ByteArrayOutputStream} with convenience methods for reading from
 * {@link InputStream} and converting to {@link ByteBuffer} or {@link ByteArrayInputStream}.
 *
 * <p>Used internally by the APK signing pipeline to buffer ZIP entry contents during
 * re-signing and repacking.
 */
package pro.magisk.core.signing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ByteArrayStream extends ByteArrayOutputStream {

    /** Reads all available data from the given input stream into this buffer. */
    public synchronized void readFrom(InputStream is) {
        readFrom(is, Integer.MAX_VALUE);
    }

    /**
     * Reads up to {@code len} bytes from the given input stream into this buffer.
     *
     * @param is  source input stream
     * @param len maximum number of bytes to read
     */
    public synchronized void readFrom(InputStream is, int len) {
        int read;
        byte buffer[] = new byte[4096];
        try {
            while ((read = is.read(buffer, 0, Math.min(len, buffer.length))) > 0) {
                write(buffer, 0, read);
                len -= read;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Returns a new {@link ByteArrayInputStream} over the buffered data. */
    public ByteArrayInputStream getInputStream() {
        return new ByteArrayInputStream(buf, 0, count);
    }

    /** Returns a {@link ByteBuffer} wrapping the buffered data. */
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(buf, 0, count);
    }
}
