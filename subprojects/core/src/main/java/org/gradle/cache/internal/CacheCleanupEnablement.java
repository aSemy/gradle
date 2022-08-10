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

package org.gradle.cache.internal;

import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.util.internal.GUtil;

import java.io.File;
import java.util.Properties;

public class CacheCleanupEnablement {
    private static final String CACHE_CLEANUP_PROPERTY = "org.gradle.cache.cleanup";

    private final GradleUserHomeDirProvider userHomeDirProvider;

    public CacheCleanupEnablement(GradleUserHomeDirProvider userHomeDirProvider) {
        this.userHomeDirProvider = userHomeDirProvider;
    }

    private boolean isDisabled() {
        File gradleUserHomeDirectory = userHomeDirProvider.getGradleUserHomeDirectory();
        File gradleProperties = new File(gradleUserHomeDirectory, "gradle.properties");
        if (gradleProperties.isFile()) {
            Properties properties = GUtil.loadProperties(gradleProperties);
            String cleanup = properties.getProperty(CACHE_CLEANUP_PROPERTY);
            return cleanup != null && cleanup.equals("false");
        }
        return false;
    }

    public boolean isEnabled() {
        return !isDisabled();
    }
}
