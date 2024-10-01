/*
 * Copyright 2023 the original author or authors.
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

import com.gradle.develocity.testing.annotations.LocalOnly
import org.gradle.integtests.fixtures.android.AndroidHome
import org.gradle.test.fixtures.dsl.GradleDsl
import org.gradle.util.GradleVersion
import org.gradle.util.internal.VersionNumber

import static org.gradle.integtests.fixtures.versions.KotlinGradlePluginVersions.hasConfigurationCacheWarnings
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Abstract base class for testing the Kotlin plugin with Android projects.
 *
 * This test exists to avoid duplicating test logic for Groovy and Kotlin DSLs.
 */
@LocalOnly(because = "Needs Android environment")
abstract class AbstractKotlinPluginAndroidSmokeTest extends AbstractSmokeTest implements RunnerFactory {
    abstract String getSampleName()
    abstract GradleDsl getDSL()

    VersionNumber kotlinPluginVersion

    def "kotlin android on android-kotlin-example using #dsl DSL (kotlin=#kotlinPluginVersion, agp=#androidPluginVersion, workers=#parallel)"(String kotlinPluginVersion, String androidPluginVersion, boolean parallel) {
        given:
        AndroidHome.assertIsSet()
        AGP_VERSIONS.assumeAgpSupportsCurrentJavaVersionAndKotlinVersion(androidPluginVersion, kotlinPluginVersion)
        this.kotlinPluginVersion = VersionNumber.parse(kotlinPluginVersion)
        useSample(getSampleName())

        def buildFileName = getDSL().fileNameFor("build")
        [buildFileName, "app/$buildFileName"].each { sampleBuildFileName ->
            replaceVariablesInFile(
                    file(sampleBuildFileName),
                    kotlinVersion: kotlinPluginVersion,
                    androidPluginVersion: androidPluginVersion,
                    androidBuildToolsVersion: TestedVersions.androidTools)
        }
        def kotlinPluginVersionNumber = VersionNumber.parse(kotlinPluginVersion)

        when:
        def result = mixedRunner(parallel, androidPluginVersion, kotlinPluginVersionNumber, 'clean', ":app:testDebugUnitTestCoverage")
            .expectDeprecationWarning(
                "Declaring an 'is-' property with a Boolean type has been deprecated. Starting with Gradle 9.0, this property will be ignored by Gradle. The combination of method name and return type is not consistent with Java Bean property rules and will become unsupported in future versions of Groovy. Add a method named 'getCrunchPngs' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType\$AgpDecorated.isCrunchPngs' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/${GradleVersion.current().version}/userguide/upgrading_version_8.html#groovy_boolean_properties",
                "https://issuetracker.google.com/issues/370546370"
            )
            .expectDeprecationWarning(
                "Declaring an 'is-' property with a Boolean type has been deprecated. Starting with Gradle 9.0, this property will be ignored by Gradle. The combination of method name and return type is not consistent with Java Bean property rules and will become unsupported in future versions of Groovy. Add a method named 'getUseProguard' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType.isUseProguard' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/${GradleVersion.current().version}/userguide/upgrading_version_8.html#groovy_boolean_properties",
                "https://issuetracker.google.com/issues/370546370"
            )
            .expectDeprecationWarning(
                "Declaring an 'is-' property with a Boolean type has been deprecated. Starting with Gradle 9.0, this property will be ignored by Gradle. The combination of method name and return type is not consistent with Java Bean property rules and will become unsupported in future versions of Groovy. Add a method named 'getWearAppUnbundled' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.api.variant.impl.ApplicationVariantImpl.isWearAppUnbundled' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/${GradleVersion.current().version}/userguide/upgrading_version_8.html#groovy_boolean_properties",
                "https://issuetracker.google.com/issues/370546370"
            )
            .build()

        then:
        result.task(':app:testDebugUnitTestCoverage').outcome == SUCCESS

        where:
// To run a specific combination, set the values here, uncomment the following four lines
//  and comment out the lines coming after
//        kotlinPluginVersion = TestedVersions.kotlin.versions.last()
//        androidPluginVersion = TestedVersions.androidGradle.versions.last()
//        parallelTasksInProject = ParallelTasksInProject.FALSE

        [kotlinPluginVersion, androidPluginVersion, parallel] << [
                TestedVersions.kotlin.versions,
                TestedVersions.androidGradle.versions,
                [true, false]
        ].combinations()

        dsl = getDSL().name()
    }

    @Override
    protected int maxConfigurationCacheProblems() {
        return hasConfigurationCacheWarnings(kotlinPluginVersion) ? 2 : 0
    }
}
