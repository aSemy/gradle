/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.integtests.tooling.r33

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.integtests.tooling.fixture.WithOldConfigurationsSupport
import org.gradle.tooling.BuildException
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType

class BuildProgressCrossVersionSpec extends ToolingApiSpecification implements WithOldConfigurationsSupport {

    def "generates events for dependency resolution"() {
        given:
        buildFile << """
            allprojects {
                apply plugin: 'java'
                ${mavenCentralRepository()}
                dependencies { ${testImplementationConfiguration} 'junit:junit:4.13' }
            }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/ThingTest.java") << """
            public class ThingTest {
                @org.junit.Test
                public void ok() { }
            }
        """

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .forTasks("build")
                        .run()
        }

        then:
        events.assertIsABuild()

        def compileJava = events.operation("Task :compileJava")
        def compileTestJava = events.operation("Task :compileTestJava")
        def test = events.operation("Task :test")

        def compileClasspath = events.operation("Resolve dependencies :compileClasspath", "Resolve dependencies of :compileClasspath")
        compileClasspath.hasAncestor compileJava

        def testCompileClasspath = events.operation("Resolve dependencies :testCompileClasspath", "Resolve dependencies of :testCompileClasspath")
        testCompileClasspath.hasAncestor compileTestJava

        def testRuntimeClasspath = events.operation(
            "Resolve dependencies :testRuntime", "Resolve dependencies :testRuntimeClasspath",
            "Resolve dependencies of :testRuntime", "Resolve dependencies of :testRuntimeClasspath")
        testRuntimeClasspath.hasAncestor test
    }

    def "generates events for failed dependency resolution"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }
            dependencies { ${implementationConfiguration} 'thing:thing:1.0' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .forTasks("build")
                        .run()
        }

        then:
        def e = thrown(BuildException)
        if (targetDist.addsTaskExecutionExceptionAroundAllTaskFailures) {
            e.cause.cause.message =~ /Could not resolve all (dependencies|files) for configuration ':compileClasspath'./
        } else {
            e.cause.message =~ /Could not resolve all (dependencies|files) for configuration ':compileClasspath'./
        }

        events.assertIsABuild()

        events.operation("Resolve dependencies :compileClasspath", "Resolve dependencies of :compileClasspath")
        // TODO: currently not marked as failed
    }

    @ToolingApiVersion(">=8.0 <8.12")
    def "does not include dependency resolution that is a child of a task when task events are not included (Tooling API client < 8.12)"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }

            // This just prevents Gradle from failing because there are test sources but no tests
            test { include 'foo' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/Thing.java") << """class ThingTest { }"""

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .addProgressListener(events, [OperationType.GENERIC] as Set)
                    .forTasks("build")
                    .run()
        }

        then:
        !events.operations.find { it.name.contains("compileClasspath") }
    }

    @ToolingApiVersion(">=8.12")
    def "does not include dependency resolution that is a child of a task when task events are not included (Tooling API client >= 8.12)"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }

            // This just prevents Gradle from failing because there are test sources but no tests
            test { include 'foo' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/Thing.java") << """class ThingTest { }"""

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .addProgressListener(events, [OperationType.GENERIC, OperationType.ROOT] as Set)
                    .forTasks("build")
                    .run()
        }

        then:
        !events.operations.find { it.name.contains("compileClasspath") }
    }
}
