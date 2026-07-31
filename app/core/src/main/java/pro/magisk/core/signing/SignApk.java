package pro.magisk.core.signing;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * APK signing pipeline that produces both JAR (v1) and APK Signature Scheme v2 signatures.
 *
 * <p>The signing process:
 * <ol>
 *   <li>Computes digests of every file in the input JAR and produces {@code MANIFEST.MF}</li>
 *   <li>Generates the {@code CERT.SF} signature file with a digest of the manifest</li>
 *   <li>Generates the CMS/PKCS#7 {@code CERT.RSA} (or {@code .EC}) signature block</li>
 *   <li>Re-packs the JAR with deterministic timestamps and alignment for {@code .so} files</li>
 *   <li>Wraps the JAR-signed APK in an APK Signature Scheme v2 block</li>
 * </ol>
 *
 * <p>Modified from AOSP SignApk (android-7.1.2_r39).
 *
 * @see <a href="https://android.googlesource.com/platform/build/+/refs/tags/android-7.1.2_r39/tools/signapk/src/com/android/signapk/SignApk.java">AOSP source</a>
 */
public class SignApk {
    private static final String CERT_SF_NAME = "META-INF/CERT.SF";
    private static final String CERT_SIG_NAME = "META-INF/CERT.%s";
    private static final String CERT_SF_MULTI_NAME = "META-INF/CERT%d.SF";
    private static final String CERT_SIG_MULTI_NAME = "META-INF/CERT%d.%s";

    // bitmasks for which hash algorithms we need the manifest to include.
    private static final int USE_SHA1 = 1;
    private static final int USE_SHA256 = 2;

    /**
     * Digest algorithm used when signing the APK using APK Signature Scheme v2.
     */
    private static final String APK_SIG_SCHEME_V2_DIGEST_ALGORITHM = "SHA-256";
    // Files matching this pattern are not copied to the output.
    private static final Pattern strip_pattern =
            Pattern.compile("^(META-INF/((.*)[.](SF|RSA|DSA|EC)|com/android/otacert))|(" +
                    Pattern.quote(JarFile.MANIFEST_NAME) + ")$");

    /**
     * Return one of USE_SHA1 or USE_SHA256 according to the signature
     * algorithm specified in the cert.
     */
    private static int get_digest_algorithm(X509Certificate cert) {
        String sig_alg = cert.getSigAlgName().toUpperCase(Locale.US);
        if ("SHA1WITHRSA".equals(sig_alg) || "MD5WITHRSA".equals(sig_alg)) {
            return USE_SHA1;
        } else if (sig_alg.startsWith("SHA256WITH")) {
            return USE_SHA256;
        } else {
            throw new IllegalArgumentException("unsupported signature algorithm \"" + sig_alg +
                    "\" in cert [" + cert.getSubjectDN());
        }
    }

    /**
     * Returns the expected signature algorithm for this key type.
     */
    private static String get_signature_algorithm(X509Certificate cert) {
        String key_type = cert.getPublicKey().getAlgorithm().toUpperCase(Locale.US);
        if ("RSA".equalsIgnoreCase(key_type)) {
            if (get_digest_algorithm(cert) == USE_SHA256) {
                return "SHA256withRSA";
            } else {
                return "SHA1withRSA";
            }
        } else if ("EC".equalsIgnoreCase(key_type)) {
            return "SHA256withECDSA";
        } else {
            throw new IllegalArgumentException("unsupported key type: " + key_type);
        }
    }

    /**
     * Add the hash(es) of every file to the manifest, creating it if
     * necessary.
     */
    private static Manifest add_digests_to_manifest(JarMap jar, int hashes)
            throws IOException, GeneralSecurityException {
        Manifest input = jar.getManifest();
        Manifest output = new Manifest();
        Attributes main = output.getMainAttributes();
        if (input != null) {
            main.putAll(input.getMainAttributes());
        } else {
            main.putValue("Manifest-Version", "1.0");
            main.putValue("Created-By", "1.0 (Android SignApk)");
        }

        MessageDigest md_sha1 = null;
        MessageDigest md_sha256 = null;
        if ((hashes & USE_SHA1) != 0) {
            md_sha1 = MessageDigest.getInstance("SHA1");
        }
        if ((hashes & USE_SHA256) != 0) {
            md_sha256 = MessageDigest.getInstance("SHA256");
        }

        byte[] buffer = new byte[4096];
        int num;

        // We sort the input entries by name, and add them to the
        // output manifest in sorted order.  We expect that the output
        // map will be deterministic.

        TreeMap<String, JarEntry> by_name = new TreeMap<>();

        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            by_name.put(entry.getName(), entry);
        }

