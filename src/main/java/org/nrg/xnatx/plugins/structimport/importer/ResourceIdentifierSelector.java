package org.nrg.xnatx.plugins.structimport.importer;

import org.apache.commons.lang3.StringUtils;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;

import java.util.Map;

/**
 * Determines which {@link ResourceIdentifierService} bean an import should use, based on the
 * upload parameters.
 * <p>
 * An explicit {@code resourceIdentifier} parameter selects a bean by name. Otherwise, the
 * {@code toggleStructuredSessionLabeling} toggle is honored: its default ({@code derived}) and a
 * blank value select the CSV-manifest service, while any other value (manual/Customize labeling)
 * selects the manifest-aware service, which uses the CSV manifest when the archive contains one
 * and falls back to the directory-walking simple service otherwise.
 */
final class ResourceIdentifierSelector {

    public static final String PARAM_RESOURCE_IDENTIFIER     = "resourceIdentifier";
    public static final String PARAM_TOGGLE_SESSION_LABELING = "toggleStructuredSessionLabeling";
    public static final String DERIVED_SESSION_LABELING      = "derived";
    public static final String MANUAL_SESSION_LABELING       = "manual";
    public static final String CSV_IDENTIFIER_SERVICE        = StringUtils.uncapitalize(CsvBasedResourceIdentifierService.class.getSimpleName());
    public static final String SIMPLE_IDENTIFIER_SERVICE     = StringUtils.uncapitalize(SimpleResourceIdentifierService.class.getSimpleName());
    public static final String MANUAL_IDENTIFIER_SERVICE     = "manualResourceIdentifierService";

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
               : MANUAL_IDENTIFIER_SERVICE;
    }
}
