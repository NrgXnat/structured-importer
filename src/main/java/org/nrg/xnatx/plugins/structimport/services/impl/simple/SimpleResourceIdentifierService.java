package org.nrg.xnatx.plugins.structimport.services.impl.simple;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifies scan resources by walking an extracted archive whose top-level
 * structure follows the convention <pre>&lt;scanId&gt;/&lt;modality&gt;/&lt;resourceName&gt;/...</pre>.
 * For example, files under <pre>1/MR/NIFTI/</pre> form the <pre>NIFTI</pre>
 * resource on scan <pre>1</pre> (an <pre>xnat:mrScanData</pre>). Anything inside
 * the resource directory — including subdirectories — is treated as content of
 * that resource.
 */
@Service
@Slf4j
public class SimpleResourceIdentifierService implements ResourceIdentifierService {
    @Override
    public Map<ScanResource, List<Path>> extractResource(final Path extractedArchive, final UserI user, final String projectId) {
        log.info("Identifying scan resources under {}", extractedArchive);
        final Map<ScanResource, List<Path>> resources = new LinkedHashMap<>();
        try {
            for (final Path scanDir : sortedSubdirectories(extractedArchive)) {
                final String scanId = scanDir.getFileName().toString();
                for (final Path modalityDir : sortedSubdirectories(scanDir)) {
                    final String modality = modalityDir.getFileName().toString();
                    for (final Path resourceDir : sortedSubdirectories(modalityDir)) {
                        final String       name     = resourceDir.getFileName().toString();
                        final ScanResource resource = new ScanResource(null, null, scanId, modality, name, null, null, null, null);
                        log.debug("Identified scan resource {} at {}", resource, resourceDir);
                        resources.put(resource, Collections.singletonList(resourceDir));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error walking extracted archive {}", extractedArchive, e);
        }
        return resources;
    }

    /**
     * Lists the immediate subdirectories of {@code directory}, sorted by name, so
     * the resulting scan/modality/resource ordering is deterministic regardless
     * of the order the filesystem reports directory entries in.
     */
    private static List<Path> sortedSubdirectories(final Path directory) throws IOException {
        final List<Path> subdirectories = new ArrayList<>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(directory, Files::isDirectory)) {
            stream.forEach(subdirectories::add);
        }
        subdirectories.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return subdirectories;
    }
}
