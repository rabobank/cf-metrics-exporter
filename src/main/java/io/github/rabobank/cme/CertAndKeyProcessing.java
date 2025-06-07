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
package io.github.rabobank.cme;

import io.github.rabobank.cme.domain.MtlsInfo;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CertAndKeyProcessing {

    private static final Logger log = Logger.getLogger(CertAndKeyProcessing.class);

    private CertAndKeyProcessing() {
        // do not create instances
    }

    static SSLContext createSslContextFromPem(MtlsInfo mtlsInfo) throws CfMetricsAgentException {
        KeyStore keyStore = null;
        CertificateFactory certFactory = null;
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
        X509Certificate clientCert = null;
        try {
            clientCert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (CertificateException e) {
            throw new CfMetricsAgentException("Failed to load client cert", e);
        }

        // Load private key
        PrivateKey privateKey = null;
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
        KeyManagerFactory keyManagerFactory = null;
        KeyStore trustStore = null;

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
        SSLContext sslContext = null;

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
        return pemKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s|\\r|\\n", "");
    }

    private static PrivateKey loadPrivateKeyFromPem(String pemKey) throws CfMetricsAgentException {
        // Clean and prepare PEM content
        String privateKeyPEM = cleanPemContent(pemKey);

        // Decode base64 to get DER bytes
        byte[] der = Base64.getDecoder().decode(privateKeyPEM);

        if (pemKey.contains("BEGIN RSA PRIVATE KEY")) {
            // Traditional RSA key format
            try {
                Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                PEMParser pemParser = new PEMParser(new StringReader(pemKey));
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                Object object = pemParser.readObject();
                if (object instanceof PEMKeyPair) {
                    return converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
                } else {
                    return converter.getPrivateKey((PrivateKeyInfo) object);
                }
            } catch (IOException e) {
                throw new CfMetricsAgentException("Failed to process rsa key", e);
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

        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.toString().endsWith(".crt"))
                    .collect(Collectors.toUnmodifiableList());
        } catch (IOException e) {
            log.error("Cannot list certificate files in directory: %s", e.getMessage());
            return Collections.emptyList();
        }
    }
}
