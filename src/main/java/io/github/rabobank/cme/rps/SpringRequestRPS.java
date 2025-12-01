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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.Textifier;

public class SpringRequestRPS implements RequestsPerSecond {
    public static final Logger log = Logger.getLogger(SpringRequestRPS.class);
    private static final boolean DUMP_ASM = Boolean.parseBoolean(System.getProperty("CF_METRICS_EXPORTER_DUMP_ASM", "false"));
    // Optional file to dump ASM textual output. Defaults to "asm-dump.txt" in working directory.
    private static final String DUMP_ASM_FILE = System.getProperty(
            "CF_METRICS_EXPORTER_DUMP_ASM_FILE",
            Paths.get(System.getProperty("user.dir", "."), "asm-dump.txt").toString()
    );
    private static final AtomicBoolean ASM_HEADER_WRITTEN = new AtomicBoolean(false);
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger(0);
    private static final AtomicLong LAST_RESET_TIME = new AtomicLong(System.currentTimeMillis());
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
                log.info("Spring transformer registered (canRetransform=true)");
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

            log.info("Spring instrumentation initialized.");
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
        if (log.isTraceEnabled()) {
            // Log call-site details to verify that injected calls are executed
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            // Indexes: 0=getStackTrace, 1=this method, 2=caller (instrumented method)
            StackTraceElement caller = st.length > 2 ? st[2] : null;
            String callerInfo = caller != null ? (caller.getClassName() + "#" + caller.getMethodName()) : "unknown";
            log.trace("incrementRequestCount() invoked by %s", callerInfo);
        }
    }

    // test helpers (package-private) – intentionally no public API impact
    static void resetForTest() {
        REQUEST_COUNTER.set(0);
        LAST_RESET_TIME.set(System.currentTimeMillis());
        currentRps = 0;
    }

    static int getRequestCountForTest() {
        return REQUEST_COUNTER.get();
    }

    // ASM-based transformer
    public static class SpringRequestRpsTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {
            if ("org/springframework/web/servlet/DispatcherServlet".equals(className)
                || "org/springframework/web/reactive/DispatcherHandler".equals(className)) {
                // Log classloader and protection domain details for diagnostics
                String clName = (loader == null) ? "<bootstrap>" : loader.getClass().getName();
                String clStr = (loader == null) ? "<bootstrap>" : loader.toString();
                ClassLoader parent = (loader == null) ? null : loader.getParent();
                URL codeSource = null;
                if (domain != null && domain.getCodeSource() != null) {
                    codeSource = domain.getCodeSource().getLocation();
                }
                if (log.isDebugEnabled()) {
                    log.debug("Transform request: class='%s', loader='%s' (%s), parentLoader='%s', codeSource='%s', retransform=%s",
                            className, clName, clStr, (parent == null ? "<none>" : parent.getClass().getName()),
                            String.valueOf(codeSource), String.valueOf(classBeingRedefined != null));
                }
            }
            if ("org/springframework/web/servlet/DispatcherServlet".equals(className)) {
                // Only instrument doService as requested
                String method = "doService";
                log.info("Transforming class '%s' method '%s'", className, method);
                return transformClass(className, classfileBuffer, method, loader);
            }
            if ("org/springframework/web/reactive/DispatcherHandler".equals(className)) {
                String methodName = "handle";
                log.info("Transforming class '%s' method '%s'", className, methodName);
                return transformClass(className, classfileBuffer, methodName, loader);
            }
            return null;
        }

        private byte[] transformClass(String internalClassName, byte[] classfileBuffer, String methodName, ClassLoader loader) {
            log.debug("Starting transformation of %s for method %s", internalClassName, methodName);
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
                        log.info("Injected call to incrementRequestCount into %s#%s", internalClassName, methodName);
                    } else {
                        log.info("No injection performed for method '%s' in %s (method not found or not eligible)", methodName, internalClassName);
                    }
                }
            };
            log.debug("Accept class visitor: " + classVisitor );
            try {
                // EXPAND_FRAMES is required when using LocalVariablesSorter/AdviceAdapter
                // to ensure expanded stack map frames are provided to visitors.
                classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
            } catch (Exception t) {
                log.error("Failed to transform class %s: %s", internalClassName, t);
            }
            log.debug("After accept class visitor: " + classVisitor );
            byte[] bytes = classWriter.toByteArray();
            // Emit ASM dump when debug logging is enabled (was trace-only). This makes it
            // easier to capture dumps in real runs where only debug is enabled.
            if (DUMP_ASM) {
                try {
                    StringWriter sw = new StringWriter(4096);
                    PrintWriter pw = new PrintWriter(sw);
                    TraceClassVisitor tcv = new TraceClassVisitor(null, new Textifier(), pw);
                    new ClassReader(bytes).accept(tcv, 0);
                    String clDetails = (loader == null) ? "<bootstrap>" : loader.getClass().getName();
                    // If file dumping is enabled, write to file (and only a short info log).
                    appendAsmDumpToFile(internalClassName, methodName, clDetails, sw.toString());
                } catch (Throwable t) {
                    log.debug("Failed to print ASM trace for %s: %s", internalClassName, t);
                }
            }
            return bytes;
        }

        private static void appendAsmDumpToFile(String internalClassName, String methodName, String loader, String asmText) throws IOException {
            Path path = Paths.get(DUMP_ASM_FILE);
            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null) {
                try { Files.createDirectories(parent); } catch (IOException ignore) { /* best-effort */ }
            }
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            StringBuilder sb = new StringBuilder(asmText.length() + 512);
            if (ASM_HEADER_WRITTEN.compareAndSet(false, true)) {
                sb.append("==== CF Metrics Exporter ASM Dump ====\n")
                  .append("file: ").append(path.toAbsolutePath()).append('\n')
                  .append("created: ").append(timestamp).append('\n')
                  .append("=====================================\n\n");
            }
            sb.append("---- class=").append(internalClassName)
              .append(", method=").append(methodName)
              .append(", loader=").append(loader)
              .append(", time=").append(timestamp)
              .append(" ----\n");
            sb.append(asmText).append('\n')
              .append("---- end ").append(internalClassName).append('#').append(methodName).append(" ----\n\n");

            byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
            // Synchronize to keep multi-threaded agent writes ordered
            synchronized (SpringRequestRPS.class) {
                Files.write(path, out, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            log.info("ASM dump written to %s (%s#%s)", path.toAbsolutePath().toString(), internalClassName, methodName);
        }
    }
}