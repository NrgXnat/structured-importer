package org.nrg.xnatx.plugins.structimport.services;

import lombok.Value;
import org.nrg.xft.security.UserI;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
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
     * {@code subjectLabel} and {@code sessionLabel} against upload parameters;
     * when both are present, the upload parameters win.
     * <p>
     * {@code customProperties} maps arbitrary XNAT property paths (as configured
     * in the column mappings) to their raw manifest values. It is never
     * {@code null} and participates in equality, so rows aggregate into the same
     * resource only when all custom values agree.
     */
    @Value
    class ScanResource {
        String              subjectLabel;
        String              sessionLabel;
        String              scanId;
        String              modality;
        String              name;
        String              seriesDescription;
        LocalDate           startDate;
        LocalTime           startTime;
        Double              subjectWeight;
        Map<String, String> customProperties;

        public ScanResource(final String subjectLabel, final String sessionLabel, final String scanId,
                            final String modality, final String name, final String seriesDescription,
                            final LocalDate startDate, final LocalTime startTime, final Double subjectWeight) {
            this(subjectLabel, sessionLabel, scanId, modality, name, seriesDescription, startDate, startTime, subjectWeight, null);
        }

        public ScanResource(final String subjectLabel, final String sessionLabel, final String scanId,
                            final String modality, final String name, final String seriesDescription,
                            final LocalDate startDate, final LocalTime startTime, final Double subjectWeight,
                            final Map<String, String> customProperties) {
            this.subjectLabel      = subjectLabel;
            this.sessionLabel      = sessionLabel;
            this.scanId            = scanId;
            this.modality          = modality;
            this.name              = name;
            this.seriesDescription = seriesDescription;
            this.startDate         = startDate;
            this.startTime         = startTime;
            this.subjectWeight     = subjectWeight;
            this.customProperties  = customProperties == null || customProperties.isEmpty()
                                     ? Collections.emptyMap()
                                     : Collections.unmodifiableMap(new LinkedHashMap<>(customProperties));
        }
    }
}
