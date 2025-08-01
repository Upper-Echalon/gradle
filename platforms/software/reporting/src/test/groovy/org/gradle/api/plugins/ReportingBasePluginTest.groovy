/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.plugins

import org.gradle.api.reporting.ReportingExtension
import org.gradle.test.fixtures.AbstractProjectBuilderSpec

class ReportingBasePluginTest extends AbstractProjectBuilderSpec {

    def "can apply plugin by id"() {
        given:
        project.apply plugin: 'reporting-base'

        expect:
        project.plugins.hasPlugin(ReportingBasePlugin)
    }

    def "adds reporting extension"() {
        given:
        project.pluginManager.apply(ReportingBasePlugin)

        expect:
        project.reporting instanceof ReportingExtension

        project.configure(project) {
            reporting {
                baseDirectory = project.layout.buildDirectory.dir("somewhere")
            }
        }
    }

    def "defaults to reports dir in build dir"() {
        project.pluginManager.apply(ReportingBasePlugin)
        def extension = project.reporting

        expect:
        extension.baseDirectory.get().asFile == new File(project.layout.buildDirectory.get().asFile, ReportingExtension.DEFAULT_REPORTS_DIR_NAME)

        when:
        project.layout.buildDirectory.set(project.file("newBuildDir"))

        then:
        extension.baseDirectory.get().asFile == new File(project.file("newBuildDir"), ReportingExtension.DEFAULT_REPORTS_DIR_NAME)
    }
}
