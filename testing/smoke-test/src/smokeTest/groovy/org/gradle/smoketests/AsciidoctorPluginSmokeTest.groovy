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

package org.gradle.smoketests


import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache

class AsciidoctorPluginSmokeTest extends AbstractPluginValidatingSmokeTest {
    @ToBeFixedForConfigurationCache(because = "Task.getProject() during execution")
    def 'asciidoctor plugin #version'() {
        given:
        buildFile << """
            plugins {
                id 'org.asciidoctor.jvm.convert' version '${TestedVersions.asciidoctor}'
            }

            ${mavenCentralRepository()}
        """

        file('src/docs/asciidoc/test.adoc') << """
            = Line Break Doc Title
            :hardbreaks:

            Rubies are red,
            Topazes are blue.
            """.stripIndent()

        when:
        runner('asciidoc').build()

        then:
        file('build/docs/asciidoc').isDirectory()
    }

    @Override
    Map<String, Versions> getPluginsToValidate() {
        def versions = Versions.of(TestedVersions.asciidoctor)
        [
            "org.asciidoctor.editorconfig": versions,
            // "org.asciidoctor.js.convert" : versions, //  Broken after 9.0. See https://github.com/asciidoctor/asciidoctor-gradle-plugin/pull/749
            "org.asciidoctor.jvm.convert": versions,
            "org.asciidoctor.jvm.epub": versions,
            // Plugin broken after JCenter dependency disappeared
            // "org.asciidoctor.jvm.gems" : versions, // Plugin broken after JCenter dependency disappeared
            "org.asciidoctor.jvm.pdf": versions,
        ]
    }

    @Override
    void configureValidation(String pluginId, String version) {
        validatePlugins {
            alwaysPasses()
        }
    }
}
