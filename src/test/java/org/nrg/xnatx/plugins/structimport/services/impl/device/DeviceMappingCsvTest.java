package org.nrg.xnatx.plugins.structimport.services.impl.device;

import org.junit.Test;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMappingRow;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

public class DeviceMappingCsvTest {

    private static final String HEADER = "Source Parameter,Source Section,XNAT Column,XNAT Property,Required,Transform,Notes";

    private static final Collection<ModalityMapping> MODALITIES = Arrays.asList(
            ModalityMapping.builder().modality("CT").scan("xnat:ctScanData").session("xnat:ctSessionData").build(),
            ModalityMapping.builder().modality("PET").scan("xnat:petScanData").session("xnat:petSessionData").build());

    @Test
    public void parsesRowsMatchingHeadersByNameInAnyOrderAndCase() {
        final DeviceMappingCsv.Parsed parsed = DeviceMappingCsv.parse("﻿xnat property,XNAT COLUMN,source parameter,required\n"
                                                                     + "xnat:imageScanData/ID,Scan ID,(constant),yes\n"
                                                                     + ",Path,(user input),no\n");

        assertThat(parsed.getProblems(), is(empty()));
        assertThat(parsed.getRows(), hasSize(2));
        final DeviceMappingRow first = parsed.getRows().get(0);
        assertThat(first.getXnatColumn(), equalTo("Scan ID"));
        assertThat(first.getXnatProperty(), equalTo("xnat:imageScanData/ID"));
        assertThat(first.getSourceParameter(), equalTo("(constant)"));
        assertThat(first.isRequired(), is(true));
        assertThat(parsed.getRows().get(1).isRequired(), is(false));
    }

    @Test
    public void reportsMissingRequiredHeaders() {
        final DeviceMappingCsv.Parsed parsed = DeviceMappingCsv.parse("XNAT Column,Notes\nScan ID,x\n");

        assertThat(parsed.getProblems(), hasItem(containsString("\"Source Parameter\"")));
        assertThat(parsed.getProblems(), hasItem(containsString("\"XNAT Property\"")));
    }

    @Test
    public void reportsBlankColumnsMalformedPropertiesAndBadRegexesByLine() {
        final DeviceMappingCsv.Parsed parsed = DeviceMappingCsv.parse("Source Parameter,XNAT Column,XNAT Property,Validation\n"
                                                                     + "a,,xnat:imageScanData/ID,\n"
                                                                     + "b,Frames,frames,\n"
                                                                     + "c,Note,xnat:imageScanData/note,[unclosed\n");

        assertThat(parsed.getProblems(), hasItem(containsString("Line 2: \"XNAT Column\" is empty")));
        assertThat(parsed.getProblems(), hasItem(containsString("Line 3: \"frames\" is not an XNAT property path")));
        assertThat(parsed.getProblems(), hasItem(containsString("Line 4: the validation expression")));
    }

    @Test
    public void reportsEmptyAndHeaderOnlyFiles() {
        assertThat(DeviceMappingCsv.parse("  ").getProblems(), hasItem(containsString("empty")));
        assertThat(DeviceMappingCsv.parse(HEADER + "\n").getProblems(), hasItem(containsString("no mapping rows")));
    }

    @Test
    public void reportsUnterminatedQuotes() {
        assertThat(DeviceMappingCsv.parse(HEADER + "\n\"unclosed,,Scan ID,xnat:imageScanData/ID,yes,,\n").getProblems(),
                   hasItem(containsString("could not be read")));
    }

