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

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class HttpUtil {

    public static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofMillis(1200);
    public static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofMillis(800);

    private final static Logger log = Logger.getLogger(HttpUtil.class);

    private HttpUtil() {
    }

    public static void sendRequest(HttpClient httpClient, HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (log.isTraceEnabled()) {
                String body = response.body();
                log.trace("Response Status Code: %d Body: %s", response.statusCode(), body.isBlank() ? "<empty>" : body);
            }
        } catch (IOException e) {
            log.error("CfMetricsAgent cannot reach: %s", e, request.uri());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("CfMetricsAgent interrupted", e);
        }
    }

    public static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .build();
    }

    public static HttpClient createHttpClientMtls(MtlsInfo mtlsInfo) throws CfMetricsAgentException {

        if (mtlsInfo == null || !mtlsInfo.isValid()) {
            throw new CfMetricsAgentException("mTLS settings are not present or not valid.");
        }

        // Create SSLContext with the provided PEM data
        SSLContext sslContext = CertAndKeyProcessing.createSslContextFromPem(mtlsInfo);

        return HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .sslContext(sslContext)
                .build();
    }

    public static String encodeBasicAuthHeader(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
