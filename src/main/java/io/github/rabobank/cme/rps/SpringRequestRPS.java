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
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

public class SpringRequestRPS implements RequestsPerSecond {
    public static final Logger log = Logger.getLogger(SpringRequestRPS.class);
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger(0);
    private static final AtomicLong LAST_RESET_TIME = new AtomicLong(System.currentTimeMillis());
    private static volatile int currentRps = 0;

    public static void initializeSpringInstrumentation(Instrumentation instrumentation) {
        try {
            log.debug("Initializing Spring instrumentation");
            instrumentation.addTransformer(new SpringRequestRpsTransformer(), true);
            log.info("Spring instrumentation initialized.");
        } catch (Exception e) {
            log.error("Spring instrumentation failed: %s", e);
            // Do not throw, continue running
        }
    }

    @Override
    public int rps() {
        if (LAST_RESET_TIME.get() < System.currentTimeMillis() - 1000) {
            updateRpsMetrics();
        }
        return currentRps;
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
            log.debug("Spring request count in last %d seconds: %d, calculated RPS: %d", secondsElapsed, count, currentRps);
        }
    }

    public static void incrementRequestCount() {
        REQUEST_COUNTER.incrementAndGet();
    }

    // ASM-based transformer
    public static class SpringRequestRpsTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {
            if ("org/springframework/web/servlet/DispatcherServlet".equals(className)) {
                String methodName = "doService";
                log.info("Transforming class '%s' method '%s'", className, methodName);
                return transformClass(classfileBuffer, methodName);
            }
            if ("org/springframework/web/reactive/DispatcherHandler".equals(className)) {
                String methodName = "handle";
                log.info("Transforming class '%s' method '%s'", className, methodName);
                return transformClass(classfileBuffer, methodName);
            }
            return null;
        }

        private byte[] transformClass(byte[] classfileBuffer, String methodName) {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (name.equals(methodName)) {
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override
                            protected void onMethodEnter() {
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "io/github/rabobank/cme/rps/SpringRequestRPS",
                                    "incrementRequestCount",
                                    "()V",
                                    false);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        }
    }
}