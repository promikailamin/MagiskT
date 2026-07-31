/**
 * APK Signature Scheme v2 implementation for signing APKs.
 *
 * <p>APK Signature Scheme v2 is a whole-file signature scheme which aims to protect every single
 * bit of the APK, as opposed to the JAR Signature Scheme which protects only the names and
 * uncompressed contents of ZIP entries. This implementation is adapted from AOSP libcore source.
 *
 * <p>The signature block is inserted into an APK Signing Block immediately before the ZIP Central
 * Directory, allowing JAR/ZIP parsers to continue working on the signed APK.
 */
package pro.magisk.core.signing;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * APK Signature Scheme v2 signer.
 *
 * <p>APK Signature Scheme v2 is a whole-file signature scheme which aims to protect every single
 * bit of the APK, as opposed to the JAR Signature Scheme which protects only the names and
 * uncompressed contents of ZIP entries.
 */
public abstract class ApkSignerV2 {
    /*
     * The two main goals of APK Signature Scheme v2 are:
     * 1. Detect any unauthorized modifications to the APK. This is achieved by making the signature
     *    cover every byte of the APK being signed.
     * 2. Enable much faster signature and integrity verification. This is achieved by requiring
     *    only a minimal amount of APK parsing before the signature is verified, thus completely
     *    bypassing ZIP entry decompression and by making integrity verification parallelizable by
     *    employing a hash tree.
     *
     * The generated signature block is wrapped into an APK Signing Block and inserted into the
     * original APK immediately before the start of ZIP Central Directory. This is to ensure that
     * JAR and ZIP parsers continue to work on the signed APK. The APK Signing Block is designed for
     * extensibility. For example, a future signature scheme could insert its signatures there as
     * well. The contract of the APK Signing Block is that all contents outside of the block must be
     * protected by signatures inside the block.
     */

    public static final int SIGNATURE_RSA_PSS_WITH_SHA256 = 0x0101;
    public static final int SIGNATURE_RSA_PSS_WITH_SHA512 = 0x0102;
    public static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    public static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
    public static final int SIGNATURE_ECDSA_WITH_SHA256 = 0x0201;
    public static final int SIGNATURE_ECDSA_WITH_SHA512 = 0x0202;
    public static final int SIGNATURE_DSA_WITH_SHA256 = 0x0301;
    public static final int SIGNATURE_DSA_WITH_SHA512 = 0x0302;

    /**
     * {@code .SF} file header section attribute indicating that the APK is signed not just with
     * JAR signature scheme but also with APK Signature Scheme v2 or newer. This attribute
     * facilitates v2 signature stripping detection.
     *
     * <p>The attribute contains a comma-separated set of signature scheme IDs.
     */
    public static final String SF_ATTRIBUTE_ANDROID_APK_SIGNED_NAME = "X-Android-APK-Signed";
    public static final String SF_ATTRIBUTE_ANDROID_APK_SIGNED_VALUE = "2";

    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 0;
    private static final int CONTENT_DIGEST_CHUNKED_SHA512 = 1;

    private static final int CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES = 1024 * 1024;

    private static final byte[] APK_SIGNING_BLOCK_MAGIC =
          new byte[] {
              0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
              0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32,
          };
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;

    private ApkSignerV2() {}

    /**
     * Signer configuration.
     */
    public static final class SignerConfig {
        /** Private key. */
        public PrivateKey private_key;

        /**
         * Certificates, with the first certificate containing the public key corresponding to
         * {@link #privateKey}.
         */
        public List<X509Certificate> certificates;

        /**
         * List of signature algorithms with which to sign (see {@code SIGNATURE_...} constants).
         */
        public List<Integer> signature_algorithms;
    }

