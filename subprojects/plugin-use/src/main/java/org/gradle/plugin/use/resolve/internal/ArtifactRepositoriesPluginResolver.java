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
package org.gradle.plugin.use.resolve.internal;

import com.google.common.base.Joiner;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.internal.artifacts.DependencyResolutionServices;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.gradle.plugin.use.PluginId;

import javax.annotation.Nonnull;

public class ArtifactRepositoriesPluginResolver implements PluginResolver {

    public static final String PLUGIN_MARKER_SUFFIX = ".gradle.plugin";

    public static ArtifactRepositoriesPluginResolver createWithDefaults(DependencyResolutionServices dependencyResolutionServices, VersionSelectorScheme versionSelectorScheme) {
        if (dependencyResolutionServices.getResolveRepositoryHandler().isEmpty()) {
            dependencyResolutionServices.getResolveRepositoryHandler().gradlePluginPortal();
        }
        return new ArtifactRepositoriesPluginResolver(dependencyResolutionServices, versionSelectorScheme);
    }

    private final DependencyResolutionServices resolution;
    private final VersionSelectorScheme versionSelectorScheme;

    public ArtifactRepositoriesPluginResolver(DependencyResolutionServices dependencyResolutionServices, VersionSelectorScheme versionSelectorScheme) {
        this.resolution = dependencyResolutionServices;
        this.versionSelectorScheme = versionSelectorScheme;
    }

    @Override
    public void resolve(PluginRequestInternal pluginRequest, PluginResolutionResult result) throws InvalidPluginRequestException {
        Dependency markerDependency = getMarkerDependency(pluginRequest);
        String markerVersion = markerDependency.getVersion();
        if (markerVersion == null) {
            handleNotFound(result, "plugin dependency must include a version number for this source");
            return;
        }

        if (markerVersion.endsWith("-SNAPSHOT")) {
            handleNotFound(result, "snapshot plugin versions are not supported");
            return;
        }

        if (versionSelectorScheme.parseSelector(markerVersion).isDynamic()) {
            handleNotFound(result, "dynamic plugin versions are not supported");
            return;
        }

        if (exists(markerDependency)) {
            handleFound(pluginRequest, markerDependency, result);
        } else {
            handleNotFound(result, "Could not resolve plugin artifact '" + getNotation(markerDependency) + "'");
        }
    }

    private void handleFound(final PluginRequestInternal pluginRequest, final Dependency markerDependency, PluginResolutionResult result) {
        result.found("Plugin Artifact Repositories", new PluginResolution() {
            @Override
            public PluginId getPluginId() {
                return pluginRequest.getId();
            }

            public void execute(@Nonnull PluginResolveContext context) {
                context.addLegacy(pluginRequest.getId(), markerDependency);
            }
        });
    }

    private void handleNotFound(PluginResolutionResult result, String message) {
        for (ArtifactRepository repository : resolution.getResolveRepositoryHandler()) {
            result.notFound(repository.getName(), message);
        }
    }

    /*
     * Checks whether the implementation artifact exists in the backing artifacts repositories.
     */
    private boolean exists(Dependency dependency) {
        ConfigurationContainer configurations = resolution.getConfigurationContainer();
        Configuration configuration = configurations.detachedConfiguration(dependency);
        configuration.setTransitive(false);
        return !configuration.getResolvedConfiguration().hasError();
    }

    private Dependency getMarkerDependency(PluginRequestInternal pluginRequest) {
        ModuleVersionSelector selector = pluginRequest.getModule();
        if (selector == null) {
            String id = pluginRequest.getId().getId();
            return new DefaultExternalModuleDependency(id, id + PLUGIN_MARKER_SUFFIX, pluginRequest.getVersion());
        } else {
            return new DefaultExternalModuleDependency(selector.getGroup(), selector.getName(), selector.getVersion());
        }
    }

    private String getNotation(Dependency dependency) {
        return Joiner.on(':').join(dependency.getGroup(), dependency.getName(), dependency.getVersion());
    }
}
