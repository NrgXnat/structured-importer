package org.nrg.xnatx.plugins.structimport.services;

import lombok.Value;
import org.nrg.xft.security.UserI;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public interface ResourceIdentifierService {
    /**
     * Identifies the scan resources within an extracted archive.
     *
     * @param extractedArchive the root of the extracted archive
     * @param user             the user performing the import; implementations may use this to resolve configuration
     * @param projectId        the project the import targets; implementations may use this to resolve project-level configuration
     * @return a map of each identified resource to the source paths that make it up
     */
    Map<ScanResource, List<Path>> extractResource(final Path extractedArchive, final UserI user, final String projectId);

    /**
     * Identifies one resource and its destination context within an XNAT archive.
     * <p>
     * All fields other than {@code scanId}, {@code modality}, and {@code name}
     * may be {@code null} when an implementation cannot determine them from the
     * archive alone (e.g. the directory-walking
     * {@code SimpleResourceIdentifierService}). The importer resolves
     * {@code subjectLabel} and {@code sessionLabel} against upload parameters
     * and rejects conflicts.
     */
    @Value
    class ScanResource {
        String    subjectLabel;
        String    sessionLabel;
        String    scanId;
        String    modality;
        String    name;
        String    seriesDescription;
        LocalDate startDate;
        LocalTime startTime;
        Double    subjectWeight;
    }
}
