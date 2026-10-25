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

package org.gradle.plugin.use.resolve.internal;

import com.google.common.util.concurrent.ExecutionError;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.plugins.ClassloaderBackedPluginDescriptorLocator;
import org.gradle.api.internal.plugins.DefaultPluginRegistry;
import org.gradle.api.internal.plugins.PluginDescriptorLocator;
import org.gradle.api.internal.plugins.PluginImplementation;
import org.gradle.api.internal.plugins.PluginInspector;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.internal.classloader.VisitableURLClassLoader;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.gradle.plugin.management.internal.autoapply.AutoAppliedDevelocityPlugin;
import org.gradle.plugin.use.PluginId;
import org.gradle.plugin.use.tracker.internal.PluginVersionTracker;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a plugin either by determining that it is already on the classpath
 * or by delegating to another resolver.
 */
public class AlreadyOnClasspathPluginResolver implements PluginResolver {
    private static final String REMOVE_VERSION_ADVICE =
        "Remove the version from this request. For a version catalog alias, request the plugin by id instead.";

    private final PluginResolver delegate;
    private final PluginRegistry corePluginRegistry;
    private final PluginDescriptorLocator pluginDescriptorLocator;
    private final String requestingBuildPath;
    private final ClassLoaderScope parentLoaderScope;
    private final PluginInspector pluginInspector;
    private final PluginVersionTracker pluginVersionTracker;

    public AlreadyOnClasspathPluginResolver(
        PluginResolver delegate,
        PluginRegistry corePluginRegistry,
        ClassLoaderScope requestingScope,
        ClassLoaderScope parentLoaderScope,
        PluginDescriptorLocator pluginDescriptorLocator,
        PluginInspector pluginInspector,
        PluginVersionTracker pluginVersionTracker
    ) {
        this.delegate = delegate;
        this.corePluginRegistry = corePluginRegistry;
        this.pluginDescriptorLocator = pluginDescriptorLocator;
        this.requestingBuildPath = findBuildPath(requestingScope);
        this.parentLoaderScope = parentLoaderScope;
        this.pluginInspector = pluginInspector;
        this.pluginVersionTracker = pluginVersionTracker;
    }

    @Override
    public PluginResolutionResult resolve(PluginRequestInternal pluginRequest) {
        PluginId pluginId = pluginRequest.getId();
        if (isCorePlugin(pluginId) || !isPresentOnClasspath(pluginId)) {
            return delegate.resolve(pluginRequest);
        }

        String version = getVersion(pluginRequest);
        if (version == null) {
            return resolveAlreadyOnClasspath(pluginId, null);
        }

        if (pluginRequest.getId().equals(AutoAppliedDevelocityPlugin.BUILD_SCAN_PLUGIN_ID) &&
            isPresentOnClasspath(AutoAppliedDevelocityPlugin.ID)
        ) {
            // The JAR that contains the enterprise plugin also contains the Develocity plugin.
            // If the user is in the process of migrating to Gradle 6 and has not yet moved away from the scan plugin,
            // they might hit this scenario when running with --scan as that will have auto applied the new plugin.
            // Instead of a generic failure, we provide more specific feedback to help people upgrade.
            // We use the same message the user would have seen if they didn't use --scan and trigger the auto apply.
            throw new InvalidPluginRequestException(pluginRequest,
                "The Develocity plugin is not compatible with this version of Gradle.\n"
                    + "Please see https://gradle.com/help/gradle-6-build-scan-plugin for more information."
            );
        }

        String existingVersion = pluginVersionTracker.findPluginVersionAt(parentLoaderScope, pluginId.getId());
        if (existingVersion == null) {
            InvalidPluginRequestException exception = new InvalidPluginRequestException(
                pluginRequest,
                "Plugin '" + pluginId + "' version '" + version + "' was requested, " +
                    "but the plugin is already on the classpath of " + describeSource(pluginId) + ", " +
                    "and Gradle cannot tell which version that is."
            );
            exception.addResolution(REMOVE_VERSION_ADVICE);
            throw exception;
        } else if (!existingVersion.equals(version)) {
            InvalidPluginRequestException exception = new InvalidPluginRequestException(
                pluginRequest,
                "Plugin '" + pluginId + "' version '" + version + "' was requested, " +
                    "but version '" + existingVersion + "' of the plugin is already on the classpath of " + describeSource(pluginId) + "."
            );
            exception.addResolution(REMOVE_VERSION_ADVICE);
            throw exception;
        }

        return resolveAlreadyOnClasspath(pluginId, existingVersion);
    }

    @Nullable
    private static String getVersion(PluginRequestInternal pluginRequest) {
        if (pluginRequest.getOriginalRequest() != null) {
            return pluginRequest.getOriginalRequest().getVersion();
        }
        return pluginRequest.getVersion();
    }

    private PluginResolutionResult resolveAlreadyOnClasspath(PluginId pluginId, @Nullable String pluginVersion) {
        DefaultPluginRegistry pluginRegistry = new DefaultPluginRegistry(pluginInspector, parentLoaderScope);
        PluginImplementation<?> plugin = pluginRegistry.lookup(pluginId);
        if (plugin != null) {
            PluginResolution pluginResolution = new ClassPathPluginResolution(pluginId, pluginVersion, plugin);
            return PluginResolutionResult.found(pluginResolution);
        } else {
            return PluginResolutionResult.notFound("Classpath", "Plugin with id '" + pluginId + "' not found.");
        }
    }

