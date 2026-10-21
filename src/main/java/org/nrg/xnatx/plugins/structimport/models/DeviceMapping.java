package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * An acquisition device and the mapping CSV that says which columns of a CSV
 * import manifest hold its metadata and which XNAT properties they fill. When
 * an upload names a device, the importer reads the manifest with the device's
 * mapping instead of the project or site-wide column mappings.
 *
 * <p>Devices are stored at the site level and may be added or overridden per
 * project. {@code scope} and {@code overridesSite} describe where an effective
 * device came from; they are set in responses only and never stored.</p>
 */
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceMapping {

    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_DRAFT    = "draft";

    private String       id;
    private String       name;
    private String       category;
    private String       manufacturer;
    private String       model;
    private String       modality;
    private String       xsiType;
    private String       metadataSource;
    private List<String> nativeFormats;
    private String       status;
    private String       notes;
    private String       csv;

    /** {@code site} or {@code project}: the scope the device is stored at. Response only. */
    private String  scope;
    /** Whether a project-level device replaces a site device with the same ID. Response only. */
    private Boolean overridesSite;
}
