package org.nrg.xnatx.plugins.structimport.services;

import org.nrg.framework.constants.Scope;
import org.nrg.framework.services.NrgService;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyDisplayMapping;

import java.util.List;

/**
 * Stores and retrieves the CSV import column-to-property mapping configuration
 * used by the structured importer. Configurations are persisted via the XNAT
 * {@link org.nrg.config.services.ConfigService} at the site and project level.
 * A project-level configuration, when present, overrides the site-wide one.
 *
 * <p>The {@code property} of each mapping is the stable contract the importer
 * understands: the {@code PROP_*} constants below enumerate the XNAT property
 * paths the structured importer can populate. Administrators may customize the
 * {@code column} text, {@code required} flag, and {@code validation} regex of a
 * mapping, but the {@code property} value is what associates a column with a
 * particular piece of scan/session/subject metadata.</p>
 */
public interface CsvImportConfigService extends NrgService {

    String TOOL_NAME                      = "CsvBasedResourceIdentifierService";
    String COLUMN_MAPPINGS_PATH           = "column-mappings";
    String PROPERTY_DISPLAY_MAPPINGS_PATH = "property-display-mappings";

    /**
     * XNAT property paths the structured importer understands.
     */
    String PROP_SCAN_ID            = "xnat:imageScanData/ID";
    String PROP_MODALITY           = "xnat:imageScanData/modality";
    String PROP_SERIES_DESCRIPTION = "xnat:imageScanData/series_description";
    String PROP_SESSION_LABEL      = "xnat:imageSessionData/label";
    String PROP_START_DATE         = "xnat:imageScanData/start_date";
    String PROP_START_TIME         = "xnat:imageScanData/start_time";
    String PROP_SUBJECT_ID         = "xnat:imageSessionData/subject_ID";
    String PROP_SUBJECT_WEIGHT     = "xnat:subjectData/demographics[@xsi:type=xnat:demographicData]/weight";
    String PROP_RESOURCE_NAME      = "xnat:abstractResource/label";

    /**
     * The "path" column is special: it is required and must have a value, but it
     * identifies a file or directory within the archive and does not map to an
     * XNAT object property. It is represented by a mapping with a blank property.
     */
    String PROP_PATH = "";

    /**
     * Retrieves the effective column mappings for an import into the given
     * project. A project-level configuration overrides the site-wide one; if no
     * project-level configuration exists, the site-wide configuration is returned
     * (creating the default site-wide configuration if none exists yet).
     *
     * @param user      the user requesting the configuration
     * @param projectId the project the import targets; may be {@code null} or blank to use the site-wide configuration
     *
     * @return the effective list of column mappings
     */
    List<CsvColumnMapping> getColumnMappings(UserI user, String projectId);

    /**
     * Retrieves the column mappings configured at a specific scope.
     *
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     *
     * @return the configured mappings, or {@code null} if none are configured at that scope
     */
    List<CsvColumnMapping> getColumnMappings(Scope scope, String entityId);

    /**
     * Creates or updates the column mappings at a specific scope.
     *
     * @param user     the user making the change
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     * @param mappings the mappings to store
     *
     * @return the stored mappings
     */
    List<CsvColumnMapping> createOrUpdateColumnMappings(UserI user, Scope scope, String entityId, List<CsvColumnMapping> mappings);

    /**
     * Disables the column mappings configured at the given scope, if any. A
     * disabled configuration is retained (and can be restored by storing
     * mappings again) but is treated as absent when resolving the effective
     * mappings, so the next-broader scope applies. Disabling a project-level
     * configuration therefore causes imports into that project to fall back to
     * the site-wide configuration.
     *
     * @param user     the user making the change
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     */
    void disableColumnMappings(UserI user, Scope scope, String entityId);

    /**
     * Deletes the column mappings configured at the given scope by clearing
     * their contents, so the next-broader scope applies. Unlike
     * {@link #disableColumnMappings(UserI, Scope, String) disabling}, the
     * previous contents are not retained. Deleting a project-level configuration
     * causes imports into that project to fall back to the site-wide
     * configuration.
     *
     * @param user     the user making the change
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     */
    void deleteColumnMappings(UserI user, Scope scope, String entityId);

    /**
     * @return the built-in default site-wide column mappings.
     */
    List<CsvColumnMapping> getDefaultColumnMappings();

    /**
     * Retrieves the property display mappings, which associate human-readable
     * display values with XFT object property paths for the column-mapping
     * editors. These are maintained at the site level only: additions made
     * while configuring a project are available to all projects.
     *
     * @return the configured mappings, or the built-in defaults when none are stored
     */
    List<PropertyDisplayMapping> getPropertyDisplayMappings();

    /**
     * Replaces the site-wide property display mappings.
     *
     * @param user     the user making the change
     * @param mappings the full list of mappings to store
     *
     * @return the stored mappings
     */
    List<PropertyDisplayMapping> setPropertyDisplayMappings(UserI user, List<PropertyDisplayMapping> mappings);

    /**
     * @return the built-in default property display mappings.
     */
    List<PropertyDisplayMapping> getDefaultPropertyDisplayMappings();

    /**
     * Creates the default site-wide configurations (column mappings and
     * property display mappings) that do not exist yet. Intended to be called
     * once at start-up.
     *
     * @param user the user to attribute the configuration creation to
     */
    void initializeSiteConfiguration(UserI user);
}
