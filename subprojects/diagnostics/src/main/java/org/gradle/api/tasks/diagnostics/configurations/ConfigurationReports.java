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

package org.gradle.api.tasks.diagnostics.configurations;

import org.gradle.api.Incubating;
import org.gradle.api.reporting.ReportContainer;
import org.gradle.api.reporting.SingleFileReport;
import org.gradle.api.tasks.Internal;

/**
 * The reporting configuration for any {@link org.gradle.api.tasks.diagnostics.AbstractConfigurationReportTask}.
 *
 * @since 7.5
 */
@Incubating
public interface ConfigurationReports extends ReportContainer<SingleFileReport> {
    /**
     * The text-based configuration report, which contains the same text as the console output.
     * <p>
     * This report <strong>IS</strong> enabled by default.
     *
     * @return The text configuration report
     */
    @Internal
    SingleFileReport getText();

    /**
     * The JSON-based configuration report.
     * <p>
     * This report is <strong>NOT</strong> enabled by default.
     *
     * @return The JSON configuration report
     */
    @Internal
    SingleFileReport getJSON();
}
