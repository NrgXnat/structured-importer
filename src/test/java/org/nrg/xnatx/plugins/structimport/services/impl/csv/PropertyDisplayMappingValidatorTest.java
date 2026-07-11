package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import org.junit.Before;
import org.junit.Test;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyDisplayMapping;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PropertyDisplayMappingValidatorTest {

    private ModalityDataTypeService modalityService;

    private final List<PropertyDisplayMapping> existing = Arrays.asList(
            PropertyDisplayMapping.builder().display("Scan ID").property("xnat:imageScanData/ID").build(),
            PropertyDisplayMapping.builder().display("Session Label").property("xnat:imageSessionData/label").build());

    @Before
    public void setUp() {
        modalityService = mock(ModalityDataTypeService.class);
        final Map<String, ModalityMapping> modalities = new LinkedHashMap<>();
        modalities.put("MR", ModalityMapping.builder().modality("MR").scan("xnat:mrScanData").session("xnat:mrSessionData").build());
        modalities.put("US", ModalityMapping.builder().modality("US").scan("xnat:usScanData").session("xnat:usSessionData").build());
        when(modalityService.getModalityMappings()).thenReturn(modalities);
    }

    @Test
    public void validNewMappingPasses() {
        final List<String> errors = validate("Repetition Time", "xnat:mrScanData/parameters/tr", null);
        assertThat(errors, is(empty()));
    }

    @Test
    public void genericRootsPass() {
        assertThat(validate("Scan Note", "xnat:imageScanData/note", null), is(empty()));
        assertThat(validate("Subject Group", "xnat:subjectData/group", null), is(empty()));
        assertThat(validate("Resource Note", "xnat:abstractResource/note", null), is(empty()));
    }

    @Test
    public void duplicateDisplayValueReportsExistingMapping() {
        final List<String> errors = validate("Scan ID", "xnat:mrScanData/parameters/tr", null);
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("\"Scan ID\" already exists"));
        assertThat(errors.get(0), containsString("xnat:imageScanData/ID"));
    }

    @Test
    public void duplicatePropertyReportsExistingMapping() {
        final List<String> errors = validate("Identifier", "xnat:imageScanData/ID", null);
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("xnat:imageScanData/ID already exists"));
        assertThat(errors.get(0), containsString("\"Scan ID\""));
    }

    @Test
    public void duplicateDisplayAndPropertyReportsBoth() {
        final List<String> errors = validate("Session Label", "xnat:imageScanData/ID", null);
        assertThat(errors, hasSize(2));
    }

    @Test
    public void duplicateChecksAreCaseInsensitive() {
        final List<String> errors = validate("scan id", "XNAT:IMAGESCANDATA/id", null);
        assertThat(errors, hasSize(2));
    }

    @Test
    public void editingSkipsTheMappingBeingReplaced() {
        // renaming "Scan ID" while keeping its property must not self-collide
        final List<String> errors = validate("Scan Identifier", "xnat:imageScanData/ID", "Scan ID");
        assertThat(errors, is(empty()));
    }

    @Test
    public void editingStillDetectsCollisionsWithOtherMappings() {
        final List<String> errors = validate("Session Label", "xnat:imageScanData/ID", "Scan ID");
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("\"Session Label\" already exists"));
    }

    @Test
    public void unconfiguredRootFails() {
        final List<String> errors = validate("Flarn", "xyz:fooSessionData/flarn", null);
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("xyz:fooSessionData is not configured"));
    }

    @Test
    public void modalityConfiguredRootPasses() {
        assertThat(validate("US Foo", "xnat:usSessionData/foo", null), is(empty()));
    }

    @Test
    public void malformedPropertyFails() {
        assertThat(validate("No Path", "xnat:mrScanData", null), hasSize(1));
        assertThat(validate("Trailing", "xnat:mrScanData/", null), hasSize(1));
    }

    @Test
    public void blankValuesFail() {
        assertThat(validate("", "xnat:mrScanData/parameters/tr", null), hasSize(1));
        assertThat(validate("Repetition Time", "  ", null), hasSize(1));
        assertThat(validate(" ", null, null), hasSize(2));
    }

    private List<String> validate(final String display, final String property, final String originalDisplay) {
        return PropertyDisplayMappingValidator.validate(existing,
                                                        PropertyDisplayMapping.builder().display(display).property(property).build(),
                                                        originalDisplay,
                                                        modalityService);
    }
}
