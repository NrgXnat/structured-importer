package org.nrg.xnatx.plugins.structimport.services.impl.device;

import lombok.Value;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMappingRow;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyTargets;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads device mapping CSVs and converts them to the column mappings the CSV
 * resource identifier service reads manifests with.
 *
 * <p>A device mapping CSV has one row per manifest column. Headers are matched
 * by name, ignoring case and order. {@code Source Parameter}, {@code XNAT Column}
 * and {@code XNAT Property} are required; {@code Source Section},
 * {@code Required}, {@code Validation}, {@code Transform} and {@code Notes} are
 * optional. Only {@code XNAT Column}, {@code XNAT Property}, {@code Required}
 * and {@code Validation} affect imports; the other columns document where the
 * value comes from on the device.</p>
 */
public final class DeviceMappingCsv {

    public static final String COL_SOURCE_PARAMETER = "Source Parameter";
    public static final String COL_SOURCE_SECTION   = "Source Section";
    public static final String COL_XNAT_COLUMN      = "XNAT Column";
    public static final String COL_XNAT_PROPERTY    = "XNAT Property";
    public static final String COL_REQUIRED         = "Required";
    public static final String COL_VALIDATION       = "Validation";
    public static final String COL_TRANSFORM        = "Transform";
    public static final String COL_NOTES            = "Notes";

    private static final List<String> REQUIRED_HEADERS = Arrays.asList(COL_SOURCE_PARAMETER, COL_XNAT_COLUMN, COL_XNAT_PROPERTY);
    private static final Pattern      PROPERTY_PATH    = Pattern.compile("^[A-Za-z][\\w-]*:[A-Za-z]\\w*(/.*)?$");

    // Device CSVs use XFT's field name for the scan start time; the importer's built-in property differs
    private static final Map<String, String> PROPERTY_ALIASES = Collections.singletonMap("xnat:imagescandata/starttime", CsvImportConfigService.PROP_START_TIME);

    private DeviceMappingCsv() {
    }

