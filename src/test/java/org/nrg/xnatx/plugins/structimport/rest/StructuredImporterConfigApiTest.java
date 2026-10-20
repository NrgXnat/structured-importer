package org.nrg.xnatx.plugins.structimport.rest;

import org.junit.Before;
import org.junit.Test;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the manifest template endpoints' content and mapping fallback. The endpoints' access
 * restrictions (Admin for the site template, project access for a project template) are enforced by
 * XNAT's security interceptors and static permission helpers, so they're left to end-to-end tests.
 */
public class StructuredImporterConfigApiTest {

    private static final List<CsvColumnMapping> PROJECT  = mappings("Project Column", "Path");
    private static final List<CsvColumnMapping> SITE     = mappings("Site Column", "Path");
    private static final List<CsvColumnMapping> DEFAULTS = mappings("Default Column", "Path");

    private CsvImportConfigService     configService;
    private StructuredImporterConfigApi api;

    @Before
    public void setUp() {
        configService = mock(CsvImportConfigService.class);
        // like the real service, report nothing stored at any scope unless a test says otherwise
        when(configService.getColumnMappings(any(Scope.class), nullable(String.class))).thenReturn(null);
        when(configService.getDefaultColumnMappings()).thenReturn(DEFAULTS);
        api = new StructuredImporterConfigApi(mock(UserManagementServiceI.class), mock(RoleHolder.class), configService, mock(ModalityDataTypeService.class));
    }

    @Test
    public void siteTemplateIsUtf8CsvAttachment() {
        when(configService.getColumnMappings(Scope.Site, null)).thenReturn(mappings("Dose (µCi)", "Temp (°C)", "Ωmega", "Path"));

        final ResponseEntity<String> response = api.getSiteManifestTemplate();

        assertThat(response.getStatusCode(), is(HttpStatus.OK));
        assertThat(response.getHeaders().getContentType().toString(), equalTo("text/csv;charset=UTF-8"));
        assertThat(response.getHeaders().getContentType().getCharset(), equalTo(StandardCharsets.UTF_8));
        final String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition, disposition.matches("attachment; filename=\"struct-import-template-\\d{8}_\\d{6}\\.csv\""), is(true));
        assertThat(response.getBody(), equalTo("Dose (µCi),Temp (°C),Ωmega,Path\r\n"));
    }

    @Test
    public void siteTemplateFallsBackToDefaultsWithoutStoringThem() {
        assertThat(api.getSiteManifestTemplate().getBody(), equalTo("Default Column,Path\r\n"));
        verify(configService, never()).createOrUpdateColumnMappings(any(), any(), any(), any());
    }

    @Test
    public void projectTemplateUsesProjectMappings() {
        when(configService.getColumnMappings(Scope.Project, "PROJ")).thenReturn(PROJECT);
        when(configService.getColumnMappings(Scope.Site, null)).thenReturn(SITE);

        assertThat(api.templateMappings("PROJ"), equalTo(PROJECT));
    }

    @Test
    public void projectTemplateFallsBackToSiteMappings() {
        // no project configuration, or a disabled one, is reported as null by the config service
        when(configService.getColumnMappings(Scope.Site, null)).thenReturn(SITE);

        assertThat(api.templateMappings("PROJ"), equalTo(SITE));
    }

    @Test
    public void projectTemplateFallsBackToDefaults() {
        assertThat(api.templateMappings("PROJ"), equalTo(DEFAULTS));
        verify(configService, never()).createOrUpdateColumnMappings(any(), any(), any(), any());
    }

    @Test
    public void siteTemplateIgnoresProjectMappings() {
        when(configService.getColumnMappings(Scope.Site, null)).thenReturn(SITE);

        assertThat(api.templateMappings(null), equalTo(SITE));
        verify(configService, never()).getColumnMappings(eq(Scope.Project), any());
    }

    private static List<CsvColumnMapping> mappings(final String... columns) {
        final CsvColumnMapping[] mappings = new CsvColumnMapping[columns.length];
        for (int index = 0; index < columns.length; index++) {
            final String column = columns[index];
            mappings[index] = CsvColumnMapping.builder().column(column).property(column.equals("Path") ? "" : "xnat:imageScanData/note").build();
        }
        return Collections.unmodifiableList(Arrays.asList(mappings));
    }
}
