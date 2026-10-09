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
import org.nrg.xdat.security.helpers.Roles;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyDisplayMapping;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvManifestTemplate;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.PropertyDisplayMappingValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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

    private static final String    TEXT_CSV      = "text/csv";
    // The importer reads manifests as UTF-8, so templates must be written as UTF-8 too; without an
    // explicit charset, XNAT's StringHttpMessageConverter falls back to ISO-8859-1
    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

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

    @ApiOperation(value = "Returns the property display mappings that populate the Property drop-down in the CSV column-mapping editors. These are maintained site-wide.",
                  response = PropertyDisplayMapping.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "The property display mappings."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/property-display-mappings", method = GET, restrictTo = Authenticated, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PropertyDisplayMapping> getPropertyDisplayMappings() {
        return configService.getPropertyDisplayMappings();
    }

    @ApiOperation(value = "Adds a property display mapping, or updates an existing one when the \"replaces\" parameter names the display value being edited. Mappings are site-wide: additions made from a project's settings are available to all projects. Editing requires site administrator privileges.")
    @ApiResponses({@ApiResponse(code = 200, message = "The stored property display mappings."),
                   @ApiResponse(code = 400, message = "The submitted mapping is invalid; the response body describes the problems."),
                   @ApiResponse(code = 403, message = "Not authorized to edit existing mappings."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/property-display-mappings", method = POST, restrictTo = Authenticated, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> savePropertyDisplayMapping(@RequestBody final PropertyDisplayMapping mapping,
                                                        @ApiParam("When editing, the display value of the mapping being replaced.")
                                                        @RequestParam(value = "replaces", required = false) final String replaces) {
        final String originalDisplay = StringUtils.trimToNull(replaces);
        if (originalDisplay != null && !Roles.isSiteAdmin(getSessionUser())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Only site administrators may edit existing property display mappings.");
        }

        final List<PropertyDisplayMapping> current = new ArrayList<>(configService.getPropertyDisplayMappings());
        final List<String> errors = PropertyDisplayMappingValidator.validate(current, mapping, originalDisplay, modalityDataTypeService);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("\n", errors));
        }

        final PropertyDisplayMapping cleaned = PropertyDisplayMapping.builder()
                                                                     .display(mapping.getDisplay().trim())
                                                                     .property(mapping.getProperty().trim())
                                                                     .build();
        final int index = originalDisplay == null ? -1 : indexOfDisplay(current, originalDisplay);
        if (index >= 0) {
            current.set(index, cleaned);
        } else {
            current.add(cleaned);
        }
        final List<PropertyDisplayMapping> stored = configService.setPropertyDisplayMappings(getSessionUser(), current);
        log.info("User {} {} the property display mapping \"{}\" -> {}", getSessionUser().getUsername(),
                 originalDisplay == null ? "added" : "updated", cleaned.getDisplay(), cleaned.getProperty());
        return ResponseEntity.ok(stored);
    }

    @ApiOperation(value = "Deletes a property display mapping by its display value.")
    @ApiResponses({@ApiResponse(code = 200, message = "The remaining property display mappings."),
                   @ApiResponse(code = 403, message = "Not authorized to delete mappings."),
                   @ApiResponse(code = 404, message = "No mapping exists with that display value."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/property-display-mappings/{display}", method = DELETE, restrictTo = Admin, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deletePropertyDisplayMapping(@ApiParam(value = "The display value of the mapping to delete.", required = true) @PathVariable final String display) {
        final List<PropertyDisplayMapping> current = new ArrayList<>(configService.getPropertyDisplayMappings());
        final int index = indexOfDisplay(current, display);
        if (index < 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No property display mapping exists with the display value \"" + display + "\"");
        }
        current.remove(index);
        final List<PropertyDisplayMapping> stored = configService.setPropertyDisplayMappings(getSessionUser(), current);
        log.info("User {} deleted the property display mapping \"{}\"", getSessionUser().getUsername(), display);
        return ResponseEntity.ok(stored);
    }

    private static int indexOfDisplay(final List<PropertyDisplayMapping> mappings, final String display) {
        for (int i = 0; i < mappings.size(); i++) {
            if (StringUtils.equalsIgnoreCase(mappings.get(i).getDisplay(), display)) {
                return i;
            }
        }
        return -1;
    }

    @ApiOperation(value = "Returns the site-wide CSV column mappings.")
    @ApiResponses({@ApiResponse(code = 200, message = "The site-wide column mappings."),
                   @ApiResponse(code = 403, message = "Not authorized to view the site configuration."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/csv-column-mappings", method = GET, restrictTo = Admin, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ColumnMappingsConfig> getSiteColumnMappings() throws JsonProcessingException {
        return ResponseEntity.ok(toConfig(siteColumnMappings()));
    }

    @ApiOperation(value = "Downloads a blank CSV manifest whose header row holds the site-wide column names, in configuration order.")
    @ApiResponses({@ApiResponse(code = 200, message = "The CSV manifest template."),
                   @ApiResponse(code = 403, message = "Not authorized to view the site configuration."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/csv-column-mappings/template", method = GET, restrictTo = Admin, produces = TEXT_CSV)
    public ResponseEntity<String> getSiteManifestTemplate() {
        return manifestTemplate(templateMappings(null), null);
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

    @ApiOperation(value = "Downloads a blank CSV manifest whose header row holds the column names that apply to imports into the project, in configuration order: the project's own mappings, or the site-wide mappings when the project has none.")
    @ApiResponses({@ApiResponse(code = 200, message = "The CSV manifest template."),
                   @ApiResponse(code = 403, message = "Not authorized to view the project configuration."),
                   @ApiResponse(code = 404, message = "The project does not exist."),
                   @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = "/projects/{projectId}/csv-column-mappings/template", method = GET, restrictTo = Authenticated, produces = TEXT_CSV)
    public ResponseEntity<?> getProjectManifestTemplate(@ApiParam(value = "The ID of the project.", required = true) @PathVariable final String projectId) {
        final ResponseEntity<?> denied = verifyProjectAccess(projectId, false);
        if (denied != null) {
            return denied;
        }
        return manifestTemplate(templateMappings(projectId), projectId);
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

    /**
     * The site-wide mappings, or the built-in defaults when none are stored yet
     * (which is what an import would then use).
     */
    private List<CsvColumnMapping> siteColumnMappings() {
        final List<CsvColumnMapping> mappings = configService.getColumnMappings(Scope.Site, null);
        return mappings != null ? mappings : configService.getDefaultColumnMappings();
    }

    /**
     * The mappings a template is built from, which are the ones an import would use: the
     * project's own mappings when it has enabled ones, otherwise the site-wide mappings, otherwise
     * the built-in defaults. Unlike {@link CsvImportConfigService#getColumnMappings(org.nrg.xft.security.UserI, String)},
     * this never stores the defaults as a side effect of a download.
     *
     * @param projectId the project the template is for, or {@code null} for the site-wide template
     */
    List<CsvColumnMapping> templateMappings(final String projectId) {
        if (projectId != null) {
            final List<CsvColumnMapping> projectMappings = configService.getColumnMappings(Scope.Project, projectId);
            if (projectMappings != null) {
                return projectMappings;
            }
        }
        return siteColumnMappings();
    }

    private static ResponseEntity<String> manifestTemplate(final List<CsvColumnMapping> mappings, final String projectId) {
        return ResponseEntity.ok()
                             .contentType(TEXT_CSV_UTF8)
                             .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + CsvManifestTemplate.filename(projectId, LocalDateTime.now()) + "\"")
                             .body(CsvManifestTemplate.build(mappings));
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
