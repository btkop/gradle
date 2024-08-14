/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.buildinit.projectspecs;

import org.gradle.api.Describable;
import org.gradle.api.Incubating;

import java.util.List;

/**
 * Represents a specification for a new type of project that the {@code init} task can generate.
 *
 * @implSpec Meant to be implemented by plugins that want to provide additional project types, with implemtations
 * being discoverable by a {@link java.util.ServiceLoader}.
 * @since 8.11
 */
@Incubating
public interface InitProjectSpec extends Describable {
    /**
     * The name of the type of project this spec will generate.
     * <p>
     * This will be used to allow the user to select a project type when running the {@code init} task.
     *
     * @return a name providing a brief description of this type of project
     * @since 8.11
     */
    @Override
    String getDisplayName();

    /**
     * Returns the parameters that can be provided to configure this project during generation.
     *
     * @return the parameters for this type of project specification
     * @since 8.11
     */
    List<InitProjectParameter<?>> getParameters();
}