        for (JarEntry entry : by_name.values()) {
            String name = entry.getName();
            if (!entry.isDirectory() && !strip_pattern.matcher(name).matches()) {
                InputStream data = jar.getInputStream(entry);
                while ((num = data.read(buffer)) > 0) {
                    if (md_sha1 != null) md_sha1.update(buffer, 0, num);
                    if (md_sha256 != null) md_sha256.update(buffer, 0, num);
                }

                Attributes attr = null;
                if (input != null) attr = input.getAttributes(name);
                attr = attr != null ? new Attributes(attr) : new Attributes();
                // Remove any previously computed digests from this entry's attributes.
                for (Iterator<Object> i = attr.keySet().iterator(); i.hasNext(); ) {
                    Object key = i.next();
                    if (!(key instanceof Attributes.Name)) {
                        continue;
                    }
                    String attribute_name_lower_case =
                            key.toString().toLowerCase(Locale.US);
                    if (attribute_name_lower_case.endsWith("-digest")) {
                        i.remove();
                    }
                }
                // Add SHA-1 digest if requested
                if (md_sha1 != null) {
                    attr.putValue("SHA1-Digest",
                            new String(Base64.encode(md_sha1.digest()), "ASCII"));
                }
                // Add SHA-256 digest if requested
                if (md_sha256 != null) {
                    attr.putValue("SHA-256-Digest",
                            new String(Base64.encode(md_sha256.digest()), "ASCII"));
                }
                output.getEntries().put(name, attr);
            }
        }

