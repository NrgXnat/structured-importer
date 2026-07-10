package org.nrg.xnatx.plugins.structimport.services.impl.yaml;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.schema.Wrappers.GenericWrapper.GenericWrapperElement;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@link ModalityDataTypeService} backed by YAML configuration files on the
 * classpath. The built-in defaults are loaded from
 * {@value #CORE_CONFIG_LOCATION} first; every other YAML file matching
 * {@value #CONFIG_LOCATION_PATTERN} is then merged on top (in a stable order),
 * so plugins and deployments can override or extend the defaults by shipping
 * their own file.
 *
 * <p>Configuration files map each top-level modality code to its {@code scan},
 * {@code session}, and {@code group} properties; files that cannot be read
 * that way are skipped with a warning. Merging is per-field: a field specified
 * by a later file replaces the earlier value, and an explicit blank string
 * clears it (e.g. {@code session: ""} removes an inherited session data
 * type).</p>
 *
 * <p>Configured modalities whose data types are not installed on this server
 * are filtered out, so a configuration can safely reference data types that
 * only exist in newer XNAT versions or optional plugins. The XFT schema is not
 * yet initialized when this bean is constructed, so the availability filter is
 * applied lazily on first use and the result memoized.</p>
 */
@Service
@Slf4j
public class YamlBasedModalityDataTypeService implements ModalityDataTypeService {

    public static final String CORE_CONFIG_LOCATION    = "META-INF/xnat/structimport/core/modality-to-xft.yaml";
    public static final String CONFIG_LOCATION_PATTERN = "classpath*:META-INF/xnat/structimport/**/*.yaml";

    private final Map<String, ModalityMapping> loaded;

    private volatile Map<String, ModalityMapping> available;

    public YamlBasedModalityDataTypeService() {
        this(CORE_CONFIG_LOCATION, CONFIG_LOCATION_PATTERN);
    }

    /**
     * Visible for testing: loads from alternate locations.
     *
     * @param coreLocation    the classpath location of the default configuration
     * @param locationPattern the resource pattern for override/extension files
     */
    YamlBasedModalityDataTypeService(final String coreLocation, final String locationPattern) {
        loaded = load(coreLocation, locationPattern);
        if (loaded.isEmpty()) {
            log.warn("No modality-to-data-type configuration was found at {} or {}; structured imports will not be able to create sessions or scans", coreLocation, locationPattern);
        } else {
            log.info("Loaded {} modality-to-data-type mapping(s): {}", loaded.size(), String.join(", ", loaded.keySet()));
        }
    }

    @Override
    public Map<String, ModalityMapping> getModalityMappings() {
        final Map<String, ModalityMapping> ordered = new LinkedHashMap<>();
        mappings().values().stream()
                  .sorted(Comparator.comparingInt(ModalityMapping::getGroupOrDefault).thenComparing(ModalityMapping::getModality))
                  .forEach(mapping -> ordered.put(mapping.getModality(), mapping));
        return ordered;
    }

    @Override
    public Optional<ModalityMapping> getModalityMapping(final String modality) {
        return StringUtils.isBlank(modality) ? Optional.empty() : Optional.ofNullable(mappings().get(modality.trim()));
    }

    @Override
    public List<ModalityMapping> getSessionModalities() {
        return getModalityMappings().values().stream()
                                    .filter(ModalityMapping::hasSessionDataType)
                                    .collect(Collectors.toList());
    }

    /**
     * The effective mappings: the loaded configuration minus modalities whose
     * data types are not installed. Filtering needs the XFT schema, which is
     * not initialized when this bean is constructed, so it runs on first use
     * after the schema is ready and the result is memoized. Until then the
     * unfiltered configuration is returned (without being cached).
     */
    private Map<String, ModalityMapping> mappings() {
        final Map<String, ModalityMapping> filtered = available;
        if (filtered != null) {
            return filtered;
        }
        if (!isSchemaInitialized()) {
            return loaded;
        }
        synchronized (this) {
            if (available == null) {
                available = filterUnavailable();
            }
            return available;
        }
    }

    private Map<String, ModalityMapping> filterUnavailable() {
        final Map<String, ModalityMapping> kept     = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final List<String>                 filtered = new ArrayList<>();
        for (final ModalityMapping mapping : loaded.values()) {
            final List<String> missing = new ArrayList<>();
            if (mapping.hasScanDataType() && !isDataTypeInstalled(mapping.getScan())) {
                missing.add(mapping.getScan());
            }
            if (mapping.hasSessionDataType() && !isDataTypeInstalled(mapping.getSession())) {
                missing.add(mapping.getSession());
            }
            if (missing.isEmpty()) {
                kept.put(mapping.getModality(), mapping);
            } else {
                filtered.add(mapping.getModality() + " (" + String.join(", ", missing) + ")");
            }
        }
        if (filtered.isEmpty()) {
            log.info("All {} configured modality-to-data-type mapping(s) are available on this system", kept.size());
        } else {
            log.info("Filtered out {} modality(ies) whose data types are not installed on this system: {}. {} modality(ies) remain available: {}",
                     filtered.size(), String.join("; ", filtered), kept.size(), String.join(", ", kept.keySet()));
        }
        return kept;
    }

    /**
     * Whether the XFT schema registry is ready; availability filtering is
     * deferred until it is. Overridable for testing.
     */
    protected boolean isSchemaInitialized() {
        return XFTManager.isInitialized();
    }

    /**
     * Whether a data type exists in this server's schema. Overridable for
     * testing. Fails open on unexpected errors so a transient schema problem
     * does not silently hide modalities.
     */
    protected boolean isDataTypeInstalled(final String dataType) {
        try {
            return GenericWrapperElement.GetElement(dataType) != null;
        } catch (ElementNotFoundException e) {
            return false;
        } catch (Exception e) {
            log.warn("Unable to determine whether the data type {} is installed; assuming it is", dataType, e);
            return true;
        }
    }

    private Map<String, ModalityMapping> load(final String coreLocation, final String locationPattern) {
        // case-insensitive so "mr" in a CSV manifest matches the configured "MR"
        final Map<String, ModalityMapping>         merged   = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());

        final Resource core = resolver.getResource("classpath:" + coreLocation);
        if (core.exists()) {
            mergeResource(merged, core);
        } else {
            log.warn("The default modality configuration {} was not found on the classpath", coreLocation);
        }

        final List<Resource> overrides = new ArrayList<>();
        try {
            overrides.addAll(Arrays.asList(resolver.getResources(locationPattern)));
        } catch (IOException e) {
            log.error("Unable to scan the classpath for modality configuration files matching {}", locationPattern, e);
        }
        // stable order so overriding behavior is deterministic across restarts
        overrides.sort(Comparator.comparing(YamlBasedModalityDataTypeService::resourceKey));
        for (final Resource override : overrides) {
            if (resourceKey(override).endsWith(coreLocation)) {
                continue;
            }
            mergeResource(merged, override);
        }
        return merged;
    }

    private void mergeResource(final Map<String, ModalityMapping> merged, final Resource resource) {
        final Map<String, ModalityMapping> config;
        try (final InputStream input = resource.getInputStream()) {
            config = new ObjectMapper(new YAMLFactory()).readValue(input, new TypeReference<Map<String, ModalityMapping>>() {});
        } catch (IOException e) {
            log.warn("Skipping {}: not a readable modality configuration ({})", resourceKey(resource), e.getMessage());
            return;
        }
        if (config == null || config.isEmpty()) {
            log.debug("Skipping {}: no modalities defined", resourceKey(resource));
            return;
        }
        for (final Map.Entry<String, ModalityMapping> entry : config.entrySet()) {
            final String          modality = StringUtils.trimToNull(entry.getKey());
            final ModalityMapping value    = entry.getValue();
            if (modality == null || value == null
                || (value.getScan() == null && value.getSession() == null && value.getGroup() == null)) {
                log.warn("Ignoring the modality entry \"{}\" in {}: no scan, session, or group properties", entry.getKey(), resourceKey(resource));
                continue;
            }
            merged.put(modality, mergeMapping(merged.get(modality), modality, value));
        }
        log.debug("Merged modality configuration from {}", resourceKey(resource));
    }

    /**
     * Per-field merge: fields present in the override replace the existing
     * values; an explicit blank string clears a field.
     */
    private static ModalityMapping mergeMapping(final ModalityMapping existing, final String modality, final ModalityMapping override) {
        if (existing == null) {
            return ModalityMapping.builder()
                                  .modality(modality)
                                  .scan(StringUtils.trimToNull(override.getScan()))
                                  .session(StringUtils.trimToNull(override.getSession()))
                                  .group(override.getGroup())
                                  .build();
        }
        return ModalityMapping.builder()
                              .modality(existing.getModality())
                              .scan(mergeField(existing.getScan(), override.getScan()))
                              .session(mergeField(existing.getSession(), override.getSession()))
                              .group(override.getGroup() != null ? override.getGroup() : existing.getGroup())
                              .build();
    }

    private static String mergeField(final String existing, final String override) {
        if (override == null) {
            return existing;
        }
        return StringUtils.trimToNull(override);
    }

    private static String resourceKey(final Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return String.valueOf(resource.getDescription());
        }
    }
}
