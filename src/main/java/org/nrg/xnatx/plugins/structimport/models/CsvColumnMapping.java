package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maps a column in a CSV import manifest to an XNAT object property.
 *
 * <ul>
 *     <li>{@code column} - the header text of the column in the CSV file.</li>
 *     <li>{@code property} - the XNAT XML path of the property the column populates
 *         (e.g. {@code xnat:imageScanData/ID}). A blank property identifies the
 *         special "path" column, which locates a file or directory within the
 *         archive rather than mapping to an XNAT object property.</li>
 *     <li>{@code required} - whether the column (and a value for it) is required.
 *         Defaults to {@code true} when not specified. Required columns must be
 *         present in the manifest and have a non-blank value; optional columns may
 *         be omitted or, if present, left blank (subject to {@code validation}).</li>
 *     <li>{@code validation} - an optional regular expression each non-blank value
 *         must match.</li>
 * </ul>
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CsvColumnMapping {

    private String  column;
    private String  property;
    private Boolean required;
    private String  validation;

    /**
     * Whether this column is required. When {@code required} is not specified it
     * defaults to {@code true}.
     *
     * @return {@code true} if the column is required, {@code false} otherwise.
     */
    @JsonIgnore
    public boolean isRequiredColumn() {
        return required == null || required;
    }
}
