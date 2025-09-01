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

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class CustomMetricsSender {

    private static final Logger log = Logger.getLogger(CustomMetricsSender.class);

    public static final String CUSTOM_THROUGHPUT_METRIC_NAME = "custom_throughput";

    private final AutoScalerInfo autoScalerInfo;
    private final ApplicationInfo applicationInfo;

    private final HttpClient client;

    private final RequestsPerSecond requestsPerSecond;

    private final String url;

    CustomMetricsSender(RequestsPerSecond requestsPerSecond, AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo) throws CfMetricsAgentException {
        this(requestsPerSecond, autoScalerInfo, applicationInfo, null);
    }

    CustomMetricsSender(RequestsPerSecond requestsPerSecond, AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo, MtlsInfo mtlsInfo) throws CfMetricsAgentException {

        if (mtlsInfo == null && !autoScalerInfo.isBasicAuthConfigured()) {
            throw new CfMetricsAgentException("No basic auth and no mTLS settings found.");
        }

        if (!autoScalerInfo.isBasicAuthConfigured()) {
            log.info("No basic auth settings found, will use mTLS instead.");
        }

        this.requestsPerSecond = requestsPerSecond;
        this.autoScalerInfo = autoScalerInfo;
        this.applicationInfo = applicationInfo;
        boolean isMtlsEnabled = !autoScalerInfo.isBasicAuthConfigured() && autoScalerInfo.isMtlsAuthConfigured();
        this.url = isMtlsEnabled ? autoScalerInfo.getUrlMtls() : autoScalerInfo.getUrl();
        this.client = isMtlsEnabled ? createHttpClientMtls(mtlsInfo) : createHttpClientBasicAuth();
    }

    private HttpClient createHttpClientBasicAuth() {
        return HttpClient.newHttpClient();
    }

    private HttpClient createHttpClientMtls(MtlsInfo mtlsInfo) throws CfMetricsAgentException {

        if (mtlsInfo == null || !mtlsInfo.isValid()) {
            throw new CfMetricsAgentException("Mtls settings are not present or not valid.");
        }

        // Create SSLContext with the provided PEM data
        SSLContext sslContext = CertAndKeyProcessing.createSslContextFromPem(mtlsInfo);

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
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
            log.error("error sending RPS", e);
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
                "        \"name\": \"" + CUSTOM_THROUGHPUT_METRIC_NAME + "\",\n" +
                "        \"value\": " + value + ",\n" +
                "        \"unit\": \"rps\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }";
    }

}