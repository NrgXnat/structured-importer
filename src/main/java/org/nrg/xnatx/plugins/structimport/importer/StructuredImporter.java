package org.nrg.xnatx.plugins.structimport.importer;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.om.XnatCtscandata;
import org.nrg.xdat.om.XnatCtsessiondata;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatMrscandata;
import org.nrg.xdat.om.XnatMrsessiondata;
import org.nrg.xdat.om.XnatPetscandata;
import org.nrg.xdat.om.XnatPetsessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSrscandata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.SaveItemHelper;
import org.nrg.xnat.helpers.file.StoredFile;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.restlet.actions.importer.ImporterHandler;
import org.nrg.xnat.restlet.actions.importer.ImporterHandlerA;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.restlet.util.XNATRestConstants;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.nrg.xft.event.XftItemEventI.CREATE;

@ImporterHandler(handler = StructuredImporter.IMPORTER_HANDLER)
@Getter(AccessLevel.PROTECTED)
@Slf4j
public class StructuredImporter extends ImporterHandlerA {
    public static final String       IMPORTER_HANDLER          = "Structured-Zip";
    public static final List<String> SUPPORTED_MODALITIES      = Arrays.asList("MR", "PET", "CT");
    public static final String       PARAM_RESOURCE_IDENTIFIER = ResourceIdentifierSelector.PARAM_RESOURCE_IDENTIFIER;

    private static final String EXTRACTED_FOLDER       = "extracted";
    private static final String PARAM_PROJECT          = "project";
    private static final String PARAM_SUBJECT          = "subject";
    private static final String PARAM_SESSION          = "session";
    private static final String PARAM_PRIMARY_MODALITY = "primary-modality";
    private static final String ROOT_URI               = "/archive/experiments/";
    private static final String RESOURCE_URI           = "/resources/%s/files";

    private final UserI                     user;
    private final UserDataCache             userDataCache;
    private final CatalogService            catalogService;
    private final ResourceIdentifierService resourceIdentifierService;
    private final FileWriterWrapperI        fileWriter;
    private final Map<String, Object>       parameters;
    private final String                    username;
    private final String                    filename;
    private final Path                      workingDirectory;
    private final String                    projectId;
    private final String                    subjectLabel;
    private final String                    sessionLabel;
    private final String                    primaryModality;

    public StructuredImporter(final Object listenerControl, final UserI user,
                              final FileWriterWrapperI fileWriter,
                              final Map<String, Object> parameters) throws ClientException {
        super(listenerControl, user);

        this.user                      = user;
        this.userDataCache             = XDAT.getContextService().getBean(UserDataCache.class);
        this.catalogService            = XDAT.getContextService().getBean(CatalogService.class);
        this.resourceIdentifierService = XDAT.getContextService().getBean(ResourceIdentifierSelector.select(parameters), ResourceIdentifierService.class);
        this.fileWriter                = fileWriter;
        this.parameters                = parameters;
        this.username                  = user.getUsername();
        this.filename                  = fileWriter.getName();
        this.workingDirectory          = determineWorkingDirectory();
        this.projectId                 = (String) parameters.get(PARAM_PROJECT);
        this.subjectLabel              = (String) parameters.get(PARAM_SUBJECT);
        this.sessionLabel              = (String) parameters.get(PARAM_SESSION);
        this.primaryModality           = (String) parameters.get(PARAM_PRIMARY_MODALITY);

        validateParameters();

        final File file = this.workingDirectory.toFile();
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
        file.deleteOnExit();
    }

