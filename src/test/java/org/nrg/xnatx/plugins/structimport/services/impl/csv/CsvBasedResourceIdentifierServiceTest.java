package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CsvBasedResourceIdentifierServiceTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private CsvImportConfigService            configService;
    private CsvBasedResourceIdentifierService service;
    private UserI                               user;
    private Path                                root;

    @Before
    public void setUp() throws IOException {
        configService = mock(CsvImportConfigService.class);
        service       = new CsvBasedResourceIdentifierService(configService);
        user          = mock(UserI.class);
        root          = Files.createTempDirectory("csv-based-import-test");
    }

    @After
    public void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                final List<Path> reverse = new ArrayList<>();
                stream.forEach(reverse::add);
                Collections.reverse(reverse);
                for (final Path path : reverse) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void parsesManifestAndAggregatesByScanResource() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("scan1/file1.nii");
        touch("scan1/file2.nii");
        touch("scan2/file1.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Start Date,Start Time,Subject Weight (g),Resource Name,Path",
                "1,MR,T1,SES_A,SUBJ_A,01/02/2026,10:30 AM,25.5,NIFTI,scan1/file1.nii",
                "1,MR,T1,SES_A,SUBJ_A,01/02/2026,10:30 AM,25.5,NIFTI,scan1/file2.nii",
                "2,PET,FDG,SES_A,SUBJ_A,01/02/2026,11:00 AM,25.5,,scan2/file1.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");

        assertThat(result.size(), is(2));
        final Iterator<Map.Entry<ScanResource, List<Path>>> it = result.entrySet().iterator();
        final Map.Entry<ScanResource, List<Path>> first = it.next();
        assertThat(first.getKey().getScanId(), equalTo("1"));
        assertThat(first.getKey().getModality(), equalTo("MR"));
        assertThat(first.getKey().getName(), equalTo("NIFTI"));
        assertThat(first.getKey().getStartDate(), equalTo(LocalDate.of(2026, 1, 2)));
        assertThat(first.getKey().getStartTime(), equalTo(LocalTime.of(10, 30)));
        assertThat(first.getKey().getSubjectWeight(), equalTo(25.5));
        assertThat(first.getValue(), hasSize(2));

        final Map.Entry<ScanResource, List<Path>> second = it.next();
        assertThat(second.getKey().getScanId(), equalTo("2"));
        assertThat(second.getKey().getName(), equalTo("NIFTI"));
        assertThat(second.getValue(), hasSize(1));
    }

    @Test
    public void parsesIso8601DateAndTime() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappingsWithoutValidation());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Start Date,Start Time,Subject Weight (g),Resource Name,Path",
                "1,CT,Series,SES,SUBJ,2026-01-02,14:45:00,,,a.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        final ScanResource key = result.keySet().iterator().next();
        assertThat(key.getStartDate(), equalTo(LocalDate.of(2026, 1, 2)));
        assertThat(key.getStartTime(), equalTo(LocalTime.of(14, 45)));
        assertThat(key.getName(), equalTo("NIFTI"));
    }

    @Test
    public void missingRequiredHeaderThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Modality,Series Description,Session Label,Subject ID,Path",
                "MR,T1,SES,SUBJ,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("missing required column"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void blankRequiredValueThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                ",MR,T1,SES,SUBJ,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("Scan ID"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void validationRegexFailureThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Subject Weight (g),Path",
                "1,MR,T1,SES,SUBJ,not-a-number,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("does not match"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void invalidDateThrowsWithRowAndColumn() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(allOptionalMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Start Date,Path",
                "1,MR,T1,SES,SUBJ,nope,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("Start Date"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void invalidTimeThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(allOptionalMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Start Time,Path",
                "1,MR,T1,SES,SUBJ,25:99,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("Start Time"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void invalidSubjectWeightWhenValidationOmittedThrowsAsNumberError() throws Exception {
        final List<CsvColumnMapping> mappings = new ArrayList<>(defaultMappings());
        mappings.replaceAll(m -> "Subject Weight (g)".equals(m.getColumn())
                ? CsvColumnMapping.builder().column(m.getColumn()).property(m.getProperty()).required(false).build()
                : m);
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Subject Weight (g),Path",
                "1,MR,T1,SES,SUBJ,abc,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("invalid number"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void absolutePathThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,/etc/passwd"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("absolute path"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void escapingPathThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,../escape.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("escapes the archive root"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void missingPathFileThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,does-not-exist.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("does not exist"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void noCsvManifestThrows() {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("No CSV manifest"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void multipleCsvManifestsThrow() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        Files.write(root.resolve("a.csv"), "Path\nfoo".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b.csv"), "Path\nfoo".getBytes(StandardCharsets.UTF_8));
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("Multiple CSV files"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void missingPathColumnInConfigThrows() throws Exception {
        final List<CsvColumnMapping> mappings = Collections.singletonList(
                CsvColumnMapping.builder().column("Scan ID").property(CsvImportConfigService.PROP_SCAN_ID).build());
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        writeCsv("Scan ID", "1");

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("does not define a path column"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void duplicatePathColumnInConfigThrows() throws Exception {
        final List<CsvColumnMapping> mappings = Arrays.asList(
                CsvColumnMapping.builder().column("Path").property("").build(),
                CsvColumnMapping.builder().column("Source").property("").build());
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        writeCsv("Path,Source", "a,b");

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("more than one path column"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void emptyConfigThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(Collections.emptyList());
        writeCsv("Path", "a");
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("No CSV import column mappings"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void blankColumnNameInConfigThrows() throws Exception {
        final List<CsvColumnMapping> mappings = Collections.singletonList(
                CsvColumnMapping.builder().column("").property(CsvImportConfigService.PROP_SCAN_ID).build());
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        writeCsv("Path", "a");
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("no column name"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void invalidValidationRegexInConfigThrows() throws Exception {
        final List<CsvColumnMapping> mappings = Arrays.asList(
                CsvColumnMapping.builder().column("Scan ID").property(CsvImportConfigService.PROP_SCAN_ID).validation("[unterminated").build(),
                CsvColumnMapping.builder().column("Path").property("").build());
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        writeCsv("Scan ID,Path", "1,a");
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("invalid validation expression"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void inconsistentSubjectWeightAcrossRowsThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Subject Weight (g),Path",
                "1,MR,T1,SES,SUBJ,25.5,a.nii",
                "2,PET,FDG,SES,SUBJ,30.0,b.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("inconsistent"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void defaultResourceNameUsedWhenColumnBlank() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Resource Name,Path",
                "1,MR,T1,SES,SUBJ,,a.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.keySet().iterator().next().getName(), equalTo(CsvBasedResourceIdentifierService.DEFAULT_RESOURCE_NAME));
    }

    @Test
    public void rowsCollapseToSameResourceWhenContextMatches() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Resource Name,Path",
                "1,MR,T1,SES,SUBJ,NIFTI,a.nii",
                "1,MR,T1,SES,SUBJ,NIFTI,b.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.size(), is(1));
        assertThat(result.values().iterator().next(), hasSize(2));
    }

    @Test
    public void optionalColumnsMayBeOmittedFromHeader() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,a.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        final ScanResource key = result.keySet().iterator().next();
        assertThat(result.values(), hasItem(notNullValue()));
        assertThat(key.getStartDate(), is((LocalDate) null));
        assertThat(key.getStartTime(), is((LocalTime) null));
        assertThat(key.getSubjectWeight(), is((Double) null));
    }

    @Test
    public void emptyRowSetReturnsEmptyMap() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        writeCsv("Scan ID,Modality,Series Description,Session Label,Subject ID,Path");

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.entrySet(), is(empty()));
    }

    @Test
    public void customPropertyValueLandsOnScanResource() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("TR", "xnat:mrScanData/parameters/tr", false, null));
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,TR,Path",
                "1,MR,T1,SES,SUBJ,2500,a.nii",
                "1,MR,T1,SES,SUBJ,2500,b.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.size(), is(1));
        final ScanResource key = result.keySet().iterator().next();
        assertThat(key.getCustomProperties().get("xnat:mrScanData/parameters/tr"), equalTo("2500"));
        assertThat(result.values().iterator().next(), hasSize(2));
    }

    @Test
    public void differingCustomScanValuesSplitResources() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("TR", "xnat:mrScanData/parameters/tr", false, null));
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,TR,Path",
                "1,MR,T1,SES,SUBJ,2500,a.nii",
                "1,MR,T1,SES,SUBJ,3000,b.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.size(), is(2));
    }

    @Test
    public void blankCustomValueIsOmittedFromResource() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("TR", "xnat:mrScanData/parameters/tr", false, null));
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,TR,Path",
                "1,MR,T1,SES,SUBJ,,a.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.keySet().iterator().next().getCustomProperties().isEmpty(), is(true));
    }

    @Test
    public void requiredCustomColumnMissingFromHeaderThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("TR", "xnat:mrScanData/parameters/tr", true, null));
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("missing required column"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void customColumnValidationRegexApplies() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("TR", "xnat:mrScanData/parameters/tr", false, "^\\d+$"));
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,TR,Path",
                "1,MR,T1,SES,SUBJ,not-a-number,a.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("does not match"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void duplicatePropertyInConfigThrows() throws Exception {
        final List<CsvColumnMapping> mappings = new ArrayList<>(defaultMappings());
        mappings.add(CsvColumnMapping.builder().column("Scan ID Again").property(CsvImportConfigService.PROP_SCAN_ID).build());
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(mappings);
        writeCsv("Path", "a");

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("more than one column"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void unsupportedCustomPropertyRootThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("Name", "xnat:projectData/name", false, null));
        writeCsv("Path", "a");

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("column \"Name\""));
        thrown.expectMessage(containsString("root element must be one of"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void inconsistentSessionLevelCustomValueThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("Coil", "xnat:mrSessionData/coil", false, null));
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Coil,Path",
                "1,MR,T1,SES,SUBJ,8ch,a.nii",
                "2,MR,T2,SES,SUBJ,32ch,b.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("inconsistent values"));
        thrown.expectMessage(containsString("Coil"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void inconsistentSubjectLevelCustomValueThrows() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("Group", "xnat:subjectData/group", false, null));
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Group,Path",
                "1,MR,T1,SES_A,SUBJ,control,a.nii",
                "2,MR,T2,SES_B,SUBJ,treatment,b.nii"
        );

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("inconsistent values"));
        thrown.expectMessage(containsString("Group"));
        service.extractResource(root, user, "PROJ");
    }

    @Test
    public void consistentSessionLevelCustomValueAcrossScansPasses() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(withCustomMapping("Coil", "xnat:mrSessionData/coil", false, null));
        touch("a.nii");
        touch("b.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Coil,Path",
                "1,MR,T1,SES,SUBJ,8ch,a.nii",
                "2,MR,T2,SES,SUBJ,8ch,b.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.size(), is(2));
    }

    @Test
    public void legacyConfigYieldsEmptyCustomProperties() throws Exception {
        when(configService.getColumnMappings(any(UserI.class), anyString())).thenReturn(defaultMappings());
        touch("a.nii");
        writeCsv(
                "Scan ID,Modality,Series Description,Session Label,Subject ID,Path",
                "1,MR,T1,SES,SUBJ,a.nii"
        );

        final Map<ScanResource, List<Path>> result = service.extractResource(root, user, "PROJ");
        assertThat(result.keySet().iterator().next().getCustomProperties().isEmpty(), is(true));
    }

    private List<CsvColumnMapping> withCustomMapping(final String column, final String property, final boolean required, final String validation) {
        final List<CsvColumnMapping> mappings = new ArrayList<>(defaultMappings());
        mappings.add(CsvColumnMapping.builder().column(column).property(property).required(required).validation(validation).build());
        return mappings;
    }

    private List<CsvColumnMapping> defaultMappings() {
        return new DefaultCsvImportConfigService(mock(org.nrg.config.services.ConfigService.class)).getDefaultColumnMappings();
    }

    private List<CsvColumnMapping> allOptionalMappings() {
        final List<CsvColumnMapping> defaults = new ArrayList<>(defaultMappings());
        final List<CsvColumnMapping> result   = new ArrayList<>();
        for (final CsvColumnMapping m : defaults) {
            if ("Path".equals(m.getColumn())) {
                result.add(m);
            } else {
                result.add(CsvColumnMapping.builder()
                                           .column(m.getColumn())
                                           .property(m.getProperty())
                                           .required(false)
                                           .build());
            }
        }
        return result;
    }

    private List<CsvColumnMapping> mappingsWithoutValidation() {
        final List<CsvColumnMapping> result = new ArrayList<>();
        for (final CsvColumnMapping m : defaultMappings()) {
            result.add(CsvColumnMapping.builder()
                                       .column(m.getColumn())
                                       .property(m.getProperty())
                                       .required(m.getRequired())
                                       .build());
        }
        return result;
    }

    private void writeCsv(final String... lines) throws IOException {
        final String content = String.join("\n", lines) + "\n";
        Files.write(root.resolve("manifest.csv"), content.getBytes(StandardCharsets.UTF_8));
    }

    private void touch(final String relative) throws IOException {
        final Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[0]);
    }
}
