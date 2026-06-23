package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.config.entities.Configuration;
import org.nrg.config.entities.ConfigurationData;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.constants.Scope;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultCsvImportConfigServiceTest {

    private static final String TOOL = CsvImportConfigService.TOOL_NAME;
    private static final String PATH = CsvImportConfigService.COLUMN_MAPPINGS_PATH;
    private static final String USER = "admin";
    private static final String PROJECT = "PROJ_1";

    private ConfigService                 configService;
    private DefaultCsvImportConfigService service;
    private UserI                         user;
    private ObjectMapper                  mapper;

    @Before
    public void setUp() {
        configService = mock(ConfigService.class);
        service       = new DefaultCsvImportConfigService(configService);
        user          = mock(UserI.class);
        when(user.getUsername()).thenReturn(USER);
        mapper = new ObjectMapper();
    }

    @Test
    public void getColumnMappingsByScopeReturnsNullWhenNoConfig() {
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(null);
        assertThat(service.getColumnMappings(Scope.Site, null), is(nullValue()));
    }

    @Test
    public void getColumnMappingsByScopeReturnsNullWhenContentsBlank() {
        final Configuration blank = configurationWith("   ");
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(blank);
        assertThat(service.getColumnMappings(Scope.Site, null), is(nullValue()));
    }

    @Test
    public void getColumnMappingsByScopeParsesStoredJson() throws Exception {
        final List<CsvColumnMapping> mappings = Arrays.asList(
                CsvColumnMapping.builder().column("Scan ID").property("xnat:imageScanData/ID").build(),
                CsvColumnMapping.builder().column("Path").property("").required(true).build());
        final Configuration stored = configurationWith(mapper.writeValueAsString(mappings));
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(stored);

        final List<CsvColumnMapping> actual = service.getColumnMappings(Scope.Site, null);
        assertThat(actual, equalTo(mappings));
    }

    @Test(expected = RuntimeException.class)
    public void getColumnMappingsByScopeWrapsParseFailures() {
        when(configService.getConfig(TOOL, PATH, Scope.Site, null))
                .thenReturn(configurationWith("not valid json"));
        service.getColumnMappings(Scope.Site, null);
    }

    @Test
    public void effectiveLookupPrefersProjectOverSite() throws Exception {
        final List<CsvColumnMapping> projectMappings = Collections.singletonList(
                CsvColumnMapping.builder().column("Project Scan ID").property("xnat:imageScanData/ID").build());
        final Configuration projectConfig = configurationWith(mapper.writeValueAsString(projectMappings));
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(projectConfig);

        final List<CsvColumnMapping> actual = service.getColumnMappings(user, PROJECT);
        assertThat(actual, equalTo(projectMappings));
        verify(configService, never()).getConfig(eq(TOOL), eq(PATH), eq(Scope.Site), any());
    }

    @Test
    public void effectiveLookupFallsBackToSiteWhenProjectAbsent() throws Exception {
        final List<CsvColumnMapping> siteMappings = Collections.singletonList(
                CsvColumnMapping.builder().column("Scan ID").property("xnat:imageScanData/ID").build());
        final Configuration siteConfig = configurationWith(mapper.writeValueAsString(siteMappings));
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(null);
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(siteConfig);

        final List<CsvColumnMapping> actual = service.getColumnMappings(user, PROJECT);
        assertThat(actual, equalTo(siteMappings));
    }

    @Test
    public void effectiveLookupCreatesDefaultWhenNothingConfigured() throws Exception {
        final Configuration defaultsConfig = configurationWith(mapper.writeValueAsString(service.getDefaultColumnMappings()));
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(null, defaultsConfig);

        final List<CsvColumnMapping> actual = service.getColumnMappings(user, null);
        assertThat(actual, equalTo(service.getDefaultColumnMappings()));

        final ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).replaceConfig(eq(USER), anyString(), eq(TOOL), eq(PATH), jsonCaptor.capture(), eq(Scope.Site), eq((String) null));
        final List<CsvColumnMapping> stored = mapper.readValue(jsonCaptor.getValue(), new TypeReference<List<CsvColumnMapping>>() {});
        assertThat(stored, equalTo(service.getDefaultColumnMappings()));
    }

    @Test
    public void createOrUpdateSerializesAndPersistsMappings() throws Exception {
        final List<CsvColumnMapping> mappings = service.getDefaultColumnMappings();
        final Configuration          stored   = configurationWith(mapper.writeValueAsString(mappings));
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(stored);

        final List<CsvColumnMapping> result = service.createOrUpdateColumnMappings(user, Scope.Project, PROJECT, mappings);

        final ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).replaceConfig(eq(USER), anyString(), eq(TOOL), eq(PATH), jsonCaptor.capture(), eq(Scope.Project), eq(PROJECT));
        assertThat(jsonCaptor.getValue(), is(notNullValue()));
        assertThat(result, equalTo(mappings));
    }

    @Test(expected = RuntimeException.class)
    public void createOrUpdateWrapsConfigServiceFailure() throws Exception {
        org.mockito.Mockito.doThrow(new org.nrg.config.exceptions.ConfigServiceException("boom"))
                           .when(configService)
                           .replaceConfig(anyString(), anyString(), eq(TOOL), eq(PATH), anyString(), eq(Scope.Site), eq((String) null));
        service.createOrUpdateColumnMappings(user, Scope.Site, null, service.getDefaultColumnMappings());
    }

    @Test
    public void initializeSiteConfigurationSkipsWhenAlreadyConfigured() throws Exception {
        final Configuration existing = configurationWith(mapper.writeValueAsString(service.getDefaultColumnMappings()));
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(existing);
        service.initializeSiteConfiguration(user);
        verify(configService, never()).replaceConfig(anyString(), anyString(), anyString(), anyString(), anyString(), any(Scope.class), any());
    }

    @Test
    public void initializeSiteConfigurationCreatesDefaultWhenAbsent() throws Exception {
        final Configuration defaultsConfig = configurationWith(mapper.writeValueAsString(service.getDefaultColumnMappings()));
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(null, defaultsConfig);

        service.initializeSiteConfiguration(user);

        verify(configService, times(1)).replaceConfig(eq(USER), anyString(), eq(TOOL), eq(PATH), anyString(), eq(Scope.Site), eq((String) null));
    }

    @Test
    public void disabledConfigIsTreatedAsAbsent() throws Exception {
        final Configuration disabled = configurationWith(mapper.writeValueAsString(service.getDefaultColumnMappings()), Configuration.DISABLED_STRING);
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(disabled);
        assertThat(service.getColumnMappings(Scope.Project, PROJECT), is(nullValue()));
    }

    @Test
    public void effectiveLookupFallsBackToSiteWhenProjectDisabled() throws Exception {
        final Configuration disabledProject = configurationWith(mapper.writeValueAsString(service.getDefaultColumnMappings()), Configuration.DISABLED_STRING);
        final List<CsvColumnMapping> siteMappings = Collections.singletonList(
                CsvColumnMapping.builder().column("Scan ID").property("xnat:imageScanData/ID").build());
        final Configuration siteConfig = configurationWith(mapper.writeValueAsString(siteMappings));
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(disabledProject);
        when(configService.getConfig(TOOL, PATH, Scope.Site, null)).thenReturn(siteConfig);

        assertThat(service.getColumnMappings(user, PROJECT), equalTo(siteMappings));
    }

    @Test
    public void enabledStatusIsHonoredAsPresent() throws Exception {
        final List<CsvColumnMapping> mappings = Collections.singletonList(
                CsvColumnMapping.builder().column("Scan ID").property("xnat:imageScanData/ID").build());
        final Configuration enabled = configurationWith(mapper.writeValueAsString(mappings), Configuration.ENABLED_STRING);
        when(configService.getConfig(TOOL, PATH, Scope.Project, PROJECT)).thenReturn(enabled);
        assertThat(service.getColumnMappings(Scope.Project, PROJECT), equalTo(mappings));
    }

    @Test
    public void disableColumnMappingsDelegatesToConfigService() throws Exception {
        service.disableColumnMappings(user, Scope.Project, PROJECT);
        verify(configService).disable(eq(USER), anyString(), eq(TOOL), eq(PATH), eq(Scope.Project), eq(PROJECT));
    }

    @Test(expected = RuntimeException.class)
    public void disableColumnMappingsWrapsConfigServiceFailure() throws Exception {
        org.mockito.Mockito.doThrow(new org.nrg.config.exceptions.ConfigServiceException("boom"))
                           .when(configService)
                           .disable(anyString(), anyString(), eq(TOOL), eq(PATH), eq(Scope.Project), eq(PROJECT));
        service.disableColumnMappings(user, Scope.Project, PROJECT);
    }

    @Test
    public void deleteColumnMappingsClearsContentsViaConfigService() throws Exception {
        service.deleteColumnMappings(user, Scope.Project, PROJECT);
        verify(configService).replaceConfig(eq(USER), anyString(), eq(TOOL), eq(PATH), eq(""), eq(Scope.Project), eq(PROJECT));
    }

    @Test(expected = RuntimeException.class)
    public void deleteColumnMappingsWrapsConfigServiceFailure() throws Exception {
        org.mockito.Mockito.doThrow(new org.nrg.config.exceptions.ConfigServiceException("boom"))
                           .when(configService)
                           .replaceConfig(anyString(), anyString(), eq(TOOL), eq(PATH), eq(""), eq(Scope.Project), eq(PROJECT));
        service.deleteColumnMappings(user, Scope.Project, PROJECT);
    }

    @Test
    public void defaultMappingsContainExpectedColumnsAndPathLocator() {
        final List<CsvColumnMapping> defaults = service.getDefaultColumnMappings();
        assertThat(findColumnFor(defaults, CsvImportConfigService.PROP_SCAN_ID), equalTo("Scan ID"));
        assertThat(findColumnFor(defaults, CsvImportConfigService.PROP_MODALITY), equalTo("Modality"));
        assertThat(findColumnFor(defaults, CsvImportConfigService.PROP_SESSION_LABEL), equalTo("Session Label"));
        assertThat(findColumnFor(defaults, CsvImportConfigService.PROP_SUBJECT_ID), equalTo("Subject ID"));
        assertThat(findColumnFor(defaults, CsvImportConfigService.PROP_PATH), equalTo("Path"));

        final CsvColumnMapping startDate = findMappingByColumn(defaults, "Start Date");
        assertThat(startDate.isRequiredColumn(), is(false));
        assertThat(startDate.getValidation(), is(notNullValue()));
    }

    private static String findColumnFor(final List<CsvColumnMapping> mappings, final String property) {
        return mappings.stream()
                       .filter(m -> property.equals(m.getProperty()))
                       .findFirst()
                       .map(CsvColumnMapping::getColumn)
                       .orElse(null);
    }

    private static CsvColumnMapping findMappingByColumn(final List<CsvColumnMapping> mappings, final String column) {
        return mappings.stream()
                       .filter(m -> column.equals(m.getColumn()))
                       .findFirst()
                       .orElseThrow(() -> new AssertionError("Missing column: " + column));
    }

    private static Configuration configurationWith(final String contents) {
        return configurationWith(contents, null);
    }

    private static Configuration configurationWith(final String contents, final String status) {
        final Configuration     config = mock(Configuration.class);
        final ConfigurationData data   = mock(ConfigurationData.class);
        when(data.getContents()).thenReturn(contents);
        when(config.getConfigData()).thenReturn(data);
        when(config.getStatus()).thenReturn(status);
        return config;
    }
}
