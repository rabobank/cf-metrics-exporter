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

public final class AutoScalerInfo {

    private static final Logger log = Logger.getLogger(AutoScalerInfo.class);

    private final String password;
    private final String username;
    private final String url;
    private final String urlMtls;

    private final AutoScalerAuthType authType;

    private AutoScalerInfo(String url, String username, String password, String urlMtls) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.urlMtls = urlMtls;
        this.authType = determineAuthType(url, username, password, urlMtls);
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
        String url = findCustomMetricValue(vcapServicesJson, "url").orElse(null);
        String urlMtls = findCustomMetricValue(vcapServicesJson, "mtls_url").orElse(null);
        String username = findCustomMetricValue(vcapServicesJson, "username").orElse(null);
        String password = findCustomMetricValue(vcapServicesJson, "password").orElse(null);
        
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

    private static boolean isBasicAuthConfigured (String url, String username, String password) {
        return url != null && username != null && password != null;
    }

    private static boolean isMtlsAuthConfigured (String urlMtls) {
        return urlMtls != null;
    }

    public AutoScalerAuthType getAuthType() {
        return authType;
    }

    private static AutoScalerAuthType determineAuthType(String url, String username, String password, String urlMtls) {
        // prefer basic auth if complete
        if (isBasicAuthConfigured(url, username, password)) {
            return AutoScalerAuthType.BASIC;
        } else if (isMtlsAuthConfigured(urlMtls)) {
            return AutoScalerAuthType.MTLS;
        } else {
            return AutoScalerAuthType.NONE;
        }
    }

    public enum AutoScalerAuthType {
        BASIC, MTLS, NONE
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
