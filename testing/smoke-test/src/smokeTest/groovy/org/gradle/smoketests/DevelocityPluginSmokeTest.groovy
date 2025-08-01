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

package org.gradle.smoketests

import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager
import org.gradle.plugin.management.internal.autoapply.AutoAppliedDevelocityPlugin
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.IntegTestPreconditions
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.gradle.util.internal.VersionNumber
import spock.lang.Issue

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

// https://plugins.gradle.org/plugin/com.gradle.develocity
class DevelocityPluginSmokeTest extends AbstractSmokeTest {

    enum CI {
        TEAM_CITY(
            AbstractSmokeTest.TestedVersions.teamCityGradlePluginRef,
            "https://raw.githubusercontent.com/etiennestuder/teamcity-build-scan-plugin/%s/agent/src/main/resources/init-scripts/develocity-injection.init.gradle"
        ),
        JENKINS(
            AbstractSmokeTest.TestedVersions.jenkinsGradlePluginRef,
            "https://raw.githubusercontent.com/jenkinsci/gradle-plugin/%s/src/main/resources/hudson/plugins/gradle/injection/init-script.gradle"
        ),
        BAMBOO(
            AbstractSmokeTest.TestedVersions.bambooGradlePluginRef,
            "https://raw.githubusercontent.com/gradle/develocity-bamboo-plugin/refs/tags/%s/src/main/resources/develocity/gradle/develocity-init-script.gradle"
        );

        String gitRef
        String urlTemplate

        CI(String gitRef, String urlTemplate) {
            this.gitRef = gitRef
            this.urlTemplate = urlTemplate
        }

        String getUrl() {
            return String.format(urlTemplate, gitRef)
        }
    }

    private static final Map<CI, String> CI_INJECTION_SCRIPT_CONTENTS = new ConcurrentHashMap<>()

    private static String getCiInjectionScriptContent(CI ci) {
        return CI_INJECTION_SCRIPT_CONTENTS.computeIfAbsent(ci) { new URL(it.getUrl()).getText(StandardCharsets.UTF_8.name()) }
    }

    private static final List<String> LEGACY_UNSUPPORTED = [
        "1.14",
        "1.15",
        "1.16",
        "2.0",
        "2.0.1",
        "2.0.2",
        "2.1",
        "2.2",
        "2.2.1",
        "2.3",
        "2.4",
        "2.4.1",
        "2.4.2"
    ]

    private static final List<String> UNSUPPORTED = [
        "3.0",
        "3.1",
        "3.1.1",
        "3.2",
        "3.2.1",
        "3.3",
        "3.3.1",
        "3.3.2",
        "3.3.3",
        "3.3.4",
        "3.4",
        "3.4.1",
        "3.5",
        "3.5.1",
        "3.5.2",
        "3.6",
        "3.6.1",
        "3.6.2",
        "3.6.3",
        "3.6.4",
        "3.7",
        "3.7.1",
        "3.7.2",
        "3.8",
        "3.8.1",
        "3.9",
        "3.10",
        "3.10.1",
        "3.10.2",
        "3.10.3",
        // "3.11", This doesn't work on Java 8, so let's not test it.
        "3.11.1",
        "3.11.2",
        "3.11.3",
        "3.11.4",
        "3.12",
        "3.12.1",
        "3.12.2",
        "3.12.3",
        "3.12.4",
        "3.12.5",
        "3.12.6",
        "3.13"
    ]

    private static final List<String> SUPPORTED = [
        "3.13.1",
        "3.13.2",
        "3.13.3",
        "3.13.4",
        "3.14",
        "3.14.1",
        "3.15",
        "3.15.1",
        "3.16",
        "3.16.1",
        "3.16.2",
        "3.17",
        "3.17.1",
        "3.17.2",
        "3.17.3",
        "3.17.4",
        "3.17.5",
        "3.17.6",
        "3.18",
        "3.18.1",
        "3.18.2",
        "3.19",
        "3.19.1",
        "3.19.2",
        "4.0",
        "4.0.1",
        "4.0.2",
        "4.1"
    ]

    // Current injection scripts support Develocity plugin 3.6.4 and above
    private static final List<String> SUPPORTED_BY_CI_INJECTION = SUPPORTED
        .findAll { VersionNumber.parse("3.6.4") <= VersionNumber.parse(it) }

