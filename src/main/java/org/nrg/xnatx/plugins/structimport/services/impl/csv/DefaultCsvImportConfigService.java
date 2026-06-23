package org.nrg.xnatx.plugins.structimport.services.impl.csv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.config.entities.Configuration;
import org.nrg.config.exceptions.ConfigServiceException;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.constants.Scope;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Stores the structured CSV import column mappings as a JSON array in the XNAT
 * {@link ConfigService} at the site and project level.
 */
@Service
@Slf4j
public class DefaultCsvImportConfigService implements CsvImportConfigService {

    private final ConfigService configService;
    private final ObjectMapper  mapper;

    @Autowired
    public DefaultCsvImportConfigService(final ConfigService configService) {
        this.configService = configService;
        this.mapper        = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @Override
    public List<CsvColumnMapping> getColumnMappings(final UserI user, final String projectId) {
        if (StringUtils.isNotBlank(projectId)) {
            final List<CsvColumnMapping> projectMappings = getColumnMappings(Scope.Project, projectId);
            if (projectMappings != null) {
                log.debug("Using project-level CSV import mappings for project {}", projectId);
                return projectMappings;
            }
        }

        final List<CsvColumnMapping> siteMappings = getColumnMappings(Scope.Site, null);
        if (siteMappings != null) {
            return siteMappings;
        }

        log.info("No site-wide CSV import mappings found; creating the default configuration.");
        return createOrUpdateColumnMappings(user, Scope.Site, null, getDefaultColumnMappings());
    }

    @Override
    public List<CsvColumnMapping> getColumnMappings(final Scope scope, final String entityId) {
        final Configuration config = configService.getConfig(TOOL_NAME, COLUMN_MAPPINGS_PATH, scope, entityId);
        if (config == null || config.getConfigData() == null || StringUtils.isBlank(config.getConfigData().getContents())) {
            return null;
        }
        if (Configuration.DISABLED_STRING.equalsIgnoreCase(config.getStatus())) {
            log.debug("Structured CSV import column mappings for scope {} entity {} are disabled; treating as not configured", scope, entityId);
            return null;
        }
        try {
            return mapper.readValue(config.getConfigData().getContents(), new TypeReference<List<CsvColumnMapping>>() {});
        } catch (IOException e) {
            log.error("Error parsing structured CSV import column mappings for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to parse structured CSV import column mappings", e);
        }
    }

    @Override
    public List<CsvColumnMapping> createOrUpdateColumnMappings(final UserI user, final Scope scope, final String entityId, final List<CsvColumnMapping> mappings) {
        final String json;
        try {
            json = mapper.writeValueAsString(mappings);
        } catch (JsonProcessingException e) {
            log.error("Error serializing structured CSV import column mappings", e);
            throw new RuntimeException("Unable to serialize structured CSV import column mappings", e);
        }

        try {
            configService.replaceConfig(user.getUsername(), "Updating structured CSV import column mappings", TOOL_NAME, COLUMN_MAPPINGS_PATH, json, scope, entityId);
        } catch (ConfigServiceException e) {
            log.error("Error storing structured CSV import column mappings for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to store structured CSV import column mappings", e);
        }

        return getColumnMappings(scope, entityId);
    }

    @Override
    public void disableColumnMappings(final UserI user, final Scope scope, final String entityId) {
        try {
            configService.disable(user.getUsername(), "Disabling structured CSV import column mappings", TOOL_NAME, COLUMN_MAPPINGS_PATH, scope, entityId);
        } catch (ConfigServiceException e) {
            log.error("Error disabling structured CSV import column mappings for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to disable structured CSV import column mappings", e);
        }
    }

    @Override
    public void deleteColumnMappings(final UserI user, final Scope scope, final String entityId) {
        try {
            configService.replaceConfig(user.getUsername(), "Deleting structured CSV import column mappings", TOOL_NAME, COLUMN_MAPPINGS_PATH, "", scope, entityId);
        } catch (ConfigServiceException e) {
            log.error("Error deleting structured CSV import column mappings for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to delete structured CSV import column mappings", e);
        }
    }

    @Override
    public void initializeSiteConfiguration(final UserI user) {
        if (getColumnMappings(Scope.Site, null) != null) {
            log.debug("Site-wide structured CSV import mappings already exist; skipping default creation.");
            return;
        }
        log.info("Creating default site-wide structured CSV import column mappings.");
        createOrUpdateColumnMappings(user, Scope.Site, null, getDefaultColumnMappings());
    }

    @Override
    public List<CsvColumnMapping> getDefaultColumnMappings() {
        return Arrays.asList(
                CsvColumnMapping.builder().column("Scan ID").property(PROP_SCAN_ID).build(),
                CsvColumnMapping.builder().column("Modality").property(PROP_MODALITY).build(),
                CsvColumnMapping.builder().column("Series Description").property(PROP_SERIES_DESCRIPTION).build(),
                CsvColumnMapping.builder().column("Session Label").property(PROP_SESSION_LABEL).build(),
                CsvColumnMapping.builder().column("Start Date").property(PROP_START_DATE).required(false)
                                .validation("^(0[1-9]|1[0-2])/(0[1-9]|[12]\\d|3[01])/\\d{4}$").build(),
                CsvColumnMapping.builder().column("Start Time").property(PROP_START_TIME).required(false)
                                .validation("^(?:0?[1-9]|1[0-2]):[0-5]\\d\\s?(?:[aApP][mM])$").build(),
                CsvColumnMapping.builder().column("Subject ID").property(PROP_SUBJECT_ID).build(),
                CsvColumnMapping.builder().column("Subject Weight (g)").property(PROP_SUBJECT_WEIGHT).required(false)
                                .validation("^\\d+(\\.\\d+)?$").build(),
                CsvColumnMapping.builder().column("Resource Name").property(PROP_RESOURCE_NAME).required(false)
                                .validation("^[A-Za-z_]{1,32}$").build(),
                CsvColumnMapping.builder().column("Path").property(PROP_PATH).required(true).build()
        );
    }
}
