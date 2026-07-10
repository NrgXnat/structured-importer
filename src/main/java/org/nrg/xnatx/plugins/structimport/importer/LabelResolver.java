package org.nrg.xnatx.plugins.structimport.importer;

import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;

/**
 * Resolves a subject or session label from the upload parameters and the value
 * a resource identifier extracted from the archive. The upload parameter takes
 * precedence: when the user customizes labeling on the upload form, any labels
 * in the archive's manifest are ignored in favor of the form values.
 */
final class LabelResolver {

    private LabelResolver() {
    }

    static String resolve(final String fieldName, final String fromParameters, final String fromResource) throws ClientException {
        if (StringUtils.isNotBlank(fromParameters)) {
            return fromParameters;
        }
        if (StringUtils.isNotBlank(fromResource)) {
            return fromResource;
        }
        throw new ClientException("Required " + fieldName + " was not specified in upload parameters or by the resource identifier service");
    }
}
