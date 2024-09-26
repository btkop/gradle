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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BrokenResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariantSet;
import org.gradle.api.internal.attributes.AttributeSchemaServices;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.AttributesFactory;
import org.gradle.api.internal.attributes.immutable.ImmutableAttributesSchema;
import org.gradle.api.internal.attributes.matching.AttributeMatcher;
import org.gradle.internal.component.model.AttributeMatchingExplanationBuilder;
import org.gradle.internal.component.resolution.failure.ResolutionFailureHandler;
import org.gradle.internal.deprecation.DeprecationLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link ArtifactVariantSelector} that uses attribute matching to select a matching set of artifacts.
 *
 * If no producer variant is compatible with the requested attributes, this selector will attempt to construct a chain of artifact
 * transforms that can produce a variant compatible with the requested attributes.
 *
 * An instance of {@link ResolutionFailureHandler} is injected in the constructor
 * to allow the caller to handle failures in a consistent manner as during graph variant selection.
 */
public class AttributeMatchingArtifactVariantSelector implements ArtifactVariantSelector {

    private final ImmutableAttributesSchema consumerSchema;
    private final TransformUpstreamDependenciesResolver dependenciesResolver;
    private final ConsumerProvidedVariantFinder consumerProvidedVariantFinder;
    private final AttributesFactory attributesFactory;
    private final AttributeSchemaServices attributeSchemaServices;
    private final TransformedVariantFactory transformedVariantFactory;
    private final ResolutionFailureHandler failureProcessor; // TODO: rename to failure handler

    AttributeMatchingArtifactVariantSelector(
        ImmutableAttributesSchema consumerSchema,
        TransformUpstreamDependenciesResolver dependenciesResolver,
        ConsumerProvidedVariantFinder consumerProvidedVariantFinder,
        AttributesFactory attributesFactory,
        AttributeSchemaServices attributeSchemaServices,
        TransformedVariantFactory transformedVariantFactory,
        ResolutionFailureHandler failureProcessor
    ) {
        this.consumerSchema = consumerSchema;
        this.dependenciesResolver = dependenciesResolver;
        this.consumerProvidedVariantFinder = consumerProvidedVariantFinder;
        this.attributesFactory = attributesFactory;
        this.attributeSchemaServices = attributeSchemaServices;
        this.transformedVariantFactory = transformedVariantFactory;
        this.failureProcessor = failureProcessor;
    }

    @Override
    public ResolvedArtifactSet select(ResolvedVariantSet producer, ImmutableAttributes requestAttributes, boolean allowNoMatchingVariants, ResolvedArtifactTransformer resolvedArtifactTransformer) {
        try {
            return doSelect(producer, allowNoMatchingVariants, resolvedArtifactTransformer, AttributeMatchingExplanationBuilder.logging(), requestAttributes);
        } catch (Exception t) {
            return new BrokenResolvedArtifactSet(failureProcessor.unknownArtifactVariantSelectionFailure(producer, requestAttributes, t));
        }
    }

    private ResolvedArtifactSet doSelect(ResolvedVariantSet producer, boolean allowNoMatchingVariants, ResolvedArtifactTransformer resolvedArtifactTransformer, AttributeMatchingExplanationBuilder explanationBuilder, ImmutableAttributes requestAttributes) {
        AttributeMatcher matcher = attributeSchemaServices.getMatcher(consumerSchema, producer.getSchema());
        ImmutableAttributes componentRequested = attributesFactory.concat(requestAttributes, producer.getOverriddenAttributes());
        final List<ResolvedVariant> variants = producer.getVariants();

        List<? extends ResolvedVariant> matches = matcher.matchMultipleCandidates(variants, componentRequested, explanationBuilder);
        if (matches.size() == 1) {
            return matches.get(0).getArtifacts();
        } else if (matches.size() > 1) {
            throw failureProcessor.ambiguousArtifactsFailure(matcher, producer, componentRequested, matches);
        }

        // We found no matches. Attempt to construct artifact transform chains which produce matching variants.
        List<TransformedVariant> transformedVariants = consumerProvidedVariantFinder.findTransformedVariants(variants, componentRequested);

        // If there are multiple potential artifact transform variants, perform attribute matching to attempt to find the best.
        if (transformedVariants.size() > 1) {
            transformedVariants = tryDisambiguate(matcher, transformedVariants, componentRequested, explanationBuilder, producer, componentRequested, transformedVariants, failureProcessor);
        }

        if (transformedVariants.size() == 1) {
            TransformedVariant result = transformedVariants.get(0);
            return resolvedArtifactTransformer.asTransformed(result.getRoot(), result.getTransformedVariantDefinition(), dependenciesResolver, transformedVariantFactory);
        }

        if (allowNoMatchingVariants) {
            return ResolvedArtifactSet.EMPTY;
        }

        throw failureProcessor.noCompatibleArtifactFailure(matcher, producer, componentRequested, variants);
    }

