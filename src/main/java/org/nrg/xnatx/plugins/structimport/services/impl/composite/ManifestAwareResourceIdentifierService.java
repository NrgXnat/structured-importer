package org.nrg.xnatx.plugins.structimport.services.impl.composite;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dispatches to the CSV-manifest service when the extracted archive contains a
 * manifest (see {@link CsvBasedResourceIdentifierService#findManifestDirectory}),
 * and to the directory-walking simple service otherwise.
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
        return extractResource(extractedArchive, user, projectId, null);
    }

    /**
     * A device's mapping only applies to a CSV manifest, so when the upload names a device the archive
     * must contain one: falling back to the directory layout would silently ignore the device.
     */
    @Override
    public Map<ScanResource, List<Path>> extractResource(final Path extractedArchive, final UserI user, final String projectId, final String deviceId) {
        if (CsvBasedResourceIdentifierService.findManifestDirectory(extractedArchive).isPresent()) {
            log.info("Found a CSV manifest under {}; using the CSV resource identifier service", extractedArchive);
            return csvService.extractResource(extractedArchive, user, projectId, deviceId);
        }
        if (StringUtils.isNotBlank(deviceId)) {
            throw new IllegalStateException("The upload names the device \"" + deviceId + "\", but no CSV manifest (*.csv) was found at the root of the archive or of its single top-level folder. A device's mapping is applied to the manifest.");
        }
        log.info("No CSV manifest at {}; using the directory-walking resource identifier service", extractedArchive);
        return simpleService.extractResource(extractedArchive, user, projectId);
    }
}