    /**
     * Signs the provided APK using APK Signature Scheme v2 and returns the signed APK as a list of
     * consecutive chunks.
     *
     * <p>NOTE: To enable APK signature verifier to detect v2 signature stripping, header sections
     * of META-INF/*.SF files of APK being signed must contain the
     * {@code X-Android-APK-Signed: true} attribute.
     *
     * @param inputApk contents of the APK to be signed. The APK starts at the current position
     *        of the buffer and ends at the limit of the buffer.
     * @param signerConfigs signer configurations, one for each signer.
     *
     * @throws ApkParseException if the APK cannot be parsed.
     * @throws InvalidKeyException if a signing key is not suitable for this signature scheme or
     *         cannot be used in general.
     * @throws SignatureException if an error occurs when computing digests of generating
     *         signatures.
     */
    public static ByteBuffer[] sign(
            ByteBuffer input_apk,
            List<SignerConfig> signer_configs)
                    throws ApkParseException, InvalidKeyException, SignatureException {
        // Slice/create a view in the inputApk to make sure that:
        // 1. inputApk is what's between position and limit of the original inputApk, and
        // 2. changes to position, limit, and byte order are not reflected in the original.
        ByteBuffer original_input_apk = input_apk;
        input_apk = original_input_apk.slice();
        input_apk.order(ByteOrder.LITTLE_ENDIAN);

        // Locate ZIP End of Central Directory (EoCD), Central Directory, and check that Central
        // Directory is immediately followed by the ZIP End of Central Directory.
        int eocdOffset = ZipUtils.find_zip_end_of_central_directory_record(input_apk);
        if (eocdOffset == -1) {
            throw new ApkParseException("Failed to locate ZIP End of Central Directory");
        }
        if (ZipUtils.is_zip64_end_of_central_directory_locator_present(input_apk, eocdOffset)) {
            throw new ApkParseException("ZIP64 format not supported");
        }
        input_apk.position(eocdOffset);
        long centralDirSizeLong = ZipUtils.get_zip_eocd_central_directory_size_bytes(input_apk);
        if (centralDirSizeLong > Integer.MAX_VALUE) {
            throw new ApkParseException(
                    "ZIP Central Directory size out of range: " + centralDirSizeLong);
        }
        int centralDirSize = (int) centralDirSizeLong;
        long centralDirOffsetLong = ZipUtils.get_zip_eocd_central_directory_offset(input_apk);
        if (centralDirOffsetLong > Integer.MAX_VALUE) {
            throw new ApkParseException(
                    "ZIP Central Directory offset in file out of range: " + centralDirOffsetLong);
        }
        int centralDirOffset = (int) centralDirOffsetLong;
        int expectedEocdOffset = centralDirOffset + centralDirSize;
        if (expectedEocdOffset < centralDirOffset) {
            throw new ApkParseException(
                    "ZIP Central Directory extent too large. Offset: " + centralDirOffset
                            + ", size: " + centralDirSize);
        }
        if (eocdOffset != expectedEocdOffset) {
            throw new ApkParseException(
                    "ZIP Central Directory not immeiately followed by ZIP End of"
                            + " Central Directory. CD end: " + expectedEocdOffset
                            + ", EoCD start: " + eocdOffset);
        }

        // Create ByteBuffers holding the contents of everything before ZIP Central Directory,
        // ZIP Central Directory, and ZIP End of Central Directory.
        input_apk.clear();
        ByteBuffer before_central_dir = get_byte_buffer(input_apk, centralDirOffset);
        ByteBuffer central_dir = get_byte_buffer(input_apk, eocdOffset - centralDirOffset);
        // Create a copy of End of Central Directory because we'll need modify its contents later.
        byte[] eocdBytes = new byte[input_apk.remaining()];
        input_apk.get(eocdBytes);
        ByteBuffer eocd = ByteBuffer.wrap(eocdBytes);
        eocd.order(input_apk.order());

        // Figure which which digests to use for APK contents.
        Set<Integer> content_digest_algorithms = new HashSet<>();
        for (SignerConfig signer_config : signer_configs) {
            for (int signatureAlgorithm : signer_config.signature_algorithms) {
                content_digest_algorithms.add(
                        get_signature_algorithm_content_digest_algorithm(signatureAlgorithm));
            }
        }

        // Compute digests of APK contents.
        Map<Integer, byte[]> content_digests; // digest algorithm ID -> digest
        try {
            content_digests =
                    compute_content_digests(
                            content_digest_algorithms,
                            new ByteBuffer[] {before_central_dir, central_dir, eocd});
        } catch (DigestException e) {
            throw new SignatureException("Failed to compute digests of APK", e);
        }

        // Sign the digests and wrap the signatures and signer info into an APK Signing Block.
        ByteBuffer apk_signing_block =
                ByteBuffer.wrap(generate_apk_signing_block(signer_configs, content_digests));

        // Update Central Directory Offset in End of Central Directory Record. Central Directory
        // follows the APK Signing Block and thus is shifted by the size of the APK Signing Block.
        centralDirOffset += apk_signing_block.remaining();
        eocd.clear();
        ZipUtils.set_zip_eocd_central_directory_offset(eocd, centralDirOffset);

        // Follow the Java NIO pattern for ByteBuffer whose contents have been consumed.
        original_input_apk.position(original_input_apk.limit());

        // Reset positions (to 0) and limits (to capacity) in the ByteBuffers below to follow the
        // Java NIO pattern for ByteBuffers which are ready for their contents to be read by caller.
        // Contrary to the name, this does not clear the contents of these ByteBuffer.
        before_central_dir.clear();
        central_dir.clear();
        eocd.clear();

        // Insert APK Signing Block immediately before the ZIP Central Directory.
        return new ByteBuffer[] {
            before_central_dir,
            apk_signing_block,
            central_dir,
            eocd,
        };
    }

