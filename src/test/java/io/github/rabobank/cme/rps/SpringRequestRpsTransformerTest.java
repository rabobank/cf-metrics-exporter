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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests that verify the ASM transformation injects a call to
 * SpringRequestRPS.incrementRequestCount() into selected target classes.
 */
class SpringRequestRpsTransformerTest implements Opcodes {

    @BeforeEach
    void setUp() {
        resetSpringRequestRpsState();
    }

    @Test
    void whenTargetClassIsAlreadyLoaded_transformationIsApplied_withRetransform() throws Exception {
        // Arrange: generate and load the original (untransformed) class first
        String internalName = "org/springframework/web/servlet/DispatcherServlet";
        byte[] original = generateClassWithVoidMethod(internalName, "doService");

        // Define class BEFORE attempting to transform: this simulates "already loaded" scenario
        Class<?> alreadyLoaded = new TestClassLoader().define(internalName.replace('/', '.'), original);

        // In a real JVM, the agent would retransform already loaded classes.
        // Here we simulate that behavior by invoking the transformer directly on the original bytes
        // and then defining the transformed class in a fresh ClassLoader.
        SpringRequestRPS.SpringRequestRpsTransformer transformer = new SpringRequestRPS.SpringRequestRpsTransformer();
        byte[] retransformed = transformer.transform(null, internalName, alreadyLoaded, null, original);
        Class<?> retransformedClass = new TestClassLoader().define(internalName.replace('/', '.'), retransformed);

        resetSpringRequestRpsState();
        Object instance = retransformedClass.getDeclaredConstructor().newInstance();
        Method m = retransformedClass.getDeclaredMethod("doService");

        // Act
        m.invoke(instance);

        // Assert: counter is incremented thanks to retransformation of the already loaded class
        assertEquals(1, getSpringRequestRpsRawCounter(),
                "Retransformation should inject the increment call for already loaded target class");
    }

    @Test
    void transformsDispatcherServlet_doService_incrementsCounter() throws Exception {
        // Arrange: generate a basic class matching the transformer target
        String internalName = "org/springframework/web/servlet/DispatcherServlet";
        byte[] original = generateClassWithVoidMethod(internalName, "doService");

        // Transform the class bytes using our ASM transformer
        SpringRequestRPS.SpringRequestRpsTransformer transformer = new SpringRequestRPS.SpringRequestRpsTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method m = cls.getDeclaredMethod("doService");

        // Assert: before call
        assertEquals(0, getSpringRequestRpsRawCounter(), "Precondition: counter should be zero before invocation");

        // Invoke the method once; transformer should inject increment
        m.invoke(instance);

        assertEquals(1, getSpringRequestRpsRawCounter(), "Counter should be incremented by transformed method");
    }

    @Test
    void transformsDispatcherHandler_handle_incrementsCounter() throws Exception {
        String internalName = "org/springframework/web/reactive/DispatcherHandler";
        byte[] original = generateClassWithVoidMethod(internalName, "handle");

        // Transform the class bytes using our ASM transformer
        SpringRequestRPS.SpringRequestRpsTransformer transformer = new SpringRequestRPS.SpringRequestRpsTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method m = cls.getDeclaredMethod("handle");

        assertEquals(0, getSpringRequestRpsRawCounter());
        m.invoke(instance);
        assertEquals(1, getSpringRequestRpsRawCounter());
    }

    // Helper to generate a minimal class with a no-arg constructor and a public void method
    private static byte[] generateClassWithVoidMethod(String internalName, String methodName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V11, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // default constructor
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // target method
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // Minimal class loader to define a class from bytes
    static class TestClassLoader extends ClassLoader {
        Class<?> define(String fqcn, byte[] bytes) {
            return defineClass(fqcn, bytes, 0, bytes.length);
        }
    }

    // -------------------
    // Test utilities (reflection based) to avoid production test hooks
    // -------------------
    private static void resetSpringRequestRpsState() {
        try {
            Class<?> cls = SpringRequestRPS.class;
            // REQUEST_COUNTER
            Field counterField = cls.getDeclaredField("REQUEST_COUNTER");
            counterField.setAccessible(true);
            AtomicInteger counter = (AtomicInteger) counterField.get(null);
            counter.set(0);

            // LAST_RESET_TIME
            Field lastResetField = cls.getDeclaredField("LAST_RESET_TIME");
            lastResetField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong lastReset = (java.util.concurrent.atomic.AtomicLong) lastResetField.get(null);
            lastReset.set(System.currentTimeMillis());

            // currentRps
            Field currentRpsField = cls.getDeclaredField("currentRps");
            currentRpsField.setAccessible(true);
            currentRpsField.setInt(null, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int getSpringRequestRpsRawCounter() {
        try {
            Field counterField = SpringRequestRPS.class.getDeclaredField("REQUEST_COUNTER");
            counterField.setAccessible(true);
            AtomicInteger counter = (AtomicInteger) counterField.get(null);
            return counter.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
