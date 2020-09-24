/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.fuse.tia.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

/**
 * This class resolves all the loaded classes by means of instrumentation
 * techniques. The test report server uses it to generate the list of
 * loaded classes per test.
 */
public class AgentClassTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = LogManager.getLogManager().getLogger(AgentClassTransformer.class.getName());

    /**
     * Structure to store the list of referenced classes
     */
    private static Set<String> referencedClasses = new LinkedHashSet<>();

    private static String CLASS_EXTENSION = ".class";

    final Set<String> reactorDeps;

    public AgentClassTransformer(Set<String> reactorDeps) {
        this.reactorDeps = reactorDeps;
    }

    /**
     * Cleans the list of referenced classes. It is needed for clean the list of
     * referenced classes between tests.
     */
    public static void cleanUp() {
        referencedClasses = new LinkedHashSet<>();
    }

    /**
     * Stores a new reference. It is called by the application constructors.
     *
     * @param name full class name to store
     */
    public static void add(String name) {
        referencedClasses.add(name);
    }

    static byte[] instrumentClassWithStaticStmt(String className, String instrumentationInstruction)
            throws CannotCompileException, NotFoundException, IOException {
        ClassPool pool = ClassPool.getDefault();
        CtClass clazz = pool.get(className);

        if (clazz.isFrozen()) {
            return clazz.toBytecode();
        }

        for (CtConstructor ctConstructor : clazz.getConstructors()) {
            ctConstructor.insertAfter(instrumentationInstruction);
        }

        CtMethod[] methods = clazz.getDeclaredMethods();
        if (methods != null) {
            for (CtMethod ctMethod : clazz.getDeclaredMethods()) {
                if (Modifier.isStatic(ctMethod.getModifiers())) {
                    ctMethod.insertAfter(instrumentationInstruction, true);
                }
            }
        }

        CtConstructor constructor = clazz.makeClassInitializer();
        constructor.insertBefore(instrumentationInstruction);

        return clazz.toBytecode();
    }

    /**
     * Resolves if an specific class belongs to a third party library or to the
     * local project
     *
     * @param protectionDomain place where the class belongs to
     * @return if belongs to a third party library
     */
    protected boolean belongsToAJarFile(ProtectionDomain protectionDomain) {
        return Optional.ofNullable(protectionDomain)
                .map(ProtectionDomain::getCodeSource)
                .map(source -> source.getLocation().getPath().endsWith(".jar"))
                .orElse(false);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {

        String jar = Optional.ofNullable(protectionDomain)
                .map(ProtectionDomain::getCodeSource)
                .map(source -> source.getLocation().getPath())
                .orElse("rt.jar");
        if (className != null && (reactorDeps.contains(jar) || jar.endsWith("/"))) {
            String normalizedName = normalizeName(className);
            return instrumentClass(normalizedName, classfileBuffer);
        }

        return classfileBuffer;
    }

    /**
     * Modifies the class constructors to report which is the loaded class.
     *
     * @param name            of the class to instrument
     * @param classfileBuffer the current binary representation to return
     *                        in case of modification exceptions
     * @return modified class bytecode
     */
    protected byte[] instrumentClass(String name, byte[] classfileBuffer) {
        try {
            return instrumentClassWithStaticStmt(name,
                    AgentClassTransformer.class.getName()
                            + ".add(\"" + name + "\");");
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "Error instrumenting " + name, e);
        }
        return classfileBuffer;
    }

    private String normalizeName(String className) {
        String aux = className.replaceAll("/", "\\.");
        if (aux.endsWith(CLASS_EXTENSION)) {
            aux = aux.substring(0, aux.length() - CLASS_EXTENSION.length());
        }
        return aux;
    }

    /**
     * @return the list of referenced classes during a program/test execution, which has been
     * resolved by instrumentation techniques
     */
    public static Set<String> getReferencedClasses() {
        return referencedClasses;
    }
}
