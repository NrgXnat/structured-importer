package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Associates a human-readable display value with an XFT object property path,
 * e.g. "Scan ID" &rarr; {@code xnat:imageScanData/ID}. These mappings populate
 * the Property drop-down in the CSV column-mapping editors and are maintained
 * site-wide: a mapping added while configuring one project is available to
 * every project.
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PropertyDisplayMapping {

    private String display;
    private String property;
}
