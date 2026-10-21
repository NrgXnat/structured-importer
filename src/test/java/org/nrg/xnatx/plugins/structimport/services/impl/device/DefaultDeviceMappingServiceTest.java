package org.nrg.xnatx.plugins.structimport.services.impl.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.nrg.config.entities.Configuration;
import org.nrg.config.entities.ConfigurationData;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.constants.Scope;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMapping;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.DeviceMappingService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultDeviceMappingServiceTest {

    private static final String TOOL    = CsvImportConfigService.TOOL_NAME;
    private static final String PATH    = DeviceMappingService.DEVICE_MAPPINGS_PATH;
    private static final String PROJECT = "PROJ_1";
    private static final String CSV     = "Source Parameter,XNAT Column,XNAT Property,Required\n"
                                          + "(user input),Scan ID,xnat:imageScanData/ID,yes\n"
                                          + "(user input),Modality,xnat:imageScanData/modality,yes\n"
                                          + "(user input),Path,,yes\n";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private ConfigService               configService;
    private DefaultDeviceMappingService service;
    private UserI                       user;
    private ObjectMapper                mapper;

    @Before
    public void setUp() {
        configService = mock(ConfigService.class);
        final ModalityDataTypeService modalities = mock(ModalityDataTypeService.class);
        final Map<String, ModalityMapping> modalityMap = new LinkedHashMap<>();
        modalityMap.put("CT", ModalityMapping.builder().modality("CT").scan("xnat:ctScanData").session("xnat:ctSessionData").build());
        modalityMap.put("MR", ModalityMapping.builder().modality("MR").scan("xnat:mrScanData").session("xnat:mrSessionData").build());
        modalityMap.put("PET", ModalityMapping.builder().modality("PET").scan("xnat:petScanData").session("xnat:petSessionData").build());
        when(modalities.getModalityMappings()).thenReturn(modalityMap);
        when(modalities.getModalityMapping(anyString())).thenAnswer(invocation -> Optional.ofNullable(modalityMap.get((String) invocation.getArgument(0))));
        service = new DefaultDeviceMappingService(configService, modalities);
        user    = mock(UserI.class);
        when(user.getUsername()).thenReturn("admin");
        mapper = new ObjectMapper();
    }

    @Test
    public void siteDevicesAreTheBundledOnesUntilSomethingIsStored() {
        final List<DeviceMapping> devices = service.getDevices(Scope.Site, null);

        assertThat(devices.stream().map(DeviceMapping::getId).collect(Collectors.toList()),
                   equalTo(Arrays.asList("bruker-skyscan-1172", "bruker-skyscan-1276", "bruker-biospec", "siemens-inveon", "revvity-ivis-spectrum", "emit-xerra")));
        assertThat(devices.get(0).getScope(), equalTo(DeviceMappingService.SCOPE_SITE));
        assertThat(devices.get(0).getCsv(), containsString("Instrument S/N"));
    }

    @Test
    public void everyBundledDeviceCanDriveAnImport() {
        for (final DeviceMapping device : service.getBundledDevices()) {
            assertThat(device.getId(), service.describe(device).getProblems(), is(empty()));
            assertThat(device.getId(), service.validate(device), is(empty()));
        }
    }

    @Test
    public void projectDevicesReplaceSiteDevicesWithTheSameIdAndFollowTheRest() throws Exception {
        stored(Scope.Site, null, device("a", "Site A"), device("b", "Site B"));
        stored(Scope.Project, PROJECT, device("b", "Project B"), device("c", "Project C"));

        final List<DeviceMapping> effective = service.getEffectiveDevices(PROJECT);

        assertThat(effective.stream().map(DeviceMapping::getName).collect(Collectors.toList()), equalTo(Arrays.asList("Site A", "Project B", "Project C")));
        assertThat(effective.get(0).getScope(), equalTo(DeviceMappingService.SCOPE_SITE));
        assertThat(effective.get(1).getScope(), equalTo(DeviceMappingService.SCOPE_PROJECT));
        assertThat(effective.get(1).getOverridesSite(), is(true));
        assertThat(effective.get(2).getOverridesSite(), is(false));
        assertThat(service.getEffectiveDevices(null), hasSize(2));
    }

    @Test
    public void disabledConfigurationIsTreatedAsAbsent() throws Exception {
        stored(Scope.Project, PROJECT, device("b", "Project B"));
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT).getStatus()).thenReturn(Configuration.DISABLED_STRING);

        assertThat(service.getDevices(Scope.Project, PROJECT), is(empty()));
    }

    @Test
    public void savingTheFirstSiteDeviceKeepsTheBundledOnes() throws Exception {
        service.saveDevice(user, Scope.Site, null, device(" new-device ", "New"));

        final List<DeviceMapping> written = written(Scope.Site, null);
        assertThat(written, hasSize(7));
        final DeviceMapping added = written.get(6);
        assertThat(added.getId(), equalTo("new-device"));
        assertThat(added.getStatus(), equalTo(DeviceMapping.STATUS_DRAFT));
        assertThat(added.getScope(), is(nullValue()));
    }

    @Test
    public void savingReplacesTheDeviceWithTheSameId() throws Exception {
        stored(Scope.Project, PROJECT, device("a", "Old"), device("b", "B"));

        service.saveDevice(user, Scope.Project, PROJECT, device("a", "New"));

        assertThat(written(Scope.Project, PROJECT).stream().map(DeviceMapping::getName).collect(Collectors.toList()), equalTo(Arrays.asList("New", "B")));
    }

    @Test
    public void deletingRemovesOnlyThatDevice() throws Exception {
        stored(Scope.Project, PROJECT, device("a", "A"), device("b", "B"));

        assertThat(service.deleteDevice(user, Scope.Project, PROJECT, "a"), is(true));
        assertThat(written(Scope.Project, PROJECT).stream().map(DeviceMapping::getId).collect(Collectors.toList()), equalTo(Collections.singletonList("b")));
    }

    @Test
    public void deletingAMissingDeviceStoresNothing() throws Exception {
        assertThat(service.deleteDevice(user, Scope.Project, PROJECT, "a"), is(false));
        verify(configService, never()).replaceConfig(anyString(), anyString(), anyString(), anyString(), anyString(), any(Scope.class), nullable(String.class));
    }

    @Test
    public void columnMappingsComeFromTheEffectiveDevice() throws Exception {
        stored(Scope.Project, PROJECT, device("a", "A"));

        final List<CsvColumnMapping> mappings = service.getColumnMappings(PROJECT, "a");

        assertThat(mappings.stream().map(CsvColumnMapping::getColumn).collect(Collectors.toList()), equalTo(Arrays.asList("Scan ID", "Modality", "Path")));
    }

    @Test
    public void columnMappingsOfAnUnknownDeviceFail() {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("No device with the ID \"nope\" is available to project " + PROJECT));
        service.getColumnMappings(PROJECT, "nope");
    }

    @Test
    public void columnMappingsOfAnUnusableDeviceFail() throws Exception {
        stored(Scope.Site, null, device("a", "A").toBuilder().csv("Source Parameter,XNAT Column,XNAT Property\nx,Scan ID,xnat:imageScanData/ID\n").build());

        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(containsString("No row is the path column"));
        service.getColumnMappings(PROJECT, "a");
    }

    @Test
    public void validateReportsBadIdsNamesAndStatuses() {
        final List<String> problems = service.validate(DeviceMapping.builder().id("bad id").status("final").csv(CSV).build());

        assertThat(problems, hasItem(containsString("The device ID \"bad id\" is invalid")));
        assertThat(problems, hasItem(containsString("needs a name")));
        assertThat(problems, hasItem(containsString("The status \"final\" is invalid")));
    }

    @Test
    public void unconfiguredModalityIsAWarningNotAProblem() {
        final DeviceMapping device = device("a", "A").toBuilder().modality("CFT").build();

        assertThat(service.validate(device), is(empty()));
        assertThat(service.describe(device).getWarnings(), hasItem(containsString("\"CFT\" is not configured")));
    }

    @Test
    public void initializationSeedsBundledDevicesKeepingLegacyReplacements() throws Exception {
        final Configuration legacy = mock(Configuration.class);
        when(legacy.getStatus()).thenReturn(Configuration.ENABLED_STRING);
        when(legacy.getContents()).thenReturn("﻿" + CSV);
        when(configService.getConfig(DefaultDeviceMappingService.LEGACY_TOOL_NAME, DefaultDeviceMappingService.LEGACY_PATH_PREFIX + "bruker-biospec")).thenReturn(legacy);

        service.initializeSiteConfiguration(user);

        final List<DeviceMapping> written = written(Scope.Site, null);
        assertThat(written, hasSize(6));
        assertThat(written.get(2).getId(), equalTo("bruker-biospec"));
        assertThat(written.get(2).getCsv(), equalTo(CSV));
        assertThat(written.get(0).getCsv(), containsString("Instrument S/N"));
    }

    @Test
    public void initializationLeavesStoredDevicesAlone() throws Exception {
        stored(Scope.Site, null);

        service.initializeSiteConfiguration(user);

        verify(configService, never()).replaceConfig(anyString(), anyString(), anyString(), anyString(), anyString(), any(Scope.class), nullable(String.class));
    }

    private static DeviceMapping device(final String id, final String name) {
        return DeviceMapping.builder().id(id).name(name).modality("CT").csv(CSV).build();
    }

    private void stored(final Scope scope, final String entityId, final DeviceMapping... devices) throws Exception {
        final ConfigurationData data = mock(ConfigurationData.class);
        when(data.getContents()).thenReturn(mapper.writeValueAsString(Arrays.asList(devices)));
        final Configuration config = mock(Configuration.class);
        when(config.getConfigData()).thenReturn(data);
        when(config.getStatus()).thenReturn(Configuration.ENABLED_STRING);
        when(configService.getConfig(TOOL, PATH, scope, entityId)).thenReturn(config);
    }

    private List<DeviceMapping> written(final Scope scope, final String entityId) throws Exception {
        final ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(configService).replaceConfig(eq("admin"), anyString(), eq(TOOL), eq(PATH), json.capture(), eq(scope), eq(entityId));
        return mapper.readValue(json.getValue(), new TypeReference<List<DeviceMapping>>() {});
    }
}
