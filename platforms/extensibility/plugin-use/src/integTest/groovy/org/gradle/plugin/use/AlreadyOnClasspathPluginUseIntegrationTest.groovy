/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.plugin.use

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.TestExecutionPreconditions
import org.gradle.util.internal.TextUtil

class AlreadyOnClasspathPluginUseIntegrationTest extends AbstractIntegrationSpec {

    private static final String REMOVE_VERSION_RESOLUTION =
        "Remove the version from this request. For a version catalog alias, request the plugin by id instead."

    def operations = new BuildOperationsFixture(executer, temporaryFolder)

    def setup() {
        withSettings("")
    }

    private void withSettings(String settings) {
        settingsFile.text = settings.stripIndent()
        settingsFile << "\nrootProject.name = 'root'\n"
    }

    def "can request buildSrc plugin"() {

        given:
        withBinaryPluginBuild("buildSrc")

        and:
        createDirs("a")
        withSettings("include('a')")

        and:
        buildFile << requestPlugin("my-plugin")
        file("a/build.gradle") << requestPlugin("my-plugin")

        when:
        succeeds "help"

        then:
        outputContains(appliedPluginOutput())

        and:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "can request non-core plugin already applied to parent project"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
            }

            include('a')

        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0")
        file("a/build.gradle") << requestPlugin("my-plugin")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "can request non-core plugin already applied to grand-parent project"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a", "a/b")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
            }

            include("a")
            include("a:b")

        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0")
        file("a/b/build.gradle") << requestPlugin("my-plugin")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        !operations.hasOperation("Apply plugin my-plugin to project ':a'")
        operations.hasOperation("Apply plugin my-plugin to project ':a:b'")
    }

    def "can request non-core plugin already requested on parent project but not applied"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
            }

            include("a")

        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0", false)
        file("a/build.gradle") << requestPlugin("my-plugin")

        when:
        succeeds "help"

        then:
        !operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "can request non-core plugin already on the classpath when a plugin resolution strategy sets a version"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
                resolutionStrategy { eachPlugin { useVersion("1.0") } }
            }

            include("a")

        """

        and:
        buildFile << requestPlugin("my-plugin")
        file("a/build.gradle") << requestPlugin("my-plugin")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    @Requires(TestExecutionPreconditions.NotEmbeddedExecutor) // TestKit usage inside of the test requires distribution
    def "can request plugin from TestKit injected classpath"() {

        given:
        withBinaryPluginBuild(".", new TestKitSpec(
            requestPlugin("my-plugin", null, false),
            requestPlugin("my-plugin"),
            true,
            """
                Assert.assertFalse(result.output.contains("${appliedPluginOutput(":")}"))
                Assert.assertTrue(result.output.contains("${appliedPluginOutput(":a")}"))
            """.stripIndent()))

        expect:
        succeeds "test"
    }

    def "can request plugin version of plugin already requested on parent project if it's the same"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
            }

            include("a")

        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0")
        file("a/build.gradle") << requestPlugin("my-plugin", "1.0")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "cannot request plugin version of plugin already requested on parent project if the version differs"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
            }

            include("a")

        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0")
        file("a/build.gradle") << requestPlugin("my-plugin", "1.0.1")

        when:
        fails "help"

        then:
        failureDescriptionStartsWith("Error resolving plugin [id: 'my-plugin', version: '1.0.1']")
        failureHasCause("Plugin 'my-plugin' version '1.0.1' was requested, but version '1.0' of the plugin is already on the classpath of the root project.")
        failure.assertHasResolution(REMOVE_VERSION_RESOLUTION)

        and:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        !operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "can request plugin version of plugin already requested on settings if it's the same"() {
        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """
            pluginManagement {
                ${withLocalPluginRepository()}
                ${requestPlugin("my-plugin", "1.0")}
            }

            include("a")
        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0")
        file("a/build.gradle") << requestPlugin("my-plugin", "1.0")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    // This is legal because settings only defines a _default_ version, and does not actually contribute to the classpath.
    def "can request plugin version of plugin already requested on settings if the version differs"() {
        given:
        withBinaryPluginPublishedLocally()
        withBinaryPluginPublishedLocally("my-other-local-plugins", "1.0.1")

        and:
        createDirs("a")
        withSettings """
            pluginManagement {
                ${withLocalPluginRepository()}
                ${requestPlugin("my-plugin", "1.0")}
            }

            include("a")
        """

        and:
        buildFile << requestPlugin("my-plugin", "1.0.1")

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
    }

    def "names the build when plugin on settings buildscript classpath is requested from buildSrc"() {

        given:
        withBinaryPluginPublishedLocally()

        and:
        withSettings """
            buildscript {
                ${withLocalPluginRepository()}
                dependencies {
                    classpath("com.acme:my-local-plugins:1.0")
                }
            }
        """
        file("buildSrc/build.gradle") << requestPlugin("my-plugin", "1.0")

        when:
        fails "help"

        then:
        failureHasCause("Plugin 'my-plugin' version '1.0' was requested, but the plugin is already on the classpath of the settings script of build ':', and Gradle cannot tell which version that is.")
    }

    // This is legal because the init script classpath is not a parent of the settings or project classpaths.
    def "can request plugin version of plugin on init script classpath if the version differs"() {
        given:
        withBinaryPluginPublishedLocally()
        withBinaryPluginPublishedLocally("my-other-local-plugins", "1.0.1")

        and:
        def initScript = file("init.gradle") << """
            initscript {
                repositories {
                    maven {
                        url = uri("${file(localPluginRepoPath).toURI()}")
                    }
                }
                dependencies {
                    classpath("com.acme:my-local-plugins:1.0")
                }
            }
            println("init script loads my-plugin from " + my.MyPlugin.protectionDomain.codeSource.location)
        """
        args("-I", initScript.absolutePath)

        and:
        withSettings """
            pluginManagement {
                ${withLocalPluginRepository()}
            }
        """
        buildFile << requestPlugin("my-plugin", "1.0.1")
        buildFile << """
            println("build script loads my-plugin from " + my.MyPlugin.protectionDomain.codeSource.location)
        """

        when:
        succeeds "help"

        then:
        outputContains(appliedPluginOutput(":"))
        output.find(/init script loads my-plugin from \S*my-local-plugins-1\.0\.jar/)
        output.find(/build script loads my-plugin from \S*my-other-local-plugins-1\.0\.1\.jar/)
    }

    def "cannot request plugin version of plugin already requested on parent project if the version differs (using settings)"() {
        given:
        withBinaryPluginPublishedLocally()

        and:
        createDirs("a")
        withSettings """

            pluginManagement {
                ${withLocalPluginRepository()}
                ${requestPlugin("my-plugin", "1.0")}
            }

            include("a")

        """

        and:
        buildFile << requestPlugin("my-plugin")
        file("a/build.gradle") << requestPlugin("my-plugin", "1.0.1")

        when:
        fails "help"

        then:
        failureDescriptionStartsWith("Error resolving plugin [id: 'my-plugin', version: '1.0.1']")
        failureHasCause("Plugin 'my-plugin' version '1.0.1' was requested, but version '1.0' of the plugin is already on the classpath of the root project.")
        failure.assertHasResolution(REMOVE_VERSION_RESOLUTION)

        and:
        operations.hasOperation("Apply plugin my-plugin to root project 'root'")
        !operations.hasOperation("Apply plugin my-plugin to project ':a'")
    }

    def "cannot request plugin version of plugin from 'buildSrc' in #requestingScript"() {
        given:
        withBinaryPluginBuild("buildSrc")

        and:
        createDirs("a")
        withSettings("include('a')")

        and:
        file(requestingScript) << requestPlugin("my-plugin", "1.0")

        when:
        fails "help"

        then:
        failureDescriptionStartsWith("Error resolving plugin [id: 'my-plugin', version: '1.0']")
        failureHasCause("Plugin 'my-plugin' version '1.0' was requested, but the plugin is already on the classpath of buildSrc, and Gradle cannot tell which version that is.")
        failure.assertHasResolution(REMOVE_VERSION_RESOLUTION)

        where:
        requestingScript << ["build.gradle", "a/build.gradle"]
    }

    def "cannot request plugin version of plugin from 'buildSrc' whose class cannot be loaded"() {

        given:
        // The superclass is only available at compile time, so loading the plugin class fails with NoClassDefFoundError
        file("buildSrc/src/main/groovy/my/MyPlugin.groovy") << """
            package my

            import org.gradle.api.*

            abstract class MyPlugin extends junit.framework.TestCase implements Plugin<Project> {
            }
        """.stripIndent()
        file("buildSrc/build.gradle") << """
            plugins {
                id("groovy")
                id("java-gradle-plugin")
            }

            gradlePlugin {
                plugins {
                    myPlugin {
                        id = "my-plugin"
                        implementationClass = "my.MyPlugin"
                    }
                }
            }

            dependencies {
                compileOnly("junit:junit:4.13")
            }

            ${mavenCentralRepository()}
        """.stripIndent()

        and:
        buildFile << requestPlugin("my-plugin", "1.0")

        when:
        fails "help"

        then:
        failureDescriptionStartsWith("Error resolving plugin [id: 'my-plugin', version: '1.0']")
        failureHasCause("Plugin 'my-plugin' version '1.0' was requested, but the plugin is already on the classpath of buildSrc, and Gradle cannot tell which version that is.")
    }

    def "cannot request plugin version of plugin from #classpathScript buildscript classpath"() {
        given:
        withBinaryPluginPublishedLocally()

        and:
        def buildscriptBlock = """
            buildscript {
                repositories {
                    maven {
                        url = uri("${file(localPluginRepoPath).toURI()}")
                    }
                }
                dependencies {
                    classpath("com.acme:my-local-plugins:1.0")
                }
            }
        """
        createDirs("a", "a/b")
        if (classpathScript == "settings.gradle") {
            withSettings(buildscriptBlock + "include('a', 'a:b')")
        } else {
            withSettings("include('a', 'a:b')")
            file(classpathScript) << buildscriptBlock
        }

        and:
        file(requestingScript) << requestPlugin("my-plugin", "1.0")

        when:
        fails "help"

        then:
        failureDescriptionStartsWith("Error resolving plugin [id: 'my-plugin', version: '1.0']")
        failureHasCause("Plugin 'my-plugin' version '1.0' was requested, but the plugin is already on the classpath of $source, and Gradle cannot tell which version that is.")
        failure.assertHasResolution(REMOVE_VERSION_RESOLUTION)

        where:
        classpathScript   | requestingScript   | source
        "settings.gradle" | "build.gradle"     | "the settings script"
        "build.gradle"    | "a/build.gradle"   | "the root project"
        "a/build.gradle"  | "a/b/build.gradle" | "project ':a'"
    }

    @Requires(TestExecutionPreconditions.NotEmbeddedExecutor) // TestKit usage inside of the test requires distribution
    def "cannot request plugin version of plugin from TestKit injected classpath"() {

        given:
        withBinaryPluginBuild(".", new TestKitSpec(
            requestPlugin("my-plugin", null, false),
            requestPlugin("my-plugin", "1.0"),
            false,
            """
                Assert.assertTrue(result.output.contains("Error resolving plugin [id: 'my-plugin', version: '1.0']"))
                Assert.assertTrue(result.output.contains("Plugin 'my-plugin' version '1.0' was requested, but the plugin is already on the classpath of TestKit (GradleRunner.withPluginClasspath()), and Gradle cannot tell which version that is."))
            """.stripIndent()))

        expect:
        succeeds "test"
    }

    private static String requestPlugin(String id, String version = null, boolean apply = true) {
        """
            plugins {
                id("$id")${version == null ? "" : " version \"$version\""}${apply ? "" : " apply false"}
            }
        """.stripIndent()
    }

    private static class TestKitSpec {
        final String rootProjectBuildScript
        final String childProjectBuildScript
        final boolean succeeds
        final String testKitAssertions

        private TestKitSpec(String rootProjectBuildScript, String childProjectBuildScript, boolean succeeds, String testKitAssertions) {
            this.rootProjectBuildScript = rootProjectBuildScript
            this.childProjectBuildScript = childProjectBuildScript
            this.succeeds = succeeds
            this.testKitAssertions = testKitAssertions
        }
    }

    private void withBinaryPluginBuild(String projectPath = ".", TestKitSpec testKitSpec = null, String version = "1.0") {
        file("$projectPath/src/main/groovy/my/MyPlugin.groovy") << """

            package my

            import org.gradle.api.*

            class MyPlugin implements Plugin<Project> {
                @Override
                void apply(Project project) {
                    println("Plugin my-plugin applied! (to ${'$'}{project.path})")
                }
            }

        """.stripIndent()
        def testKitDependencies = testKitSpec ? """
            testImplementation(gradleTestKit())
            testImplementation('junit:junit:4.13')
        """ : ""
        file("$projectPath/build.gradle") << """

            plugins {
                id("groovy")
                id("java-gradle-plugin")
            }

            group = "com.acme"
            version = "$version"

            gradlePlugin {
                plugins {
                    myPlugin {
                        id = "my-plugin"
                        implementationClass = "my.MyPlugin"
                    }
                }
            }

            dependencies {
                compileOnly(gradleApi())
                $testKitDependencies
            }

            ${mavenCentralRepository()}

        """.stripIndent()
        if (testKitSpec) {
            file("src/test/groovy/my/MyPluginTest.groovy") << """

                package my

                import org.junit.*
                import org.junit.rules.*

                import org.gradle.testkit.runner.*

                class MyPluginTest {

                    @Rule public TemporaryFolder tmpDir = new TemporaryFolder()

                    @Test
                    public void assertions() {

                        // given:
                        def rootDir = tmpDir.newFolder("root")
                        new File(rootDir, "settings.gradle").text = \"\"\"
                            include("a")
                            rootProject.name = "root"
                        \"\"\".stripIndent()
                        new File(rootDir, "build.gradle").text = \"\"\"
                            ${testKitSpec.rootProjectBuildScript ?: ""}
                        \"\"\".stripIndent()
                        new File(rootDir,"a").mkdirs()
                        new File(rootDir, "a/build.gradle").text = \"\"\"
                            ${testKitSpec.childProjectBuildScript ?: ""}
                        \"\"\".stripIndent()

                        //when:
                        def runner = GradleRunner.create()
                            .withGradleInstallation(new File("${TextUtil.normaliseFileSeparators(distribution.gradleHomeDir.absolutePath)}"))
                            .withTestKitDir(new File("${TextUtil.normaliseFileSeparators(executer.gradleUserHomeDir.absolutePath)}"))
                            .withPluginClasspath()
                            .withProjectDir(rootDir)
                            .withArguments("help")
                        def result = runner.${testKitSpec.succeeds ? "build" : "buildAndFail"}()

                        // then:
                        ${testKitSpec.testKitAssertions}
                    }
                }

            """.stripIndent()
        }
    }

    private static String appliedPluginOutput(String projectPath = null) {
        "Plugin my-plugin applied!${projectPath == null ? "" : " (to $projectPath)"}"
    }

    private static String localPluginRepoPath = "local-plugin-repository"

    private static String withLocalPluginRepository() {
        """
            repositories {
                maven {
                    url = uri("$localPluginRepoPath")
                }
            }
        """.stripIndent()
    }

    private void withBinaryPluginPublishedLocally(String pluginBundleName = "my-local-plugins", String version = "1.0") {
        withBinaryPluginBuild(pluginBundleName, null, version)
        file("$pluginBundleName/settings.gradle").createFile()
        file("$pluginBundleName/build.gradle") << """
            apply plugin: "maven-publish"
            publishing { repositories { maven { url = uri("../$localPluginRepoPath") } } }
        """.stripIndent()

        executer.inDirectory(file(pluginBundleName)).withTasks("publish").run()

        file("$localPluginRepoPath/com/acme/$pluginBundleName/$version/$pluginBundleName-${version}.jar").assertExists()
        file("$localPluginRepoPath/my-plugin/my-plugin.gradle.plugin/$version/my-plugin.gradle.plugin-${version}.pom").assertExists()
    }
}
