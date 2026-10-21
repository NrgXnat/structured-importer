package org.nrg.xnatx.plugins.structimport.services;

import org.nrg.framework.constants.Scope;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMappingDetail;

import java.util.List;
import java.util.Optional;

/**
 * Stores the acquisition devices the structured importer knows and each one's
 * mapping CSV. Devices are persisted as a JSON array via the XNAT
 * {@link org.nrg.config.services.ConfigService} at the site and project level.
 * The devices available to a project are the site devices plus the project's
 * own; a project device replaces a site device with the same ID.
 *
 * <p>When an upload names a device, its mapping CSV supplies the column
 * mappings the CSV manifest is read with, in place of the project or
 * site-wide column mappings (see {@link CsvImportConfigService}).</p>
 */
public interface DeviceMappingService {

    String DEVICE_MAPPINGS_PATH = "device-mappings";
    String SCOPE_SITE           = "site";
    String SCOPE_PROJECT        = "project";

    /**
     * The devices stored at one scope. When nothing has been stored at the site yet, the site
     * devices are the ones bundled with the plugin.
     *
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     *
     * @return the devices, each with its {@code scope} set; empty when none are stored at a project
     */
    List<DeviceMapping> getDevices(Scope scope, String entityId);

    /**
     * The devices available for imports into a project: the site devices, with any project device of
     * the same ID in its place, followed by the project's other devices.
     *
     * @param projectId the project, or {@code null} for the site devices only
     */
    List<DeviceMapping> getEffectiveDevices(String projectId);

    /**
     * Looks up one device available for imports into a project.
     *
     * @param projectId the project, or {@code null} to look only at site devices
     * @param deviceId  the device ID (case-sensitive)
     */
    Optional<DeviceMapping> getEffectiveDevice(String projectId, String deviceId);

    /**
     * Parses the device's mapping CSV and converts it to the column mappings an import would use.
     */
    DeviceMappingDetail describe(DeviceMapping device);

    /**
     * Checks a device before it's stored.
     *
     * @return the problems found, or an empty list when the device can be stored and used for imports
     */
    List<String> validate(DeviceMapping device);

    /**
     * Adds a device at a scope, or replaces the device with the same ID there.
     *
     * @param user     the user making the change
     * @param scope    {@link Scope#Site} or {@link Scope#Project}
     * @param entityId {@code null} for site, the project ID for project scope
     * @param device   the device; it's expected to have passed {@link #validate(DeviceMapping)}
     *
     * @return the stored device
     */
    DeviceMapping saveDevice(UserI user, Scope scope, String entityId, DeviceMapping device);

    /**
     * Removes a device from a scope. Removing a project device that overrides a site device makes the
     * site device apply to the project again.
     *
     * @return whether a device with that ID was stored at the scope
     */
    boolean deleteDevice(UserI user, Scope scope, String entityId, String deviceId);

    /**
     * The column mappings an import into the project reads its manifest with when the upload names
     * the device.
     *
     * @throws IllegalStateException when the device isn't available to the project or its mapping
     *                               CSV can't drive an import; the message says why
     */
    List<CsvColumnMapping> getColumnMappings(String projectId, String deviceId);

    /**
     * @return the devices bundled with the plugin, with their mapping CSVs
     */
    List<DeviceMapping> getBundledDevices();

    /**
     * Stores the bundled devices as the site devices when no site devices are stored yet. A device
     * whose CSV was replaced in the deprecated device mapping plugin keeps the replacement. Intended to
     * be called once at start-up.
     *
     * @param user the user to attribute the configuration creation to
     */
    void initializeSiteConfiguration(UserI user);
}
