package org.nrg.xnatx.plugins.structimport.services.impl.composite;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dispatches to the CSV-manifest service when the extracted archive contains a
 * manifest at its root, and to the directory-walking simple service otherwise.
 * Used for manual (Customize) labeling, where the upload parameters supply the
 * subject and session labels but any CSV manifest should still drive scan
 * metadata. Manifest presence can only be checked after extraction, which is
 * why this dispatch cannot happen in the selector that runs when the importer
 * is constructed.
 */
@Service("manualResourceIdentifierService")
@Slf4j
public class ManifestAwareResourceIdentifierService implements ResourceIdentifierService {

    private final CsvBasedResourceIdentifierService csvService;
    private final SimpleResourceIdentifierService   simpleService;

    @Autowired
    public ManifestAwareResourceIdentifierService(final CsvBasedResourceIdentifierService csvService,
                                                  final SimpleResourceIdentifierService simpleService) {
        this.csvService    = csvService;
        this.simpleService = simpleService;
    }

    @Override
    public Map<ScanResource, List<Path>> extractResource(final Path extractedArchive, final UserI user, final String projectId) {
        if (hasManifest(extractedArchive)) {
            log.info("Found a CSV manifest at {}; using the CSV resource identifier service", extractedArchive);
            return csvService.extractResource(extractedArchive, user, projectId);
        }
        log.info("No CSV manifest at {}; using the directory-walking resource identifier service", extractedArchive);
        return simpleService.extractResource(extractedArchive, user, projectId);
    }

    private static boolean hasManifest(final Path root) {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.csv")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan archive root " + root + " for CSV manifest", e);
        }
    }
}
