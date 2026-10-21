package org.nrg.xnatx.plugins.structimport.services.impl.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.config.entities.Configuration;
import org.nrg.config.exceptions.ConfigServiceException;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.constants.Scope;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.structimport.models.CsvColumnMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMapping;
import org.nrg.xnatx.plugins.structimport.models.DeviceMappingDetail;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.nrg.xnatx.plugins.structimport.services.DeviceMappingService;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stores the structured importer's devices as a JSON array in the XNAT
 * {@link ConfigService}, under the same tool as the CSV column mappings, at
 * the site and project level. The bundled devices are read from
 * {@code META-INF/xnat/structimport/device-mappings/devices.json} and the CSVs
 * beside it.
 */
@Service
@Slf4j
public class DefaultDeviceMappingService implements DeviceMappingService {

    static final String BUNDLED_ROOT = "META-INF/xnat/structimport/device-mappings/";

    // Where the deprecated device mapping plugin stored a site admin's replacement CSV for a device
    static final String LEGACY_TOOL_NAME   = "devicemapping";
    static final String LEGACY_PATH_PREFIX = "mappings/";

    private static final Pattern      DEVICE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final List<String> STATUSES  = Arrays.asList(DeviceMapping.STATUS_VERIFIED, DeviceMapping.STATUS_DRAFT);

    private final ConfigService           configService;
    private final ModalityDataTypeService modalityDataTypeService;
    private final ObjectMapper            mapper;

