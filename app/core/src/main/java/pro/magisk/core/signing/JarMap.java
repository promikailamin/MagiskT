/**
 * Abstract in-memory representation of a JAR file, used during APK signing.
 *
 * <p>Supports two backing implementations:
 * <ul>
 *   <li>{@link FileMap} — backed by a {@link JarFile} on disk (random access)</li>
 *   <li>{@link StreamMap} — backed by a {@link JarInputStream} (sequential, reads all entries
 *       eagerly into memory)</li>
 * </ul>
 *
 * <p>Entries written via {@link #getOutputStream} are buffered in {@link ByteArrayStream}
 * and take precedence over backing-source entries when read back.
 */
package pro.magisk.core.signing;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public abstract class JarMap implements Closeable {

    LinkedHashMap<String, JarEntry> entry_map;

    /** Opens a JAR from a file on disk. {@code verify} enables JAR signature verification. */
    public static JarMap open(File file, boolean verify) throws IOException {
        return new FileMap(file, verify, ZipFile.OPEN_READ);
    }

    /** Opens a JAR from an input stream (reads all entries eagerly into memory). */
    public static JarMap open(InputStream is, boolean verify) throws IOException {
        return new StreamMap(is, verify);
    }

    /** Returns the backing {@link File} if this map is file-backed, or {@code null}. */
    public File get_file() {
        return null;
    }

    /** Returns the JAR {@link Manifest}, or {@code null} if none exists. */
    public abstract Manifest getManifest() throws IOException;

    /**
     * Returns an {@link InputStream} for reading the given ZIP entry's data.
     * Checks the in-memory entry map first, then falls back to the backing source.
     */
    public InputStream getInputStream(ZipEntry ze) throws IOException {
        JarMapEntry e = get_map_entry(ze.getName());
        return e != null ? e.data.getInputStream() : null;
    }

    /**
     * Returns an {@link OutputStream} for writing (or overwriting) a ZIP entry.
     * Written data is buffered in memory and takes precedence over the backing source.
     */
    public OutputStream get_output_stream(ZipEntry ze) {
        if (entry_map == null)
            entry_map = new LinkedHashMap<>();
        JarMapEntry e = new JarMapEntry(ze.getName());
        entry_map.put(ze.getName(), e);
        return e.data;
    }

    /**
     * Returns the raw byte content for a ZIP entry, or {@code null} if not found.
     */
    public byte[] get_raw_data(ZipEntry ze) throws IOException {
        JarMapEntry e = get_map_entry(ze.getName());
        return e != null ? e.data.toByteArray() : null;
    }

    /** Returns an enumeration of all JAR entries. */
    public abstract Enumeration<JarEntry> entries();

    public final ZipEntry getEntry(String name) {
        return getJarEntry(name);
    }

    public JarEntry getJarEntry(String name) {
        return get_map_entry(name);
    }

    /** Looks up an entry by name in the in-memory entry map (thread-safe via synchronization). */
    JarMapEntry get_map_entry(String name) {
        JarMapEntry e = null;
        if (entry_map != null)
            e = (JarMapEntry) entry_map.get(name);
        return e;
    }

    /** File-backed implementation: delegates to {@link JarFile} and falls back to in-memory map. */
    private static class FileMap extends JarMap {

        private JarFile jar_file;

        FileMap(File file, boolean verify, int mode) throws IOException {
            jar_file = new JarFile(file, verify, mode);
        }

        @Override
        public File get_file() {
            return new File(jar_file.getName());
        }

        @Override
        public Manifest getManifest() throws IOException {
            return jar_file.getManifest();
        }

        @Override
        public InputStream getInputStream(ZipEntry ze) throws IOException {
            InputStream is = super.getInputStream(ze);
            return is != null ? is : jar_file.getInputStream(ze);
        }

        @Override
        public byte[] get_raw_data(ZipEntry ze) throws IOException {
            byte[] b = super.get_raw_data(ze);
            if (b != null)
                return b;
            ByteArrayStream bytes = new ByteArrayStream();
            bytes.read_from(jar_file.getInputStream(ze));
            return bytes.toByteArray();
        }

        @Override
        public Enumeration<JarEntry> entries() {
            return jar_file.entries();
        }

        @Override
        public JarEntry getJarEntry(String name) {
            JarEntry e = get_map_entry(name);
            return e != null ? e : jar_file.getJarEntry(name);
        }

        @Override
        public void close() throws IOException {
            jar_file.close();
        }
    }

    /** Stream-backed implementation: reads all entries eagerly into memory via {@link JarInputStream}. */
    private static class StreamMap extends JarMap {

        private JarInputStream jis;

        StreamMap(InputStream is, boolean verify) throws IOException {
            jis = new JarInputStream(is, verify);
            entry_map = new LinkedHashMap<>();
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                entry_map.put(entry.getName(), new JarMapEntry(entry, jis));
            }
        }

        @Override
        public Manifest getManifest() {
            return jis.getManifest();
        }

        @Override
        public Enumeration<JarEntry> entries() {
            return Collections.enumeration(entry_map.values());
        }

        @Override
        public void close() throws IOException {
            jis.close();
        }
    }

    /** A {@link JarEntry} with an in-memory {@link ByteArrayStream} for its data. */
    private static class JarMapEntry extends JarEntry {

        ByteArrayStream data;

        JarMapEntry(JarEntry je, InputStream is) {
            super(je);
            data = new ByteArrayStream();
            data.read_from(is);
        }

        JarMapEntry(String s) {
            super(s);
            data = new ByteArrayStream();
        }
    }
}
