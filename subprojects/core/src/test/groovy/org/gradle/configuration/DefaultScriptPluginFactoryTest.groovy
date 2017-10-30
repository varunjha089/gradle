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
package org.gradle.configuration

import com.google.common.collect.Lists
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.internal.DocumentationRegistry
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.ScriptHandlerFactory
import org.gradle.api.internal.initialization.ScriptHandlerInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectScript
import org.gradle.api.provider.ProviderFactory
import org.gradle.groovy.scripts.BasicScript
import org.gradle.groovy.scripts.DefaultScript
import org.gradle.groovy.scripts.ScriptCompiler
import org.gradle.groovy.scripts.ScriptCompilerFactory
import org.gradle.groovy.scripts.ScriptRunner
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.groovy.scripts.internal.BuildScriptData
import org.gradle.groovy.scripts.internal.FactoryBackedCompileOperation
import org.gradle.internal.Factory
import org.gradle.internal.classloader.ClasspathHasher
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.hash.FileHasher
import org.gradle.internal.hash.HashCode
import org.gradle.internal.hash.StreamHasher
import org.gradle.internal.logging.LoggingManagerInternal
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.resource.TextResourceLoader
import org.gradle.internal.service.ServiceRegistry
import org.gradle.model.internal.inspect.ModelRuleSourceDetector
import org.gradle.plugin.management.internal.DefaultPluginRequests
import org.gradle.plugin.management.internal.autoapply.AutoAppliedPluginHandler
import org.gradle.plugin.use.internal.PluginRequestApplicator
import spock.lang.Specification

class DefaultScriptPluginFactoryTest extends Specification {

    def scriptCompilerFactory = Mock(ScriptCompilerFactory)
    def scriptCompiler = Mock(ScriptCompiler)
    def scriptSource = Mock(ScriptSource)
    def scriptRunner = Mock(ScriptRunner)
    def script = Mock(BasicScript)
    def instantiator = Mock(Instantiator)
    def targetScope = Mock(ClassLoaderScope)
    def baseScope = Mock(ClassLoaderScope)
    def scopeClassLoader = Mock(ClassLoader)
    def baseChildClassLoader = Mock(ClassLoader)
    def scriptHandlerFactory = Mock(ScriptHandlerFactory)
    def pluginRequestApplicator = Mock(PluginRequestApplicator)
    def scriptHandler = Mock(ScriptHandlerInternal)
    def classPathScriptRunner = Mock(ScriptRunner)
    def loggingManagerFactory = Mock(Factory) as Factory<LoggingManagerInternal>
    def loggingManager = Mock(LoggingManagerInternal)
    def fileLookup = TestFiles.fileLookup()
    def directoryFileTreeFactory = Mock(DirectoryFileTreeFactory)
    def documentationRegistry = Mock(DocumentationRegistry)
    def classpathHasher = Mock(ClasspathHasher)
    def providerFactory = Mock(ProviderFactory)
    def textResourceLoader = Mock(TextResourceLoader)
    def streamHasher = Mock(StreamHasher)
    def fileHasher = Mock(FileHasher)
    def autoAppliedPluginHandler = Mock(AutoAppliedPluginHandler)

    def factory = new DefaultScriptPluginFactory(scriptCompilerFactory, loggingManagerFactory, instantiator, scriptHandlerFactory, pluginRequestApplicator, fileLookup,
        directoryFileTreeFactory, documentationRegistry, new ModelRuleSourceDetector(), providerFactory, textResourceLoader,
        streamHasher, fileHasher, autoAppliedPluginHandler)

    def setup() {
        def configurations = Mock(ConfigurationContainer)
        scriptHandler.configurations >> configurations
        scriptHandler.scriptClassPath >> Mock(ClassPath)
        classPathScriptRunner.data >> new DefaultPluginRequests(Lists.newArrayList())
        def configuration = Mock(Configuration)
        configurations.getByName(ScriptHandler.CLASSPATH_CONFIGURATION) >> configuration
        configuration.getFiles() >> Collections.emptySet()
        baseScope.getExportClassLoader() >> baseChildClassLoader
        classpathHasher.hash(_) >> HashCode.fromInt(123)

        1 * targetScope.getLocalClassLoader() >> scopeClassLoader
        1 * autoAppliedPluginHandler.mergeWithAutoAppliedPlugins(_, _) >> new DefaultPluginRequests(Lists.newArrayList())
    }

