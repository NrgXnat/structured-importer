package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyTargets;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Identifies scan resources from a CSV manifest at the root of the extracted
 * archive. Each row identifies a source path (file or directory) and the scan
 * context it belongs to. Multiple rows aggregate to the same resource when
 * subject, session, scan, modality, and resource name all match.
 *
 * <p>The mapping between CSV columns and XNAT object properties is supplied by
 * the {@link CsvImportConfigService}, resolved against the target project
 * (falling back to the site-wide configuration). The {@code property} value of
 * each mapping identifies which piece of metadata the column supplies; see the
 * {@code PROP_*} constants on {@link CsvImportConfigService}.</p>
 */
@Service("csvBasedResourceIdentifierService")
@Slf4j
public class CsvBasedResourceIdentifierService implements ResourceIdentifierService {

    static final String DEFAULT_RESOURCE_NAME = "NIFTI";

    private static final List<DateTimeFormatter> DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private static final List<DateTimeFormatter> TIME_FORMATS = Arrays.asList(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.US),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mma").toFormatter(Locale.US),
            DateTimeFormatter.ISO_LOCAL_TIME);

    private final CsvImportConfigService  configService;
    private final ModalityDataTypeService modalityDataTypeService;

    @Autowired
    public CsvBasedResourceIdentifierService(final CsvImportConfigService configService, final ModalityDataTypeService modalityDataTypeService) {
        this.configService           = configService;
        this.modalityDataTypeService = modalityDataTypeService;
    }

    @Override
    public Map<ScanResource, List<Path>> extractResource(final Path extractedArchive, final UserI user, final String projectId) {
        log.info("Identifying scan resources from CSV manifest under {}", extractedArchive);
        final List<CsvColumnMapping> mappings = configService.getColumnMappings(user, projectId);
        final MappingContext         context  = new MappingContext(mappings, modalityDataTypeService.getModalityMappings().values());
        final Path                      csv      = findManifest(extractedArchive);
        try (final Reader reader = Files.newBufferedReader(csv);
             final CSVParser parser = CSVFormat.DEFAULT
                     .builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreSurroundingSpaces(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            context.validateHeader(parser.getHeaderMap().keySet());
            return parseRows(parser, extractedArchive, context);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read CSV manifest at " + csv, e);
        }
    }

    private Path findManifest(final Path root) {
        final List<Path> csvs = new ArrayList<>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.csv")) {
            stream.forEach(csvs::add);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan archive root " + root + " for CSV manifest", e);
        }
        if (csvs.isEmpty()) {
            throw new IllegalStateException("No CSV manifest (*.csv) found at archive root " + root);
        }
        if (csvs.size() > 1) {
            throw new IllegalStateException("Multiple CSV files found at archive root " + root + "; expected exactly one: " + csvs);
        }
        return csvs.get(0);
    }

    private Map<ScanResource, List<Path>> parseRows(final CSVParser parser, final Path root, final MappingContext context) {
        final Set<String>                   header    = parser.getHeaderMap().keySet();
        final Map<ScanResource, List<Path>> resources = new LinkedHashMap<>();

        for (final CSVRecord record : parser) {
            context.validateRow(record, header);

            final String subjectLabel      = context.value(record, header, CsvImportConfigService.PROP_SUBJECT_ID);
            final String sessionLabel      = context.value(record, header, CsvImportConfigService.PROP_SESSION_LABEL);
            final String scanId            = context.value(record, header, CsvImportConfigService.PROP_SCAN_ID);
            final String modality          = context.value(record, header, CsvImportConfigService.PROP_MODALITY);
            validateModality(modality, record.getRecordNumber());
            final String seriesDescription = context.value(record, header, CsvImportConfigService.PROP_SERIES_DESCRIPTION);
            final String resourceValue     = context.value(record, header, CsvImportConfigService.PROP_RESOURCE_NAME);
            final String name              = StringUtils.isNotBlank(resourceValue) ? resourceValue : DEFAULT_RESOURCE_NAME;

            final LocalDate startDate     = parseDate(record, context.column(CsvImportConfigService.PROP_START_DATE), header);
            final LocalTime startTime     = parseTime(record, context.column(CsvImportConfigService.PROP_START_TIME), header);
            final Double    subjectWeight = parseDouble(record, context.column(CsvImportConfigService.PROP_SUBJECT_WEIGHT), header);

            final String pathValue  = required(record, context.getPathColumn());
            final Path   sourcePath = resolveAndValidatePath(root, pathValue, record.getRecordNumber());

            final Map<String, String> customProperties = new LinkedHashMap<>();
            for (final Map.Entry<String, String> custom : context.getCustomColumnsByProperty().entrySet()) {
                final String value = valueOrNull(record, custom.getValue(), header);
                if (value != null) {
                    customProperties.put(custom.getKey(), value);
                }
            }

            final ScanResource resource = new ScanResource(subjectLabel, sessionLabel, scanId, modality, name,
                                                           seriesDescription, startDate, startTime, subjectWeight,
                                                           customProperties);
            resources.computeIfAbsent(resource, k -> new ArrayList<>()).add(sourcePath);
        }

        validateConsistency(resources.keySet(), context);
        return resources;
    }

    /**
     * Each row's modality determines the data type of the scan the importer
     * creates, so it must be configured with a scan data type in the
     * {@link ModalityDataTypeService}.
     */
    private void validateModality(final String modality, final long rowNumber) {
        if (modality == null) {
            return;
        }
        final Optional<ModalityMapping> mapping = modalityDataTypeService.getModalityMapping(modality);
        if (!mapping.isPresent()) {
            throw new IllegalStateException("CSV row " + rowNumber + " specifies the modality \"" + modality + "\", which is not configured for the structured importer");
        }
        if (!mapping.get().hasScanDataType()) {
            throw new IllegalStateException("CSV row " + rowNumber + " specifies the modality \"" + modality + "\", which has no scan data type configured");
        }
    }

    private static String required(final CSVRecord record, final String column) {
        final String value = record.get(column);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("CSV row " + record.getRecordNumber() + " has a blank value for required column \"" + column + "\"");
        }
        return value;
    }

    private static LocalDate parseDate(final CSVRecord record, final String column, final Set<String> header) {
        final String value = valueOrNull(record, column, header);
        if (value == null) {
            return null;
        }
        for (final DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next supported format
            }
        }
        throw new IllegalStateException("CSV row " + record.getRecordNumber() + " has an invalid date \"" + value + "\" in column \"" + column + "\" (expected MM/dd/yyyy or ISO-8601 yyyy-MM-dd)");
    }

    private static LocalTime parseTime(final CSVRecord record, final String column, final Set<String> header) {
        final String value = valueOrNull(record, column, header);
        if (value == null) {
            return null;
        }
        for (final DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalTime.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next supported format
            }
        }
        throw new IllegalStateException("CSV row " + record.getRecordNumber() + " has an invalid time \"" + value + "\" in column \"" + column + "\" (expected h:mm a or ISO-8601 HH:mm[:ss])");
    }

    private static Double parseDouble(final CSVRecord record, final String column, final Set<String> header) {
        final String value = valueOrNull(record, column, header);
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("CSV row " + record.getRecordNumber() + " has an invalid number \"" + value + "\" in column \"" + column + "\"", e);
        }
    }

    private static String valueOrNull(final CSVRecord record, final String column, final Set<String> header) {
        if (column == null || !header.contains(column)) {
            return null;
        }
        final String value = record.get(column);
        return StringUtils.isBlank(value) ? null : value;
    }

    private static Path resolveAndValidatePath(final Path root, final String csvPath, final long rowNumber) {
        if (csvPath.startsWith("/") || csvPath.startsWith("\\")) {
            throw new IllegalStateException("CSV row " + rowNumber + " has an absolute path \"" + csvPath + "\"; paths must be relative to the archive root");
        }
        final Path resolved   = root.resolve(csvPath).normalize();
        final Path normalRoot = root.normalize();
        if (!resolved.startsWith(normalRoot)) {
            throw new IllegalStateException("CSV row " + rowNumber + " path \"" + csvPath + "\" escapes the archive root");
        }
        if (!Files.exists(resolved)) {
            throw new IllegalStateException("CSV row " + rowNumber + " path \"" + csvPath + "\" does not exist in the archive");
        }
        return resolved;
    }

    /**
     * Across all rows that share a subject, subject-level values (weight and any
     * SUBJECT-target custom properties) must agree; session-level custom
     * properties must likewise agree across all rows sharing a subject and
     * session. Per-scan fields are already keyed into the {@code ScanResource},
     * so the map-key equality enforces agreement at aggregation time — but
     * distinct keys whose subject- or session-level fields disagree indicate a
     * manifest error.
     */
    private static void validateConsistency(final Iterable<ScanResource> resources, final MappingContext context) {
        final String                    weightColumn     = context.column(CsvImportConfigService.PROP_SUBJECT_WEIGHT);
        final Map<String, Double>       weightsBySubject = new LinkedHashMap<>();
        final Map<List<String>, String> customByTarget   = new LinkedHashMap<>();
        for (final ScanResource resource : resources) {
            final String subject = resource.getSubjectLabel();
            final Double weight  = resource.getSubjectWeight();
            if (weight != null) {
                final Double existing = weightsBySubject.putIfAbsent(subject, weight);
                if (existing != null && !Objects.equals(existing, weight)) {
                    throw new IllegalStateException("CSV manifest has inconsistent " + StringUtils.defaultIfBlank(weightColumn, "subject weight") + " values for subject \"" + subject + "\": " + existing + " vs " + weight);
                }
            }
            for (final Map.Entry<String, String> entry : resource.getCustomProperties().entrySet()) {
                final String                     property = entry.getKey();
                final PropertyTargets.TargetType target   = context.targetOf(property);
                if (target == PropertyTargets.TargetType.SCAN) {
                    continue;
                }
                final List<String> key = target == PropertyTargets.TargetType.SUBJECT
                                         ? Arrays.asList(property, subject)
                                         : Arrays.asList(property, subject, resource.getSessionLabel());
                final String existing = customByTarget.putIfAbsent(key, entry.getValue());
                if (existing != null && !existing.equals(entry.getValue())) {
                    final String column = context.getCustomColumnsByProperty().get(property);
                    throw new IllegalStateException("CSV manifest has inconsistent values in column \"" + column + "\" (" + property + ") for subject \"" + subject + "\""
                                                    + (target == PropertyTargets.TargetType.SESSION ? " session \"" + resource.getSessionLabel() + "\"" : "")
                                                    + ": \"" + existing + "\" vs \"" + entry.getValue() + "\"");
                }
            }
        }
    }

    /**
     * Resolves a set of column mappings into the lookups needed while parsing a
     * single manifest: column-by-property, the special path column, and the
     * compiled validation patterns.
     */
    private static final class MappingContext {

        private final List<CsvColumnMapping>       mappings;
        private final Collection<ModalityMapping>  modalityMappings;
        private final Map<String, String>          columnByProperty       = new LinkedHashMap<>();
        private final Map<String, String>          customColumnByProperty = new LinkedHashMap<>();
        private final Map<String, Pattern>         patternByColumn        = new LinkedHashMap<>();
        private final String                       pathColumn;

        private MappingContext(final List<CsvColumnMapping> mappings, final Collection<ModalityMapping> modalityMappings) {
            if (mappings == null || mappings.isEmpty()) {
                throw new IllegalStateException("No CSV import column mappings are configured");
            }
            this.mappings         = mappings;
            this.modalityMappings = modalityMappings;

            final Set<String> seenProperties     = new LinkedHashSet<>();
            String            resolvedPathColumn = null;
            for (final CsvColumnMapping mapping : mappings) {
                final String column = mapping.getColumn();
                if (StringUtils.isBlank(column)) {
                    throw new IllegalStateException("CSV import configuration has a mapping with no column name");
                }
                if (StringUtils.isBlank(mapping.getProperty())) {
                    if (resolvedPathColumn != null) {
                        throw new IllegalStateException("CSV import configuration defines more than one path column (a mapping with no property): \"" + resolvedPathColumn + "\" and \"" + column + "\"");
                    }
                    resolvedPathColumn = column;
                } else {
                    final String property   = mapping.getProperty();
                    final String normalized = property.toLowerCase(Locale.ROOT);
                    if (!seenProperties.add(normalized)) {
                        throw new IllegalStateException("CSV import configuration maps the property \"" + property + "\" from more than one column");
                    }
                    if (PropertyTargets.isBuiltIn(property)) {
                        columnByProperty.put(normalized, column);
                    } else {
                        try {
                            PropertyTargets.targetOf(property, modalityMappings);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalStateException("CSV import configuration column \"" + column + "\": " + e.getMessage(), e);
                        }
                        // XFT paths are case-sensitive, so custom properties keep their original case
                        customColumnByProperty.put(property, column);
                    }
                }
                if (StringUtils.isNotBlank(mapping.getValidation())) {
                    try {
                        patternByColumn.put(column, Pattern.compile(mapping.getValidation()));
                    } catch (PatternSyntaxException e) {
                        throw new IllegalStateException("CSV import configuration has an invalid validation expression for column \"" + column + "\": " + mapping.getValidation(), e);
                    }
                }
            }
            if (resolvedPathColumn == null) {
                throw new IllegalStateException("CSV import configuration does not define a path column (a mapping with no property)");
            }
            this.pathColumn = resolvedPathColumn;
        }

        private String getPathColumn() {
            return pathColumn;
        }

        private Map<String, String> getCustomColumnsByProperty() {
            return customColumnByProperty;
        }

        private PropertyTargets.TargetType targetOf(final String property) {
            return PropertyTargets.targetOf(property, modalityMappings);
        }

        private String column(final String property) {
            return columnByProperty.get(property.toLowerCase(Locale.ROOT));
        }

        private String value(final CSVRecord record, final Set<String> header, final String property) {
            final String column = column(property);
            if (column == null || !header.contains(column)) {
                return null;
            }
            final String value = record.get(column);
            return StringUtils.isBlank(value) ? null : value;
        }

        private void validateHeader(final Set<String> header) {
            final List<String> missing = new ArrayList<>();
            for (final CsvColumnMapping mapping : mappings) {
                if (mapping.isRequiredColumn() && !header.contains(mapping.getColumn())) {
                    missing.add(mapping.getColumn());
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("CSV manifest is missing required column(s): " + missing);
            }
        }

        private void validateRow(final CSVRecord record, final Set<String> header) {
            for (final CsvColumnMapping mapping : mappings) {
                final String column = mapping.getColumn();
                if (!header.contains(column)) {
                    continue;
                }
                final String value = record.get(column);
                if (StringUtils.isBlank(value)) {
                    if (mapping.isRequiredColumn()) {
                        throw new IllegalStateException("CSV row " + record.getRecordNumber() + " has a blank value for required column \"" + column + "\"");
                    }
                    continue;
                }
                final Pattern pattern = patternByColumn.get(column);
                if (pattern != null && !pattern.matcher(value).matches()) {
                    throw new IllegalStateException("CSV row " + record.getRecordNumber() + " value \"" + value + "\" in column \"" + column + "\" does not match the required format " + pattern.pattern());
                }
            }
        }
    }
}
