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
import io.github.rabobank.cme.rps.RequestsPerSecond;

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
    private final AutoScalerInfo autoScalerInfo;
    private final ApplicationInfo applicationInfo;

    private HttpClient client = HttpClient.newHttpClient();
    private final RequestsPerSecond requestsPerSecond;

    CustomMetricsSender(RequestsPerSecond requestsPerSecond, AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo) {
        this.requestsPerSecond = requestsPerSecond;
        this.autoScalerInfo = autoScalerInfo;
        this.applicationInfo = applicationInfo;
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
                    .uri(URI.create(autoScalerInfo.getUrl() + "/v1/apps/" + applicationInfo.getApplicationId() + "/metrics"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + encodeUsernamePassword(autoScalerInfo))
                    .POST(publisher)
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (log.isDebugEnabled()) {
                    log.debug("Response Status Code: %d", response.statusCode());
                    log.debug("Response Body: %s", response.body());
                }
            } catch (IOException e) {
                log.error("cannot reach server: %s %s", request.uri(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("agent interrupted: %s", e);
            }
        } catch (Throwable e) {
            log.error("unexpected agent error: %s", e);
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