    void "configures a target object using script"() {
        given:
        final Object target = new Object()

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, false)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(DefaultScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(DefaultScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(true)
        _ * scriptRunner.runDoesSomething >> true
        1 * scriptRunner.run(target, _ as ServiceRegistry)
        0 * scriptRunner._
    }

    void "configures a project object using script with imperative and inheritable code"() {
        given:
        def target = Mock(ProjectInternal)

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, true)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(ProjectScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(ProjectScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(true)
        _ * scriptRunner.runDoesSomething >> true
        _ * scriptRunner.hasMethods >> true
        1 * scriptRunner.script >> script
        1 * target.setScript(script)
        0 * target.addDeferredConfiguration(_)
        1 * scriptRunner.run(target, _ as ServiceRegistry)
        0 * scriptRunner._
    }

    void "configures a project object using script with imperative code"() {
        given:
        def target = Mock(ProjectInternal)

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, true)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(ProjectScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(ProjectScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(true)
        _ * scriptRunner.runDoesSomething >> true
        _ * scriptRunner.hasMethods >> false
        0 * target.setScript(_)
        0 * target.addDeferredConfiguration(_)
        1 * scriptRunner.run(target, _ as ServiceRegistry)
        0 * scriptRunner._
    }

    void "configures a project object using script with inheritable and deferred code"() {
        given:
        def target = Mock(ProjectInternal)

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, true)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(ProjectScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(ProjectScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(false)
        _ * scriptRunner.runDoesSomething >> true
        _ * scriptRunner.hasMethods >> true
        1 * scriptRunner.script >> script
        1 * target.setScript(script)
        1 * target.addDeferredConfiguration(_)
        0 * scriptRunner._
    }

    void "configures a project object using script with deferred code"() {
        given:
        def target = Mock(ProjectInternal)

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, true)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(ProjectScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(ProjectScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(false)
        _ * scriptRunner.runDoesSomething >> true
        _ * scriptRunner.hasMethods >> false
        0 * target.setScript(_)
        1 * target.addDeferredConfiguration(_)
        0 * scriptRunner._
    }

    void "configures a project object using empty script"() {
        given:
        def target = Mock(ProjectInternal)

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, true)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(ProjectScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(ProjectScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(false)
        _ * scriptRunner.runDoesSomething >> false
        _ * scriptRunner.hasMethods >> false
        0 * scriptRunner._
    }

    void "configured target uses given script plugin factory for nested scripts"() {
        given:
        def otherScriptPluginFactory = Mock(ScriptPluginFactory)
        factory.setScriptPluginFactory(otherScriptPluginFactory)
        final Object target = new Object()

        when:
        def configurer = factory.create(scriptSource, scriptHandler, targetScope, baseScope, false)
        configurer.apply(target)

        then:
        1 * loggingManagerFactory.create() >> loggingManager
        1 * scriptCompilerFactory.createCompiler(scriptSource) >> scriptCompiler
        1 * scriptCompiler.compile(DefaultScript, _ as FactoryBackedCompileOperation, baseChildClassLoader, _) >> classPathScriptRunner
        1 * classPathScriptRunner.run(target, _ as ServiceRegistry)
        1 * scriptCompiler.compile(DefaultScript, { it.transformer != null }, scopeClassLoader, !null) >> scriptRunner
        _ * scriptRunner.data >> new BuildScriptData(true)
        _ * scriptRunner.runDoesSomething >> true
        1 * scriptRunner.run(target, { scriptServices -> scriptServices.get(ScriptPluginFactory) == otherScriptPluginFactory })
        0 * scriptRunner._
    }
}
