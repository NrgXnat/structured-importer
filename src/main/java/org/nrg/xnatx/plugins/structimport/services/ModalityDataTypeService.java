package org.nrg.xnatx.plugins.structimport.services;

import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides the mapping between modality codes and the XNAT data types the
 * structured importer creates for sessions and scans of those modalities. Not
 * every modality defines both: some are scan-only (e.g. secondary captures) and
 * some are session-only. Each modality also carries a display {@code group}
 * from 0 (most common, sorts first) to 3 (rarely used).
 */
public interface ModalityDataTypeService {

    /**
     * The full modality configuration: the defaults merged with any overrides
     * and additions contributed on the classpath.
     *
     * @return the mappings keyed by modality code, ordered by group and then modality
     */
    Map<String, ModalityMapping> getModalityMappings();

    /**
     * Looks up a single modality's mapping. Matching is case-insensitive.
     *
     * @param modality the modality code, e.g. {@code MR}
     *
     * @return the mapping, or empty if the modality is not configured
     */
    Optional<ModalityMapping> getModalityMapping(String modality);

    /**
     * The modalities that can head an image session, i.e. those with a
     * configured session data type.
     *
     * @return the session-capable mappings, ordered by group and then modality
     */
    List<ModalityMapping> getSessionModalities();
}
