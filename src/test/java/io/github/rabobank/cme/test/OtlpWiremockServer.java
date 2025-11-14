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
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class OtlpWiremockServer {

    public static final int HTTP_PORT = 54318;

    public static WireMockServer createWiremockServer() {

        WireMockServer wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .port(HTTP_PORT)
                        .stubRequestLoggingDisabled(false)
                        .notifier(new ConsoleNotifier(true)) // Enable verbose logging

        );

        wireMockServer.stubFor(post(urlEqualTo("/v1/metrics"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                ));

        return wireMockServer;
    }

    public static void main(String[] args) {
        WireMockServer wireMockServer = OtlpWiremockServer.createWiremockServer();
        wireMockServer.start();
    }
}