    @Override
    public List<String> call() throws ClientException, ServerException {
        try {
            processing("Extracting file " + getFilename() + " for user " + getUsername() + " into folder " + getWorkingDirectory());
            try (final InputStream input = fileWriter.getInputStream()) {
                ArchiveExtractor.extract(input, getFilename(), getWorkingDirectory());
            }
            ArchiveExtractor.extractNested(getWorkingDirectory());
            processing("Extracted file " + getFilename() + " into folder " + getWorkingDirectory());

            final Map<ScanResource, List<Path>> resources = resourceIdentifierService.extractResource(getWorkingDirectory(), getUser(), getProjectId());
            processing("Identified " + resources.size() + " scan resource(s) in " + getWorkingDirectory());

            final Map<SessionContext, Map<ScanResource, List<Path>>> grouped = groupBySession(resources);
            processing("Grouped resources into " + grouped.size() + " session(s)");

            final List<String> uris = new ArrayList<>();
            for (final Map.Entry<SessionContext, Map<ScanResource, List<Path>>> entry : grouped.entrySet()) {
                uris.add(createSession(entry.getKey(), entry.getValue()));
            }
            if (uris.isEmpty()) {
                log.warn("Tried to import sessions from {} but didn't find anything actionable", getFilename());
                failed("Tried to import sessions from " + getFilename() + " but didn't find anything actionable", true);
            } else {
                if (uris.size() == 1) {
                    log.info("Completed import of session {}", uris.get(0));
                } else {
                    log.info("Completed import of {} sessions:\n * {}", uris.size(), String.join("\n * ", uris));
                }
                completed("Archive:" + String.join(";", uris), true);
            }
            return uris;
        } catch (IOException e) {
            throw new ClientException("Unable to read data from file " + getFilename(), e);
        } finally {
            final File workingDir = getWorkingDirectory().toFile();
            if (workingDir.exists()) {
                try {
                    FileUtils.deleteDirectory(workingDir);
                    log.debug("Deleted temporary folder: {}", getWorkingDirectory());
                } catch (IOException e) {
                    log.warn("Could not delete temporary folder: {}", getWorkingDirectory(), e);
                }
            }
        }
    }

    private Map<SessionContext, Map<ScanResource, List<Path>>> groupBySession(final Map<ScanResource, List<Path>> resources) throws ClientException {
        final Map<SessionContext, Map<ScanResource, List<Path>>> grouped = new LinkedHashMap<>();
        for (final Map.Entry<ScanResource, List<Path>> entry : resources.entrySet()) {
            final SessionContext context = resolveSessionContext(entry.getKey());
            grouped.computeIfAbsent(context, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }
        return grouped;
    }

    private SessionContext resolveSessionContext(final ScanResource resource) throws ClientException {
        final String subject = resolveValue(PARAM_SUBJECT, getSubjectLabel(), resource.getSubjectLabel());
        final String session = resolveValue(PARAM_SESSION, getSessionLabel(), resource.getSessionLabel());
        return new SessionContext(subject, session);
    }

    private String resolveValue(final String fieldName, final String fromParameters, final String fromResource) throws ClientException {
        final boolean hasParameter = StringUtils.isNotBlank(fromParameters);
        final boolean hasResource  = StringUtils.isNotBlank(fromResource);
        if (hasParameter && hasResource) {
            throw new ClientException("Conflict for " + fieldName + ": upload parameter specifies \"" + fromParameters + "\" but the resource identifier specifies \"" + fromResource + "\". Specify the value in only one location.");
        }
        if (hasParameter) {
            return fromParameters;
        }
        if (hasResource) {
            return fromResource;
        }
        throw new ClientException("Required " + fieldName + " was not specified in upload parameters or by the resource identifier service");
    }

    private String createSession(final SessionContext context, final Map<ScanResource, List<Path>> resources) throws ClientException, ServerException {
        final String               subjectLabel = context.getSubjectLabel();
        final String               sessionLabel = context.getSessionLabel();
        final XnatSubjectdata      subject      = getOrCreateSubject(subjectLabel, resources.keySet());
        final XnatImagesessiondata session      = createNewSession();

        try {
            session.setId(XnatExperimentdata.CreateNewID());
            session.setProject(getProjectId());
            session.setSubjectId(subject.getId());
            session.setLabel(sessionLabel);
            session.setModality(getPrimaryModality());

            SaveItemHelper.authorizedSave(session, getUser(), false, false, EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_FORM, "Created session " + sessionLabel + " for subject " + subjectLabel + " in project " + getProjectId()));

            for (final Map.Entry<ScanResource, List<Path>> entry : resources.entrySet()) {
                final ScanResource resource = entry.getKey();
                final List<Path>   sources  = entry.getValue();

                final XnatImagescandata scan = createScanObject(resource.getModality());
                scan.setImageSessionId(session.getId());
                scan.setId(resource.getScanId());
                scan.setSeriesDescription(StringUtils.defaultIfBlank(resource.getSeriesDescription(), resource.getScanId()));
                scan.setModality(resource.getModality());
                if (resource.getStartDate() != null) {
                    scan.setStartDate(java.sql.Date.valueOf(resource.getStartDate()));
                }
                if (resource.getStartTime() != null) {
                    scan.setStarttime(java.sql.Time.valueOf(resource.getStartTime()));
                }
                SaveItemHelper.authorizedSave(scan, getUser(), false, false, EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_FORM, "Created scan " + scan.getId() + " on session " + sessionLabel + " in project " + getProjectId()));
                log.info("Created scan {} for session {}", scan.getId(), session.getId());

                final XnatResourcecatalog catalog = createResourceCatalog(session.getId(), scan.getId(), resource.getName());
                log.info("Created catalog for resource {} on session {} scan {} at {}", resource.getName(), session.getId(), scan.getId(), catalog.getUri());

                final Path target = Paths.get(catalog.getUri()).getParent();
                log.info("Moving {} source(s) for resource {} into {}", sources.size(), resource.getName(), target);
                moveResourceFiles(sources, target);

                final String resourceUri = "/archive/experiments/" + session.getId() + "/scans/" + scan.getId() + "/resources/" + resource.getName();
                log.info("Refreshing catalog at {} via URI {}", catalog.getUri(), resourceUri);
                catalogService.refreshResourceCatalog(getUser(), resourceUri, CatalogService.Operation.All);
            }
        } catch (Exception e) {
            throw new ClientException("An error occurred while trying to create session " + sessionLabel + " for subject " + subjectLabel + " in project " + getProjectId(), e);
        }

