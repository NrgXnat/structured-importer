package org.nrg.xnatx.plugins.structimport.importer;

import org.nrg.action.ClientException;
import org.nrg.xdat.base.BaseElement;
import org.nrg.xnatx.plugins.structimport.models.PropertyTargets;
import org.nrg.xnatx.plugins.structimport.models.PropertyTargets.TargetType;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the custom (non-built-in) property values carried on
 * {@link ScanResource#getCustomProperties()} to the XNAT objects the importer
 * creates. Property paths under the generic {@code xnat:imageScanData} and
 * {@code xnat:imageSessionData} roots are re-rooted to the concrete data type
 * of the target object; modality-specific roots must match the target's data
 * type exactly. Subject paths are applied as-is.
 */
final class CustomPropertyApplier {

    private CustomPropertyApplier() {
    }

    /**
     * Resolves a configured property path against the concrete data type of the
     * object it will be set on.
     *
     * @param property       the configured property path, e.g. {@code xnat:imageScanData/note} or {@code xnat:mrScanData/parameters/tr}
     * @param targetXsiType  the xsi type of the target object, e.g. {@code xnat:mrScanData}
     *
     * @return the path to pass to {@code setProperty}
     *
     * @throws ClientException if the property has a modality-specific root that does not match the target
     */
    static String resolvePath(final String property, final String targetXsiType) throws ClientException {
        final TargetType target = PropertyTargets.targetOf(property);
        if (target == TargetType.SUBJECT) {
            return property;
        }
        final String root = PropertyTargets.rootElement(property);
        if (root.equalsIgnoreCase(PropertyTargets.GENERIC_SCAN_ROOT) || root.equalsIgnoreCase(PropertyTargets.GENERIC_SESSION_ROOT)) {
            return targetXsiType + "/" + PropertyTargets.relativePath(property);
        }
        if (!root.equalsIgnoreCase(targetXsiType)) {
            throw new ClientException("Cannot set property \"" + property + "\": it applies to " + root + " but the target object is " + targetXsiType);
        }
        return property;
    }

    /**
     * Sets every custom property matching {@code targetType} on the given object.
     *
     * @throws ClientException if a path cannot be resolved for the target or XFT rejects the value
     */
    static void applyProperties(final BaseElement target, final TargetType targetType, final Map<String, String> properties) throws ClientException {
        for (final Map.Entry<String, String> entry : properties.entrySet()) {
            final String property = entry.getKey();
            if (PropertyTargets.targetOf(property) != targetType) {
                continue;
            }
            final String path = resolvePath(property, target.getXSIType());
            try {
                target.setProperty(path, entry.getValue());
            } catch (Exception e) {
                throw new ClientException("Unable to set property \"" + property + "\" to \"" + entry.getValue() + "\" on " + target.getXSIType(), e);
            }
        }
    }

    /**
     * Collects the first value of each custom property matching {@code targetType}
     * across the given resources. Manifest consistency validation guarantees the
     * values agree, so "first" is not a tie-break.
     */
    static Map<String, String> collectProperties(final Iterable<ScanResource> resources, final TargetType targetType) {
        final Map<String, String> collected = new LinkedHashMap<>();
        for (final ScanResource resource : resources) {
            for (final Map.Entry<String, String> entry : resource.getCustomProperties().entrySet()) {
                if (PropertyTargets.targetOf(entry.getKey()) == targetType) {
                    collected.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return collected;
    }
}