    /**
     * Given a set of potential transform chains, attempt to reduce the set to a minimal set of preferred candidates.
     * Ideally, this method would return a single candidate.
     * <p>
     * This method starts by performing attribute matching on the candidates. This leverages disambiguation rules
     * from the {@link AttributeMatcher} to reduce the set of candidates. Return a single candidate only one remains.
     * <p>
     * If there are multiple results after disambiguation, return a subset of the results such that all candidates have
     * incompatible attributes values when matched with the <strong>last</strong> candidate. In some cases, this step is
     * able to arbitrarily reduces the candidate set to a single candidate as long as all remaining candidates are
     * compatible with each other.
     */
    private static List<TransformedVariant> tryDisambiguate(
        AttributeMatcher matcher,
        List<TransformedVariant> candidates,
        ImmutableAttributes componentRequested,
        AttributeMatchingExplanationBuilder explanationBuilder,
        ResolvedVariantSet targetVariantSet,
        ImmutableAttributes requestedAttributes,
        List<TransformedVariant> transformedVariants,
        ResolutionFailureHandler failureHandler
    ) {
        List<TransformedVariant> matches = matcher.matchMultipleCandidates(candidates, componentRequested, explanationBuilder);
        if (matches.size() == 1) {
            return matches;
        }

        assert !matches.isEmpty();

        List<TransformedVariant> differentTransforms = new ArrayList<>(1);

        // Choosing the last candidate here is arbitrary.
        TransformedVariant last = matches.get(matches.size() - 1);
        differentTransforms.add(last);

        // Find any other candidate which does not match with the last candidate.
        for (int i = 0; i < matches.size() - 1; i++) {
            TransformedVariant current = matches.get(i);
            if (!matcher.areMutuallyCompatible(current.getAttributes(), last.getAttributes())) {
                differentTransforms.add(current);
            }
        }

        if (differentTransforms.isEmpty()) {
            throw new IllegalStateException("No different transformations found out of: " + matches.size() + " matches; this can't happen!");
        } else if (differentTransforms.size() == 1) {
            // We chose some arbitrary transformation chain even though there were mutually compatible
            // This Questionable behavior - where the "different" chains just resequencings of the same chain?
            List<TransformedVariant> nonJustResequencedChains = findDistinctTransformationChains(matches);
            if (nonJustResequencedChains.size() > 1) {
                throw failureHandler.ambiguousArtifactTransformsFailure(targetVariantSet, requestedAttributes, nonJustResequencedChains);

                // TODO: maybe deprecate first
//            DeprecationLogger.deprecateBehaviour("FOO")
//                .willBecomeAnErrorInGradle9()
//                .undocumented()
//                .nagUser();
            }
        } else {
            // differentTransforms.size() > 1 = Ambiguity
            throw failureHandler.ambiguousArtifactTransformsFailure(targetVariantSet, componentRequested, transformedVariants);
        }

        return differentTransforms;
    }

    private static List<TransformedVariant> findDistinctTransformationChains(List<TransformedVariant> allChains) {
        // Map from a fingerprint to the variants that contain such a fingerprint
        Map<TransformationFingerprint, List<TransformedVariant>> distinctChains = new LinkedHashMap<>();

        // Map each transformation chain's unique fingerprint to the list of chains sharing it
        allChains.forEach(chain -> distinctChains.computeIfAbsent(new TransformationFingerprint(chain), f -> new ArrayList<>()).add(chain));

        // Return an arbitrary representative of each unique transformation chain
        return distinctChains.values().stream()
            .map(transformedVariants -> transformedVariants.iterator().next())
            .collect(Collectors.toList());
    }

    private static final class TransformationFingerprint {
        private final Set<Node> steps;

        public TransformationFingerprint(TransformedVariant variant) {
            steps = new HashSet<>();

            steps.add(variant.getTransformChain().)
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


        private static final class Node {
            private final ImmutableAttributes fromAttributes;
            private final ImmutableAttributes toAttributes;
            private final Class<? extends TransformAction<?>> actionType;

            private Node(ImmutableAttributes fromAttributes, ImmutableAttributes toAttributes, Class<? extends TransformAction<?>> actionType) {
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

                Node node = (Node) o;
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