    @Autowired
    public DefaultDeviceMappingService(final ConfigService configService, final ModalityDataTypeService modalityDataTypeService) {
        this.configService           = configService;
        this.modalityDataTypeService = modalityDataTypeService;
        this.mapper                  = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @Override
    public List<DeviceMapping> getDevices(final Scope scope, final String entityId) {
        final List<DeviceMapping> devices = storedOrDefault(scope, entityId);
        final String              label = scope == Scope.Site ? SCOPE_SITE : SCOPE_PROJECT;
        return devices.stream().map(device -> device.toBuilder().scope(label).overridesSite(null).build()).collect(Collectors.toList());
    }

    @Override
    public List<DeviceMapping> getEffectiveDevices(final String projectId) {
        final Map<String, DeviceMapping> effective = new LinkedHashMap<>();
        for (final DeviceMapping device : getDevices(Scope.Site, null)) {
            effective.put(device.getId(), device);
        }
        if (StringUtils.isNotBlank(projectId)) {
            for (final DeviceMapping device : getDevices(Scope.Project, projectId)) {
                final boolean overrides = effective.containsKey(device.getId());
                effective.put(device.getId(), device.toBuilder().overridesSite(overrides).build());
            }
        }
        return new ArrayList<>(effective.values());
    }

    @Override
    public Optional<DeviceMapping> getEffectiveDevice(final String projectId, final String deviceId) {
        return getEffectiveDevices(projectId).stream().filter(device -> StringUtils.equals(device.getId(), deviceId)).findFirst();
    }

    @Override
    public DeviceMappingDetail describe(final DeviceMapping device) {
        final DeviceMappingCsv.Parsed     parsed   = DeviceMappingCsv.parse(device.getCsv());
        final List<String>                problems = new ArrayList<>(parsed.getProblems());
        final List<String>                warnings = new ArrayList<>();
        final List<CsvColumnMapping>      columnMappings;
        if (problems.isEmpty()) {
            final DeviceMappingCsv.Conversion conversion = DeviceMappingCsv.toColumnMappings(parsed.getRows(), modalityDataTypeService.getModalityMappings().values());
            problems.addAll(conversion.getProblems());
            warnings.addAll(conversion.getWarnings());
            columnMappings = conversion.getColumnMappings();
        } else {
            columnMappings = Collections.emptyList();
        }
        if (StringUtils.isNotBlank(device.getModality()) && !modalityDataTypeService.getModalityMapping(device.getModality()).isPresent()) {
            warnings.add("The modality \"" + device.getModality() + "\" is not configured for the structured importer, so it can't be preselected as the primary modality and manifest rows can't use it.");
        }
        return DeviceMappingDetail.builder()
                                  .device(device)
                                  .rows(parsed.getRows())
                                  .columnMappings(columnMappings)
                                  .problems(problems)
                                  .warnings(warnings)
                                  .build();
    }

    @Override
    public List<String> validate(final DeviceMapping device) {
        final List<String> problems = new ArrayList<>();
        if (device == null) {
            problems.add("No device was submitted.");
            return problems;
        }
        final String id = StringUtils.trimToEmpty(device.getId());
        if (!DEVICE_ID.matcher(id).matches()) {
            problems.add("The device ID \"" + id + "\" is invalid. Use 1 to 64 letters, digits, '_' or '-', starting with a letter or digit.");
        }
        if (StringUtils.isBlank(device.getName())) {
            problems.add("The device needs a name.");
        }
        final String status = StringUtils.trimToNull(device.getStatus());
        if (status != null && !STATUSES.contains(status)) {
            problems.add("The status \"" + status + "\" is invalid. Use " + String.join(" or ", STATUSES) + ".");
        }
        problems.addAll(describe(device).getProblems());
        return problems;
    }

    @Override
    public DeviceMapping saveDevice(final UserI user, final Scope scope, final String entityId, final DeviceMapping device) {
        final DeviceMapping       cleaned = clean(device);
        final List<DeviceMapping> devices = new ArrayList<>(storedOrDefault(scope, entityId));
        final int                 index   = indexOf(devices, cleaned.getId());
        if (index >= 0) {
            devices.set(index, cleaned);
        } else {
            devices.add(cleaned);
        }
        store(user, scope, entityId, devices, (index >= 0 ? "Updated" : "Added") + " structured importer device " + cleaned.getId());
        return cleaned.toBuilder().scope(scope == Scope.Site ? SCOPE_SITE : SCOPE_PROJECT).build();
    }

    @Override
    public boolean deleteDevice(final UserI user, final Scope scope, final String entityId, final String deviceId) {
        final List<DeviceMapping> devices = new ArrayList<>(storedOrDefault(scope, entityId));
        final int                 index   = indexOf(devices, deviceId);
        if (index < 0) {
            return false;
        }
        devices.remove(index);
        store(user, scope, entityId, devices, "Deleted structured importer device " + deviceId);
        return true;
    }

    @Override
    public List<CsvColumnMapping> getColumnMappings(final String projectId, final String deviceId) {
        final DeviceMapping device = getEffectiveDevice(projectId, deviceId).orElseThrow(() -> new IllegalStateException(
                "No device with the ID \"" + deviceId + "\" is available" + (StringUtils.isBlank(projectId) ? "" : " to project " + projectId)));
        final DeviceMappingDetail detail = describe(device);
        if (!detail.getProblems().isEmpty()) {
            throw new IllegalStateException("The mapping CSV of the device \"" + device.getName() + "\" can't be used for imports: " + String.join(" ", detail.getProblems()));
        }
        return detail.getColumnMappings();
    }

    @Override
    public List<DeviceMapping> getBundledDevices() {
        try (final InputStream input = new ClassPathResource(BUNDLED_ROOT + "devices.json").getInputStream()) {
            final List<DeviceMapping> devices = new ArrayList<>();
            for (final JsonNode node : mapper.readTree(input)) {
                final DeviceMapping device = mapper.treeToValue(node, DeviceMapping.class);
                device.setCsv(readBundledCsv(node.path("mappingFile").asText()));
                devices.add(device);
            }
            return devices;
        } catch (IOException e) {
            log.error("Unable to read the structured importer's bundled devices", e);
            throw new RuntimeException("Unable to read the structured importer's bundled devices", e);
        }
    }

    @Override
    public void initializeSiteConfiguration(final UserI user) {
        if (readStored(Scope.Site, null) != null) {
            log.debug("Site-wide structured importer devices already exist; skipping default creation.");
            return;
        }
        final List<DeviceMapping> devices = new ArrayList<>();
        for (final DeviceMapping device : getBundledDevices()) {
            final String legacyCsv = legacyCsv(device.getId());
            if (legacyCsv != null) {
                log.info("Using the CSV the device mapping plugin stored for device {} in place of the bundled one", device.getId());
                device.setCsv(legacyCsv);
            }
            devices.add(device);
        }
        log.info("Creating the default site-wide structured importer devices ({} device(s)).", devices.size());
        store(user, Scope.Site, null, devices, "Creating default structured importer devices");
    }

    /**
     * @return the devices stored at the scope, or {@code null} when nothing (or a disabled configuration) is stored there
     */
    private List<DeviceMapping> readStored(final Scope scope, final String entityId) {
        final Configuration config = configService.getConfig(CsvImportConfigService.TOOL_NAME, DEVICE_MAPPINGS_PATH, scope, entityId);
        if (config == null || config.getConfigData() == null || StringUtils.isBlank(config.getConfigData().getContents())
            || Configuration.DISABLED_STRING.equalsIgnoreCase(config.getStatus())) {
            return null;
        }
        try {
            return mapper.readValue(config.getConfigData().getContents(), new TypeReference<List<DeviceMapping>>() {});
        } catch (IOException e) {
            log.error("Error parsing structured importer devices for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to parse structured importer devices", e);
        }
    }

    private List<DeviceMapping> storedOrDefault(final Scope scope, final String entityId) {
        final List<DeviceMapping> stored = readStored(scope, entityId);
        return stored != null ? stored : scope == Scope.Site ? getBundledDevices() : Collections.emptyList();
    }

    private void store(final UserI user, final Scope scope, final String entityId, final List<DeviceMapping> devices, final String reason) {
        final String json;
        try {
            json = mapper.writeValueAsString(devices.stream().map(device -> device.toBuilder().scope(null).overridesSite(null).build()).collect(Collectors.toList()));
        } catch (JsonProcessingException e) {
            log.error("Error serializing structured importer devices", e);
            throw new RuntimeException("Unable to serialize structured importer devices", e);
        }
        try {
            configService.replaceConfig(user.getUsername(), reason, CsvImportConfigService.TOOL_NAME, DEVICE_MAPPINGS_PATH, json, scope, entityId);
        } catch (ConfigServiceException e) {
            log.error("Error storing structured importer devices for scope {} entity {}", scope, entityId, e);
            throw new RuntimeException("Unable to store structured importer devices", e);
        }
    }

    private String readBundledCsv(final String mappingFile) throws IOException {
        try (final InputStream input = new ClassPathResource(BUNDLED_ROOT + mappingFile).getInputStream()) {
            return DeviceMappingCsv.stripBom(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
        }
    }

    private String legacyCsv(final String deviceId) {
        try {
            final Configuration config = configService.getConfig(LEGACY_TOOL_NAME, LEGACY_PATH_PREFIX + deviceId);
            if (config == null || !Configuration.ENABLED_STRING.equalsIgnoreCase(config.getStatus()) || StringUtils.isBlank(config.getContents())) {
                return null;
            }
            return DeviceMappingCsv.stripBom(config.getContents());
        } catch (RuntimeException e) {
            log.warn("Unable to read the device mapping plugin's CSV for device {}; using the bundled CSV", deviceId, e);
            return null;
        }
    }

    private static DeviceMapping clean(final DeviceMapping device) {
        final List<String> formats = device.getNativeFormats() == null ? null
                                     : device.getNativeFormats().stream().map(StringUtils::trimToNull).filter(Objects::nonNull).collect(Collectors.toList());
        return device.toBuilder()
                     .id(StringUtils.trim(device.getId()))
                     .name(StringUtils.trim(device.getName()))
                     .category(StringUtils.trimToNull(device.getCategory()))
                     .manufacturer(StringUtils.trimToNull(device.getManufacturer()))
                     .model(StringUtils.trimToNull(device.getModel()))
                     .modality(StringUtils.trimToNull(device.getModality()))
                     .xsiType(StringUtils.trimToNull(device.getXsiType()))
                     .metadataSource(StringUtils.trimToNull(device.getMetadataSource()))
                     .nativeFormats(formats == null || formats.isEmpty() ? null : formats)
                     .status(StringUtils.defaultIfBlank(StringUtils.trim(device.getStatus()), DeviceMapping.STATUS_DRAFT))
                     .notes(StringUtils.trimToNull(device.getNotes()))
                     .csv(DeviceMappingCsv.stripBom(device.getCsv()))
                     .scope(null)
                     .overridesSite(null)
                     .build();
    }

    private static int indexOf(final List<DeviceMapping> devices, final String deviceId) {
        for (int i = 0; i < devices.size(); i++) {
            if (StringUtils.equals(devices.get(i).getId(), deviceId)) {
                return i;
            }
        }
        return -1;
    }
}
