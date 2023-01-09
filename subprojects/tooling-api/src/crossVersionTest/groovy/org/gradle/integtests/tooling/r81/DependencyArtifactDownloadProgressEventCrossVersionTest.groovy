/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.integtests.tooling.r81

import org.gradle.integtests.tooling.fixture.AbstractHttpCrossVersionSpec
import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.test.fixtures.Flaky
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.download.internal.NotFoundFileDownloadSuccessResult

@ToolingApiVersion(">=8.1")
@TargetGradleVersion(">=8.1")
class DependencyArtifactDownloadProgressEventCrossVersionTest extends AbstractHttpCrossVersionSpec {

    @Flaky(because = "https://github.com/gradle/gradle-private/issues/3638")
    def "generates success event for failing first attempt to get dependency"() {
        toolingApi.requireIsolatedUserHome()

        def projectFModuleMissing = mavenHttpRepo.module('group', 'projectF', '2.0')
        projectFModuleMissing.missing()
        def projectF2 = getMavenHttpRepo("/repo2").module('group', 'projectF', '2.0').publish()
        projectF2.pom.expectGet()
        projectF2.artifact.expectGet()

        initSettingsFile()

        buildFile << """
            allprojects {
                apply plugin:'java-library'
            }
            ${repositories(mavenHttpRepo, getMavenHttpRepo("/repo2"))}
            dependencies {
                implementation "group:projectF:2.0"
            }
        """
        addConfigurationClassPathPrintToBuildFile()

        when:
        def events = ProgressEvents.create()
        withConnection { ProjectConnection connection ->
            def build = connection.newBuild()
            collectOutputs(build)
            build.addProgressListener(events, OperationType.FILE_DOWNLOAD)
                .run()
        }

        then:
        events.operations.size() == 3
        events.trees == events.operations
        events.operation("Download ${projectFModuleMissing.pom.uri}").assertIsDownload(projectFModuleMissing.pom)
        events.operation("Download ${projectF2.pom.uri}").assertIsDownload(projectF2.pom)
        events.operation("Download ${projectF2.artifact.uri}").assertIsDownload(projectF2.artifact)
        events.operations.every { it.successful }
        events.operations.pop().result instanceof NotFoundFileDownloadSuccessResult
    }
}

//Download http://127.0.0.1:50028/repo/group/projectF/2.0/projectF-2.0.pom started,
// Download http://127.0.0.1:50028/repo/group/projectF/2.0/projectF-2.0.pom not found,
// Download http://127.0.0.1:50028/repo2/group/projectF/2.0/projectF-2.0.pom started,
// Download http://127.0.0.1:50028/repo2/group/projectF/2.0/projectF-2.0.pom succeeded,
// Download http://127.0.0.1:50028/repo2/group/projectF/2.0/projectF-2.0.jar started,
// Download http://127.0.0.1:50028/repo2/group/projectF/2.0/projectF-2.0.jar succeeded