    private static Map<Integer, byte[]> compute_content_digests(
            Set<Integer> digest_algorithms,
            ByteBuffer[] contents) throws DigestException {
        // For each digest algorithm the result is computed as follows:
        // 1. Each segment of contents is split into consecutive chunks of 1 MB in size.
        //    The final chunk will be shorter iff the length of segment is not a multiple of 1 MB.
        //    No chunks are produced for empty (zero length) segments.
        // 2. The digest of each chunk is computed over the concatenation of byte 0xa5, the chunk's
        //    length in bytes (uint32 little-endian) and the chunk's contents.
        // 3. The output digest is computed over the concatenation of the byte 0x5a, the number of
        //    chunks (uint32 little-endian) and the concatenation of digests of chunks of all
        //    segments in-order.

        int chunkCount = 0;
        for (ByteBuffer input : contents) {
            chunkCount += get_chunk_count(input.remaining(), CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES);
        }

        final Map<Integer, byte[]> digests_of_chunks = new HashMap<>(digest_algorithms.size());
        for (int digest_algorithm : digest_algorithms) {
            int digestOutputSizeBytes = get_content_digest_algorithm_output_size_bytes(digest_algorithm);
            byte[] concatenationOfChunkCountAndChunkDigests =
                    new byte[5 + chunkCount * digestOutputSizeBytes];
            concatenationOfChunkCountAndChunkDigests[0] = 0x5a;
            set_unsigned_int32_little_engian(
                    chunkCount, concatenationOfChunkCountAndChunkDigests, 1);
            digests_of_chunks.put(digest_algorithm, concatenationOfChunkCountAndChunkDigests);
        }

        int chunkIndex = 0;
        byte[] chunkContentPrefix = new byte[5];
        chunkContentPrefix[0] = (byte) 0xa5;
        // Optimization opportunity: digests of chunks can be computed in parallel.
        for (ByteBuffer input : contents) {
            while (input.hasRemaining()) {
                int chunkSize =
                        Math.min(input.remaining(), CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES);
                final ByteBuffer chunk = get_byte_buffer(input, chunkSize);
                for (int digest_algorithm : digest_algorithms) {
                    String jca_algorithm_name =
                            get_content_digest_algorithm_jca_digest_algorithm(digest_algorithm);
                    MessageDigest md;
                    try {
                        md = MessageDigest.getInstance(jca_algorithm_name);
                    } catch (NoSuchAlgorithmException e) {
                        throw new DigestException(
                                jca_algorithm_name + " MessageDigest not supported", e);
                    }
                    // Reset position to 0 and limit to capacity. Position would've been modified
                    // by the preceding iteration of this loop. NOTE: Contrary to the method name,
                    // this does not modify the contents of the chunk.
                    chunk.clear();
                    set_unsigned_int32_little_engian(chunk.remaining(), chunkContentPrefix, 1);
                    md.update(chunkContentPrefix);
                    md.update(chunk);
                    byte[] concatenationOfChunkCountAndChunkDigests =
                            digests_of_chunks.get(digest_algorithm);
                    int expectedDigestSizeBytes =
                            get_content_digest_algorithm_output_size_bytes(digest_algorithm);
                    int actualDigestSizeBytes =
                            md.digest(
                                    concatenationOfChunkCountAndChunkDigests,
                                    5 + chunkIndex * expectedDigestSizeBytes,
                                    expectedDigestSizeBytes);
                    if (actualDigestSizeBytes != expectedDigestSizeBytes) {
                        throw new DigestException(
                                "Unexpected output size of " + md.getAlgorithm()
                                        + " digest: " + actualDigestSizeBytes);
                    }
                }
                chunkIndex++;
            }
        }

        Map<Integer, byte[]> result = new HashMap<>(digest_algorithms.size());
        for (Map.Entry<Integer, byte[]> entry : digests_of_chunks.entrySet()) {
            int digest_algorithm = entry.getKey();
            byte[] concatenationOfChunkCountAndChunkDigests = entry.getValue();
            String jca_algorithm_name = get_content_digest_algorithm_jca_digest_algorithm(digest_algorithm);
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(jca_algorithm_name);
            } catch (NoSuchAlgorithmException e) {
                throw new DigestException(jca_algorithm_name + " MessageDigest not supported", e);
            }
            result.put(digest_algorithm, md.digest(concatenationOfChunkCountAndChunkDigests));
        }
        return result;
    }

    private static int get_chunk_count(int inputSize, int chunkSize) {
        return (inputSize + chunkSize - 1) / chunkSize;
    }

    private static void set_unsigned_int32_little_engian(int value, byte[] result, int offset) {
        result[offset] = (byte) (value & 0xff);
        result[offset + 1] = (byte) ((value >> 8) & 0xff);
        result[offset + 2] = (byte) ((value >> 16) & 0xff);
        result[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static byte[] generate_apk_signing_block(
            List<SignerConfig> signer_configs,
            Map<Integer, byte[]> content_digests) throws InvalidKeyException, SignatureException {
        byte[] apkSignatureSchemeV2Block =
                generate_apk_signature_scheme_v2_block(signer_configs, content_digests);
        return generate_apk_signing_block(apkSignatureSchemeV2Block);
    }

    private static byte[] generate_apk_signing_block(byte[] apkSignatureSchemeV2Block) {
        // FORMAT:
        // uint64:  size (excluding this field)
        // repeated ID-value pairs:
        //     uint64:           size (excluding this field)
        //     uint32:           ID
        //     (size - 4) bytes: value
        // uint64:  size (same as the one above)
        // uint128: magic

        int resultSize =
                8 // size
                + 8 + 4 + apkSignatureSchemeV2Block.length // v2Block as ID-value pair
                + 8 // size
                + 16 // magic
                ;
        ByteBuffer result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        long blockSizeFieldValue = resultSize - 8;
        result.putLong(blockSizeFieldValue);

        long pairSizeFieldValue = 4 + apkSignatureSchemeV2Block.length;
        result.putLong(pairSizeFieldValue);
        result.putInt(APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
        result.put(apkSignatureSchemeV2Block);

        result.putLong(blockSizeFieldValue);
        result.put(APK_SIGNING_BLOCK_MAGIC);

        return result.array();
    }

    private static byte[] generate_apk_signature_scheme_v2_block(
            List<SignerConfig> signer_configs,
            Map<Integer, byte[]> content_digests) throws InvalidKeyException, SignatureException {
        // FORMAT:
        // * length-prefixed sequence of length-prefixed signer blocks.

        List<byte[]> signer_blocks = new ArrayList<>(signer_configs.size());
        int signerNumber = 0;
        for (SignerConfig signer_config : signer_configs) {
            signerNumber++;
            byte[] signerBlock;
            try {
                signerBlock = generate_signer_block(signer_config, content_digests);
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Signer #" + signerNumber + " failed", e);
            } catch (SignatureException e) {
                throw new SignatureException("Signer #" + signerNumber + " failed", e);
            }
            signer_blocks.add(signerBlock);
        }

        return encode_as_sequence_of_length_prefixed_elements(
                new byte[][] {
                    encode_as_sequence_of_length_prefixed_elements(signer_blocks),
                });
    }

    private static byte[] generate_signer_block(
            SignerConfig signer_config,
            Map<Integer, byte[]> content_digests) throws InvalidKeyException, SignatureException {
        if (signer_config.certificates.isEmpty()) {
            throw new SignatureException("No certificates configured for signer");
        }
        PublicKey public_key = signer_config.certificates.get(0).getPublicKey();

        byte[] encodedPublicKey = encode_public_key(public_key);

        V2SignatureSchemeBlock.SignedData signed_data = new V2SignatureSchemeBlock.SignedData();
        try {
            signed_data.certificates = encode_certificates(signer_config.certificates);
        } catch (CertificateEncodingException e) {
            throw new SignatureException("Failed to encode certificates", e);
        }

        List<Pair<Integer, byte[]>> digests =
                new ArrayList<>(signer_config.signature_algorithms.size());
        for (int signatureAlgorithm : signer_config.signature_algorithms) {
            int contentDigestAlgorithm =
                    get_signature_algorithm_content_digest_algorithm(signatureAlgorithm);
            byte[] contentDigest = content_digests.get(contentDigestAlgorithm);
            if (contentDigest == null) {
                throw new RuntimeException(
                        get_content_digest_algorithm_jca_digest_algorithm(contentDigestAlgorithm)
                        + " content digest for "
                        + getSignatureAlgorithmJcaSignatureAlgorithm(signatureAlgorithm)
                        + " not computed");
            }
            digests.add(Pair.create(signatureAlgorithm, contentDigest));
        }
        signed_data.digests = digests;

        V2SignatureSchemeBlock.Signer signer = new V2SignatureSchemeBlock.Signer();
        // FORMAT:
        // * length-prefixed sequence of length-prefixed digests:
        //   * uint32: signature algorithm ID
        //   * length-prefixed bytes: digest of contents
        // * length-prefixed sequence of certificates:
        //   * length-prefixed bytes: X.509 certificate (ASN.1 DER encoded).
        // * length-prefixed sequence of length-prefixed additional attributes:
        //   * uint32: ID
        //   * (length - 4) bytes: value
        signer.signed_data = encode_as_sequence_of_length_prefixed_elements(new byte[][] {
            encode_as_sequence_of_length_prefixed_pairs_of_int_and_length_prefixed_bytes(signed_data.digests),
            encode_as_sequence_of_length_prefixed_elements(signed_data.certificates),
            // additional attributes
            new byte[0],
        });
        signer.public_key = encodedPublicKey;
        signer.signatures = new ArrayList<>();
        for (int signatureAlgorithm : signer_config.signature_algorithms) {
            Pair<String, ? extends AlgorithmParameterSpec> signature_params =
                    getSignatureAlgorithmJcaSignatureAlgorithm(signatureAlgorithm);
            String jca_signature_algorithm = signature_params.getFirst();
            AlgorithmParameterSpec jca_signature_algorithm_params = signature_params.get_second();
            byte[] signatureBytes;
            try {
                Signature signature = Signature.getInstance(jca_signature_algorithm);
                signature.initSign(signer_config.private_key);
                if (jca_signature_algorithm_params != null) {
                    signature.setParameter(jca_signature_algorithm_params);
                }
                signature.update(signer.signed_data);
                signatureBytes = signature.sign();
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Failed sign using " + jca_signature_algorithm, e);
            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                    | SignatureException e) {
                throw new SignatureException("Failed sign using " + jca_signature_algorithm, e);
            }

            try {
                Signature signature = Signature.getInstance(jca_signature_algorithm);
                signature.initVerify(public_key);
                if (jca_signature_algorithm_params != null) {
                    signature.setParameter(jca_signature_algorithm_params);
                }
                signature.update(signer.signed_data);
                if (!signature.verify(signatureBytes)) {
                    throw new SignatureException("Signature did not verify");
                }
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Failed to verify generated " + jca_signature_algorithm
                        + " signature using public key from certificate", e);
            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                    | SignatureException e) {
                throw new SignatureException("Failed to verify generated " + jca_signature_algorithm
                        + " signature using public key from certificate", e);
            }

            signer.signatures.add(Pair.create(signatureAlgorithm, signatureBytes));
        }

        // FORMAT:
        // * length-prefixed signed data
        // * length-prefixed sequence of length-prefixed signatures:
        //   * uint32: signature algorithm ID
        //   * length-prefixed bytes: signature of signed data
        // * length-prefixed bytes: public key (X.509 SubjectPublicKeyInfo, ASN.1 DER encoded)
        return encode_as_sequence_of_length_prefixed_elements(
                new byte[][] {
                    signer.signed_data,
                    encode_as_sequence_of_length_prefixed_pairs_of_int_and_length_prefixed_bytes(
                            signer.signatures),
                    signer.public_key,
                });
    }

    private static final class V2SignatureSchemeBlock {
        private static final class Signer {
            public byte[] signed_data;
            public List<Pair<Integer, byte[]>> signatures;
            public byte[] public_key;
        }

        private static final class SignedData {
            public List<Pair<Integer, byte[]>> digests;
            public List<byte[]> certificates;
        }
    }

    private static byte[] encode_public_key(PublicKey public_key) throws InvalidKeyException {
        byte[] encodedPublicKey = null;
        if ("X.509".equals(public_key.getFormat())) {
            encodedPublicKey = public_key.getEncoded();
        }
        if (encodedPublicKey == null) {
            try {
                encodedPublicKey =
                        KeyFactory.getInstance(public_key.getAlgorithm())
                                .getKeySpec(public_key, X509EncodedKeySpec.class)
                                .getEncoded();
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new InvalidKeyException(
                        "Failed to obtain X.509 encoded form of public key " + public_key
                                + " of class " + public_key.getClass().getName(),
                        e);
            }
        }
        if ((encodedPublicKey == null) || (encodedPublicKey.length == 0)) {
            throw new InvalidKeyException(
                    "Failed to obtain X.509 encoded form of public key " + public_key
                            + " of class " + public_key.getClass().getName());
        }
        return encodedPublicKey;
    }

    public static List<byte[]> encode_certificates(List<X509Certificate> certificates)
            throws CertificateEncodingException {
        List<byte[]> result = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            result.add(certificate.getEncoded());
        }
        return result;
    }

    private static byte[] encode_as_sequence_of_length_prefixed_elements(List<byte[]> sequence) {
        return encode_as_sequence_of_length_prefixed_elements(
                sequence.toArray(new byte[sequence.size()][]));
    }

    private static byte[] encode_as_sequence_of_length_prefixed_elements(byte[][] sequence) {
        int payloadSize = 0;
        for (byte[] element : sequence) {
            payloadSize += 4 + element.length;
        }
        ByteBuffer result = ByteBuffer.allocate(payloadSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] element : sequence) {
            result.putInt(element.length);
            result.put(element);
        }
        return result.array();
      }

    private static byte[] encode_as_sequence_of_length_prefixed_pairs_of_int_and_length_prefixed_bytes(
            List<Pair<Integer, byte[]>> sequence) {
        int resultSize = 0;
        for (Pair<Integer, byte[]> element : sequence) {
            resultSize += 12 + element.get_second().length;
        }
        ByteBuffer result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (Pair<Integer, byte[]> element : sequence) {
            byte[] second = element.get_second();
            result.putInt(8 + second.length);
            result.putInt(element.getFirst());
            result.putInt(second.length);
            result.put(second);
        }
        return result.array();
    }

    /**
     * Relative <em>get</em> method for reading {@code size} number of bytes from the current
     * position of this buffer.
     *
     * <p>This method reads the next {@code size} bytes at this buffer's current position,
     * returning them as a {@code ByteBuffer} with start set to 0, limit and capacity set to
     * {@code size}, byte order set to this buffer's byte order; and then increments the position by
     * {@code size}.
     */
    private static ByteBuffer get_byte_buffer(ByteBuffer source, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size: " + size);
        }
        int originalLimit = source.limit();
        int position = source.position();
        int limit = position + size;
        if ((limit < position) || (limit > originalLimit)) {
            throw new BufferUnderflowException();
        }
        source.limit(limit);
        try {
            ByteBuffer result = source.slice();
            result.order(source.order());
            source.position(limit);
            return result;
        } finally {
            source.limit(originalLimit);
        }
    }

    private static Pair<String, ? extends AlgorithmParameterSpec>
            getSignatureAlgorithmJcaSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
                return Pair.create(
                        "SHA256withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
            case SIGNATURE_RSA_PSS_WITH_SHA512:
                return Pair.create(
                        "SHA512withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
                return Pair.create("SHA256withRSA", null);
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return Pair.create("SHA512withRSA", null);
            case SIGNATURE_ECDSA_WITH_SHA256:
                return Pair.create("SHA256withECDSA", null);
            case SIGNATURE_ECDSA_WITH_SHA512:
                return Pair.create("SHA512withECDSA", null);
            case SIGNATURE_DSA_WITH_SHA256:
                return Pair.create("SHA256withDSA", null);
            case SIGNATURE_DSA_WITH_SHA512:
                return Pair.create("SHA512withDSA", null);
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static int get_signature_algorithm_content_digest_algorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA256:
                return CONTENT_DIGEST_CHUNKED_SHA256;
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_DSA_WITH_SHA512:
                return CONTENT_DIGEST_CHUNKED_SHA512;
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static String get_content_digest_algorithm_jca_digest_algorithm(int digest_algorithm) {
        switch (digest_algorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return "SHA-256";
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return "SHA-512";
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorithm: " + digest_algorithm);
        }
    }

    private static int get_content_digest_algorithm_output_size_bytes(int digest_algorithm) {
        switch (digest_algorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return 256 / 8;
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return 512 / 8;
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorithm: " + digest_algorithm);
        }
    }

    /**
     * Indicates that APK file could not be parsed.
     */
    public static class ApkParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ApkParseException(String message) {
            super(message);
        }

        public ApkParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Pair of two elements.
     */
    private static class Pair<A, B> {
        private final A m_first;
        private final B m_second;

        private Pair(A first, B second) {
            m_first = first;
            m_second = second;
        }

        public static <A, B> Pair<A, B> create(A first, B second) {
            return new Pair<>(first, second);
        }

        public A getFirst() {
            return m_first;
        }

        public B get_second() {
            return m_second;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((m_first == null) ? 0 : m_first.hashCode());
            result = prime * result + ((m_second == null) ? 0 : m_second.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            @SuppressWarnings("rawtypes")
            Pair other = (Pair) obj;
            if (m_first == null) {
                if (other.m_first != null) {
                    return false;
                }
            } else if (!m_first.equals(other.m_first)) {
                return false;
            }
            if (m_second == null) {
                return other.m_second == null;
            } else return m_second.equals(other.m_second);
        }
    }
}
