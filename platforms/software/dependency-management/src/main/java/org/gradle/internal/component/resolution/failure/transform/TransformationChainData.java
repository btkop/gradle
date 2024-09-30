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

package org.gradle.internal.component.resolution.failure.transform;

import com.google.common.collect.ImmutableList;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.internal.attributes.ImmutableAttributes;

import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a variant which is produced as the result of applying an artifact transform chain
 * to a root producer variant.
 * <p>
 * Immutable data class.  Meant to be easily serialized as part of build operation recording and tracing.
 */
public final class TransformationChainData {
    private final SourceVariantData startingVariant;
    private final ImmutableList<TransformData> steps;
    private final ImmutableAttributes finalAttributes;

    public TransformationChainData(SourceVariantData startingVariant, ImmutableList<TransformData> steps, ImmutableAttributes finalAttributes) {
        this.startingVariant = startingVariant;
        this.steps = steps;
        this.finalAttributes = finalAttributes;
    }

    /**
     * The variant that was used as the starting point for this chain of transformations.
     *
     * @return initial variant
     */
    public SourceVariantData getInitialVariant() {
        return startingVariant;
    }

    public String describeTransformations() {
        return steps.stream()
            .map(TransformData::getTransformName)
            .collect(Collectors.joining(", "));
    }

    /**
     * The complete resulting set of attributes on the "virtual variant" created by processing the source variant
     * completely through this transformation chain.
     * <p>
     * This explicitly includes attributes of the source variant that were not modified by any transformations.
     *
     * @return attributes as described
     */
    public ImmutableAttributes getFinalAttributes() {
        return finalAttributes;
    }

    public TransformationFingerprint fingerprint() {
        return new TransformationFingerprint(this);
    }

    public static final class TransformationFingerprint {
        private final HashSet<Step> steps;

        public TransformationFingerprint(TransformationChainData chain) {
            steps = chain.steps.stream()
                .map(s -> new Step(s.getFromAttributes(), s.getToAttributes(), s.getTransformActionClass()))
                .collect(Collectors.toCollection(HashSet::new));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            TransformationFingerprint that = (TransformationFingerprint) o;
            return Objects.equals(steps, that.steps);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(steps);
        }

        private static final class Step {
            private final ImmutableAttributes fromAttributes;
            private final ImmutableAttributes toAttributes;
            private final Class<? extends TransformAction<?>> actionType;

            private Step(ImmutableAttributes fromAttributes, ImmutableAttributes toAttributes, Class<? extends TransformAction<?>> actionType) {
                this.fromAttributes = fromAttributes;
                this.toAttributes = toAttributes;
                this.actionType = actionType;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }

                Step node = (Step) o;
                return fromAttributes.equals(node.fromAttributes) && toAttributes.equals(node.toAttributes) && actionType.equals(node.actionType);
            }

            @Override
            public int hashCode() {
                int result = fromAttributes.hashCode();
                result = 31 * result + toAttributes.hashCode();
                result = 31 * result + actionType.hashCode();
                return result;
            }
        }
    }
}
