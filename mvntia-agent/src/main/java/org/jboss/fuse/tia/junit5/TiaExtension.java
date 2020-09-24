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
package org.jboss.fuse.tia.junit5;

import java.util.Set;

import org.jboss.fuse.tia.agent.Agent;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class TiaExtension implements ExecutionCondition {

    private volatile Set<String> disabledTests;

    public TiaExtension() {
        new Thread(this::getDisabledTests).start();
    }

    protected Set<String> getDisabledTests() {
        if (disabledTests == null) {
            synchronized (this) {
                if (disabledTests == null) {
                    disabledTests = Agent.getClient().disabledTests();
                }
            }
        }
        return disabledTests;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Set<String> map = getDisabledTests();
        String testClass = context.getRequiredTestClass().getName();
        if (map.contains(testClass)) {
            return ConditionEvaluationResult.disabled("Disabled by TIA");
        } else {
            return ConditionEvaluationResult.enabled("Enabled by TIA");
        }
    }


}
