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

package org.gradle.internal.buildtree;

import org.gradle.execution.EntryTaskSelector;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildState;

import javax.annotation.Nullable;

public class DefaultBuildTreeWorkPreparer implements BuildTreeWorkPreparer {
    private final BuildState targetBuild;
    private final BuildLifecycleController buildController;

    public DefaultBuildTreeWorkPreparer(BuildState targetBuild, BuildLifecycleController buildLifecycleController) {
        this.targetBuild = targetBuild;
        this.buildController = buildLifecycleController;
    }

    @Override
    public void scheduleRequestedTasks(BuildTreeWorkGraph graph, @Nullable EntryTaskSelector selector) {
        buildController.prepareToScheduleTasks();
        graph.scheduleWork(graphBuilder -> graphBuilder.withWorkGraph(targetBuild, builder -> builder.addRequestedTasks(selector)));
    }
}