    @Test
    public void convertsRowsToColumnMappings() {
        final DeviceMappingCsv.Conversion conversion = convert(HEADER,
                                                               "(constant),,Scan ID,xnat:imageScanData/ID,yes,,",
                                                               "Study Date and Time,Acquisition,Start Time,xnat:imageScanData/startTime,no,,",
                                                               "Source Voltage (kV),Acquisition,kVp,xnat:ctScanData/parameters/kvp,no,number,",
                                                               "Output Directory,Reconstruction,Path,,no,,");

        assertThat(conversion.getProblems(), is(empty()));
        assertThat(conversion.getWarnings(), is(empty()));
        final List<CsvColumnMapping> mappings = conversion.getColumnMappings();
        assertThat(mappings.stream().map(CsvColumnMapping::getColumn).collect(Collectors.toList()), equalTo(Arrays.asList("Scan ID", "Start Time", "kVp", "Path")));
        // XFT's field name for the scan start time becomes the importer's built-in property, so the value is parsed as a time
        assertThat(mappings.get(1).getProperty(), equalTo(CsvImportConfigService.PROP_START_TIME));
        assertThat(mappings.get(1).isRequiredColumn(), is(false));
        assertThat(mappings.get(2).getProperty(), equalTo("xnat:ctScanData/parameters/kvp"));
        // the path column is always required, whatever the CSV says
        assertThat(mappings.get(3).getProperty(), equalTo(""));
        assertThat(mappings.get(3).isRequiredColumn(), is(true));
    }

    @Test
    public void skipsPropertiesTheImporterCantSetAndMergesRepeatedColumns() {
        final DeviceMappingCsv.Conversion conversion = convert(HEADER,
                                                               "Image Format,Acquisition,Resource Format,xnat:resourceCatalog/format,no,copy,RAW",
                                                               "Result File Type,Reconstruction,Resource Format,xnat:resourceCatalog/format,no,copy,RECON",
                                                               "Pixel Size,Reconstruction,Voxel Size X (mm),xnat:ctScanData/parameters/voxelRes/x,no,,",
                                                               "Pixel Size,Reconstruction,Voxel Size X (mm),xnat:ctScanData/parameters/voxelRes/x,yes,,",
                                                               "(user input),,Path,,yes,,");

        assertThat(conversion.getProblems(), is(empty()));
        assertThat(conversion.getWarnings(), hasSize(1));
        assertThat(conversion.getWarnings().get(0), containsString("\"Resource Format\" is ignored"));
        assertThat(conversion.getColumnMappings(), hasSize(2));
        assertThat(conversion.getColumnMappings().get(0).isRequiredColumn(), is(true));
    }

    @Test
    public void reportsColumnsAndPropertiesMappedTwice() {
        final DeviceMappingCsv.Conversion conversion = convert(HEADER,
                                                               "a,,Scan ID,xnat:imageScanData/ID,yes,,",
                                                               "b,,Scan ID,xnat:imageScanData/note,yes,,",
                                                               "c,,Scan Number,xnat:imageScanData/ID,yes,,",
                                                               "(user input),,Path,,yes,,");

        assertThat(conversion.getProblems(), hasItem(containsString("The column \"Scan ID\" is mapped to more than one property")));
        assertThat(conversion.getProblems(), hasItem(containsString("The property xnat:imageScanData/ID is filled from more than one column")));
    }

    @Test
    public void requiresExactlyOnePathColumn() {
        assertThat(convert(HEADER, "a,,Scan ID,xnat:imageScanData/ID,yes,,").getProblems(), hasItem(containsString("No row is the path column")));
        assertThat(convert(HEADER, "a,,Path,,yes,,", "b,,Folder,,yes,,").getProblems(), hasItem(containsString("only one path column")));
    }

    @Test
    public void bundledSkyScanMappingConvertsWithoutProblems() throws Exception {
        final String csv = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                getClass().getClassLoader().getResource(DefaultDeviceMappingService.BUNDLED_ROOT + "bruker-skyscan-nrecon.csv").toURI())), "UTF-8");
        final DeviceMappingCsv.Parsed parsed = DeviceMappingCsv.parse(csv);
        assertThat(parsed.getProblems(), is(empty()));

        final DeviceMappingCsv.Conversion conversion = DeviceMappingCsv.toColumnMappings(parsed.getRows(), MODALITIES);
        assertThat(conversion.getProblems(), is(empty()));
        assertThat(conversion.getColumnMappings().stream().map(CsvColumnMapping::getColumn).collect(Collectors.toList()),
                   hasItem("kVp"));
    }

    private static DeviceMappingCsv.Conversion convert(final String... lines) {
        final DeviceMappingCsv.Parsed parsed = DeviceMappingCsv.parse(String.join("\n", lines) + "\n");
        assertThat(parsed.getProblems(), is(empty()));
        return DeviceMappingCsv.toColumnMappings(parsed.getRows(), MODALITIES);
    }
}
