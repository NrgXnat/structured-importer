package org.nrg.xnatx.plugins.structimport.models;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PropertyTargetsTest {

    private static final List<ModalityMapping> MODALITIES = Arrays.asList(
            ModalityMapping.builder().modality("MR").scan("xnat:mrScanData").session("xnat:mrSessionData").build(),
            ModalityMapping.builder().modality("PET").scan("xnat:petScanData").session("xnat:petSessionData").build(),
            ModalityMapping.builder().modality("US").scan("xnat:usScanData").session("xnat:usSessionData").build(),
            ModalityMapping.builder().modality("SC").scan("xnat:scScanData").build(),
            ModalityMapping.builder().modality("PETMR").session("xnat:petmrSessionData").build());

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void classifiesGenericRoots() {
        assertThat(PropertyTargets.targetOf("xnat:imageScanData/note", MODALITIES), is(PropertyTargets.TargetType.SCAN));
        assertThat(PropertyTargets.targetOf("xnat:imageSessionData/note", MODALITIES), is(PropertyTargets.TargetType.SESSION));
        assertThat(PropertyTargets.targetOf("xnat:subjectData/group", MODALITIES), is(PropertyTargets.TargetType.SUBJECT));
    }

    @Test
    public void classifiesModalityConfiguredRoots() {
        assertThat(PropertyTargets.targetOf("xnat:mrScanData/parameters/tr", MODALITIES), is(PropertyTargets.TargetType.SCAN));
        assertThat(PropertyTargets.targetOf("xnat:usScanData/foo", MODALITIES), is(PropertyTargets.TargetType.SCAN));
        assertThat(PropertyTargets.targetOf("xnat:scScanData/foo", MODALITIES), is(PropertyTargets.TargetType.SCAN));
        assertThat(PropertyTargets.targetOf("xnat:mrSessionData/coil", MODALITIES), is(PropertyTargets.TargetType.SESSION));
        assertThat(PropertyTargets.targetOf("xnat:petmrSessionData/foo", MODALITIES), is(PropertyTargets.TargetType.SESSION));
    }

    @Test
    public void genericRootsWorkWithoutModalityMappings() {
        assertThat(PropertyTargets.targetOf("xnat:imageScanData/note", Collections.<ModalityMapping>emptyList()), is(PropertyTargets.TargetType.SCAN));
        assertThat(PropertyTargets.targetOf("xnat:subjectData/group", null), is(PropertyTargets.TargetType.SUBJECT));
    }

    @Test
    public void rootMatchingIsCaseInsensitive() {
        assertThat(PropertyTargets.targetOf("XNAT:MRSCANDATA/parameters/tr", MODALITIES), is(PropertyTargets.TargetType.SCAN));
    }

    @Test
    public void unconfiguredRootThrows() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("configured as a scan or session type"));
        PropertyTargets.targetOf("xyz:fooSessionData/flarn", MODALITIES);
    }

    @Test
    public void missingRelativePathThrows() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("Malformed property"));
        PropertyTargets.targetOf("xnat:mrScanData", MODALITIES);
    }

    @Test
    public void trailingSlashThrows() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(containsString("Malformed property"));
        PropertyTargets.targetOf("xnat:mrScanData/", MODALITIES);
    }

    @Test
    public void nullThrows() {
        thrown.expect(IllegalArgumentException.class);
        PropertyTargets.targetOf(null, MODALITIES);
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
