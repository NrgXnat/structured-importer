package org.nrg.xnatx.plugins.structimport.importer;

import org.nrg.action.ClientException;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Groups one session's resources by scan ID, so the importer creates each scan
 * once and adds every resource to it. A scan's data type comes from its
 * modality and its metadata is set once, so resources of the same scan must
 * agree on both; otherwise one resource's values would silently replace
 * another's (e.g. the directory layout {@code 1/MR/NIFTI} next to
 * {@code 1/CT/DICOM}). Runs before anything is saved, so a conflict leaves no
 * partial session behind.
 */
final class ScanGrouper {

    private ScanGrouper() {
    }

    static Map<String, Map<ScanResource, List<Path>>> group(final String sessionLabel, final Map<ScanResource, List<Path>> resources) throws ClientException {
        final Map<String, Map<ScanResource, List<Path>>> scans = new LinkedHashMap<>();
        for (final Map.Entry<ScanResource, List<Path>> entry : resources.entrySet()) {
            final ScanResource                  resource = entry.getKey();
            final Map<ScanResource, List<Path>> scan     = scans.computeIfAbsent(resource.getScanId(), k -> new LinkedHashMap<>());
            if (!scan.isEmpty()) {
                validateSameScan(sessionLabel, scan.keySet().iterator().next(), resource);
            }
            scan.put(resource, entry.getValue());
        }
        return scans;
    }

    private static void validateSameScan(final String sessionLabel, final ScanResource first, final ScanResource other) throws ClientException {
        final String scan = "scan " + first.getScanId() + " of session " + sessionLabel;
        if (!Objects.equals(first.getModality(), other.getModality())) {
            throw new ClientException("The resources " + first.getName() + " and " + other.getName() + " of " + scan
                                      + " specify conflicting modalities \"" + first.getModality() + "\" and \"" + other.getModality() + "\"; a scan can only have one modality");
        }
        if (!Objects.equals(first.getSeriesDescription(), other.getSeriesDescription())
            || !Objects.equals(first.getStartDate(), other.getStartDate())
            || !Objects.equals(first.getStartTime(), other.getStartTime())
            || !Objects.equals(first.getCustomProperties(), other.getCustomProperties())) {
            throw new ClientException("The resources " + first.getName() + " and " + other.getName() + " of " + scan + " specify conflicting scan metadata");
        }
    }
}
