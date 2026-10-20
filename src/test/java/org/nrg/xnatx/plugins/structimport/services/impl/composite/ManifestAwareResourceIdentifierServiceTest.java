package org.nrg.xnatx.plugins.structimport.services.impl.composite;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ManifestAwareResourceIdentifierServiceTest {

    private CsvBasedResourceIdentifierService      csvService;
    private SimpleResourceIdentifierService        simpleService;
    private ManifestAwareResourceIdentifierService service;
    private UserI                                  user;
    private Path                                   root;

    private final Map<ScanResource, List<Path>> csvResult    = Collections.emptyMap();
    private final Map<ScanResource, List<Path>> simpleResult = Collections.emptyMap();

    @Before
    public void setUp() throws IOException {
        csvService    = mock(CsvBasedResourceIdentifierService.class);
        simpleService = mock(SimpleResourceIdentifierService.class);
        service       = new ManifestAwareResourceIdentifierService(csvService, simpleService);
        user          = mock(UserI.class);
        root          = Files.createTempDirectory("manifest-aware-test");
        when(csvService.extractResource(any(Path.class), any(UserI.class), anyString())).thenReturn(csvResult);
        when(simpleService.extractResource(any(Path.class), any(UserI.class), anyString())).thenReturn(simpleResult);
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
    public void dispatchesToCsvServiceWhenManifestPresent() throws IOException {
        Files.write(root.resolve("manifest.csv"), "Path\na".getBytes(StandardCharsets.UTF_8));

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result, sameInstance(csvResult));
        verify(csvService).extractResource(root, user, "PROJ");
        verify(simpleService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }

    @Test
    public void dispatchesToSimpleServiceWhenNoManifest() throws IOException {
        Files.createDirectories(root.resolve("1/MR/NIFTI"));

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result, sameInstance(simpleResult));
        verify(simpleService).extractResource(root, user, "PROJ");
        verify(csvService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }

    @Test
    public void csvInSingleTopLevelFolderCountsAsManifest() throws IOException {
        Files.createDirectories(root.resolve("wrapper/1"));
        Files.write(root.resolve("wrapper/manifest.csv"), "Path\n1".getBytes(StandardCharsets.UTF_8));

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result, sameInstance(csvResult));
        verify(csvService).extractResource(root, user, "PROJ");
        verify(simpleService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }

    @Test
    public void csvInSingleTopLevelFolderBesideMacClutterCountsAsManifest() throws IOException {
        Files.createDirectories(root.resolve("wrapper/1"));
        Files.createDirectories(root.resolve("__MACOSX/wrapper"));
        Files.write(root.resolve("__MACOSX/wrapper/._manifest.csv"), new byte[0]);
        Files.write(root.resolve(".DS_Store"), new byte[0]);
        Files.write(root.resolve("wrapper/manifest.csv"), "Path\n1".getBytes(StandardCharsets.UTF_8));

        service.extractResource(root, user, "PROJ");

        verify(csvService).extractResource(root, user, "PROJ");
        verify(simpleService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }

    @Test
    public void csvInsideSingleScanDirectoryLayoutDoesNotCountAsManifest() throws IOException {
        // a one-scan, one-modality, one-resource layout is a chain of single folders; a data CSV in the
        // resource folder must not be mistaken for a manifest
        Files.createDirectories(root.resolve("1/MR/NIFTI"));
        Files.createDirectories(root.resolve("__MACOSX"));
        Files.write(root.resolve("1/MR/NIFTI/img.nii"), new byte[0]);
        Files.write(root.resolve("1/MR/NIFTI/physio.csv"), "time,value\n0,1".getBytes(StandardCharsets.UTF_8));

        service.extractResource(root, user, "PROJ");

        verify(simpleService).extractResource(root, user, "PROJ");
        verify(csvService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }

    @Test
    public void csvInSubdirectoryAlongsideOtherEntriesDoesNotCountAsManifest() throws IOException {
        Files.createDirectories(root.resolve("1/MR/NIFTI"));
        Files.createDirectories(root.resolve("2/MR/NIFTI"));
        Files.write(root.resolve("1/notes.csv"), "Path\na".getBytes(StandardCharsets.UTF_8));

        service.extractResource(root, user, "PROJ");

        verify(simpleService).extractResource(root, user, "PROJ");
        verify(csvService, never()).extractResource(any(Path.class), any(UserI.class), anyString());
    }
}
