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

package org.gradle.nativeplatform.test.xctest.plugins;

import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.swift.SwiftComponent;
import org.gradle.language.swift.plugins.SwiftBasePlugin;
import org.gradle.language.swift.plugins.SwiftExecutablePlugin;
import org.gradle.language.swift.plugins.SwiftLibraryPlugin;
import org.gradle.language.swift.tasks.CreateSwiftBundle;
import org.gradle.language.swift.tasks.SwiftCompile;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.tasks.LinkMachOBundle;
import org.gradle.nativeplatform.test.xctest.SwiftXCTestSuite;
import org.gradle.nativeplatform.test.xctest.SwiftXcodeXCTestSuite;
import org.gradle.nativeplatform.test.xctest.internal.DefaultSwiftXcodeXCTestSuite;
import org.gradle.nativeplatform.test.xctest.internal.MacOSSdkPlatformPathLocator;
import org.gradle.nativeplatform.test.xctest.tasks.XcTest;
import org.gradle.testing.base.plugins.TestingBasePlugin;
import org.gradle.util.GUtil;

import javax.inject.Inject;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A plugin that sets up the infrastructure for testing native binaries with XCTest test framework. It also adds conventions on top of it.
 *
 * @since 4.2
 */
@Incubating
public class XCTestConventionPlugin implements Plugin<ProjectInternal> {
    private final MacOSSdkPlatformPathLocator sdkPlatformPathLocator;

    @Inject
    public XCTestConventionPlugin(MacOSSdkPlatformPathLocator sdkPlatformPathLocator) {
        this.sdkPlatformPathLocator = sdkPlatformPathLocator;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(TestingBasePlugin.class);
        project.getPluginManager().apply(SwiftBasePlugin.class);

        TaskContainer tasks = project.getTasks();

        // Create test suite component
        SwiftXCTestSuite component = createTestSuite(project);

        configureTestSuiteBuildingTasks(project, component);
        configureTestSuiteWithTestedComponentWhenAvailable(project);

        // Create test suite test task
        Task testingTask = createTestingTask(project);

        // Create component lifecycle task
        Task test = tasks.create(component.getName());
        if (OperatingSystem.current().isMacOsX()) {
            test.dependsOn(testingTask);
        }

        // Create check lifecycle task
        Task check = tasks.getByName("check");
        check.dependsOn(test);
    }

    private void configureTestSuiteBuildingTasks(Project project, SwiftXCTestSuite component) {
        if (component instanceof SwiftXcodeXCTestSuite) {
            TaskContainer tasks = project.getTasks();

            // Configure compile task
            SwiftCompile compile = (SwiftCompile) tasks.getByName("compileTestSwift");
            compile.getCompilerArgs().addAll(project.provider(new Callable<List<String>>() {
                @Override
                public List<String> call() throws Exception {
                    File frameworkDir = new File(sdkPlatformPathLocator.find(), "Developer/Library/Frameworks");
                    return Arrays.asList("-g", "-F" + frameworkDir.getAbsolutePath());
                }
            }));

            // Add a link task
            LinkMachOBundle link = (LinkMachOBundle) tasks.getByName("linkTest");
            link.getLinkerArgs().set(project.provider(new Callable<List<String>>() {
                @Override
                public List<String> call() throws Exception {
                    File frameworkDir = new File(sdkPlatformPathLocator.find(), "Developer/Library/Frameworks");
                    return Lists.newArrayList("-F" + frameworkDir.getAbsolutePath(), "-framework", "XCTest", "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks", "-Xlinker", "-rpath", "-Xlinker", "@loader_path/../Frameworks");
                }
            }));
        }
    }

    private static XcTest createTestingTask(Project project) {
        final DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();
        TaskContainer tasks = project.getTasks();

        CreateSwiftBundle bundle = tasks.withType(CreateSwiftBundle.class).getByName("bundleSwiftTest");

        final XcTest xcTest = tasks.create("xcTest", XcTest.class);
        xcTest.getTestBundleDirectory().set(bundle.getOutputDir());
        xcTest.getWorkingDirectory().set(buildDirectory.dir("bundle/test"));
        xcTest.onlyIf(new Spec<Task>() {
            @Override
            public boolean isSatisfiedBy(Task element) {
                return xcTest.getTestBundleDirectory().getAsFile().get().exists();
            }
        });

        return xcTest;
    }

    private static SwiftXCTestSuite createTestSuite(Project project) {
        // TODO - Reuse logic from Swift*Plugin
        // TODO - component name and extension name aren't the same
        // TODO - should use `src/xctext/swift` as the convention?
        // Add the component extension
        SwiftXCTestSuite testSuite = project.getObjects().newInstance(DefaultSwiftXcodeXCTestSuite.class,
            "test", project.getConfigurations());
        project.getExtensions().add(SwiftXCTestSuite.class, "xctest", testSuite);
        project.getComponents().add(testSuite);
        project.getComponents().add(testSuite.getDevelopmentBinary());

        // Setup component
        testSuite.getModule().set(GUtil.toCamelCase(project.getName() + "Test"));

        return testSuite;
    }

    private void configureTestSuiteWithTestedComponentWhenAvailable(final Project project) {
        project.getPlugins().withType(SwiftExecutablePlugin.class, configureTestSuiteWithTestedComponent(project));
        project.getPlugins().withType(SwiftLibraryPlugin.class, configureTestSuiteWithTestedComponent(project));
    }

    private static <T> Action<? super T> configureTestSuiteWithTestedComponent(final Project project) {
        return new Action<T>() {
            @Override
            public void execute(T plugin) {
                TaskContainer tasks = project.getTasks();
                SwiftComponent testedComponent = project.getComponents().withType(SwiftComponent.class).getByName("main");
                SwiftXCTestSuite testSuite = project.getExtensions().getByType(SwiftXCTestSuite.class);

                // Connect test suite with tested component
                testSuite.setTestedComponent(testedComponent);

                // Configure test suite compile task from tested component compile task
                SwiftCompile compileMain = tasks.withType(SwiftCompile.class).getByName("compileDebugSwift");
                SwiftCompile compileTest = tasks.withType(SwiftCompile.class).getByName("compileTestSwift");
                compileTest.includes(compileMain.getObjectFileDir());

                // Configure test suite link task from tested component compiled objects
                AbstractLinkTask linkTest = tasks.withType(AbstractLinkTask.class).getByName("linkTest");
                linkTest.source(testedComponent.getDevelopmentBinary().getObjects());
            }
        };
    }
}
