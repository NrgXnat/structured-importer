package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.Test;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class CsvManifestTemplateTest {

    @Test
    public void headerRowListsColumnsInConfigurationOrder() {
        final String template = CsvManifestTemplate.build(Arrays.asList(
                mapping("Scan ID", "xnat:imageScanData/ID"),
                mapping("Modality", "xnat:imageScanData/modality"),
                mapping("Path", "")));

        assertThat(template, equalTo("Scan ID,Modality,Path\r\n"));
    }

    @Test
    public void columnsWithCommasAndQuotesRoundTripThroughTheManifestParser() throws Exception {
        final List<String> columns  = Arrays.asList("Subject Weight (g)", "Dose, mCi", "Coil \"type\"", "Path");
        final List<CsvColumnMapping> mappings = new ArrayList<>();
        for (final String column : columns) {
            mappings.add(mapping(column, column.equals("Path") ? "" : "xnat:imageScanData/note"));
        }

        final String template = CsvManifestTemplate.build(mappings);

        // read the header back the way CsvBasedResourceIdentifierService does
        try (final CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                                                       .setIgnoreSurroundingSpaces(true).setTrim(true).build()
                                                       .parse(new StringReader(template))) {
            assertThat(parser.getHeaderNames(), equalTo(columns));
        }
    }

    @Test
    public void siteFilenameEndsWithTimestamp() {
        assertThat(CsvManifestTemplate.filename(null, LocalDateTime.of(2026, 10, 8, 10, 31, 33)),
                   equalTo("struct-import-template-20261008_103133.csv"));
    }

    @Test
    public void projectFilenamePutsProjectIdBeforeTimestamp() {
        assertThat(CsvManifestTemplate.filename("PROJECT", LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
                   equalTo("struct-import-template-PROJECT-20260102_030405.csv"));
    }

    private static CsvColumnMapping mapping(final String column, final String property) {
        return CsvColumnMapping.builder().column(column).property(property).build();
    }
}
