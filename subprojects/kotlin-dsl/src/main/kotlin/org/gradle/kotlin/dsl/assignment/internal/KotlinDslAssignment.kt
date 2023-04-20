/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.kotlin.dsl.assignment.internal

import org.gradle.util.internal.IncubationLogger

object KotlinDslAssignment {

    const val ASSIGNMENT_SYSTEM_PROPERTY = "org.gradle.unsafe.kotlin.assignment"

    fun isAssignmentOverloadEnabled() =
        System.getProperty(ASSIGNMENT_SYSTEM_PROPERTY, "true").trim() != "false"

    fun emitIncubatingLogMessage() {
        if (isAssignmentOverloadEnabled()) {
            // If the assignment overload is disabled for a project but some plugin was compiled using it, we should not do any logging.
            IncubationLogger.incubatingFeatureUsed("Kotlin DSL property assignment")
        }
    }
}
