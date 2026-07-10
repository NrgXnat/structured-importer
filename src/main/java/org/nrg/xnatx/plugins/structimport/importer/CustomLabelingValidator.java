package org.nrg.xnatx.plugins.structimport.importer;

import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Guards custom (Customize) labeling: when the upload parameters supply a
 * subject or session label, that single pair of values replaces whatever the
 * manifest says — which is only coherent for an archive holding one subject
 * and one session. Runs against the extracted resources before their labels
 * are overridden, so a multi-subject or multi-session manifest fails with a
 * clear message instead of being silently collapsed into one session.
 */
final class CustomLabelingValidator {

    private CustomLabelingValidator() {
    }

    static void validate(final Iterable<ScanResource> resources, final String subjectParam, final String sessionParam) throws ClientException {
        if (StringUtils.isBlank(subjectParam) && StringUtils.isBlank(sessionParam)) {
            return;
        }
        final Set<String> subjects = new LinkedHashSet<>();
        final Set<String> sessions = new LinkedHashSet<>();
        for (final ScanResource resource : resources) {
            if (StringUtils.isNotBlank(resource.getSubjectLabel())) {
                subjects.add(resource.getSubjectLabel());
            }
            if (StringUtils.isNotBlank(resource.getSessionLabel())) {
                sessions.add(resource.getSessionLabel());
            }
        }
        if (subjects.size() > 1 || sessions.size() > 1) {
            throw new ClientException("The archive's manifest contains " + subjects.size() + " subject(s) " + subjects
                                      + " and " + sessions.size() + " session(s) " + sessions
                                      + ". Archives with multiple subjects or sessions cannot be imported when custom subject and session labels are specified; remove the custom labels or split the archive into one archive per session.");
        }
    }
}
