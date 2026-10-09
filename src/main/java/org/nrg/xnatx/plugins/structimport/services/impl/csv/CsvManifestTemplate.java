package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a blank CSV manifest whose header row holds the configured column
 * names, in configuration order, for users to fill in before an import. Uses
 * the same commons-csv format family the {@link CsvBasedResourceIdentifierService}
 * reads, so column names containing commas or quotes round-trip.
 */
public final class CsvManifestTemplate {

    private static final String            NAME_PREFIX      = "struct-import-template";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private CsvManifestTemplate() {
    }

    /**
     * Names a downloaded template, e.g. {@code struct-import-template-20261008_103133.csv} for the
     * site or {@code struct-import-template-PROJECT-20261008_103133.csv} for a project.
     *
     * @param projectId the project the template is for, or {@code null} for the site-wide template
     * @param timestamp when the template was generated
     */
    public static String filename(final String projectId, final LocalDateTime timestamp) {
        return NAME_PREFIX + (projectId == null ? "" : "-" + projectId) + "-" + TIMESTAMP_FORMAT.format(timestamp) + ".csv";
    }

    public static String build(final List<CsvColumnMapping> mappings) {
        final StringWriter writer = new StringWriter();
        try (final CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord(mappings.stream().map(CsvColumnMapping::getColumn).collect(Collectors.toList()));
        } catch (IOException e) {
            // StringWriter doesn't throw
            throw new IllegalStateException("Unable to write the CSV manifest template", e);
        }
        return writer.toString();
    }
}
