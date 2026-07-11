package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import org.apache.commons.lang3.StringUtils;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyDisplayMapping;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates a property display mapping before it is saved:
 *
 * <ul>
 *     <li>the display value and the object property must each be unique across
 *         the existing mappings (matching is case-insensitive);</li>
 *     <li>the property's root data type must be usable by the importer — either
 *         one of the generic roots ({@code xnat:imageScanData},
 *         {@code xnat:imageSessionData}, {@code xnat:subjectData},
 *         {@code xnat:abstractResource}) or a data type configured as a scan or
 *         session type in the {@link ModalityDataTypeService}.</li>
 * </ul>
 *
 * Validation failures are returned as user-facing messages rather than thrown,
 * so callers can report all problems at once.
 */
public final class PropertyDisplayMappingValidator {

    /**
     * Roots that are always valid regardless of the modality configuration.
     */
    private static final List<String> GENERIC_ROOTS = new ArrayList<>();

    static {
        GENERIC_ROOTS.add("xnat:imageScanData");
        GENERIC_ROOTS.add("xnat:imageSessionData");
        GENERIC_ROOTS.add("xnat:subjectData");
        GENERIC_ROOTS.add("xnat:abstractResource");
    }

    private PropertyDisplayMappingValidator() {
    }

    /**
     * Validates a new or edited mapping against the existing mappings and the
     * modality configuration.
     *
     * @param existing        the currently stored mappings
     * @param candidate       the mapping being saved
     * @param originalDisplay when editing, the display value of the mapping being replaced
     *                        (excluded from the duplicate checks); {@code null} when adding
     * @param modalityService supplies the configured scan and session data types
     *
     * @return user-facing error messages; empty when the mapping is valid
     */
    public static List<String> validate(final List<PropertyDisplayMapping> existing,
                                        final PropertyDisplayMapping candidate,
                                        final String originalDisplay,
                                        final ModalityDataTypeService modalityService) {
        final List<String> errors = new ArrayList<>();

        final String display  = StringUtils.trimToNull(candidate == null ? null : candidate.getDisplay());
        final String property = StringUtils.trimToNull(candidate == null ? null : candidate.getProperty());
        if (display == null) {
            errors.add("A display value is required.");
        }
        if (property == null) {
            errors.add("An object property is required.");
        }
        if (!errors.isEmpty()) {
            return errors;
        }

        for (final PropertyDisplayMapping mapping : existing) {
            if (originalDisplay != null && StringUtils.equalsIgnoreCase(mapping.getDisplay(), originalDisplay)) {
                continue;
            }
            if (StringUtils.equalsIgnoreCase(mapping.getDisplay(), display)) {
                errors.add("The display value \"" + display + "\" already exists: it is mapped to " + mapping.getProperty() + ".");
            }
            if (StringUtils.equalsIgnoreCase(mapping.getProperty(), property)) {
                errors.add("The object property " + property + " already exists: it is mapped from \"" + mapping.getDisplay() + "\".");
            }
        }

        final int slash = property.indexOf('/');
        if (slash < 1 || slash == property.length() - 1) {
            errors.add("The object property must be a data type followed by a property path, e.g. xnat:mrScanData/parameters/tr.");
            return errors;
        }
        final String root = property.substring(0, slash);
        if (!isValidRoot(root, modalityService)) {
            errors.add("The data type " + root + " is not configured as a session or scan data type for any modality. Valid data types: " + validRoots(modalityService) + ".");
        }
        return errors;
    }

    private static boolean isValidRoot(final String root, final ModalityDataTypeService modalityService) {
        for (final String generic : GENERIC_ROOTS) {
            if (generic.equalsIgnoreCase(root)) {
                return true;
            }
        }
        for (final ModalityMapping mapping : modalityService.getModalityMappings().values()) {
            if (StringUtils.equalsIgnoreCase(mapping.getScan(), root) || StringUtils.equalsIgnoreCase(mapping.getSession(), root)) {
                return true;
            }
        }
        return false;
    }

    private static String validRoots(final ModalityDataTypeService modalityService) {
        final Set<String> roots = new LinkedHashSet<>(GENERIC_ROOTS);
        final Set<String> configured = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final ModalityMapping mapping : modalityService.getModalityMappings().values()) {
            if (mapping.hasScanDataType()) {
                configured.add(mapping.getScan());
            }
            if (mapping.hasSessionDataType()) {
                configured.add(mapping.getSession());
            }
        }
        roots.addAll(configured);
        return String.join(", ", roots);
    }

    /**
     * @return the roots that are always valid regardless of the modality configuration
     */
    public static List<String> getGenericRoots() {
        return new ArrayList<>(GENERIC_ROOTS);
    }
}