        return UriParserUtils.getArchiveUri(session);
    }

    private void moveResourceFiles(final List<Path> sources, final Path target) throws IOException {
        for (final Path source : sources) {
            if (Files.isDirectory(source)) {
                moveDirectoryContents(source, target);
            } else {
                final Path targetPath = target.resolve(source.getFileName().toString());
                FileUtils.createParentDirectories(targetPath.toFile());
                log.debug("Moving resource file from {} to {}", source, targetPath);
                Files.move(source, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void moveDirectoryContents(final Path source, final Path target) throws IOException {
        try (final Stream<Path> stream = Files.walk(source)) {
            final List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            for (final Path file : files) {
                final Path relative   = source.relativize(file);
                final Path targetPath = target.resolve(relative.toString());
                FileUtils.createParentDirectories(targetPath.toFile());
                log.debug("Moving resource file in directory {} from {} to {}", source, file, targetPath);
                Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private XnatSubjectdata getOrCreateSubject(final String subjectLabel, final Iterable<ScanResource> resources) throws ClientException, ServerException {
        final XnatSubjectdata existing = XnatSubjectdata.GetSubjectByIdOrProjectlabelCaseInsensitive(getProjectId(), subjectLabel, getUser(), false);
        if (existing != null) {
            return existing;
        }
        log.info("Creating new subject {} in project {}", subjectLabel, projectId);
        final String subjectId;
        try {
            subjectId = XnatSubjectdata.CreateNewID();
        } catch (Exception e) {
            failed("unable to create new subject ID");
            throw new ServerException("Unable to create new subject ID", e);
        }
        final XnatSubjectdata created = new XnatSubjectdata();
        created.setId(subjectId);
        created.setLabel(subjectLabel);
        created.setProject(getProjectId());
        final Double weight = firstNonNullWeight(resources);
        if (weight != null) {
            try {
                created.setProperty("xnat:subjectData/demographics[@xsi:type=xnat:demographicData]/weight", weight);
            } catch (Exception e) {
                throw new ClientException("Unable to set weight on subject " + subjectLabel, e);
            }
        }
        try {
            SaveItemHelper.authorizedSave(created, getUser(), false, true, EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_FORM, "Created subject " + subjectLabel + " in project " + getProjectId()));
        } catch (Exception e) {
            throw new ClientException("An error occurred while trying to create subject " + subjectLabel + " in project " + getProjectId(), e);
        }
        XDAT.triggerXftItemEvent(created, CREATE);
        return XnatSubjectdata.GetSubjectByIdOrProjectlabelCaseInsensitive(getProjectId(), subjectLabel, getUser(), false);
    }

    private static Double firstNonNullWeight(final Iterable<ScanResource> resources) {
        for (final ScanResource resource : resources) {
            if (resource.getSubjectWeight() != null) {
                return resource.getSubjectWeight();
            }
        }
        return null;
    }

    private XnatImagesessiondata createNewSession() {
        switch (getPrimaryModality()) {
            case "MR":
                return new XnatMrsessiondata();
            case "PET":
                return new XnatPetsessiondata();
            case "CT":
                return new XnatCtsessiondata();
            default:
                throw new IllegalArgumentException("Invalid modality: " + getPrimaryModality());
        }
    }

    private XnatImagescandata createScanObject(final String modality) {
        switch (modality) {
            case "MR":
                return new XnatMrscandata();
            case "PET":
                return new XnatPetscandata();
            case "CT":
                return new XnatCtscandata();
            case "SR":
                return new XnatSrscandata();
            default:
                throw new IllegalArgumentException("Invalid modality: " + modality);
        }
    }

    private XnatResourcecatalog createResourceCatalog(final String sessionId, final String scanId, final String resourceName) throws ServerException {
        final String parentUri = ROOT_URI + sessionId + "/scans/" + scanId + String.format(RESOURCE_URI, resourceName);
        log.debug("Creating the {} folder for scan {} of session {} at URI {}", resourceName, scanId, sessionId, parentUri);
        try {
            final XnatResourcecatalog created = catalogService.createAndInsertResourceCatalog(getUser(), parentUri, 1, resourceName, resourceName + " resource for session " + sessionId + " scan " + scanId, null, null);
            log.debug("Created the {} folder for scan {} of session {} at URI {}", resourceName, scanId, sessionId, UriParserUtils.getArchiveUri(created));
            return created;
        } catch (Exception e) {
            throw new ServerException("An error occurred verifying the " + resourceName + " resource folder for scan " + scanId + " of session " + sessionId, e);
        }
    }

    private void validateParameters() throws ClientException {
        if (parameters == null || parameters.isEmpty()) {
            throw new ClientException("Missing required parameters");
        }
        if (StringUtils.isBlank(projectId)) {
            throw new ClientException("Missing required parameter: " + PARAM_PROJECT);
        }
        if (StringUtils.isBlank(primaryModality)) {
            throw new ClientException("Missing required parameter: " + PARAM_PRIMARY_MODALITY);
        }
        if (!SUPPORTED_MODALITIES.contains(primaryModality)) {
            throw new ClientException("This importer currently only supports the modalities \"" + String.join("\", \"", SUPPORTED_MODALITIES) + "\"");
        }
        if (!Permissions.verifyProjectExists(XDAT.getNamedParameterJdbcTemplate(), projectId)) {
            throw new ClientException("Project " + projectId + " does not exist");
        }
        if (!Permissions.canEditProject(getUser(), projectId)) {
            throw new ClientException("User " + getUsername() + " cannot edit project " + projectId);
        }
    }

    private Path determineWorkingDirectory() {
        return fileWriter instanceof StoredFile
               ? ((StoredFile) fileWriter).getStored().getParentFile().toPath().resolve(EXTRACTED_FOLDER)
               : userDataCache.getUserDataCache(user).resolve(XNATRestConstants.getPrearchiveTimestamp());
    }

    @Value
    private static class SessionContext {
        String subjectLabel;
        String sessionLabel;
    }
}
