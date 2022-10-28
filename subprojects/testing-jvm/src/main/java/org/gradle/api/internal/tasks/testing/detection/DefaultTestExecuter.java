/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.detection;

import com.google.common.collect.ImmutableList;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.tasks.testing.DistributionModule;
import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.internal.tasks.testing.processors.MaxNParallelTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.PatternMatchTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RestartEveryNTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RunPreviousFailedFirstTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.TestMainAction;
import org.gradle.api.internal.tasks.testing.worker.ForkingTestClassProcessor;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.Factory;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.time.Clock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.worker.WorkerProcessFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The default test class scanner factory.
 */
public class DefaultTestExecuter implements TestExecuter<JvmTestExecutionSpec> {

    private static final Logger LOGGER = Logging.getLogger(DefaultTestExecuter.class);

    private final WorkerProcessFactory workerFactory;
    private final ActorFactory actorFactory;
    private final ModuleRegistry moduleRegistry;
    private final WorkerLeaseService workerLeaseService;
    private final int maxWorkerCount;
    private final Clock clock;
    private final DocumentationRegistry documentationRegistry;
    private final DefaultTestFilter testFilter;
    private TestClassProcessor processor;

    public DefaultTestExecuter(
        WorkerProcessFactory workerFactory, ActorFactory actorFactory, ModuleRegistry moduleRegistry,
        WorkerLeaseService workerLeaseService, int maxWorkerCount,
        Clock clock, DocumentationRegistry documentationRegistry, DefaultTestFilter testFilter
    ) {
        this.workerFactory = workerFactory;
        this.actorFactory = actorFactory;
        this.moduleRegistry = moduleRegistry;
        this.workerLeaseService = workerLeaseService;
        this.maxWorkerCount = maxWorkerCount;
        this.clock = clock;
        this.documentationRegistry = documentationRegistry;
        this.testFilter = testFilter;
    }

    @Override
    public void execute(final JvmTestExecutionSpec testExecutionSpec, TestResultProcessor testResultProcessor) {
        final TestFramework testFramework = testExecutionSpec.getTestFramework();
        final WorkerTestClassProcessorFactory testInstanceFactory = testFramework.getProcessorFactory();

        // TODO: Loading jars from the Gradle distribution can lead confusion in regards
        // to which test framework dependencies actually end up on the classpath, and can
        // even lead to multiple different versions on the classpath at once. We do our
        // best to detect these duplicates if the test itself provides the dependencies,
        // but this process is not perfect.
        // Once test suites are de-incubated, we should deprecate this distribution-loading
        // behavior entirely and rely on the tests to always provide their implementation
        // dependencies.

        final List<File> classpath;
        final List<File> modulePath;
        if (testFramework.getUseDistributionDependencies()) {
            if (testExecutionSpec.getTestIsModule()) {
                classpath = pathWithAdditionalJars(testExecutionSpec.getClasspath(), testFramework.getTestWorkerApplicationClasses());
                modulePath = pathWithAdditionalJars(testExecutionSpec.getModulePath(), testFramework.getTestWorkerApplicationModules());
            } else {
                // For non-module tests, add all additional distribution jars to the classpath.
                List<? extends DistributionModule> additionalClasspath = ImmutableList.<DistributionModule>builder()
                    .addAll(testFramework.getTestWorkerApplicationClasses())
                    .addAll(testFramework.getTestWorkerApplicationModules())
                    .build();

                classpath = pathWithAdditionalJars(testExecutionSpec.getClasspath(), additionalClasspath);
                modulePath = ImmutableList.copyOf(testExecutionSpec.getModulePath());
            }
        } else {
            classpath = ImmutableList.copyOf(testExecutionSpec.getClasspath());
            modulePath = ImmutableList.copyOf(testExecutionSpec.getModulePath());
        }

        final Factory<TestClassProcessor> forkingProcessorFactory = new Factory<TestClassProcessor>() {
            @Override
            public TestClassProcessor create() {
                return new ForkingTestClassProcessor(workerLeaseService, workerFactory, testInstanceFactory, testExecutionSpec.getJavaForkOptions(),
                    classpath, modulePath, testFramework.getWorkerConfigurationAction(), moduleRegistry, documentationRegistry);
            }
        };
        final Factory<TestClassProcessor> reforkingProcessorFactory = new Factory<TestClassProcessor>() {
            @Override
            public TestClassProcessor create() {
                return new RestartEveryNTestClassProcessor(forkingProcessorFactory, testExecutionSpec.getForkEvery());
            }
        };
        processor =
            new PatternMatchTestClassProcessor(testFilter,
                new RunPreviousFailedFirstTestClassProcessor(testExecutionSpec.getPreviousFailedTestClasses(),
                    new MaxNParallelTestClassProcessor(getMaxParallelForks(testExecutionSpec), reforkingProcessorFactory, actorFactory)));

        final FileTree testClassFiles = testExecutionSpec.getCandidateClassFiles();

        Runnable detector;
        if (testExecutionSpec.isScanForTestClasses() && testFramework.getDetector() != null) {
            TestFrameworkDetector testFrameworkDetector = testFramework.getDetector();
            testFrameworkDetector.setTestClasses(new ArrayList<File>(testExecutionSpec.getTestClassesDirs().getFiles()));
            testFrameworkDetector.setTestClasspath(classpath);
            detector = new DefaultTestClassScanner(testClassFiles, testFrameworkDetector, processor);
        } else {
            detector = new DefaultTestClassScanner(testClassFiles, null, processor);
        }

        new TestMainAction(detector, processor, testResultProcessor, workerLeaseService, clock, testExecutionSpec.getPath(), "Gradle Test Run " + testExecutionSpec.getIdentityPath()).run();
    }

