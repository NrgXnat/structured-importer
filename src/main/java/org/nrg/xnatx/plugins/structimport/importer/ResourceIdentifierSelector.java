package org.nrg.xnatx.plugins.structimport.importer;

import org.apache.commons.lang.StringUtils;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;

import java.util.Map;

/**
 * Determines which {@link org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService}
 * bean an import should use, based on the upload parameters.
 * <p>
 * An explicit {@code resourceIdentifier} parameter selects a bean by name.
 * Otherwise the legacy {@code toggleStructuredSessionLabeling} toggle is
 * honored: its default ({@code derived}) and a blank value select the
 * CSV-manifest service, while any other value selects the directory-walking
 * simple service.
 */
final class ResourceIdentifierSelector {

    static final String PARAM_RESOURCE_IDENTIFIER = "resourceIdentifier";

    private static final String PARAM_TOGGLE_SESSION_LABELING = "toggleStructuredSessionLabeling";
    private static final String DERIVED_SESSION_LABELING      = "derived";
    private static final String CSV_IDENTIFIER_SERVICE        = CsvBasedResourceIdentifierService.class.getSimpleName();
    private static final String SIMPLE_IDENTIFIER_SERVICE     = SimpleResourceIdentifierService.class.getSimpleName();

    private ResourceIdentifierSelector() {
    }

    static String select(final Map<String, Object> parameters) {
        final String resourceIdentifierService = (String) parameters.get(PARAM_RESOURCE_IDENTIFIER);
        if (StringUtils.isNotBlank(resourceIdentifierService)) {
            return resourceIdentifierService;
        }
        final String toggleSessionLabel = (String) parameters.get(PARAM_TOGGLE_SESSION_LABELING);
        return StringUtils.isBlank(toggleSessionLabel) || StringUtils.equals(toggleSessionLabel, DERIVED_SESSION_LABELING)
               ? CSV_IDENTIFIER_SERVICE
               : SIMPLE_IDENTIFIER_SERVICE;
    }
}
