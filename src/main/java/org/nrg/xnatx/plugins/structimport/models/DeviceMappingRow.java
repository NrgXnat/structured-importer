package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a device mapping CSV: the device parameter a value comes from,
 * the manifest column that carries it, and the XNAT property it fills. A blank
 * {@code xnatProperty} marks the manifest's path column.
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceMappingRow {

    private String  sourceParameter;
    private String  sourceSection;
    private String  xnatColumn;
    private String  xnatProperty;
    private boolean required;
    private String  validation;
    private String  transform;
    private String  notes;
}
