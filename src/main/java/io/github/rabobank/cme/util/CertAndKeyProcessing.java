/*
 * Copyright (C) 2025 Peter Paul Bakker - Rabobank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.rabobank.cme.util;

import io.github.rabobank.cme.CfMetricsAgentException;
import io.github.rabobank.cme.Logger;
import io.github.rabobank.cme.domain.MtlsInfo;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.rabobank.cme.domain.MtlsInfo.INVALID_MTLS_INFO;

public final class CertAndKeyProcessing {

    private static final Logger log = Logger.getLogger(CertAndKeyProcessing.class);

    private static final Pattern LINEBREAKS = Pattern.compile("\\s|\\r|\\n");

    private CertAndKeyProcessing() {
        // do not create instances
    }

    static SSLContext createSslContextFromPem(MtlsInfo mtlsInfo) throws CfMetricsAgentException {
        KeyStore keyStore;
        CertificateFactory certFactory;
        try {
            // Load client certificate and key
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null); // Initialize empty keystore

            // Convert PEM key and certificate to PKCS12 keystore entry
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new CfMetricsAgentException("Failed to instantite KeyStores", e);
        }

        // Load client certificate
        byte[] certBytes = mtlsInfo.getCert().getBytes(StandardCharsets.UTF_8);
        X509Certificate clientCert;
        try {
            clientCert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (CertificateException e) {
            throw new CfMetricsAgentException("Failed to load client cert", e);
        }

        // Load private key
        PrivateKey privateKey;
        try {
            privateKey = loadPrivateKeyFromPem(mtlsInfo.getKey());
        } catch (Exception e) {
            throw new CfMetricsAgentException("Failed to load private client key", e);
        }

        // Add client certificate and private key to keystore
        try {
            keyStore.setKeyEntry("client-cert", privateKey, new char[0], new Certificate[]{clientCert});
        } catch (KeyStoreException e) {
            throw new CfMetricsAgentException("Failed to add client-cert to keyStore", e);
        }
        KeyManagerFactory keyManagerFactory;
        KeyStore trustStore;

        try {
            // Set up key manager
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, new char[0]);

            // Load CA certificates into trust store
            trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null); // Initialize empty truststore
        } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException |
                 UnrecoverableKeyException e) {
            throw new CfMetricsAgentException("Failed to create trust store", e);
        }

        try {
            // Load CA certificates
            if (mtlsInfo.getCa() != null && !mtlsInfo.getCa().isEmpty()) {
                try (ByteArrayInputStream caStream = new ByteArrayInputStream(mtlsInfo.getCa().getBytes(StandardCharsets.UTF_8))) {
                    Collection<? extends Certificate> caCerts = certFactory.generateCertificates(caStream);
                    int i = 0;
                    for (Certificate caCert : caCerts) {
                        trustStore.setCertificateEntry("ca-cert-" + i++, caCert);
                    }
                }
            }
        } catch (IOException | CertificateException | KeyStoreException e) {
            throw new CfMetricsAgentException("Failed to load ca certs into trust store", e);
        }
        SSLContext sslContext;

        try {
            // Set up trust manager
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            // Create and initialize the SSLContext
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(),
                    new SecureRandom()
            );
        } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            throw new CfMetricsAgentException("Failed to setup trust manager", e);
        }

        return sslContext;
    }

    private static String cleanPemContent(String pemKey) {
        String pemWithoutHeaders = pemKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "");
        return LINEBREAKS.matcher(pemWithoutHeaders).replaceAll("");
    }

    private static PrivateKey loadPrivateKeyFromPem(String pemKey) throws CfMetricsAgentException {
        // Clean and prepare PEM content
        String privateKeyPEM = cleanPemContent(pemKey);

        // Decode base64 to get DER bytes
        byte[] der = Base64.getDecoder().decode(privateKeyPEM);

        if (pemKey.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 (RSA PRIVATE KEY) format (DER-encoded ASN.1 sequence)
            try {
                RSAPrivateCrtKeySpec spec = parsePkcs1PrivateKey(der);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePrivate(spec);
            } catch (Exception e) {
                throw new CfMetricsAgentException("Failed to process PKCS#1 rsa key", e);
            }
        } else if (pemKey.contains("BEGIN PRIVATE KEY")) {
            try {
                // PKCS#8 format
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
                return keyFactory.generatePrivate(keySpec);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new CfMetricsAgentException("Failed to process pkcs8 key", e);
            }
        }
        else {
            throw new CfMetricsAgentException("Failed to load private key, unknown format");
        }
    }

    // Minimal ASN.1 DER parser for PKCS#1 RSA private keys.
    //
    // - This parser (parsePkcs1PrivateKey and the nested DerReader) was added to this
    //   project to remove the dependency on Bouncy Castle and still support PKCS#1 ("BEGIN RSA PRIVATE KEY") keys.
    // - The implementation is based on the PKCS#1 RSAPrivateKey structure as defined in RFC 3447
    //   and the basic DER encoding rules (see ITU‑T X.690).
    // - It is intentionally minimal and only implements what is required to parse unencrypted
    //   RSA private keys used by typical OpenSSL outputs.
    // Structure (RFC 3447):
    // RSAPrivateKey ::= SEQUENCE {
    //   version           Version,
    //   modulus           INTEGER,  -- n
    //   publicExponent    INTEGER,  -- e
    //   privateExponent   INTEGER,  -- d
    //   prime1            INTEGER,  -- p
    //   prime2            INTEGER,  -- q
    //   exponent1         INTEGER,  -- d mod (p-1)
    //   exponent2         INTEGER,  -- d mod (q-1)
    //   coefficient       INTEGER,  -- (inverse of q) mod p
    //   otherPrimeInfos   OtherPrimeInfos OPTIONAL
    // }
    private static RSAPrivateCrtKeySpec parsePkcs1PrivateKey(byte[] der) throws IOException {
        DerReader reader = new DerReader(der);
        DerReader seq = reader.readSequence();
        // version
        seq.readInteger();
        BigInteger n = seq.readInteger();
        BigInteger e = seq.readInteger();
        BigInteger d = seq.readInteger();
        BigInteger p = seq.readInteger();
        BigInteger q = seq.readInteger();
        BigInteger dp = seq.readInteger();
        BigInteger dq = seq.readInteger();
        BigInteger qi = seq.readInteger();
        return new RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi);
    }

    // Helper DER reader
    private static final class DerReader {
        private final byte[] data;
        private int pos;

        DerReader(byte[] data) { this.data = data; this.pos = 0; }

        DerReader readSequence() throws IOException {
            int tag = readByte();
            if (tag != 0x30) {
                throw new IOException("Expected SEQUENCE (0x30), found: 0x" + Integer.toHexString(tag));
            }
            int length = readLength();
            byte[] content = readBytes(length);
            return new DerReader(content);
        }

        BigInteger readInteger() throws IOException {
            int tag = readByte();
            if (tag != 0x02) {
                throw new IOException("Expected INTEGER (0x02), found: 0x" + Integer.toHexString(tag));
            }
            int length = readLength();
            byte[] value = readBytes(length);
            return new BigInteger(value);
        }

        private int readByte() throws IOException {
            if (pos >= data.length) throw new IOException("Unexpected end of DER data");
            return data[pos++] & 0xFF;
        }

        private int readLength() throws IOException {
            int b = readByte();
            if ((b & 0x80) == 0) {
                return b;
            }
            int numBytes = b & 0x7F;
            if (numBytes == 0 || numBytes > 4) {
                throw new IOException("Unsupported DER length byte count: " + numBytes);
            }
            int length = 0;
            for (int i = 0; i < numBytes; i++) {
                length = (length << 8) | readByte();
            }
            return length;
        }

        private byte[] readBytes(int len) throws IOException {
            if (pos + len > data.length) throw new IOException("Length exceeds available data");
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }
    }

    static List<Path> listAllCrtFiles(String certPath) {
        if (certPath == null) {
            log.error("cert path is not available, no ca files found");
            return Collections.emptyList();
        }
        Path directory = Path.of(certPath);
        if (!Files.exists(directory)) {
            log.error("Certificate directory does not exist: %s", certPath);
            return Collections.emptyList();
        }
        if (!Files.isDirectory(directory)) {
            log.error("Certificate path is not a directory: %s", certPath);
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.toString().endsWith(".crt"))
                    .collect(Collectors.toUnmodifiableList());
        } catch (IOException e) {
            log.error("Cannot list certificate files in directory: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    public static MtlsInfo initializeMtlsInfo() {
        String cfInstanceCert = System.getenv("CF_INSTANCE_CERT");
        String cfInstanceKey = System.getenv("CF_INSTANCE_KEY");
        String cfSystemCertPath = System.getenv("CF_SYSTEM_CERT_PATH");

        if (cfSystemCertPath == null) {
            log.error("CF_SYSTEM_CERT_PATH is not available in env variables.");
            return INVALID_MTLS_INFO;
        }

        if (cfInstanceCert == null) {
            log.error("CF_INSTANCE_CERT is not available in env variables.");
            return INVALID_MTLS_INFO;
        }

        if (cfInstanceKey == null) {
            log.error("CF_INSTANCE_KEY is not available in env variables.");
            return INVALID_MTLS_INFO;
        }

        List<Path> crtFiles = listAllCrtFiles(cfSystemCertPath);

        if (crtFiles.isEmpty()) {
            log.error("No CA certificates (*.crt files) found in %s.", cfSystemCertPath);
            return INVALID_MTLS_INFO;
        }

        return MtlsInfo.extractMtlsInfo(Path.of(cfInstanceKey), Path.of(cfInstanceCert), crtFiles);
    }
}
