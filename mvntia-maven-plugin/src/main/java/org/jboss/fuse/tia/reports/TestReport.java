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
package org.jboss.fuse.tia.reports;

import java.util.Set;

/**
 * Test method report which represents an executed tests and the loaded classes for that test.
 */
public class TestReport {

    private final String test;
    private final String method;
    private final Set<String> classes;

    public TestReport(String test, String method, Set<String> classes) {
        this.test = test;
        this.method = method;
        this.classes = classes;
    }

    /**
     * Class name that contain tests
     *
     * @return class name that contain tests
     */
    public String getTestClass() {
        return test;
    }

    /**
     * Test method
     *
     * @return test method
     */
    public String getTestMethod() {
        return method;
    }

    /**
     * Referenced classes by the test that has been loaded any point of its execution.
     * Classes that belong to third party libraries are excluded.
     *
     * @return Referenced classes by the test that has been loaded any point of its execution.
     */
    public Set<String> getReferencedClasses() {
        return classes;
    }

    /**
     * Test method identifier (class name + "#" + method name).
     * It is not allowed to have test methods with the same name under the same class.
     *
     * @return test method id.
     */
    public String getTestMethodId() {
        return getTestClass() + "#" + getTestMethod();
    }

    /**
     * Resolves if any of the referenced classes in the test report (and the test itself) are
     * impacted / referenced from a list of modified files.
     *
     * @param modifiedFiles to consider
     * @return if the test needs to be re-executed because it is impacted by a change.
     */
    public boolean isImpactedBy(Set<String> modifiedFiles) {
        return modifiedFiles.stream()
                .anyMatch(file -> !file.endsWith(".class")
                        &&
                        (matchesWithFile(getTestClass(), file) ||
                                getReferencedClasses().stream().anyMatch(clazz -> matchesWithFile(clazz, file))));
    }

    private boolean matchesWithFile(String clazz, String statusFile) {
        String testFilePath = toFilePath(getParentClassName(clazz));
        return fileWithoutExtension(statusFile).endsWith(testFilePath);
    }

    private String toFilePath(String name) {
        return name.replaceAll("\\.", "/");
    }

    private String fileWithoutExtension(String statusFile) {
        int index = statusFile.lastIndexOf(".");
        if (index > -1) {
            return statusFile.substring(0, index);
        }
        return statusFile;
    }

    private String getParentClassName(String className) {
        int innerClassIndex = className.indexOf("$");
        if (innerClassIndex > -1) {
            return className.substring(0, innerClassIndex);
        }
        return className;
    }


    @Override
    public boolean equals(Object o) {
        if (o instanceof TestReport) {
            TestReport aux = (TestReport) o;
            return aux.test.equals(test)
                    && aux.method.equals(method)
                    && aux.classes.equals(classes);
        }
        return false;
    }

}
