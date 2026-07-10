package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Covers the pure path-resolution logic. The {@code applyProperties} XFT calls
 * require an initialized XNAT context and are exercised by manual testing.
 */
public class CustomPropertyApplierTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void genericScanPathIsReRootedToConcreteType() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:imageScanData/note", "xnat:mrScanData"),
                   equalTo("xnat:mrScanData/note"));
    }

    @Test
    public void genericSessionPathIsReRootedToConcreteType() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:imageSessionData/note", "xnat:petSessionData"),
                   equalTo("xnat:petSessionData/note"));
    }

    @Test
    public void matchingModalitySpecificPathIsUnchanged() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:mrScanData/parameters/tr", "xnat:mrScanData"),
                   equalTo("xnat:mrScanData/parameters/tr"));
    }

    @Test
    public void modalitySpecificPathMatchIsCaseInsensitive() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:mrscandata/parameters/tr", "xnat:mrScanData"),
                   equalTo("xnat:mrscandata/parameters/tr"));
    }

    @Test
    public void mismatchedModalitySpecificPathThrows() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("xnat:petScanData"));
        thrown.expectMessage(containsString("xnat:mrScanData"));
        CustomPropertyApplier.resolvePath("xnat:petScanData/tracer/name", "xnat:mrScanData");
    }

    @Test
    public void subjectPathIsAppliedAsIs() throws Exception {
        assertThat(CustomPropertyApplier.resolvePath("xnat:subjectData/group", "xnat:subjectData"),
                   equalTo("xnat:subjectData/group"));
    }
}