    private static final VersionNumber FIRST_VERSION_SUPPORTING_ISOLATED_PROJECTS = VersionNumber.parse("3.15")
    private static final VersionNumber FIRST_VERSION_SUPPORTING_ISOLATED_PROJECTS_FOR_TEST_ACCELERATION = VersionNumber.parse("3.17")
    private static final VersionNumber FIRST_VERSION_SUPPORTING_SAFE_MODE = VersionNumber.parse("3.15")
    private static final VersionNumber FIRST_VERSION_UNDER_DEVELOCITY_BRAND = VersionNumber.parse("3.17")

    def "coverage at least up to auto-applied version"() {
        expect:
        VersionNumber.parse(AutoAppliedDevelocityPlugin.VERSION) <= VersionNumber.parse(SUPPORTED.last())
    }

    def "can use plugin #version"() {
        when:
        usePluginVersion version

        then:
        scanRunner()
            .build().output.contains("Build scan written to")

        where:
        version << SUPPORTED
    }

    @Issue("https://github.com/gradle/gradle/issues/34252")
    def "does not fail when using TD #version"() {
        when:
        usePluginVersion version

        buildFile << """
            dependencies {
                testImplementation("org.testng:testng:7.5.1")
                testRuntimeOnly("org.junit.support:testng-engine:1.0.6")
            }

            tasks.named("test") {
                develocity {
                    testDistribution {
                        enabled = true
                        maxRemoteExecutors = 0
                    }
                    testRetry {
                        maxRetries = 1
                    }
                }
            }
        """

        // The underlying problem is reproducible with a TestNG based test
        file("src/test/java/MyFlakyTest.java").java """
import org.testng.annotations.AfterSuite;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MyFlakyTest {

    private static final Path LIFECYCLE_MARKER_FILE = Paths.get("flakyLifecycleMarker.txt");
    private static final Path TEST_MARKER_FILE = Paths.get("flakyTestMarker.txt");

    @AfterSuite
    public static void flakyAfterSuite() throws IOException {
        if (!Files.exists(LIFECYCLE_MARKER_FILE)) {
            Files.createFile(LIFECYCLE_MARKER_FILE);
            throw new RuntimeException("AfterSuite goes boom!");
        }
    }

    @Test
    public void flakyTest() throws IOException {
        if (!Files.exists(TEST_MARKER_FILE)) {
            Files.createFile(TEST_MARKER_FILE);
            throw new RuntimeException("test goes boom!");
        }
    }

    @Test
    public void successfulTest() {

    }
}
        """

        then:
        scanRunner("test").build()

        where:
        version << SUPPORTED.grep { String version -> VersionNumber.parse(version) >= FIRST_VERSION_UNDER_DEVELOCITY_BRAND }
    }

    @Requires(value = IntegTestPreconditions.NotConfigCached, reason = "Isolated projects implies config cache")
    def "can use plugin #version with isolated projects"() {
        when:
        usePluginVersion version

        then:
        scanRunner("-Dorg.gradle.unsafe.isolated-projects=true")
            .build().output.contains("Build scan written to")

        where:
        version << SUPPORTED
            .findAll { FIRST_VERSION_SUPPORTING_ISOLATED_PROJECTS <= VersionNumber.parse(it) }
    }

    @Requires(value = IntegTestPreconditions.NotConfigCached, reason = "Isolated projects implies config cache")
    def "can use plugin #version with isolated projects and test acceleration features"() {
        when:
        usePluginVersion version
        ["project1", "project2"].each { projectName ->
            setupJavaProject(file(projectName)).with {
                buildFile << """
                    tasks.withType(Test).configureEach {
                        develocity {
                            testRetry {
                                maxRetries = 3
                            }
                        }
                    }

                """
            }
            settingsFile << """
                include ':${projectName}'
            """
        }
        settingsFile << """
            include ':project1'
            include ':project2'
        """


        then:
        scanRunner("-Dorg.gradle.unsafe.isolated-projects=true")
            .build().output.contains("Build scan written to")

        when:
        createTest(file("project1"), "MyTest1")
        createTest(file("project2"), "MyTest1")

        then:
        with(scanRunner("-Dorg.gradle.unsafe.isolated-projects=true")
            .build()) {
            output.contains("Build scan written to")
            output.contains("Reusing configuration cache.")
            task(":project1:test").outcome == TaskOutcome.SUCCESS
            task(":project2:test").outcome == TaskOutcome.SUCCESS
        }

        when:
        file("project1/build.gradle") << """
            println("Change a project so it's reconfigured")
        """
        createTest(file("project1"), "MyTest2")
        createTest(file("project2"), "MyTest2")

        then:
        with(scanRunner("-Dorg.gradle.unsafe.isolated-projects=true")
            .build()) {
            output.contains("Build scan written to")
            task(":project1:test").outcome == TaskOutcome.SUCCESS
            task(":project2:test").outcome == TaskOutcome.SUCCESS
        }

        where:
        version << SUPPORTED
            .findAll { VersionNumber.parse(it) >= FIRST_VERSION_SUPPORTING_ISOLATED_PROJECTS_FOR_TEST_ACCELERATION }
    }

