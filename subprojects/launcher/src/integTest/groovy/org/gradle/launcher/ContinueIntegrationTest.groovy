/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.launcher

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.file.TestFile

class ContinueIntegrationTest extends AbstractIntegrationSpec {

    def "--continue flag"() {
        given:
        buildScript()

        when:
        args("--continue")
        fails(":failTask", ":successTask")

        then:
        output.contains("2 actionable tasks: 2 executed")

        when:
        fails(":failTask", ":successTask")

        then:
        output.contains("1 actionable task: 1 executed")
    }

    def "-Dorg.gradle.continue property"() {
        given:
        buildScript()

        when:
        args("-Dorg.gradle.continue=true")
        fails(":failTask", ":successTask")

        then:
        output.contains("2 actionable tasks: 2 executed")

        when:
        args("-Dorg.gradle.continue=false")
        fails(":failTask", ":successTask")

        then:
        output.contains("1 actionable task: 1 executed")
    }

    private TestFile buildScript() {
        buildScript """
            tasks.register("failTask") {
                doFirst {
                    throw RuntimeException("failTask failed")
                }
            }

            tasks.register("successTask") {
                doFirst {
                    println("successTask success")
                }
            }
        """
    }
}
