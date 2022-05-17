/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.internal.changedetection.state;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.gradle.api.internal.file.pattern.PathMatcher;
import org.gradle.api.internal.file.pattern.PatternMatcherFactory;
import org.gradle.internal.fingerprint.hashing.RegularFileSnapshotContext;
import org.gradle.internal.fingerprint.hashing.ResourceHasher;
import org.gradle.internal.fingerprint.hashing.ZipEntryContext;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.io.IoFunction;
import org.gradle.internal.io.IoSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class PropertiesFileAwareClasspathResourceHasher implements ResourceHasher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesFileAwareClasspathResourceHasher.class);
    private final ResourceHasher delegate;
    private final Map<PathMatcher, ResourceEntryFilter> propertiesFileFilters;
    private final List<String> propertiesFilePatterns;

    public PropertiesFileAwareClasspathResourceHasher(ResourceHasher delegate, Map<String, ResourceEntryFilter> propertiesFileFilters) {
        this.delegate = delegate;
        ImmutableList.Builder<String> patterns = ImmutableList.builder();
        ImmutableMap.Builder<PathMatcher, ResourceEntryFilter> filters = ImmutableMap.builder();
        propertiesFileFilters.forEach((pattern, resourceEntryFilter) -> {
            filters.put(PatternMatcherFactory.compile(false, pattern), resourceEntryFilter);
            patterns.add(pattern);
        });
        this.propertiesFileFilters = filters.build();
        this.propertiesFilePatterns = patterns.build();
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        delegate.appendConfigurationToHasher(hasher);
        hasher.putString(getClass().getName());
        propertiesFilePatterns.forEach(hasher::putString);
        propertiesFileFilters.values().forEach(resourceEntryFilter -> resourceEntryFilter.appendConfigurationToHasher(hasher));
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshotContext snapshotContext) throws IOException {
        ResourceEntryFilter resourceEntryFilter = matchingFiltersFor(snapshotContext.getRelativePathSegments());
        // If this is a properties file and we have matching filters for it, attempt to hash the properties.
        // If this is not a properties file, or we encounter an error while hashing the properties, hash with the delegate.
        return Optional.ofNullable(resourceEntryFilter)
            .flatMap(filter -> tryHashWithFallback(snapshotContext, filter))
            .orElseGet(IoSupplier.wrap(() -> delegate.hash(snapshotContext)));
    }

    @Nullable
    @Override
    public HashCode hash(ZipEntryContext zipEntryContext) throws IOException {
        ResourceEntryFilter resourceEntryFilter = matchingFiltersFor(zipEntryContext.getRelativePathSegments());
        Optional<ZipEntryContext> safeContext = Optional.ofNullable(resourceEntryFilter)
            .flatMap(filter -> zipEntryContext.withFallbackSafety());

        // We can't just map() here because the delegate can return null, which means we can't
        // distinguish between a context unsafe for fallback and a call to a delegate that
        // returns null.  To avoid calling the delegate twice, we use a conditional instead.
        if (safeContext.isPresent()) {
            // If this is a properties file and we can fallback safely, attempt to hash the properties.
            // If we encounter an error, hash with the delegate using the safe fallback.
            return safeContext.flatMap(IoFunction.wrap(context -> tryHashWithFallback(context, resourceEntryFilter)))
                .orElseGet(IoSupplier.wrap(() -> delegate.hash(safeContext.get())));
        } else {
            // If this is not a properties file, or we cannot fallback safely, hash with the delegate.
            return delegate.hash(zipEntryContext);
        }
    }

    private Optional<HashCode> tryHashWithFallback(RegularFileSnapshotContext snapshotContext, ResourceEntryFilter resourceEntryFilter) {
        try (FileInputStream propertiesFileInputStream = new FileInputStream(snapshotContext.getSnapshot().getAbsolutePath())){
            return Optional.of(hashProperties(propertiesFileInputStream, resourceEntryFilter));
        } catch (Exception e) {
            LOGGER.debug("Could not load fingerprint for " + snapshotContext.getSnapshot().getAbsolutePath() + ". Falling back to full entry fingerprinting", e);
            return Optional.empty();
        }
    }

    private Optional<HashCode> tryHashWithFallback(ZipEntryContext zipEntryContext, ResourceEntryFilter resourceEntryFilter) {
        try {
            return Optional.of(zipEntryContext.getEntry().withInputStream(inputStream -> hashProperties(inputStream, resourceEntryFilter)));
        } catch (Exception e) {
            LOGGER.debug("Could not load fingerprint for " + zipEntryContext.getRootParentName() + "!" + zipEntryContext.getFullName() + ". Falling back to full entry fingerprinting", e);
            return Optional.empty();
        }
    }

    @Nullable
    private ResourceEntryFilter matchingFiltersFor(Supplier<String[]> relativePathSegments) {
        List<ResourceEntryFilter> matchingFilters = propertiesFileFilters.entrySet().stream()
            .filter(entry -> entry.getKey().matches(relativePathSegments.get(), 0))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());

        if (matchingFilters.size() == 0) {
            return null;
        } else if (matchingFilters.size() == 1) {
            return matchingFilters.get(0);
        } else {
            return new UnionResourceEntryFilter(matchingFilters);
        }
    }

    private HashCode hashProperties(InputStream inputStream, ResourceEntryFilter propertyResourceFilter) throws IOException {
        Hasher hasher = Hashing.newHasher();
        Properties properties = new Properties();
        properties.load(new InputStreamReader(inputStream, new PropertyResourceBundleFallbackCharset()));
        Map<String, String> entries = Maps.fromProperties(properties);
        entries
            .entrySet()
            .stream()
            .filter(entry ->
                !propertyResourceFilter.shouldBeIgnored(entry.getKey()))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                hasher.putString(entry.getKey());
                hasher.putString(entry.getValue());
            });
        return hasher.hash();
    }
}
