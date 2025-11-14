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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.nio.file.Files.readAllBytes;

public final class MtlsInfo {

    private static final Logger log = Logger.getLogger(MtlsInfo.class);

    public static final MtlsInfo INVALID_MTLS_INFO = new MtlsInfo(null, null, null);

    private final String ca;
    private final String cert;
    private final String key;

    private MtlsInfo(String ca, String cert, String key) {
        this.ca = ca;
        this.cert = cert;
        this.key = key;
    }

    public String getCa() {
        return ca;
    }
    public String getCert() {
        return cert;
    }
    public String getKey() {
        return key;
    }

    public static MtlsInfo extractMtlsInfo(Path keyFile, Path certFile, List<Path> caFiles) {
        String key = readFile(keyFile).orElse(null);
        String cert = readFile(certFile).orElse(null);
        String ca = readFiles(caFiles).orElse(null);
        return new MtlsInfo(ca, cert, key);
    }

    private static Optional<String> readFiles(List<Path> caFiles) {
        StringBuilder ca = new StringBuilder(128);
        for (Path caFile : caFiles) {
            readFile(caFile).ifPresent(ca::append);
        }
        return ca.length() == 0 ? Optional.empty() : Optional.of(ca.toString());
    }

    private static Optional<String> readFile(Path fileName) {
        return readFile(fileName, "UTF-8");
    }

    @SuppressWarnings("PMD.AvoidLoadingAllFromFile") // need all bytes anyway
    private static Optional<String> readFile(Path fileName, String encoding) {
        try {
            return Optional.of(new String(readAllBytes(fileName), encoding));
        } catch (Exception e) {
            log.error("Failed to read file: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isValid() {
        return ca != null && cert != null && key != null && !ca.isBlank() && !cert.isBlank() && !key.isBlank();
    }
}
