package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Covers the pure path-resolution logic. The {@code applyProperties} XFT calls
 * require an initialized XNAT context and are exercised by manual testing.
 */
public class CustomPropertyApplierTest {

    private static final List<ModalityMapping> MODALITIES = Arrays.asList(
            ModalityMapping.builder().modality("MR").scan("xnat:mrScanData").session("xnat:mrSessionData").build(),
            ModalityMapping.builder().modality("PET").scan("xnat:petScanData").session("xnat:petSessionData").build(),
            ModalityMapping.builder().modality("US").scan("xnat:usScanData").session("xnat:usSessionData").build());

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void genericScanPathIsReRootedToConcreteType() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:imageScanData/note", "xnat:mrScanData", MODALITIES),
                   equalTo("xnat:mrScanData/note"));
    }

    @Test
    public void genericSessionPathIsReRootedToConcreteType() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:imageSessionData/note", "xnat:petSessionData", MODALITIES),
                   equalTo("xnat:petSessionData/note"));
    }

    @Test
    public void matchingDataTypeSpecificPathIsUnchanged() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:mrScanData/parameters/tr", "xnat:mrScanData", MODALITIES),
                   equalTo("xnat:mrScanData/parameters/tr"));
    }

    @Test
    public void modalityConfiguredRootBeyondLegacySetResolves() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:usScanData/foo", "xnat:usScanData", MODALITIES),
                   equalTo("xnat:usScanData/foo"));
    }

    @Test
    public void dataTypeSpecificPathMatchIsCaseInsensitive() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:mrscandata/parameters/tr", "xnat:mrScanData", MODALITIES),
                   equalTo("xnat:mrscandata/parameters/tr"));
    }

    @Test
    public void mismatchedDataTypeSpecificPathThrows() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("xnat:petScanData"));
        thrown.expectMessage(containsString("xnat:mrScanData"));
        CustomPropertyApplier.resolvePath("xnat:petScanData/tracer/name", "xnat:mrScanData", MODALITIES);
    }

    @Test
    public void subjectPathIsAppliedAsIs() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:subjectData/group", "xnat:subjectData", MODALITIES),
                   equalTo("xnat:subjectData/group"));
    }
}
