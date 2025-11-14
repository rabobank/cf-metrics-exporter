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
package io.github.rabobank.cme.rps;

import io.github.rabobank.cme.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SpringRequestRPS implements RequestsPerSecond {
    // make available for the byte buddy handler to log some debug
    public static final Logger log = Logger.getLogger(SpringRequestRPS.class);

    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger(0);
    private static final AtomicLong LAST_RESET_TIME = new AtomicLong(System.currentTimeMillis());

    private static volatile int currentRps = 0;

    public static void initializeSpringInstrumentation(Instrumentation instrumentation) {
        log.debug("Initializing Spring instrumentation");

        AgentBuilder.Listener debugListener = new AgentBuilder.Listener() {
            @Override
            public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                log.trace("Discovered: %s", typeName);
            }

            @Override
            public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                log.info("Successfully transformed: %s", typeDescription.getName());
            }

            @Override
            public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
                log.trace("Ignored: %s", typeDescription.getName());
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                log.error("Error transforming: %s", throwable, typeName);
            }

            @Override
            public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                log.trace("Completed: %s", typeName);
            }
        };

        new AgentBuilder.Default()
                .with(debugListener)
                .type(ElementMatchers.named("org.springframework.web.reactive.DispatcherHandler")
                        .or(ElementMatchers.named("org.springframework.web.servlet.DispatcherServlet"))
                )
                .transform((builder, type, classLoader, module, protectionDomain) -> {
                    printClassLoaderInfo(type, classLoader);
                    return builder.method(ElementMatchers.named("handle").or(ElementMatchers.named("doService")))
                            .intercept(Advice.to(HandlerMethodAdvice.class));
                })
                .installOn(instrumentation);

        log.info("Spring instrumentation initialized.");
    }

    private static void printClassLoaderInfo(TypeDescription type, ClassLoader classLoader) {
        log.info("Transforming class: %s using classloader: %s",
                type.getName(), classLoader != null ? classLoader.getClass().getName() : "bootstrap");
    }

    @Override
    public int rps() {
        if (LAST_RESET_TIME.get() < System.currentTimeMillis() - 1000) {
            updateRpsMetrics();
        }
        return currentRps;
    }

    public static final class HandlerMethodAdvice {
        private HandlerMethodAdvice() {
            // Private constructor to prevent instantiation
        }
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Origin String method, @Advice.This Object thiz) {
            if (log.isTraceEnabled()) {
                log.trace("Count request for %s on %s", method, thiz.getClass().getName());
            }
            incrementRequestCount();
        }
    }

    // Method to reset and calculate RPS periodically
    private static void updateRpsMetrics() {
        long currentTimeMillis = System.currentTimeMillis();
        long previousTimeMillis = LAST_RESET_TIME.getAndSet(currentTimeMillis);
        int count = REQUEST_COUNTER.getAndSet(0);

        // Calculate seconds elapsed, minimum 1 to avoid division by zero
        long secondsElapsed = Math.max(1, (currentTimeMillis - previousTimeMillis) / 1000);

        // Report 1 RPS if there is at least 1 count, 0 if it is really 0 hits
        currentRps = (int) Math.ceil((double) count / secondsElapsed);

        if (log.isDebugEnabled()) {
            log.debug("Spring request count in last %d seconds: %d, calculated RPS: %d",
                    secondsElapsed, count, currentRps);
        }
    }

    public static void incrementRequestCount() {
        REQUEST_COUNTER.incrementAndGet();
    }
}