    /**
     * Parses a device mapping CSV.
     *
     * @param csv the CSV text, which may start with a byte-order mark
     *
     * @return the rows, and the problems that make the file unusable (empty when it is valid)
     */
    public static Parsed parse(final String csv) {
        final List<DeviceMappingRow> rows     = new ArrayList<>();
        final List<String>           problems = new ArrayList<>();
        if (StringUtils.isBlank(csv)) {
            problems.add("The mapping CSV is empty.");
            return new Parsed(rows, problems);
        }
        try (final CSVParser parser = CSVFormat.DEFAULT.builder()
                                                       .setHeader()
                                                       .setSkipHeaderRecord(true)
                                                       .setIgnoreSurroundingSpaces(true)
                                                       .setTrim(true)
                                                       .setIgnoreEmptyLines(true)
                                                       .build()
                                                       .parse(new StringReader(stripBom(csv)))) {
            final Map<String, String> headers = new HashMap<>();
            for (final String header : parser.getHeaderNames()) {
                headers.put(header.trim().toLowerCase(Locale.ROOT), header);
            }
            for (final String required : REQUIRED_HEADERS) {
                if (!headers.containsKey(required.toLowerCase(Locale.ROOT))) {
                    problems.add("The header row has no \"" + required + "\" column.");
                }
            }
            if (!problems.isEmpty()) {
                return new Parsed(rows, problems);
            }
            for (final CSVRecord record : parser) {
                final DeviceMappingRow row = DeviceMappingRow.builder()
                                                             .sourceParameter(cell(record, headers, COL_SOURCE_PARAMETER))
                                                             .sourceSection(cell(record, headers, COL_SOURCE_SECTION))
                                                             .xnatColumn(cell(record, headers, COL_XNAT_COLUMN))
                                                             .xnatProperty(cell(record, headers, COL_XNAT_PROPERTY))
                                                             .required(isYes(cell(record, headers, COL_REQUIRED)))
                                                             .validation(StringUtils.trimToNull(cell(record, headers, COL_VALIDATION)))
                                                             .transform(cell(record, headers, COL_TRANSFORM))
                                                             .notes(cell(record, headers, COL_NOTES))
                                                             .build();
                // the header is line 1, so data record n is on line n + 1 (ignoring quoted line breaks)
                final long line = record.getRecordNumber() + 1;
                if (StringUtils.isBlank(row.getXnatColumn())) {
                    problems.add("Line " + line + ": \"" + COL_XNAT_COLUMN + "\" is empty.");
                }
                if (StringUtils.isNotBlank(row.getXnatProperty()) && !PROPERTY_PATH.matcher(row.getXnatProperty()).matches()) {
                    problems.add("Line " + line + ": \"" + row.getXnatProperty() + "\" is not an XNAT property path. Use the form xnat:imageScanData/frames.");
                }
                if (row.getValidation() != null) {
                    try {
                        Pattern.compile(row.getValidation());
                    } catch (PatternSyntaxException e) {
                        problems.add("Line " + line + ": the validation expression \"" + row.getValidation() + "\" is not a valid regular expression.");
                    }
                }
                rows.add(row);
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException e) {
            problems.add("The mapping CSV could not be read: " + e.getMessage());
            return new Parsed(Collections.emptyList(), problems);
        }
        if (problems.isEmpty() && rows.isEmpty()) {
            problems.add("The mapping CSV has a header row but no mapping rows.");
        }
        return new Parsed(rows, problems);
    }

    /**
     * Converts parsed device mapping rows to column mappings. Rows whose property the importer cannot
     * set, such as resource catalog properties, are left out with a warning: manifests may still carry
     * those columns, and the importer ignores them. A column listed twice with the same property is
     * merged. Problems are reported when the result can't drive an import: a column mapped to two
     * properties, a property filled from two columns, or other than exactly one path column (a row
     * with no XNAT property).
     *
     * @param rows             the parsed rows
     * @param modalityMappings the effective modality mappings, whose data types are accepted as property roots
     *
     * @return the column mappings in row order, with any problems and warnings
     */
    public static Conversion toColumnMappings(final List<DeviceMappingRow> rows, final Collection<ModalityMapping> modalityMappings) {
        final Map<String, CsvColumnMapping> byColumn         = new LinkedHashMap<>();
        final Map<String, String>           columnByProperty = new HashMap<>();
        final List<String>                  problems         = new ArrayList<>();
        final List<String>                  warnings         = new ArrayList<>();
        final List<String>                  pathColumns      = new ArrayList<>();

        for (final DeviceMappingRow row : rows) {
            final String column = StringUtils.trimToEmpty(row.getXnatColumn());
            if (column.isEmpty()) {
                continue;
            }
            final String property = canonicalProperty(row.getXnatProperty());
            if (!property.isEmpty() && !PropertyTargets.isBuiltIn(property)) {
                try {
                    PropertyTargets.targetOf(property, modalityMappings);
                } catch (IllegalArgumentException e) {
                    final String warning = "The column \"" + column + "\" is ignored on import: the structured importer can't set " + property + ".";
                    if (!warnings.contains(warning)) {
                        warnings.add(warning);
                    }
                    continue;
                }
            }

            final CsvColumnMapping existing = byColumn.get(column);
            if (existing != null) {
                if (existing.getProperty().equalsIgnoreCase(property)) {
                    if (row.isRequired()) {
                        existing.setRequired(true);
                    }
                } else {
                    problems.add("The column \"" + column + "\" is mapped to more than one property: "
                                 + describe(existing.getProperty()) + " and " + describe(property) + ".");
                }
                continue;
            }
            if (property.isEmpty()) {
                pathColumns.add(column);
            } else {
                final String other = columnByProperty.putIfAbsent(property.toLowerCase(Locale.ROOT), column);
                if (other != null) {
                    problems.add("The property " + property + " is filled from more than one column: \"" + other + "\" and \"" + column + "\".");
                    continue;
                }
            }
            byColumn.put(column, CsvColumnMapping.builder()
                                                 .column(column)
                                                 .property(property)
                                                 .required(row.isRequired())
                                                 .validation(row.getValidation())
                                                 .build());
        }

        if (pathColumns.isEmpty()) {
            problems.add("No row is the path column. Add a row with an empty \"" + COL_XNAT_PROPERTY + "\", such as \"Path\", for the manifest column that holds each file's location.");
        } else if (pathColumns.size() > 1) {
            problems.add("More than one row has an empty \"" + COL_XNAT_PROPERTY + "\", but only one path column is allowed: \"" + String.join("\", \"", pathColumns) + "\".");
        } else {
            // the importer can't locate a row's files without a path, so it's always required
            byColumn.get(pathColumns.get(0)).setRequired(true);
        }
        return new Conversion(new ArrayList<>(byColumn.values()), problems, warnings);
    }

    static String stripBom(final String text) {
        return text != null && text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static String canonicalProperty(final String property) {
        final String trimmed = StringUtils.trimToEmpty(property);
        return PROPERTY_ALIASES.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private static String describe(final String property) {
        return property.isEmpty() ? "the path column" : property;
    }

    private static String cell(final CSVRecord record, final Map<String, String> headers, final String column) {
        final String header = headers.get(column.toLowerCase(Locale.ROOT));
        return header == null || !record.isSet(header) ? "" : StringUtils.trimToEmpty(record.get(header));
    }

    private static boolean isYes(final String value) {
        return Arrays.asList("yes", "y", "true", "1").contains(StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT));
    }

    @Value
    public static class Parsed {
        List<DeviceMappingRow> rows;
        List<String>           problems;
    }

    @Value
    public static class Conversion {
        List<CsvColumnMapping> columnMappings;
        List<String>           problems;
        List<String>           warnings;
    }
}
