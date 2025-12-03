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
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SpringRequestRPS implements RequestsPerSecond {
    public static final Logger log = Logger.getLogger(SpringRequestRPS.class);

    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger(0);
    private static final AtomicLong LAST_RESET_TIME = new AtomicLong(System.currentTimeMillis());
    @SuppressWarnings("PMD.AvoidUsingVolatile") // volatile is needed for cross-thread visibility of currentRps
    private static volatile int currentRps = 0;
    // Ensure we don't register the transformer more than once across tests/JVM lifecycle
    private static final AtomicBoolean TRANSFORMER_INSTALLED = new AtomicBoolean(false);
    // Ensure we only run the expensive retransformation pass once to avoid double-instrumentation
    private static final AtomicBoolean RETRANSFORM_EXECUTED = new AtomicBoolean(false);

    public static void initializeSpringInstrumentation(Instrumentation instrumentation) {
        try {
            log.debug("Initializing Spring instrumentation");
            if (TRANSFORMER_INSTALLED.compareAndSet(false, true)) {
                SpringRequestRpsTransformer transformer = new SpringRequestRpsTransformer();
                instrumentation.addTransformer(transformer, true);
                log.debug("Spring transformer registered (canRetransform=true)");
            } else {
                log.debug("Spring transformer already registered, skipping duplicate registration");
            }

            // Attempt to retransform already loaded target classes so that
            // the instrumentation also applies when Spring was loaded before the agent.
            if (instrumentation.isRetransformClassesSupported()) {
                if (RETRANSFORM_EXECUTED.compareAndSet(false, true)) {
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        String name = loaded.getName();
                        if (isTargetClass(name) && instrumentation.isModifiableClass(loaded)) {
                            try {
                                log.info("Retransforming already loaded class '%s'", name);
                                instrumentation.retransformClasses(loaded);
                            } catch (Exception ex) {
                                log.error("Failed to retransform class %s: %s", name, ex);
                            }
                        }
                    }
                } else {
                    log.debug("Retransformation pass already executed once, skipping to avoid double instrumentation");
                }
            } else {
                log.info("Retransform classes not supported by current JVM/instrumentation.");
            }

            log.info("Spring instrumentation initialized");
        } catch (Exception e) {
            log.error("Spring instrumentation failed: %s", e);
            // Do not throw, continue running
        }
    }

    private static boolean isTargetClass(String fqcn) {
        // Only instrument the two explicitly requested Spring entry points
        return "org.springframework.web.servlet.DispatcherServlet".equals(fqcn)
                || "org.springframework.web.reactive.DispatcherHandler".equals(fqcn);
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

        // Report 1 RPS if there is at least 1 count, 0 only if it is really 0 hits
        currentRps = count == 0 ? 0 : Math.max(1, (int) Math.round((double) count / secondsElapsed));

        if (log.isDebugEnabled()) {
            log.debug("Spring request count in last %d seconds: %d, calculated avg RPS: %d", secondsElapsed, count, currentRps);
        }
    }

    public static void incrementRequestCount() {
        REQUEST_COUNTER.incrementAndGet();
    }

    // ASM-based transformer
    public static class SpringRequestRpsTransformer implements ClassFileTransformer {

        public static final String DISPATCHER_SERVLET_CLASS_PATH = "org/springframework/web/servlet/DispatcherServlet";
        public static final String REACTIVE_DISPATCHER_HANDLER_PATH = "org/springframework/web/reactive/DispatcherHandler";

        @Override
        @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null means: did not transform class, see javadoc
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {

            if (DISPATCHER_SERVLET_CLASS_PATH.equals(className)
                    || REACTIVE_DISPATCHER_HANDLER_PATH.equals(className)) {
                log.debug("Starting transformation of class %s", className);

                if (log.isDebugEnabled()) {
                    // Log classloader and protection domain details for diagnostics
                    String clName = (loader == null) ? "<bootstrap>" : loader.getClass().getName();
                    String clStr = (loader == null) ? "<bootstrap>" : loader.toString();
                    ClassLoader parent = (loader == null) ? null : loader.getParent();
                    URL codeSource = null;
                    if (domain != null && domain.getCodeSource() != null) {
                        codeSource = domain.getCodeSource().getLocation();
                    }
                    log.debug("Transform request: class='%s', loader='%s' (%s), parentLoader='%s', codeSource='%s', retransform=%s",
                            className, clName, clStr, (parent == null ? "<none>" : parent.getClass().getName()),
                            String.valueOf(codeSource), String.valueOf(classBeingRedefined != null));
                }

                if (DISPATCHER_SERVLET_CLASS_PATH.equals(className)) {
                    // Only instrument doService as requested
                    String method = "doService";
                    log.info("Transforming class '%s' method '%s'", className, method);
                    return transformClass(className, classfileBuffer, method);
                }

                if (REACTIVE_DISPATCHER_HANDLER_PATH.equals(className)) {
                    String methodName = "handle";
                    log.info("Transforming class '%s' method '%s'", className, methodName);
                    return transformClass(className, classfileBuffer, methodName);
                }
            }
            return null;
        }

        private byte[] transformClass(String internalClassName, byte[] classfileBuffer, String methodName) {
            log.debug("Starting transformation of class %s for method %s", internalClassName, methodName);
            ClassReader classReader = new ClassReader(classfileBuffer);
            // Use COMPUTE_MAXS to avoid ClassWriter frame recomputation that may require
            // application class loading (getCommonSuperClass). We also expand frames in
            // the reader/accept path to satisfy LocalVariablesSorter/AdviceAdapter.
            ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, classWriter) {
                private boolean injected;

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    log.trace("Visiting method %s#%s", internalClassName, name);
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (name.equals(methodName)) {
                        // Skip abstract or native methods (no bytecode to modify)
                        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                            log.debug("Skipping method '%s' in %s because it is abstract/native", name, internalClassName);
                            return mv;
                        }
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override
                            protected void onMethodEnter() {
                                log.debug("Injecting call to incrementRequestCount into %s#%s", internalClassName, methodName);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/github/rabobank/cme/rps/SpringRequestRPS",
                                        "incrementRequestCount",
                                        "()V",
                                        false);
                                injected = true;
                            }
                        };
                    }
                    return mv;
                }

                @Override
                public void visitEnd() {
                    super.visitEnd();
                    if (injected) {
                        log.trace("Injected call to incrementRequestCount into %s#%s", internalClassName, methodName);
                    } else {
                        log.trace("No injection performed for method '%s' in %s (method not found or not eligible)", methodName, internalClassName);
                    }
                }
            };
            try {
                // EXPAND_FRAMES is required when using LocalVariablesSorter/AdviceAdapter
                // to ensure expanded stack map frames are provided to visitors.
                classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
            } catch (Exception t) {
                log.error("Failed to transform class %s: %s", internalClassName, t);
            }
            return classWriter.toByteArray();
        }
    }

}