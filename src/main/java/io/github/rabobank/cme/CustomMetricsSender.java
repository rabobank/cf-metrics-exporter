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

import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.domain.AutoScalerInfo;
import io.github.rabobank.cme.domain.MtlsInfo;
import io.github.rabobank.cme.rps.RequestsPerSecond;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;

public class CustomMetricsSender {

    private static final Logger log = Logger.getLogger(CustomMetricsSender.class);
    private final AutoScalerInfo autoScalerInfo;
    private final ApplicationInfo applicationInfo;

    private final HttpClient client;

    private final RequestsPerSecond requestsPerSecond;

    private final boolean isMtlsEnabled;
    private final String url;

    CustomMetricsSender(RequestsPerSecond requestsPerSecond, AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo, MtlsInfo mtlsInfo) throws CfMetricsAgentException {
        this.requestsPerSecond = requestsPerSecond;
        this.autoScalerInfo = autoScalerInfo;
        this.applicationInfo = applicationInfo;
        this.isMtlsEnabled = !autoScalerInfo.isBasicAuthConfigured() && autoScalerInfo.isMtlsAuthConfigured();
        this.url = isMtlsEnabled ? autoScalerInfo.getUrlMtls() : autoScalerInfo.getUrl();
        this.client = createHttpClient(autoScalerInfo, mtlsInfo);
    }

    private static HttpClient createHttpClient(AutoScalerInfo autoScalerInfo, MtlsInfo mtlsInfo) throws CfMetricsAgentException {
        if (autoScalerInfo.isBasicAuthConfigured()) {
            return HttpClient.newHttpClient();
        }
        else {
            log.info("No basic auth settings found, will use mTLS instead.");
            if (!autoScalerInfo.isMtlsAuthConfigured()) {
                throw new CfMetricsAgentException("No mTLS url found.");
            }

            // Create SSLContext with the provided PEM data
            SSLContext sslContext = createSslContextFromPem(mtlsInfo);

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();
        }
    }

    private static SSLContext createSslContextFromPem(MtlsInfo mtlsInfo) throws CfMetricsAgentException {
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
        } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException | UnrecoverableKeyException e) {
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

    private static PrivateKey loadPrivateKeyFromPem(String pemKey) throws Exception {
        // Clean and prepare PEM content
        String privateKeyPEM = cleanPemContent(pemKey);

        // Decode base64 to get DER bytes
        byte[] der = Base64.getDecoder().decode(privateKeyPEM);

        if (pemKey.contains("BEGIN RSA PRIVATE KEY")) {
            // Traditional RSA key format
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            PEMParser pemParser = new PEMParser(new StringReader(pemKey));
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            Object object = pemParser.readObject();
            if (object instanceof PEMKeyPair) {
                return converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
            } else {
                return converter.getPrivateKey((PrivateKeyInfo) object);
            }
        } else if (pemKey.contains("BEGIN PRIVATE KEY")) {
            // PKCS#8 format
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
            return keyFactory.generatePrivate(keySpec);
        }
        else {
            throw new CfMetricsAgentException("Failed to load private key, unknown format");
        }
    }

    public void send() {
        try {
            int rps = requestsPerSecond.rps();

            if (rps == -1) {
                log.info("Tomcat RPS not available, skip sending RPS.");
                return;
            }

            log.info("Sending RPS: %d", rps);

            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(
                    createPayload(rps, applicationInfo.getIndex()), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(2))
                    .uri(URI.create(url + "/v1/apps/" + applicationInfo.getApplicationId() + "/metrics"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + encodeUsernamePassword(autoScalerInfo))
                    .POST(publisher)
                    .build();

            sendRequest(request);

        } catch (Throwable e) {
            log.error("unexpected agent error", e);
        }
    }

    private void sendRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (log.isTraceEnabled()) {
                String body = response.body();
                log.trace("Response Status Code: %d Body: %s", response.statusCode(), body.isBlank() ? "<empty>" : body);
            }
        } catch (IOException e) {
            log.error("cannot reach server: %s", e, request.uri());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("agent interrupted", e);
        }
    }

    private static String encodeUsernamePassword(AutoScalerInfo autoScalerInfo) {
        return Base64.getEncoder().encodeToString((autoScalerInfo.getUsername() + ":" + autoScalerInfo.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    private String createPayload(int value, int index) {

        return "{\n" +
                "    \"instance_index\": " + index + ",\n" +
                "    \"metrics\": [\n" +
                "      {\n" +
                "        \"name\": \"custom_http_throughput\",\n" +
                "        \"value\": " + value + ",\n" +
                "        \"unit\": \"rps\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }";
    }

}