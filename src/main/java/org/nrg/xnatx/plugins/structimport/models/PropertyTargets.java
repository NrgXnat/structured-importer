package org.nrg.xnatx.plugins.structimport.models;

import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies the XNAT property paths used in CSV column mappings by the object
 * they apply to. A property path is a root element (e.g. {@code xnat:mrScanData})
 * followed by a slash and a relative path within that element (e.g.
 * {@code parameters/tr}). The root element determines whether the property is
 * set on the scan, the session, or the subject; root matching is
 * case-insensitive, but the relative path is passed to XFT unchanged.
 *
 * <p>Besides the generic roots ({@code xnat:imageScanData},
 * {@code xnat:imageSessionData}, {@code xnat:subjectData}), any data type
 * configured as a scan or session type in the modality-to-data-type
 * configuration is a valid root, so classification requires the effective
 * {@link ModalityMapping} collection.</p>
 */
public final class PropertyTargets {

    public enum TargetType {
        SCAN, SESSION, SUBJECT
    }

    /**
     * Roots that apply to any scan or session regardless of modality; paths under
     * these are re-rooted to the concrete data type of the target object when applied.
     */
    public static final String GENERIC_SCAN_ROOT    = "xnat:imageScanData";
    public static final String GENERIC_SESSION_ROOT = "xnat:imageSessionData";
    public static final String SUBJECT_ROOT         = "xnat:subjectData";

    private static final Set<String> BUILT_INS = buildBuiltIns();

    private PropertyTargets() {
    }

    /**
     * @param propertyPath      the property path to classify
     * @param modalityMappings  the effective modality mappings, whose scan and session
     *                          data types are accepted as roots
     *
     * @return the target object type for the given property path
     *
     * @throws IllegalArgumentException if the path is malformed or its root element is not supported
     */
    public static TargetType targetOf(final String propertyPath, final Collection<ModalityMapping> modalityMappings) {
        final String root = rootElement(propertyPath);
        if (root.equalsIgnoreCase(GENERIC_SCAN_ROOT)) {
            return TargetType.SCAN;
        }
        if (root.equalsIgnoreCase(GENERIC_SESSION_ROOT)) {
            return TargetType.SESSION;
        }
        if (root.equalsIgnoreCase(SUBJECT_ROOT)) {
            return TargetType.SUBJECT;
        }
        if (modalityMappings != null) {
            for (final ModalityMapping mapping : modalityMappings) {
                if (root.equalsIgnoreCase(mapping.getScan())) {
                    return TargetType.SCAN;
                }
                if (root.equalsIgnoreCase(mapping.getSession())) {
                    return TargetType.SESSION;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported property \"" + propertyPath + "\": the root element must be "
                                           + GENERIC_SCAN_ROOT + ", " + GENERIC_SESSION_ROOT + ", " + SUBJECT_ROOT
                                           + ", or a data type configured as a scan or session type for a modality");
    }

    /**
     * @return the root element of the property path, i.e. everything before the first slash
     *
     * @throws IllegalArgumentException if the path has no root element or no relative path
     */
    public static String rootElement(final String propertyPath) {
        final int slash = propertyPath == null ? -1 : propertyPath.indexOf('/');
        if (slash < 1 || slash == propertyPath.length() - 1) {
            throw new IllegalArgumentException("Malformed property \"" + propertyPath + "\": expected <rootElement>/<relative/path>, e.g. xnat:mrScanData/parameters/tr");
        }
        return propertyPath.substring(0, slash);
    }

    /**
     * @return the property path relative to its root element, i.e. everything after the first slash
     */
    public static String relativePath(final String propertyPath) {
        rootElement(propertyPath);
        return propertyPath.substring(propertyPath.indexOf('/') + 1);
    }

    /**
     * @return whether the path is one of the built-in properties enumerated on
     *         {@link CsvImportConfigService} (case-insensitive)
     */
    public static boolean isBuiltIn(final String propertyPath) {
        return propertyPath != null && BUILT_INS.contains(propertyPath.toLowerCase(Locale.ROOT));
    }

    private static Set<String> buildBuiltIns() {
        return Arrays.asList(CsvImportConfigService.PROP_SCAN_ID,
                             CsvImportConfigService.PROP_MODALITY,
                             CsvImportConfigService.PROP_SERIES_DESCRIPTION,
                             CsvImportConfigService.PROP_SESSION_LABEL,
                             CsvImportConfigService.PROP_START_DATE,
                             CsvImportConfigService.PROP_START_TIME,
                             CsvImportConfigService.PROP_SUBJECT_ID,
                             CsvImportConfigService.PROP_SUBJECT_WEIGHT,
                             CsvImportConfigService.PROP_RESOURCE_NAME)
                     .stream()
                     .map(property -> property.toLowerCase(Locale.ROOT))
                     .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
