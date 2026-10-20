package org.nrg.xnatx.plugins.structimport.importer;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.base.BaseElement;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.SaveItemHelper;
import org.nrg.xnat.DicomObjectIdentifier;
import org.nrg.xnat.helpers.file.StoredFile;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.restlet.actions.importer.ImporterHandler;
import org.nrg.xnat.restlet.actions.importer.ImporterHandlerA;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.restlet.util.XNATRestConstants;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.models.PropertyTargets;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.ResourceIdentifierService.ScanResource;
import org.restlet.data.Status;
import org.springframework.beans.BeansException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.nrg.xft.event.XftItemEventI.CREATE;

@ImporterHandler(handler = StructuredImporter.IMPORTER_HANDLER)
@Getter(AccessLevel.PROTECTED)
@Slf4j
public class StructuredImporter extends ImporterHandlerA {
    public static final String IMPORTER_HANDLER          = "Structured-Zip";
    public static final String NO_FILES_IMPORTED_PREFIX  = "NoFilesImported:";

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
    private final String                    resourceIdentifierServiceName;
    private final ModalityDataTypeService   modalityDataTypeService;
    private final FileWriterWrapperI        fileWriter;
    private final Map<String, Object>       parameters;
    private final String                    username;
    private final String                    filename;
    private final Path                      workingDirectory;
    private final String                    projectId;
    private final String                    subjectLabel;
    private final String                    sessionLabel;
    private final String                    primaryModality;

    private ResourceIdentifierService resourceIdentifierService;
    private boolean                   validated;
    private boolean                   terminalStatusPublished;

    /**
     * Only captures the upload's state: validation happens in {@link #setIdentifier(DicomObjectIdentifier)}.
     * Core builds importers reflectively, so anything thrown here reaches the caller wrapped in an
     * InvocationTargetException, which core reports as a message-less HTTP 500.
     */
    public StructuredImporter(final Object listenerControl, final UserI user,
                              final FileWriterWrapperI fileWriter,
                              final Map<String, Object> parameters) {
        super(listenerControl, user);

        this.user                          = user;
        this.userDataCache                 = XDAT.getContextService().getBean(UserDataCache.class);
        this.catalogService                = XDAT.getContextService().getBean(CatalogService.class);
        this.resourceIdentifierServiceName = ResourceIdentifierSelector.select(parameters);
        this.modalityDataTypeService       = XDAT.getContextService().getBean(ModalityDataTypeService.class);
        this.fileWriter                    = fileWriter;
        this.parameters                    = parameters;
        this.username                      = user.getUsername();
        this.filename                      = fileWriter.getName();
        this.workingDirectory              = determineWorkingDirectory();
        this.projectId                     = (String) parameters.get(PARAM_PROJECT);
        this.subjectLabel                  = (String) parameters.get(PARAM_SUBJECT);
        this.sessionLabel                  = (String) parameters.get(PARAM_SESSION);
        this.primaryModality               = (String) parameters.get(PARAM_PRIMARY_MODALITY);
    }

