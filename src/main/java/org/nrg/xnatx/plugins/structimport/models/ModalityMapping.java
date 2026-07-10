package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * Maps a modality code (e.g. {@code MR}, {@code US}) to the XNAT data types the
 * structured importer instantiates for it.
 *
 * <ul>
 *     <li>{@code modality} - the modality code; matches the value of the modality
 *         column in a CSV manifest and the primary-modality upload parameter.</li>
 *     <li>{@code scan} - the data type used for scans of this modality (e.g.
 *         {@code xnat:mrScanData}). May be absent for modalities that only have a
 *         session type.</li>
 *     <li>{@code session} - the data type used for sessions of this modality (e.g.
 *         {@code xnat:mrSessionData}). May be absent for scan-only modalities.</li>
 *     <li>{@code group} - display precedence: group 0 modalities are the most
 *         commonly used and sort first, group 3 the most rarely used. Defaults to
 *         {@value #DEFAULT_GROUP} when not specified.</li>
 * </ul>
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModalityMapping {

    public static final int DEFAULT_GROUP = 3;

    private String  modality;
    private String  scan;
    private String  session;
    private Integer group;

    /**
     * @return the display group, defaulting to {@value #DEFAULT_GROUP} when not specified
     */
    @JsonIgnore
    public int getGroupOrDefault() {
        return group == null ? DEFAULT_GROUP : group;
    }

    @JsonIgnore
    public boolean hasScanDataType() {
        return StringUtils.isNotBlank(scan);
    }

    @JsonIgnore
    public boolean hasSessionDataType() {
        return StringUtils.isNotBlank(session);
    }
}