    @Requires(value = IntegTestPreconditions.NotConfigCached, reason = "Isolated projects implies config cache")
    def "cannot use plugin #version with isolated projects"() {
        when:
        usePluginVersion version

        and:
        def output = scanRunner("-Dorg.gradle.unsafe.isolated-projects=true")
            .build().output

        then:
        output.contains("Gradle Enterprise plugin $version has been disabled as it is incompatible with Isolated Projects. Upgrade to Gradle Enterprise plugin 3.15 or newer to restore functionality.")
        !output.contains("Build scan written to")

        where:
        version << SUPPORTED
            .findAll { VersionNumber.parse(it) < FIRST_VERSION_SUPPORTING_ISOLATED_PROJECTS }
    }

    def "cannot use plugin #version"() {
        when:
        usePluginVersion version

        and:
        def output = runner("--stacktrace")
            .buildAndFail().output

        then:
        output.contains(GradleEnterprisePluginManager.OLD_SCAN_PLUGIN_VERSION_MESSAGE)

        where:
        version << LEGACY_UNSUPPORTED
    }

    def "plugin is disabled for unsupported version #version"() {
        def expectedToFailWithConfigCacheProblem =
            GradleContextualExecuter.configCache && VersionNumber.parse(version) < VersionNumber.parse("3.4")

        when:
        usePluginVersion version

        and:
        def runner = scanRunner()
        def buildResult = expectedToFailWithConfigCacheProblem ? runner.buildAndFail() : runner.build()
        def output = buildResult.output

        then:
        output.contains("Gradle Enterprise plugin $version has been disabled as it is incompatible with this version of Gradle. Upgrade to Gradle Enterprise plugin 3.13.1 or newer to restore functionality.")
        !output.contains("Build scan written to")

        if (expectedToFailWithConfigCacheProblem) {
            assert output.contains("1 problem was found storing the configuration cache.")
            assert output =~ /.*registration of listener on '\S+' is unsupported.*/
        }

        where:
        version << UNSUPPORTED
    }

