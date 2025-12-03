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
package io.github.rabobank.cme.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.rabobank.cme.cf.CustomMetricsSender;
import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.domain.AutoScalerInfo;
import io.github.rabobank.cme.domain.MtlsInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts WireMock with mutual TLS and verifies the agent sender posts metrics over mTLS.
 */
@TestInstance(Lifecycle.PER_CLASS)
class CustomMetricsSenderMtlsTest {

    private WireMockServer autoscalerWireMock;
    private WireMockServer otlpWireMock;

    @BeforeAll
    void setUpAll() {
        // Ensure test certificates are present (generated during Maven compile phase)
        Path base = Path.of("target", "generated-certs");
        Path clientKey = base.resolve("client-key.pem");
        Path clientPem = base.resolve("client.pem");
        Path cacertsDir = base.resolve("cacerts");

        assertTrue(Files.exists(clientKey), "Expected mTLS client key to exist at " + clientKey);
        assertTrue(Files.exists(clientPem), "Expected mTLS client certificate to exist at " + clientPem);
        assertTrue(Files.isDirectory(cacertsDir), "Expected CA certs directory to exist at " + cacertsDir);

        // Start WireMock servers (AutoScaler on HTTPS with mTLS and OTLP on HTTP)
        autoscalerWireMock = AppAutoscalerWiremockServer.createWiremockServer();
        autoscalerWireMock.start();

        // OTLP server is not strictly needed for mTLS test, but start for completeness
        otlpWireMock = OtlpWiremockServer.createWiremockServer();
        otlpWireMock.start();
    }

    @AfterAll
    void tearDownAll() {
        if (autoscalerWireMock != null) {
            autoscalerWireMock.stop();
        }
        if (otlpWireMock != null) {
            otlpWireMock.stop();
        }
    }

    @Test
    void sendsMetricOverMtlsToAutoscaler() throws Exception {

        // Build MtlsInfo from generated PEM files
        Path base = Path.of("target", "generated-certs");
        // Ensure we test the PKCS#1 code path explicitly by generating a PKCS#1 RSA private key PEM
        Path clientKey = base.resolve("client-key-pkcs1.pem");
        Path clientPem = base.resolve("client.pem");
        Path cacertsDir = base.resolve("cacerts");

       // Ensure we're testing with a PKCS#1 RSA key (BEGIN RSA PRIVATE KEY)
       String keyPem = Files.readString(clientKey);
        System.out.println("[DEBUG_LOG] First line of client-key-pkcs1.pem: " + keyPem.lines().findFirst().orElse("<empty>"));
        assertTrue(keyPem.contains("BEGIN RSA PRIVATE KEY"), "Expected client-key-pkcs1.pem to be PKCS#1 (BEGIN RSA PRIVATE KEY). If not, the test setup script may have changed.");

        List<Path> caFiles;

        try (var fileStream = Files.list(cacertsDir)) {
            caFiles = fileStream.filter(p -> p.getFileName().toString().endsWith(".crt"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list CA files in " + cacertsDir, e);
        }

        MtlsInfo mtlsInfo = MtlsInfo.extractMtlsInfo(clientKey, clientPem, caFiles);

        // Construct AutoScalerInfo using VCAP_SERVICES-like JSON with only mtls_url
        String vcapServicesJson = "{\n" +
                "  \"user-provided\": [{\n" +
                "    \"credentials\": {\n" +
                "      \"custom_metrics\": { \"mtls_url\": \"https://localhost:" + AppAutoscalerWiremockServer.HTTPS_PORT + "\" }\n" +
                "    }\n" +
                "  }]\n" +
                "}";

        AutoScalerInfo autoScalerInfo = AutoScalerInfo.extractMetricsServerInfo(vcapServicesJson);

        // Application info matching the path stubbed in AppAutoscalerWiremockServer
        ApplicationInfo appInfo = new ApplicationInfo("6f452e74-application-id", 0, "app", "space", "org");

        // Create sender and emit a metric, which should POST to WireMock over HTTPS with client cert
        CustomMetricsSender sender = new CustomMetricsSender(autoScalerInfo, appInfo, mtlsInfo);
        sender.emitMetric("custom_rps", 42);

        // Verify WireMock received the expected POST
         autoscalerWireMock.verify(postRequestedFor(urlEqualTo("/v1/apps/6f452e74-application-id/metrics")));
     }

}
