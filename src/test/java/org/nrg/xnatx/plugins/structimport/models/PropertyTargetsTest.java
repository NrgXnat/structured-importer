package org.nrg.xnatx.plugins.structimport.models;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PropertyTargetsTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void classifiesScanRoots() {
        for (final String root : Arrays.asList("xnat:imageScanData", "xnat:mrScanData", "xnat:petScanData", "xnat:ctScanData", "xnat:srScanData")) {
            assertThat(PropertyTargets.targetOf(root + "/parameters/tr"), is(PropertyTargets.TargetType.SCAN));
        }
    }

    @Test
    public void classifiesSessionRoots() {
        for (final String root : Arrays.asList("xnat:imageSessionData", "xnat:mrSessionData", "xnat:petSessionData", "xnat:ctSessionData")) {
            assertThat(PropertyTargets.targetOf(root + "/note"), is(PropertyTargets.TargetType.SESSION));
        }
    }

    @Test
    public void classifiesSubjectRoot() {
        assertThat(PropertyTargets.targetOf("xnat:subjectData/group"), is(PropertyTargets.TargetType.SUBJECT));
    }

    @Test
    public void rootMatchingIsCaseInsensitive() {
        assertThat(PropertyTargets.targetOf("XNAT:MRSCANDATA/parameters/tr"), is(PropertyTargets.TargetType.SCAN));
    }

    @Test
    public void unknownRootThrowsWithAcceptedRoots() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("root element must be one of"));
        PropertyTargets.targetOf("xnat:projectData/name");
    }

    @Test
    public void missingRelativePathThrows() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("Malformed property"));
        PropertyTargets.targetOf("xnat:mrScanData");
    }

    @Test
    public void trailingSlashThrows() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("Malformed property"));
        PropertyTargets.targetOf("xnat:mrScanData/");
    }

    @Test
    public void nullThrows() {
        thrown.expect(IllegalArgumentException.class);
        PropertyTargets.targetOf(null);
    }

    @Test
    public void splitsRootAndRelativePath() {
        assertThat(PropertyTargets.rootElement("xnat:mrScanData/parameters/tr"), equalTo("xnat:mrScanData"));
        assertThat(PropertyTargets.relativePath("xnat:mrScanData/parameters/tr"), equalTo("parameters/tr"));
    }

    @Test
    public void builtInsAreRecognizedCaseInsensitively() {
        for (final String property : Arrays.asList(CsvImportConfigService.PROP_SCAN_ID,
                                                   CsvImportConfigService.PROP_MODALITY,
                                                   CsvImportConfigService.PROP_SERIES_DESCRIPTION,
                                                   CsvImportConfigService.PROP_SESSION_LABEL,
                                                   CsvImportConfigService.PROP_START_DATE,
                                                   CsvImportConfigService.PROP_START_TIME,
                                                   CsvImportConfigService.PROP_SUBJECT_ID,
                                                   CsvImportConfigService.PROP_SUBJECT_WEIGHT,
                                                   CsvImportConfigService.PROP_RESOURCE_NAME)) {
            assertThat(property, PropertyTargets.isBuiltIn(property), is(true));
            assertThat(property, PropertyTargets.isBuiltIn(property.toUpperCase()), is(true));
        }
    }

    @Test
    public void customPathsAreNotBuiltIn() {
        assertThat(PropertyTargets.isBuiltIn("xnat:mrScanData/parameters/tr"), is(false));
        assertThat(PropertyTargets.isBuiltIn(null), is(false));
    }
}