    /**
     * Describes where the plugin on the classpath came from.
     */
    private String describeSource(PluginId pluginId) {
        Class<?> pluginClass = findPluginClass(pluginId);
        if (pluginClass != null) {
            ClassLoaderScope definingScope = findDefiningScope(pluginClass);
            if (definingScope != null) {
                return describeScope(definingScope);
            }
            if (isLoadedFromInjectedClasspath(pluginClass)) {
                return "TestKit (GradleRunner.withPluginClasspath())";
            }
        }
        // The plugin came from a class loader exported into a scope by some other plugin resolver,
        // so describe the topmost scope that sees the plugin, which is the one it was exported into.
        return describeScope(findTopmostScopeWithDescriptor(pluginId));
    }

    /**
     * Loads the plugin class, or returns null if it cannot be loaded.
     * <p>
     * This is only used to describe the plugin in an error message, so a broken plugin must not replace that error.
     * A class that cannot be linked surfaces as a {@link LinkageError}, wrapped in an {@link ExecutionError} by the plugin registry's cache.
     */
    @Nullable
    private Class<?> findPluginClass(PluginId pluginId) {
        try {
            PluginImplementation<?> plugin = new DefaultPluginRegistry(pluginInspector, parentLoaderScope).lookup(pluginId);
            return plugin == null ? null : plugin.asClass();
        } catch (RuntimeException | LinkageError | ExecutionError e) {
            return null;
        }
    }

    /**
     * Finds the scope that loaded the plugin class from its own classpath, if any.
     */
    @Nullable
    private ClassLoaderScope findDefiningScope(Class<?> pluginClass) {
        ClassLoaderScope scope = parentLoaderScope;
        while (true) {
            if (scope.defines(pluginClass)) {
                return scope;
            }
            ClassLoaderScope parent = scope.getParent();
            if (parent == scope) {
                // See RootClassLoaderScope#getParent()
                return null;
            }
            scope = parent;
        }
    }

    /**
     * Whether the plugin class was loaded from the {@code injected-plugin} scope created by {@code DefaultInjectedClasspathPluginResolver}.
     * That scope is not a parent of any script scope, so it is recognized by the name of its class loader, which contains the scope path.
     */
    private static boolean isLoadedFromInjectedClasspath(Class<?> pluginClass) {
        ClassLoader classLoader = pluginClass.getClassLoader();
        return classLoader instanceof VisitableURLClassLoader
            && ((VisitableURLClassLoader) classLoader).getName().contains(":injected-plugin(local)");
    }

    private ClassLoaderScope findTopmostScopeWithDescriptor(PluginId pluginId) {
        ClassLoaderScope scope = parentLoaderScope;
        ClassLoaderScope parent = scope.getParent();
        while (parent != scope && new ClassloaderBackedPluginDescriptorLocator(parent.getExportClassLoader()).findPluginDescriptor(pluginId.getId()) != null) {
            scope = parent;
            parent = scope.getParent();
        }
        return scope;
    }

    /**
     * Turns a scope, as created by e.g. {@code SettingsFactory} or {@code InstantiatingBuildLoader}, into a description for users.
     */
    private String describeScope(ClassLoaderScope scope) {
        String scopeName = scope.getId().getName();
        if (scopeName.startsWith("project[")) {
            // The project's build tree path, which includes the build path
            return "project '" + bracketedPath(scopeName) + "'";
        }
        String buildPath = buildPathOf(scopeName);
        if (buildPath == null) {
            return "'" + scopeName + "'";
        }
        // Nested builds such as buildSrc see the parent build's settings classpath, so name the build when it is not the requesting one
        String inBuild = buildPath.equals(requestingBuildPath) ? "" : " of build '" + buildPath + "'";
        if (scopeName.startsWith("buildSrc[")) {
            return "buildSrc" + inBuild;
        }
        if (scopeName.startsWith("settings[")) {
            return "the settings script" + inBuild;
        }
        return "the root project" + inBuild;
    }

    /**
     * The path of the build that a scope belongs to, found from the nearest scope that names its build.
     */
    private static String findBuildPath(ClassLoaderScope scope) {
        while (true) {
            String buildPath = buildPathOf(scope.getId().getName());
            if (buildPath != null) {
                return buildPath;
            }
            ClassLoaderScope parent = scope.getParent();
            if (parent == scope) {
                return ":";
            }
            scope = parent;
        }
    }

    /**
     * Extracts the build path from a scope name like {@code settings[:included]}, or null if the scope does not name a build.
     */
    @Nullable
    private static String buildPathOf(String scopeName) {
        if (scopeName.startsWith("buildSrc[") || scopeName.startsWith("settings[") || scopeName.startsWith("root-project[")) {
            return bracketedPath(scopeName);
        }
        return null;
    }

    private static String bracketedPath(String scopeName) {
        return scopeName.substring(scopeName.indexOf('[') + 1, scopeName.length() - 1);
    }

    private boolean isPresentOnClasspath(PluginId pluginId) {
        return pluginDescriptorLocator.findPluginDescriptor(pluginId.toString()) != null;
    }

    private boolean isCorePlugin(PluginId pluginId) {
        return corePluginRegistry.lookup(pluginId) != null;
    }
}