    /**
     * Validates the upload parameters while the request is still being handled. Core's buildImporter calls
     * this directly (not reflectively) right after construction, before the import is submitted to the
     * executor for asynchronous uploads, and Importer.handlePost turns an IllegalArgumentException from
     * buildImporter into HTTP 400 with the exception's message. Validating in {@link #call()} alone is too
     * late for asynchronous uploads: core has already responded 200 "Submitted for archival" by then.
     */
    @Override
    public ImporterHandlerA setIdentifier(final DicomObjectIdentifier<XnatProjectdata> identifier) {
        super.setIdentifier(identifier);
        try {
            validateParameters();
        } catch (ClientException e) {
            log.warn("Rejected structured import of {}: {}", getFilename(), e.getMessage());
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return this;
    }

    @Override
    public List<String> call() throws ClientException, ServerException {
        //noinspection TryWithIdenticalCatches
        try {
            // Normally already done by setIdentifier() when core built the importer
            if (!validated) {
                validateParameters();
            }

            final File workingDir = getWorkingDirectory().toFile();
            //noinspection ResultOfMethodCallIgnored
            workingDir.mkdirs();
            workingDir.deleteOnExit();

            processing("Extracting file " + getFilename() + " for user " + getUsername() + " into folder " + getWorkingDirectory());
            try (final InputStream input = fileWriter.getInputStream()) {
                ArchiveExtractor.extract(input, getFilename(), getWorkingDirectory());
            }
            ArchiveExtractor.extractNested(getWorkingDirectory());
            processing("Extracted file " + getFilename() + " into folder " + getWorkingDirectory());

            final Map<ScanResource, List<Path>> resources = resourceIdentifierService.extractResource(getWorkingDirectory(), getUser(), getProjectId());
            processing("Identified " + resources.size() + " scan resource(s) in " + getWorkingDirectory());

            CustomLabelingValidator.validate(resources.keySet(), getSubjectLabel(), getSessionLabel());

            final Map<SessionContext, Map<ScanResource, List<Path>>> grouped = groupBySession(resources);
            processing("Grouped resources into " + grouped.size() + " session(s)");

            final List<String> uris = new ArrayList<>();
            for (final Map.Entry<SessionContext, Map<ScanResource, List<Path>>> entry : grouped.entrySet()) {
                uris.add(createSession(entry.getKey(), entry.getValue()));
            }
            if (uris.isEmpty()) {
                // The upload itself succeeded, so report that rather than a failure: the activity tab gets a
                // terminal non-failure message (rendered by importActivity.js) and a synchronous request
                // gets 204 No Content, which the core Importer resource sets from the exception's status.
                final String message = "The archive " + getFilename() + " was uploaded successfully, but no files were imported because no scan resources were found in it.";
                log.warn("No scan resources found in {}, nothing imported", getFilename());
                publishCompleted(NO_FILES_IMPORTED_PREFIX + message);
                throw new ClientException(Status.SUCCESS_NO_CONTENT, message);
            }
            if (uris.size() == 1) {
                log.info("Completed import of session {}", uris.get(0));
            } else {
                log.info("Completed import of {} sessions:\n * {}", uris.size(), String.join("\n * ", uris));
            }
            publishCompleted("Archive:" + String.join(";", uris));
            return uris;
        } catch (ClientException e) {
            throw reportFailure(e);
        } catch (ServerException e) {
            throw reportFailure(e);
        } catch (IOException e) {
            throw reportFailure(new ClientException("Unable to read data from file " + getFilename() + ": " + e.getMessage(), e));
        } catch (IllegalStateException e) {
            // Resource identifier services report manifest and configuration problems this way
            throw reportFailure(new ClientException("An error was found in the file " + getFilename() + ": " + e.getMessage(), e));
        } catch (RuntimeException e) {
            throw reportFailure(new ServerException("An unexpected error occurred importing the file " + getFilename() + ": " + e.getMessage(), e));
        } catch (Error e) {
            // Usually a NoSuchMethodError or NoClassDefFoundError from running against a different XNAT version
            throw reportFailure(e, "A system error occurred importing the file " + getFilename() + ": " + e);
        } finally {
            if (!terminalStatusPublished) {
                // Only reached when publishing the failure above itself failed, e.g. while out of memory
                publishFailed("The import of the file " + getFilename() + " ended unexpectedly");
            }
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

    /**
     * Publishes a terminal failure status for the throwable before it's rethrown. When the import runs
     * asynchronously (the compressed uploader), the executor discards the throwable, so without this the
     * activity tab never learns the import ended. Nothing is published when a terminal status already went
     * out, such as for the 204 "no files imported" exception.
     */
    private <T extends Throwable> T reportFailure(final T throwable) {
        return reportFailure(throwable, StringUtils.defaultIfBlank(throwable.getMessage(), throwable.getClass().getSimpleName()));
    }

    private <T extends Throwable> T reportFailure(final T throwable, final String message) {
        if (!terminalStatusPublished) {
            log.error("Import of {} failed", getFilename(), throwable);
            publishFailed(message);
        }
        return throwable;
    }

    private void publishCompleted(final String message) {
        completed(message, true);
        terminalStatusPublished = true;
    }

    /**
     * Publishes a terminal failure status. This is best-effort: it's called while handling another throwable,
     * which must not be masked if publishing fails too.
     */
    private void publishFailed(final String message) {
        try {
            failed(message, true);
            terminalStatusPublished = true;
        } catch (Throwable t) {
            log.error("Unable to publish the failure status for the import of {}: {}", getFilename(), message, t);
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
        final String subject = LabelResolver.resolve(PARAM_SUBJECT, getSubjectLabel(), resource.getSubjectLabel());
        final String session = LabelResolver.resolve(PARAM_SESSION, getSessionLabel(), resource.getSessionLabel());
        return new SessionContext(subject, session);
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
            CustomPropertyApplier.applyProperties(session, PropertyTargets.TargetType.SESSION,
                                                  CustomPropertyApplier.collectProperties(resources.keySet(), PropertyTargets.TargetType.SESSION, getModalityDataTypes()),
                                                  getModalityDataTypes());

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
                CustomPropertyApplier.applyProperties(scan, PropertyTargets.TargetType.SCAN, resource.getCustomProperties(), getModalityDataTypes());
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
        } catch (ClientException e) {
            throw e;
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
        CustomPropertyApplier.applyProperties(created, PropertyTargets.TargetType.SUBJECT,
                                              CustomPropertyApplier.collectProperties(resources, PropertyTargets.TargetType.SUBJECT, getModalityDataTypes()),
                                              getModalityDataTypes());
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

    private XnatImagesessiondata createNewSession() throws ClientException {
        final ModalityMapping mapping  = requireModalityMapping(getPrimaryModality());
        final String          dataType = mapping.getSession();
        if (StringUtils.isBlank(dataType)) {
            throw new ClientException("The modality \"" + mapping.getModality() + "\" has no session data type configured");
        }
        final Object instance = instantiateDataType(dataType);
        if (!(instance instanceof XnatImagesessiondata)) {
            throw new ClientException("The data type " + dataType + " configured for sessions of modality \"" + mapping.getModality() + "\" is not an image session data type");
        }
        return (XnatImagesessiondata) instance;
    }

    private XnatImagescandata createScanObject(final String modality) throws ClientException {
        final ModalityMapping mapping  = requireModalityMapping(modality);
        final String          dataType = mapping.getScan();
        if (StringUtils.isBlank(dataType)) {
            throw new ClientException("The modality \"" + mapping.getModality() + "\" has no scan data type configured");
        }
        final Object instance = instantiateDataType(dataType);
        if (!(instance instanceof XnatImagescandata)) {
            throw new ClientException("The data type " + dataType + " configured for scans of modality \"" + mapping.getModality() + "\" is not an image scan data type");
        }
        return (XnatImagescandata) instance;
    }

    private Collection<ModalityMapping> getModalityDataTypes() {
        return modalityDataTypeService.getModalityMappings().values();
    }

    private ModalityMapping requireModalityMapping(final String modality) throws ClientException {
        final Optional<ModalityMapping> mapping = modalityDataTypeService.getModalityMapping(modality);
        if (!mapping.isPresent()) {
            throw new ClientException("The modality \"" + modality + "\" is not configured for the structured importer");
        }
        return mapping.get();
    }

    private Object instantiateDataType(final String dataType) throws ClientException {
        try {
            return BaseElement.GetGeneratedItem(XFTItem.NewItem(dataType, getUser()));
        } catch (Exception e) {
            throw new ClientException("Unable to create an instance of data type " + dataType + "; is that data type installed on this server?", e);
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
        final Optional<ModalityMapping> mapping = modalityDataTypeService.getModalityMapping(primaryModality);
        if (!mapping.isPresent() || !mapping.get().hasSessionDataType()) {
            throw new ClientException("The modality \"" + primaryModality + "\" is not configured for session creation; supported session modalities: "
                                      + modalityDataTypeService.getSessionModalities().stream().map(ModalityMapping::getModality).collect(Collectors.joining(", ")));
        }
        if (!Permissions.verifyProjectExists(XDAT.getNamedParameterJdbcTemplate(), projectId)) {
            throw new ClientException("Project " + projectId + " does not exist");
        }
        if (!Permissions.canEditProject(getUser(), projectId)) {
            throw new ClientException("User " + getUsername() + " cannot edit project " + projectId);
        }
        resourceIdentifierService = resolveResourceIdentifierService();
        validated = true;
    }

    private ResourceIdentifierService resolveResourceIdentifierService() throws ClientException {
        try {
            return XDAT.getContextService().getBean(resourceIdentifierServiceName, ResourceIdentifierService.class);
        } catch (BeansException e) {
            throw new ClientException("Unknown resource identifier service: " + resourceIdentifierServiceName, e);
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
