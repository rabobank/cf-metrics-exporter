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
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.io.File;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class AppAutoscalerWiremockServer {

    public static final int HTTP_PORT = 58080;
    public static final int HTTPS_PORT = 58443;

    public static WireMockServer createWiremockServer() {

        File keystoreFile = new File("target/generated-certs/server.p12");
        File truststoreFile = new File("target/generated-certs/server-truststore.p12");

        System.out.println("Keystore file  : " + (keystoreFile.exists() ? keystoreFile.getPath() : "NOT FOUND: " + keystoreFile));
        System.out.println("Truststore file: " + (truststoreFile.exists() ? truststoreFile.getPath() : "NOT FOUND: " + truststoreFile));

        if (!(keystoreFile.exists() && truststoreFile.exists())) {
            throw new RuntimeException("Could not find keystore or truststore files in resources");
        }

        WireMockServer wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .port(HTTP_PORT)
                        .httpsPort(HTTPS_PORT)
                        .keystorePath(keystoreFile.getPath())
                        .keystorePassword("changeit")
                        .keyManagerPassword("changeit")
                        .keystoreType("PKCS12")
                        .trustStorePath(truststoreFile.getPath())
                        .trustStorePassword("changeit")
                        .trustStoreType("PKCS12")
                        .needClientAuth(true)
        );

        wireMockServer.stubFor(post(urlEqualTo("/v1/apps/6f452e74-application-id/metrics"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                ));

        return wireMockServer;
    }

    public static void main(String[] args) {
        WireMockServer wireMockServer = AppAutoscalerWiremockServer.createWiremockServer();
        wireMockServer.start();
    }
}