    @Requires(IntegTestPreconditions.NotConfigCached)
    def "can inject plugin #pluginVersion in #ci using '#ciScriptVersion' script version"() {
        def versionNumber = VersionNumber.parse(pluginVersion)
        def initScript = "init-script.gradle"
        file(initScript) << getCiInjectionScriptContent(ci)

        // URL is not relevant as long as it's valid due to the `-Dscan.dump` parameter
        file("gradle.properties") << """
            systemProp.develocity.plugin.version=$pluginVersion
            systemProp.develocity.injection.init-script-name=$initScript
            systemProp.develocity.url=http://localhost:5086
            systemProp.develocity.injection-enabled=true

            # since bamboo 2.3.0 and jenkins 2.15
            systemProp.develocity-injection.develocity-plugin.version=$pluginVersion
            systemProp.develocity-injection.init-script-name=$initScript
            systemProp.develocity-injection.url=http://localhost:5086
            systemProp.develocity-injection.enabled=true
        """.stripIndent()

        setupLocalBuildCache()
        setupJavaProject()
        if (supportsSafeMode(versionNumber)) {
            new TestFile(buildFile).with {
                touch()
                prepend("""
                    plugins {
                        id "org.gradle.test-retry" version "${TestedVersions.testRetryPlugin}"
                    }
                """)
            }
        }

        when:
        def result = scanRunner("--init-script", initScript)
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "WARNING: The following functionality has been deprecated and will be removed in the next major release of the Develocity Gradle plugin:")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "WARNING: The following functionality has been deprecated and will be removed in the next major release of the Develocity Gradle plugin. " +
                    "For assistance with migration, see https://gradle.com/help/gradle-plugin-develocity-migration.")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "WARNING: The following functionality has been deprecated and will be removed in the next major release of the Develocity Gradle plugin. Run with '-Ddevelocity.deprecation.captureOrigin=true' to see where the deprecated functionality is being used. " +
                    "For assistance with migration, see https://gradle.com/help/gradle-plugin-develocity-migration.")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "- The deprecated \"gradleEnterprise.server\" API has been replaced by \"develocity.server\"")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "- The deprecated \"gradleEnterprise.allowUntrustedServer\" API has been replaced by \"develocity.allowUntrustedServer\"")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "- The deprecated \"gradleEnterprise.buildScan.uploadInBackground\" API has been replaced by \"develocity.buildScan.uploadInBackground\"")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber,
                "- The deprecated \"gradleEnterprise.buildScan.value\" API has been replaced by \"develocity.buildScan.value\"")
            .maybeExpectLegacyDeprecationWarningIf(FIRST_VERSION_UNDER_DEVELOCITY_BRAND <= versionNumber && ci == CI.TEAM_CITY,
                "- The deprecated \"gradleEnterprise.buildScan.buildScanPublished\" API has been replaced by \"develocity.buildScan.buildScanPublished\"")
            .maybeExpectLegacyDeprecationWarning(
                "Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. " +
                    "This is scheduled to be removed in Gradle 10. " +
                    "Use assignment ('url = <value>') instead. " +
                    "Consult the upgrading guide for further information: https://docs.gradle.org/${GradleVersion.current().version}/userguide/upgrading_version_8.html#groovy_space_assignment_syntax"
            )
            .build()

        then:
        result.output.contains("Build scan written to")

        where:
        [ci, pluginVersion] << [CI.values(), SUPPORTED_BY_CI_INJECTION].combinations()
        ciScriptVersion = ci.gitRef
    }

    private static boolean supportsSafeMode(VersionNumber pluginVersion) {
        pluginVersion >= FIRST_VERSION_SUPPORTING_SAFE_MODE
    }

    BuildResult build(String... args) {
        scanRunner(args).build()
    }

    SmokeTestGradleRunner scanRunner(String... args) {
        // Run with --build-cache to test also build cache events
        runner("build", "-Dscan.dump", "--build-cache", *args)
    }

    void usePluginVersion(String version) {
        def develocityPlugin = VersionNumber.parse(version) >= VersionNumber.parse("3.17")
        def gradleEnterprisePlugin = VersionNumber.parse(version) >= VersionNumber.parse("3.0")
        if (develocityPlugin) {
            settingsFile << """
                plugins {
                    id "com.gradle.develocity" version "$version"
                }

                develocity {
                    buildScan {
                        termsOfUseUrl = 'https://gradle.com/help/legal-terms-of-use'
                        termsOfUseAgree = 'yes'
                    }
                }
            """
        } else if (gradleEnterprisePlugin) {
            settingsFile << """
                plugins {
                    id "com.gradle.enterprise" version "$version"
                }

                gradleEnterprise {
                    buildScan {
                        termsOfServiceUrl = 'https://gradle.com/terms-of-service'
                        termsOfServiceAgree = 'yes'
                    }
                }
            """
        } else {
            buildFile << """
                plugins {
                    id "com.gradle.build-scan" version "$version"
                }

                buildScan {
                    termsOfServiceUrl = 'https://gradle.com/terms-of-service'
                    termsOfServiceAgree = 'yes'
                }
            """
        }

        setupLocalBuildCache()
        setupJavaProject()
    }

    private TestFile setupJavaProject(TestFile projectDir = new TestFile(testProjectDir)) {
        projectDir.file("build.gradle") << """
            apply plugin: 'java'
            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnitJupiter()
                    }
                }
            }
        """

        projectDir.file("src/main/java/MySource.java") << """
            public class MySource {
                public static boolean isTrue() { return true; }
            }
        """

        createTest(projectDir, "MyTest")
        projectDir
    }

    void createTest(TestFile projectDir, String testName) {
        projectDir.file("src/test/java/${testName}.java").java"""
            import org.junit.jupiter.api.*;
            import static org.junit.jupiter.api.Assertions.*;

            public class ${testName} {
               @Test
               public void test() {
                  assertTrue(MySource.isTrue());
               }
            }
        """
    }
}
