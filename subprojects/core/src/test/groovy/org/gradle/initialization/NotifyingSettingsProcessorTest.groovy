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

package org.gradle.initialization

import org.gradle.StartParameter
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.SettingsInternal
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.internal.operations.TestBuildOperationExecutor
import org.gradle.internal.progress.BuildOperationCategory
import org.gradle.util.Path
import spock.lang.Specification

class NotifyingSettingsProcessorTest extends Specification {

    def buildOperationExecutor = new TestBuildOperationExecutor()
    def settingsProcessor = Mock(SettingsProcessor)
    def gradleInternal = Mock(GradleInternal)
    def settingsLocation = Mock(SettingsLocation)
    def buildOperationScriptPlugin = new NotifyingSettingsProcessor(settingsProcessor, buildOperationExecutor)
    def classLoaderScope = Mock(ClassLoaderScope)
    def startParameter = Mock(StartParameter)
    def settingsInternal = Mock(SettingsInternal)
    def rootProjectDescriptor = Mock(ProjectDescriptor)
    def subProjectDescriptor = Mock(ProjectDescriptor)
    def buildPath = Path.path(":")
    def rootDir = new File("root")
    def rootBuildScriptFile = new File(rootDir, "root.gradle")
    def subDir = new File("root")
    def subBuildScriptFile = new File(subDir, "sub.gradle")

    def "delegates to decorated settings processor"() {
        given:
        rootProject()
        settings()

        when:
        buildOperationScriptPlugin.process(gradleInternal, settingsLocation, classLoaderScope, startParameter)

        then:
        1 * settingsProcessor.process(gradleInternal, settingsLocation, classLoaderScope, startParameter) >> settingsInternal
    }

    def "exposes build operation with settings configuration result"() {
        given:
        rootProject()
        settings()

        when:
        buildOperationScriptPlugin.process(gradleInternal, settingsLocation, classLoaderScope, startParameter)

        then:
        1 * settingsProcessor.process(gradleInternal, settingsLocation, classLoaderScope, startParameter) >> settingsInternal

        and:
        buildOperationExecutor.operations.size() == 1
        buildOperationExecutor.operations.get(0).displayName == "Configure settings"
        buildOperationExecutor.operations.get(0).name == "Configure settings"

        buildOperationExecutor.operations.get(0).operationType == BuildOperationCategory.UNCATEGORIZED
        buildOperationExecutor.operations.get(0).details.settingsDir == rootDir.absolutePath
        buildOperationExecutor.operations.get(0).details.settingsFile == "settings.gradle"
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).buildPath == ":"
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject.name == "root"
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject.path == ":"
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject.identityPath == ":"
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject.projectDir == rootDir.absolutePath
        buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject.buildFile == rootBuildScriptFile.absolutePath
    }

    def "exposes nested project structure"() {
        given:
        settings()
        rootProject(nestedProject())

        when:
        1 * settingsProcessor.process(gradleInternal, settingsLocation, classLoaderScope, startParameter) >> settingsInternal
        buildOperationScriptPlugin.process(gradleInternal, settingsLocation, classLoaderScope, startParameter)
        def rootProject = buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject

        then:
        rootProject.children[0].name== "sub"
        rootProject.children[0].path == ":sub"
        rootProject.children[0].identityPath == ":sub"
        rootProject.children[0].projectDir == subDir.absolutePath
        rootProject.children[0].buildFile == subBuildScriptFile.absolutePath
    }

    def "calculates identity path for nested projects"() {
        given:
        settings(Path.path(":composite"))
        rootProject(nestedProject())

        when:
        1 * settingsProcessor.process(gradleInternal, settingsLocation, classLoaderScope, startParameter) >> settingsInternal
        buildOperationScriptPlugin.process(gradleInternal, settingsLocation, classLoaderScope, startParameter)
        def rootProject = buildOperationExecutor.log.mostRecentResult(ConfigureSettingsBuildOperationType).rootProject

        then:
        rootProject.children[0].identityPath == ":composite:sub"
    }

    private void settings(Path buildPath = buildPath) {
        _ * settingsInternal.gradle >> gradleInternal
        _ * gradleInternal.getIdentityPath() >> buildPath
        _ * buildPath.absolutePath(":") >> ":"
        _ * settingsLocation.settingsDir >> rootDir
        def scriptSource = Mock(ScriptSource)
        _ * scriptSource.getFileName() >> "settings.gradle"
        _ * settingsLocation.settingsScriptSource >> scriptSource
    }

    private void rootProject(Set<ConfigureSettingsBuildOperationType.Result.ProjectDescription> children = []) {
        1 * settingsInternal.rootProject >> rootProjectDescriptor

        _ * rootProjectDescriptor.projectDir >> rootDir
        _ * rootProjectDescriptor.buildFile >> rootBuildScriptFile
        _ * rootProjectDescriptor.path >> ":"
        _ * rootProjectDescriptor.name >> "root"
        _ * rootProjectDescriptor.getChildren() >> children
    }

    private Set<ConfigureSettingsBuildOperationType.Result.ProjectDescription> nestedProject() {
        _ * subProjectDescriptor.projectDir >> subDir
        _ * subProjectDescriptor.buildFile >> subBuildScriptFile
        _ * subProjectDescriptor.path >> ":sub"
        _ * subProjectDescriptor.name >> "sub"
        _ * subProjectDescriptor.getChildren() >> ([] as Set)
        [subProjectDescriptor]
    }
}