package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ScanGrouperTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void resourcesOfSameScanAreGroupedTogether() throws Exception {
        final Map<String, Map<ScanResource, List<Path>>> scans = ScanGrouper.group("SES", resources(
                resource("1", "MR", "NIFTI", "T1"),
                resource("1", "MR", "DICOM", "T1"),
                resource("2", "CT", "DICOM", "Head")));

        assertThat(scans.size(), is(2));
        assertThat(scans.get("1").size(), is(2));
        assertThat(scans.get("2").size(), is(1));
    }

    @Test
    public void conflictingModalitiesFail() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("conflicting modalities \"MR\" and \"CT\""));
        ScanGrouper.group("SES", resources(
                resource("1", "MR", "NIFTI", null),
                resource("1", "CT", "DICOM", null)));
    }

    @Test
    public void conflictingScanMetadataFails() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("conflicting scan metadata"));
        ScanGrouper.group("SES", resources(
                resource("1", "MR", "NIFTI", "T1"),
                resource("1", "MR", "DICOM", "T2")));
    }

    private static ScanResource resource(final String scanId, final String modality, final String name, final String seriesDescription) {
        return new ScanResource(null, null, scanId, modality, name, seriesDescription, null, null, null);
    }

    private static Map<ScanResource, List<Path>> resources(final ScanResource... resources) {
        final Map<ScanResource, List<Path>> map = new LinkedHashMap<>();
        for (final ScanResource resource : resources) {
            map.put(resource, Collections.singletonList(Paths.get(resource.getName())));
        }
        return map;
    }
}
