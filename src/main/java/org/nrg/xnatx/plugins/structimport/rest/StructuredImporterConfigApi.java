package org.nrg.xnatx.plugins.structimport.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.constants.Scope;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Authenticated;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

/**
 * XAPI controller for managing the structured importer's CSV column-mapping
 * configuration at the site and project level. Backs the corresponding site
 * admin and project settings Spawner panels.
 *
 * <p>The configuration is exchanged as a small object with a single
 * {@code columnMappings} field whose value is the mapping list serialized as a
 * JSON array string, matching the {@code panel.textarea} used by the Spawner
 * forms.</p>
 */
@Api("Structured Importer CSV column-mapping configuration API")
@XapiRestController
@RequestMapping(value = "/structured-importer")
@Slf4j
public class StructuredImporterConfigApi extends AbstractXapiRestController {

    private final CsvImportConfigService  configService;
    private final ModalityDataTypeService modalityDataTypeService;
    private final ObjectMapper            mapper;

    @Autowired
    public StructuredImporterConfigApi(final UserManagementServiceI userManagementService,
                                       final RoleHolder roleHolder,
                                       final CsvImportConfigService configService,
                                       final ModalityDataTypeService modalityDataTypeService) {
        super(userManagementService, roleHolder);
        this.configService           = configService;
        this.modalityDataTypeService = modalityDataTypeService;
        this.mapper                  = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @ApiOperation(value = "Returns the full modality-to-data-type configuration: the built-in defaults merged with any overrides or additions found on the classpath. Modalities are ordered by group (0 = most common) and then by modality code.",
                  response = ModalityMapping.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "The modality configuration."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/modalities", method = GET, restrictTo = Authenticated, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ModalityMapping> getModalityMappings() {
        return new ArrayList<>(modalityDataTypeService.getModalityMappings().values());
    }

    @ApiOperation(value = "Returns the site-wide CSV column mappings.")
    @ApiResponses({@ApiResponse(code = 200, message = "The site-wide column mappings."),
                   @ApiResponse(code = 403, message = "Not authorized to view the site configuration."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/csv-column-mappings", method = GET, restrictTo = Admin, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ColumnMappingsConfig> getSiteColumnMappings() throws JsonProcessingException {
        List<CsvColumnMapping> mappings = configService.getColumnMappings(Scope.Site, null);
        if (mappings == null) {
            mappings = configService.getDefaultColumnMappings();
        }
        return ResponseEntity.ok(toConfig(mappings));
    }

    @ApiOperation(value = "Sets the site-wide CSV column mappings.")
    @ApiResponses({@ApiResponse(code = 200, message = "The stored column mappings."),
                   @ApiResponse(code = 400, message = "The submitted column mappings are invalid."),
                   @ApiResponse(code = 403, message = "Not authorized to change the site configuration."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/csv-column-mappings", method = POST, restrictTo = Admin, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setSiteColumnMappings(@RequestBody final ColumnMappingsConfig config) throws JsonProcessingException {
        final List<CsvColumnMapping> mappings;
        try {
            mappings = parse(config);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("Unable to parse column mappings JSON: " + e.getMessage());
        }
        if (mappings.isEmpty()) {
            return ResponseEntity.badRequest().body("Site CSV column mappings cannot be empty.");
        }
        final List<CsvColumnMapping> stored = configService.createOrUpdateColumnMappings(getSessionUser(), Scope.Site, null, mappings);
        log.info("User {} updated the site-wide CSV column mappings ({} mapping(s))", getSessionUser().getUsername(), stored.size());
        return ResponseEntity.ok(toConfig(stored));
    }

    @ApiOperation(value = "Returns the project-level CSV column mappings, or empty if the project uses the site-wide configuration.")
    @ApiResponses({@ApiResponse(code = 200, message = "The project-level column mappings."),
                   @ApiResponse(code = 403, message = "Not authorized to view the project configuration."),
                   @ApiResponse(code = 404, message = "The project does not exist."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/projects/{projectId}/csv-column-mappings", method = GET, restrictTo = Authenticated, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getProjectColumnMappings(@ApiParam(value = "The ID of the project.", required = true) @PathVariable final String projectId) throws JsonProcessingException {
        final ResponseEntity<?> denied = verifyProjectAccess(projectId, false);
        if (denied != null) {
            return denied;
        }
        final List<CsvColumnMapping> mappings = configService.getColumnMappings(Scope.Project, projectId);
        return ResponseEntity.ok(toConfig(mappings != null ? mappings : Collections.emptyList()));
    }

    @ApiOperation(value = "Sets the project-level CSV column mappings, overriding the site-wide configuration for this project.")
    @ApiResponses({@ApiResponse(code = 200, message = "The stored column mappings."),
                   @ApiResponse(code = 400, message = "The submitted column mappings are invalid."),
                   @ApiResponse(code = 403, message = "Not authorized to change the project configuration."),
                   @ApiResponse(code = 404, message = "The project does not exist."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/projects/{projectId}/csv-column-mappings", method = POST, restrictTo = Authenticated, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setProjectColumnMappings(@ApiParam(value = "The ID of the project.", required = true) @PathVariable final String projectId,
                                                      @RequestBody final ColumnMappingsConfig config) throws JsonProcessingException {
        final ResponseEntity<?> denied = verifyProjectAccess(projectId, false);
        if (denied != null) {
            return denied;
        }
        final List<CsvColumnMapping> mappings;
        try {
            mappings = parse(config);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("Unable to parse column mappings JSON: " + e.getMessage());
        }
        if (mappings.isEmpty()) {
            return ResponseEntity.badRequest().body("Project CSV column mappings cannot be empty. Projects with no mappings of their own use the site-wide configuration.");
        }
        final List<CsvColumnMapping> stored = configService.createOrUpdateColumnMappings(getSessionUser(), Scope.Project, projectId, mappings);
        log.info("User {} updated the CSV column mappings for project {} ({} mapping(s))", getSessionUser().getUsername(), projectId, stored.size());
        return ResponseEntity.ok(toConfig(stored));
    }

    @ApiOperation(value = "Disables this project's CSV column mappings so imports fall back to the site-wide configuration. The mappings are retained and can be restored by saving them again.")
    @ApiResponses({@ApiResponse(code = 200, message = "The project mappings were disabled."),
                   @ApiResponse(code = 403, message = "Not authorized to administer the project configuration."),
                   @ApiResponse(code = 404, message = "The project does not exist."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/projects/{projectId}/csv-column-mappings/disable", method = POST, restrictTo = Authenticated, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> disableProjectColumnMappings(@ApiParam(value = "The ID of the project.", required = true) @PathVariable final String projectId) {
        final ResponseEntity<?> denied = verifyProjectAccess(projectId, true);
        if (denied != null) {
            return denied;
        }
        configService.disableColumnMappings(getSessionUser(), Scope.Project, projectId);
        log.info("User {} disabled the CSV column mappings for project {}", getSessionUser().getUsername(), projectId);
        return ResponseEntity.ok().build();
    }

    @ApiOperation(value = "Deletes this project's CSV column mappings so imports fall back to the site-wide configuration.")
    @ApiResponses({@ApiResponse(code = 200, message = "The project mappings were deleted."),
                   @ApiResponse(code = 403, message = "Not authorized to administer the project configuration."),
                   @ApiResponse(code = 404, message = "The project does not exist."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/projects/{projectId}/csv-column-mappings", method = DELETE, restrictTo = Authenticated, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteProjectColumnMappings(@ApiParam(value = "The ID of the project.", required = true) @PathVariable final String projectId) {
        final ResponseEntity<?> denied = verifyProjectAccess(projectId, true);
        if (denied != null) {
            return denied;
        }
        configService.deleteColumnMappings(getSessionUser(), Scope.Project, projectId);
        log.info("User {} deleted the CSV column mappings for project {}", getSessionUser().getUsername(), projectId);
        return ResponseEntity.ok().build();
    }

    /**
     * Verifies the session user may act on the given project's configuration.
     *
     * @param projectId    the target project
     * @param requireOwner when {@code true}, requires the user to be a project owner (used for
     *                     destructive operations); otherwise project edit access is sufficient
     *
     * @return a 404/403 response when the project is missing or the user is not permitted, or {@code null} when access is granted
     */
    private ResponseEntity<?> verifyProjectAccess(final String projectId, final boolean requireOwner) {
        if (!Permissions.verifyProjectExists(XDAT.getNamedParameterJdbcTemplate(), projectId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Project " + projectId + " does not exist");
        }
        final boolean permitted = requireOwner
                                  ? Permissions.isProjectOwner(getSessionUser(), projectId)
                                  : Permissions.canEditProject(getSessionUser(), projectId);
        if (!permitted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You do not have permission to administer settings for project " + projectId);
        }
        return null;
    }

    private List<CsvColumnMapping> parse(final ColumnMappingsConfig config) throws IOException {
        final String json = config == null ? null : config.getColumnMappings();
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        return mapper.readValue(json, new TypeReference<List<CsvColumnMapping>>() {});
    }

    private ColumnMappingsConfig toConfig(final List<CsvColumnMapping> mappings) throws JsonProcessingException {
        final ColumnMappingsConfig config = new ColumnMappingsConfig();
        config.setColumnMappings(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mappings));
        return config;
    }

    /**
     * Carrier for the column-mapping list as a JSON array string, matching the
     * {@code columnMappings} text area in the Spawner site and project settings
     * forms.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnMappingsConfig {
        private String columnMappings;
    }
}
