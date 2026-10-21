package org.nrg.xnatx.plugins.structimport.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A device with its mapping CSV parsed: the rows, the column mappings the
 * importer reads manifests with, any problems that stop the device being used
 * for imports, and warnings about rows the importer ignores.
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class DeviceMappingDetail {

    private DeviceMapping          device;
    private List<DeviceMappingRow> rows;
    private List<CsvColumnMapping> columnMappings;
    private List<String>           problems;
    private List<String>           warnings;
}
