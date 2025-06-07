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

import java.util.concurrent.atomic.AtomicBoolean;

public class Logger {

    private static final String LOG_LEVEL = System.getProperty("CF_METRICS_EXPORTER_LOG");

    private static final AtomicBoolean isDebugEnabled =
        new AtomicBoolean("debug".equalsIgnoreCase(LOG_LEVEL));
    private static final AtomicBoolean isTraceEnabled =
        new AtomicBoolean(isDebugEnabled.get() || "trace".equalsIgnoreCase(LOG_LEVEL));

    public static final Object[] NO_ARGS = {};
    private final String className;

    private Logger(Class<?> clazz) {
        this.className = clazz.getSimpleName();
    }

    public static void enableDebug() {
        isDebugEnabled.set(true);
        isTraceEnabled.set(false);
    }
    public static void enableTrace() {
        isTraceEnabled.set(true);
        isDebugEnabled.set(true);
    }

    public void debug(String message) {
        debug(message, NO_ARGS);
    }

    public void debug(String message, Object... args) {
        if (isDebugEnabled.get()) {
            println("[DEBUG]", message, args);
        }
    }

    public void info(String message, Object... args) {
        println("[INFO]", message, args);
    }

    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz);
    }

    private void println(String prefix, String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        System.out.println(prefix + " " + className + ": " + message);
    }

    private void println(String prefix, String message, Throwable throwable, Object... args) {
        StringBuilder messageWithThrowable = new StringBuilder(message + " Error: " + throwable);
        Throwable cause = throwable.getCause();
        while (cause != null) {
            messageWithThrowable.append(" Caused by: ").append(cause);
            cause = cause.getCause();
        }
        if (args.length > 0) {
            message = String.format(messageWithThrowable.toString(), args);
        } else {
            message = messageWithThrowable.toString();
        }
        System.out.println(prefix + " " + className + ": " + message);
        if (isDebugEnabled()) {
            throwable.printStackTrace(System.out);
        }
    }

    public void error(String message, Object... args) {
        println("[ERROR]", message, args);
    }

    public void error(String message, Throwable throwable, Object... args) {
        println("[ERROR]", message, throwable, args);
    }

    public void trace(String message, Object... args) {
        if (isTraceEnabled.get()) {
            println("[TRACE]", message, args);
        }
    }

    public boolean isDebugEnabled() {
        return isDebugEnabled.get();
    }
    public boolean isTraceEnabled() {
        return isTraceEnabled.get();
    }
}
