package org.nrg.xnatx.plugins.structimport.services.impl.simple;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;

public class SimpleResourceIdentifierServiceTest {

    private SimpleResourceIdentifierService service;
    private UserI                           user;
    private Path                            root;

    @Before
    public void setUp() throws IOException {
        service = new SimpleResourceIdentifierService();
        user    = mock(UserI.class);
        root    = Files.createTempDirectory("simple-import-test");
    }

    @After
    public void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                final List<Path> reverse = new ArrayList<>();
                stream.forEach(reverse::add);
                Collections.reverse(reverse);
                for (final Path path : reverse) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void identifiesResourceFromScanModalityResourceConvention() throws Exception {
        touch("1/MR/NIFTI/image.nii");

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result.size(), is(1));
        final Map.Entry<ScanResource, List<Path>> entry = result.entrySet().iterator().next();
        final ScanResource resource = entry.getKey();
        assertThat(resource.getScanId(), equalTo("1"));
        assertThat(resource.getModality(), equalTo("MR"));
        assertThat(resource.getName(), equalTo("NIFTI"));
        // The directory walker cannot determine subject/session context; those come from upload parameters.
        assertThat(resource.getSubjectLabel(), is((String) null));
        assertThat(resource.getSessionLabel(), is((String) null));
        assertThat(entry.getValue(), hasSize(1));
        assertThat(entry.getValue().get(0), equalTo(root.resolve("1/MR/NIFTI")));
    }

    @Test
    public void identifiesMultipleScansModalitiesAndResources() throws Exception {
        touch("1/MR/NIFTI/a.nii");
        touch("1/MR/DICOM/b.dcm");
        touch("2/PET/NIFTI/c.nii");

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result.size(), is(3));
        assertThat(result.keySet().stream()
                         .anyMatch(r -> "1".equals(r.getScanId()) && "MR".equals(r.getModality()) && "DICOM".equals(r.getName())),
                   is(true));
        assertThat(result.keySet().stream()
                         .anyMatch(r -> "2".equals(r.getScanId()) && "PET".equals(r.getModality()) && "NIFTI".equals(r.getName())),
                   is(true));
    }

    @Test
    public void resourcesAreOrderedDeterministicallyByName() throws Exception {
        touch("3/MR/NIFTI/c.nii");
        touch("1/MR/NIFTI/a.nii");
        touch("2/MR/NIFTI/b.nii");
        touch("1/MR/DICOM/d.dcm");

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        final List<String> order = new ArrayList<>();
        for (final ScanResource resource : result.keySet()) {
            order.add(resource.getScanId() + "/" + resource.getModality() + "/" + resource.getName());
        }
        // Sorted by scan, then modality, then resource name.
        assertThat(order, contains("1/MR/DICOM", "1/MR/NIFTI", "2/MR/NIFTI", "3/MR/NIFTI"));
    }

    @Test
    public void treatsWholeResourceDirectoryAsContent() throws Exception {
        touch("1/MR/NIFTI/sub/deep.nii");

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result.size(), is(1));
        // The resource value is the resource directory itself, not its individual files.
        assertThat(result.values().iterator().next().get(0), equalTo(root.resolve("1/MR/NIFTI")));
    }

    @Test
    public void emptyArchiveYieldsNoResources() {
        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.entrySet(), is(empty()));
    }

    @Test
    public void scanDirectoryWithoutResourceLevelYieldsNoResources() throws Exception {
        // Only scan/modality levels exist; no resource directory underneath.
        Files.createDirectories(root.resolve("1/MR"));
        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.entrySet(), is(empty()));
    }

    @Test
    public void returnsResultRatherThanThrowingOnNonexistentRoot() {
        // The service logs and returns whatever it has gathered on IO errors.
        final Map<ScanResource, List<Path>> result = service.extractResource(root.resolve("missing"), user, "PROJ");
        assertThat(result, is(notNullValue()));
        assertThat(result.entrySet(), is(empty()));
    }

    private void touch(final String relative) throws IOException {
        final Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[0]);
    }
}