        return output;
    }

    /**
     * Write a .SF file with a digest of the specified manifest.
     */
    private static void write_signature_file(Manifest manifest, OutputStream out,
                                           int hash)
            throws IOException, GeneralSecurityException {
        Manifest sf = new Manifest();
        Attributes main = sf.getMainAttributes();
        main.putValue("Signature-Version", "1.0");
        main.putValue("Created-By", "1.0 (Android SignApk)");
        // Add APK Signature Scheme v2 signature stripping protection.
        // This attribute indicates that this APK is supposed to have been signed using one or
        // more APK-specific signature schemes in addition to the standard JAR signature scheme
        // used by this code. APK signature verifier should reject the APK if it does not
        // contain a signature for the signature scheme the verifier prefers out of this set.
        main.putValue(
                ApkSignerV2.SF_ATTRIBUTE_ANDROID_APK_SIGNED_NAME,
                ApkSignerV2.SF_ATTRIBUTE_ANDROID_APK_SIGNED_VALUE);

        MessageDigest md = MessageDigest.getInstance(hash == USE_SHA256 ? "SHA256" : "SHA1");
        PrintStream print = new PrintStream(new DigestOutputStream(new ByteArrayOutputStream(), md),
                true, "UTF-8");

        // Digest of the entire manifest
        manifest.write(print);
        print.flush();
        main.putValue(hash == USE_SHA256 ? "SHA-256-Digest-Manifest" : "SHA1-Digest-Manifest",
                new String(Base64.encode(md.digest()), "ASCII"));

        Map<String, Attributes> entries = manifest.getEntries();
        for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
            // Digest of the manifest stanza for this entry.
            print.print("Name: " + entry.getKey() + "\r\n");
            for (Map.Entry<Object, Object> att : entry.getValue().entrySet()) {
                print.print(att.getKey() + ": " + att.getValue() + "\r\n");
            }
            print.print("\r\n");
            print.flush();

            Attributes sf_attr = new Attributes();
            sf_attr.putValue(hash == USE_SHA256 ? "SHA-256-Digest" : "SHA1-Digest",
                    new String(Base64.encode(md.digest()), "ASCII"));
            sf.getEntries().put(entry.getKey(), sf_attr);
        }

        CountOutputStream cout = new CountOutputStream(out);
        sf.write(cout);

        // A bug in the java.util.jar implementation of Android platforms
        // up to version 1.6 will cause a spurious IOException to be thrown
        // if the length of the signature file is a multiple of 1024 bytes.
        // As a workaround, add an extra CRLF in this case.
        if ((cout.size() % 1024) == 0) {
            cout.write('\r');
            cout.write('\n');
        }
    }

    /**
     * Sign data and write the digital signature to 'out'.
     */
    private static void write_signature_block(
            CMSTypedData data, X509Certificate public_key, PrivateKey private_key, OutputStream out)
            throws IOException,
            CertificateEncodingException,
            OperatorCreationException,
            CMSException {
        ArrayList<X509Certificate> cert_list = new ArrayList<>(1);
        cert_list.add(public_key);
        JcaCertStore certs = new JcaCertStore(cert_list);

        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder(get_signature_algorithm(public_key))
                .build(private_key);
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .setDirectSignature(true)
                        .build(signer, public_key)
        );
        gen.addCertificates(certs);
        CMSSignedData sig_data = gen.generate(data, false);

        try (ASN1InputStream asn1 = new ASN1InputStream(sig_data.getEncoded())) {
            ASN1OutputStream dos = ASN1OutputStream.create(out, ASN1Encoding.DER);
            dos.writeObject(asn1.readObject());
        }
    }

    /**
     * Copy all the files in a manifest from input to output.  We set
     * the modification times in the output to a fixed time, so as to
     * reduce variation in the output file and make incremental OTAs
     * more efficient.
     */
    private static void copy_files(Manifest manifest, JarMap in, JarOutputStream out,
                                  long timestamp, int defaultAlignment) throws IOException {
        byte[] buffer = new byte[4096];
        int num;

        Map<String, Attributes> entries = manifest.getEntries();
        ArrayList<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);

        boolean firstEntry = true;
        long offset = 0L;

        // We do the copy in two passes -- first copying all the
        // entries that are STORED, then copying all the entries that
        // have any other compression flag (which in practice means
        // DEFLATED).  This groups all the stored entries together at
        // the start of the file and makes it easier to do alignment
        // on them (since only stored entries are aligned).

        for (String name : names) {
            JarEntry in_entry = in.getJarEntry(name);
            JarEntry out_entry;
            if (in_entry.getMethod() != JarEntry.STORED) continue;
            // Preserve the STORED method of the input entry.
            out_entry = new JarEntry(in_entry);
            out_entry.setTime(timestamp);
            // Discard comment and extra fields of this entry to
            // simplify alignment logic below and for consistency with
            // how compressed entries are handled later.
            out_entry.setComment(null);
            out_entry.setExtra(null);

            // 'offset' is the offset into the file at which we expect
            // the file data to begin.  This is the value we need to
            // make a multiple of 'alignement'.
            offset += JarFile.LOCHDR + out_entry.getName().length();
            if (firstEntry) {
                // The first entry in a jar file has an extra field of
                // four bytes that you can't get rid of; any extra
                // data you specify in the JarEntry is appended to
                // these forced four bytes.  This is JAR_MAGIC in
                // JarOutputStream; the bytes are 0xfeca0000.
                offset += 4;
                firstEntry = false;
            }
            int alignment = get_stored_entry_data_alignment(name, defaultAlignment);
            if (alignment > 0 && (offset % alignment != 0)) {
                // Set the "extra data" of the entry to between 1 and
                // alignment-1 bytes, to make the file data begin at
                // an aligned offset.
                int needed = alignment - (int) (offset % alignment);
                out_entry.setExtra(new byte[needed]);
                offset += needed;
            }

            out.putNextEntry(out_entry);

            InputStream data = in.getInputStream(in_entry);
            while ((num = data.read(buffer)) > 0) {
                out.write(buffer, 0, num);
                offset += num;
            }
            out.flush();
        }

        // Copy all the non-STORED entries.  We don't attempt to
        // maintain the 'offset' variable past this point; we don't do
        // alignment on these entries.

        for (String name : names) {
            JarEntry in_entry = in.getJarEntry(name);
            JarEntry out_entry;
            if (in_entry.getMethod() == JarEntry.STORED) continue;
            // Create a new entry so that the compressed len is recomputed.
            out_entry = new JarEntry(name);
            out_entry.setTime(timestamp);
            out.putNextEntry(out_entry);

            InputStream data = in.getInputStream(in_entry);
            while ((num = data.read(buffer)) > 0) {
                out.write(buffer, 0, num);
            }
            out.flush();
        }
    }

    /**
     * Returns the multiple (in bytes) at which the provided {@code STORED} entry's data must start
     * relative to start of file or {@code 0} if alignment of this entry's data is not important.
     */
    private static int get_stored_entry_data_alignment(String entry_name, int defaultAlignment) {
        if (defaultAlignment <= 0) {
            return 0;
        }

        if (entry_name.endsWith(".so")) {
            // Align .so contents to memory page boundary to enable memory-mapped
            // execution.
            return 4096;
        } else {
            return defaultAlignment;
        }
    }

    private static void sign_file(Manifest manifest,
                                 X509Certificate[] public_key, PrivateKey[] private_key,
                                 long timestamp, JarOutputStream output_jar) throws Exception {
        // MANIFEST.MF
        JarEntry je = new JarEntry(JarFile.MANIFEST_NAME);
        je.setTime(timestamp);
        output_jar.putNextEntry(je);
        manifest.write(output_jar);

        int numKeys = public_key.length;
        for (int k = 0; k < numKeys; ++k) {
            // CERT.SF / CERT#.SF
            je = new JarEntry(numKeys == 1 ? CERT_SF_NAME :
                    (String.format(Locale.US, CERT_SF_MULTI_NAME, k)));
            je.setTime(timestamp);
            output_jar.putNextEntry(je);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            write_signature_file(manifest, baos, get_digest_algorithm(public_key[k]));
            byte[] signed_data = baos.toByteArray();
            output_jar.write(signed_data);

            // CERT.{EC,RSA} / CERT#.{EC,RSA}
            final String key_type = public_key[k].getPublicKey().getAlgorithm();
            je = new JarEntry(numKeys == 1 ? (String.format(CERT_SIG_NAME, key_type)) :
                    (String.format(Locale.US, CERT_SIG_MULTI_NAME, k, key_type)));
            je.setTime(timestamp);
            output_jar.putNextEntry(je);
            write_signature_block(new CMSProcessableByteArray(signed_data),
                    public_key[k], private_key[k], output_jar);
        }
    }

    /**
     * Converts the provided lists of private keys, their X.509 certificates, and digest algorithms
     * into a list of APK Signature Scheme v2 {@code SignerConfig} instances.
     */
    private static List<ApkSignerV2.SignerConfig> create_v2_signer_configs(
            PrivateKey[] private_keys, X509Certificate[] certificates, String[] digest_algorithms)
            throws InvalidKeyException {
        if (private_keys.length != certificates.length) {
            throw new IllegalArgumentException(
                    "The number of private keys must match the number of certificates: "
                            + private_keys.length + " vs" + certificates.length);
        }
        List<ApkSignerV2.SignerConfig> result = new ArrayList<>(private_keys.length);
        for (int i = 0; i < private_keys.length; i++) {
            PrivateKey private_key = private_keys[i];
            X509Certificate certificate = certificates[i];
            PublicKey public_key = certificate.getPublicKey();
            String key_algorithm = private_key.getAlgorithm();
            if (!key_algorithm.equalsIgnoreCase(public_key.getAlgorithm())) {
                throw new InvalidKeyException(
                        "Key algorithm of private key #" + (i + 1) + " does not match key"
                                + " algorithm of public key #" + (i + 1) + ": " + key_algorithm
                                + " vs " + public_key.getAlgorithm());
            }
            ApkSignerV2.SignerConfig signer_config = new ApkSignerV2.SignerConfig();
            signer_config.private_key = private_key;
            signer_config.certificates = Collections.singletonList(certificate);
            List<Integer> signature_algorithms = new ArrayList<>(digest_algorithms.length);
            for (String digest_algorithm : digest_algorithms) {
                try {
                    signature_algorithms.add(get_v2_signature_algorithm(key_algorithm, digest_algorithm));
                } catch (IllegalArgumentException e) {
                    throw new InvalidKeyException(
                            "Unsupported key and digest algorithm combination for signer #"
                                    + (i + 1), e);
                }
            }
            signer_config.signature_algorithms = signature_algorithms;
            result.add(signer_config);
        }
        return result;
    }

    private static int get_v2_signature_algorithm(String key_algorithm, String digest_algorithm) {
        if ("SHA-256".equalsIgnoreCase(digest_algorithm)) {
            if ("RSA".equalsIgnoreCase(key_algorithm)) {
                // Use RSASSA-PKCS1-v1_5 signature scheme instead of RSASSA-PSS to guarantee
                // deterministic signatures which make life easier for OTA updates (fewer files
                // changed when deterministic signature schemes are used).
                return ApkSignerV2.SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256;
            } else if ("EC".equalsIgnoreCase(key_algorithm)) {
                return ApkSignerV2.SIGNATURE_ECDSA_WITH_SHA256;
            } else if ("DSA".equalsIgnoreCase(key_algorithm)) {
                return ApkSignerV2.SIGNATURE_DSA_WITH_SHA256;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm: " + key_algorithm);
            }
        } else if ("SHA-512".equalsIgnoreCase(digest_algorithm)) {
            if ("RSA".equalsIgnoreCase(key_algorithm)) {
                // Use RSASSA-PKCS1-v1_5 signature scheme instead of RSASSA-PSS to guarantee
                // deterministic signatures which make life easier for OTA updates (fewer files
                // changed when deterministic signature schemes are used).
                return ApkSignerV2.SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512;
            } else if ("EC".equalsIgnoreCase(key_algorithm)) {
                return ApkSignerV2.SIGNATURE_ECDSA_WITH_SHA512;
            } else if ("DSA".equalsIgnoreCase(key_algorithm)) {
                return ApkSignerV2.SIGNATURE_DSA_WITH_SHA512;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm: " + key_algorithm);
            }
        } else {
            throw new IllegalArgumentException("Unsupported digest algorithm: " + digest_algorithm);
        }
    }

    /**
     * Signs the given JAR with both JAR (v1) and APK Signature Scheme v2 and writes the
     * result to {@code outputStream}.
     *
     * @param cert         the X.509 certificate whose public key corresponds to {@code key}
     * @param key          the private key for signing
     * @param inputJar     the (possibly unsigned) JAR to sign
     * @param outputStream stream to receive the signed APK
     */
    public static void sign(X509Certificate cert, PrivateKey key,
                            JarMap input_jar, OutputStream output_stream) throws Exception {
        int alignment = 4;
        int hashes = 0;

        X509Certificate[] public_key = new X509Certificate[1];
        public_key[0] = cert;
        hashes |= get_digest_algorithm(public_key[0]);

        // Set all ZIP file timestamps to Jan 1 2009 00:00:00.
        long timestamp = 1230768000000L;
        // The Java ZipEntry API we're using converts milliseconds since epoch into MS-DOS
        // timestamp using the current timezone. We thus adjust the milliseconds since epoch
        // value to end up with MS-DOS timestamp of Jan 1 2009 00:00:00.
        timestamp -= TimeZone.getDefault().getOffset(timestamp);

        PrivateKey[] private_key = new PrivateKey[1];
        private_key[0] = key;

        // Generate, in memory, an APK signed using standard JAR Signature Scheme.
        ByteArrayStream v1_signed_apk_buf = new ByteArrayStream();
        JarOutputStream output_jar = new JarOutputStream(v1_signed_apk_buf);
        // Use maximum compression for compressed entries because the APK lives forever on
        // the system partition.
        output_jar.setLevel(9);
        Manifest manifest = add_digests_to_manifest(input_jar, hashes);
        copy_files(manifest, input_jar, output_jar, timestamp, alignment);
        sign_file(manifest, public_key, private_key, timestamp, output_jar);
        output_jar.close();
        ByteBuffer v1_signed_apk = v1_signed_apk_buf.toByteBuffer();

        ByteBuffer[] output_chunks;
        List<ApkSignerV2.SignerConfig> signer_configs = create_v2_signer_configs(private_key, public_key,
                new String[]{APK_SIG_SCHEME_V2_DIGEST_ALGORITHM});
        output_chunks = ApkSignerV2.sign(v1_signed_apk, signer_configs);

        // This assumes outputChunks are array-backed. To avoid this assumption, the
        // code could be rewritten to use FileChannel.
        for (ByteBuffer output_chunk : output_chunks) {
            output_stream.write(output_chunk.array(),
                    output_chunk.arrayOffset() + output_chunk.position(), output_chunk.remaining());
            output_chunk.position(output_chunk.limit());
        }
    }

    /**
     * Write to another stream and track how many bytes have been
     * written.
     */
    private static class CountOutputStream extends FilterOutputStream {
        private int mCount;

        public CountOutputStream(OutputStream out) {
            super(out);
            mCount = 0;
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            mCount++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            mCount += len;
        }

        public int size() {
            return mCount;
        }
    }
}
