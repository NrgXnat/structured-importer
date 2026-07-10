package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;

public class CustomLabelingValidatorTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void noCustomLabelsNeverFails() throws Exception {
        CustomLabelingValidator.validate(resources("S1", "A", "S2", "B"), null, "");
    }

    @Test
    public void singleSubjectAndSessionPasses() throws Exception {
        CustomLabelingValidator.validate(resources("S1", "A", "S1", "A"), "CUSTOM_SUBJ", "CUSTOM_SES");
    }

    @Test
    public void multipleSubjectsFail() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("multiple subjects or sessions"));
        CustomLabelingValidator.validate(resources("S1", "A", "S2", "A"), "CUSTOM_SUBJ", "CUSTOM_SES");
    }

    @Test
    public void multipleSessionsFail() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("multiple subjects or sessions"));
        CustomLabelingValidator.validate(resources("S1", "A", "S1", "B"), "CUSTOM_SUBJ", "CUSTOM_SES");
    }

    @Test
    public void subjectParamAloneStillGuards() throws Exception {
        thrown.expect(ClientException.class);
        CustomLabelingValidator.validate(resources("S1", "A", "S2", "A"), "CUSTOM_SUBJ", null);
    }

    @Test
    public void nullManifestLabelsPass() throws Exception {
        // the directory-walking service produces resources without labels
        CustomLabelingValidator.validate(resources(null, null, null, null), "CUSTOM_SUBJ", "CUSTOM_SES");
    }

    @Test
    public void emptyResourcesPass() throws Exception {
        CustomLabelingValidator.validate(Collections.<ScanResource>emptyList(), "CUSTOM_SUBJ", "CUSTOM_SES");
    }

    private static List<ScanResource> resources(final String subject1, final String session1, final String subject2, final String session2) {
        return Arrays.asList(
                new ScanResource(subject1, session1, "1", "MR", "NIFTI", null, null, null, null),
                new ScanResource(subject2, session2, "2", "MR", "NIFTI", null, null, null, null));
    }
}