    @Override
    public void stopNow() {
        if (processor != null) {
            processor.stopNow();
        }
    }

    private int getMaxParallelForks(JvmTestExecutionSpec testExecutionSpec) {
        int maxParallelForks = testExecutionSpec.getMaxParallelForks();
        if (maxParallelForks > maxWorkerCount) {
            LOGGER.info("{}.maxParallelForks ({}) is larger than max-workers ({}), forcing it to {}", testExecutionSpec.getPath(), maxParallelForks, maxWorkerCount, maxWorkerCount);
            maxParallelForks = maxWorkerCount;
        }
        return maxParallelForks;
    }

    /**
     * Create a classpath or modulePath, as a list of files, given both the files provided by the test spec and a list of
     * modules to potentially load from the Gradle distribution. For each additional module to load, we check if the
     * test files contains a jar which satisfies the module-to-load. If the test files do not contain a satisfactory
     * jar, the given additional module is loaded from the Gradle distribution.
     *
     * TODO: This process is performed on a best-effort basis by matching against test file jar names. In the future
     * may want to consider updating this code to take dependency-management into account, which can provide
     * a more accurate view of {@code testFiles} that does not depend on string regex matching.
     *
     * @param testFiles A set of jars, as given from a {@link JvmTestExecutionSpec}'s classpath or modulePath.
     * @param additionalModules The names of any additional modules to potentially load from the Gradle distribution.
     *
     * @return A set of files representing the constructed classpath or modulePath.
     */
    private List<File> pathWithAdditionalJars(Iterable<? extends File> testFiles, List<? extends DistributionModule> additionalModules) {
        List<File> outputFiles = new ArrayList<File>();

        List<? extends DistributionModule> mutableAdditionalModules = new ArrayList<DistributionModule>(additionalModules);
        for (File f : testFiles) {
            // Loop through each additional jar from the distribution. If the test classpath provides
            // this jar, don't load it from the distribution.
            Iterator<? extends DistributionModule> it = mutableAdditionalModules.iterator();
            while (it.hasNext()) {
                DistributionModule module = it.next();
                if (module.getFileNameMatcher().matcher(f.getName()).matches()) {
                    // The test classpath provides this jar.
                    it.remove();
                }
            }
            outputFiles.add(f);
        }

        List<? extends DistributionModule> toLoad = mutableAdditionalModules;
        int numMutableModules = mutableAdditionalModules.size();
        if (numMutableModules > 0 && numMutableModules < additionalModules.size()) {
            // The test classpath only provides some of the additional modules. So,
            // load all of our modules so that we don't get version mismatches between our
            // own dependencies. (Eg junit-platform-launcher-1.9.0 and junit-platform-commons-1.6.0)
            toLoad = additionalModules;
        }

        // For any modules not provided by the test, load them from the distribution.
        for (DistributionModule module : toLoad) {
            outputFiles.addAll(moduleRegistry.getExternalModule(module.getModuleName()).getImplementationClasspath().getAsFiles());

            // TODO: The user is relying on dependencies from the Gradle distribution. Emit a deprecation warning.
            // We may want to wait for test-suites to be de-incubated here. If users are using the `test.useJUnitPlatform`
            // syntax, they will need to list their framework dependency manually, but if they are using the
            // `testing.suites.test.useJUnitFramework` syntax, they do not need to explicitly list their dependencies.
            // We don't want to push users to add their dependencies explicitly if test suites will remove that
            // requirement in the future.
        }

        return outputFiles;
    }
}
