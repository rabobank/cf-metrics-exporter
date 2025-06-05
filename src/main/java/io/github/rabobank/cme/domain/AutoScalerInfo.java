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
package io.github.rabobank.cme.domain;

import io.github.rabobank.cme.Logger;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoScalerInfo {

    private static final Logger log = Logger.getLogger(AutoScalerInfo.class);

    private final String password;
    private final String username;
    private final String url;
    private final String urlMtls;

    public AutoScalerInfo(String url, String username, String password, String urlMtls) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.urlMtls = urlMtls;
    }

    public String getPassword() {
        return password;
    }

    public String getUrl() {
        return url;
    }

    public String getUrlMtls() {
        return urlMtls;
    }

    public String getUsername() {
        return username;
    }

    public static AutoScalerInfo extractMetricsServerInfo(String vcapServicesJson) {
        String url = findUrl(vcapServicesJson).orElse(null);
        String urlMtls = findUrlMtls(vcapServicesJson).orElse(null);
        String username = findUsername(vcapServicesJson).orElse(null);
        String password = findPassword(vcapServicesJson).orElse(null);
        
        return new AutoScalerInfo(url, username, password, urlMtls);
    }

    private static Optional<String> findCustomMetricValue(String vcapServices, String key) {
        Pattern pattern = Pattern.compile("\"custom_metrics\"\\s*:\\s*\\{[^}]*\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(vcapServices);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        } else {
            log.debug("'" + key + "' not found in 'custom_metrics' within VCAP_SERVICES");
        }
        return Optional.empty();
    }

    private static Optional<String> findPassword(String vcapServices) {
        return findCustomMetricValue(vcapServices, "password");
    }

    private static Optional<String> findUsername(String vcapServices) {
        return findCustomMetricValue(vcapServices, "username");
    }

    private static Optional<String> findUrl(String vcapServices) {
        return findCustomMetricValue(vcapServices, "url");
    }

    private static Optional<String> findUrlMtls(String vcapServices) {
        return findCustomMetricValue(vcapServices, "url-mtls");
    }

    public boolean isBasicAuthConfigured () {
        return (url != null && username != null && password != null);
    }

    public boolean isMtlsAuthConfigured () {
        return urlMtls != null;
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException("hashCode() not implemented");
    }

    @Override
    public boolean equals(Object obj) {
        throw new UnsupportedOperationException("equals() not implemented");
    }
}
