package org.nrg.xnatx.plugins.structimport.initialize;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnatx.plugins.structimport.services.DeviceMappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stores the bundled devices as the site-wide devices at start-up when no
 * site-wide devices exist yet.
 */
@Component
@Slf4j
public class CreateDefaultDeviceMappings extends AbstractInitializingTask {

    private final XnatAppInfo          appInfo;
    private final DeviceMappingService deviceMappingService;

    @Autowired
    public CreateDefaultDeviceMappings(final XnatAppInfo appInfo,
                                       final DeviceMappingService deviceMappingService) {
        super();
        this.appInfo              = appInfo;
        this.deviceMappingService = deviceMappingService;
    }

    @Override
    public String getTaskName() {
        return "CreateDefaultDeviceMappings";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        log.debug("Initializing default structured importer devices.");

        if (!appInfo.isInitialized()) {
            log.debug("XnatAppInfo is not initialized; deferring creation of the default structured importer devices.");
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }

        final UserI user = Users.getAdminUser();
        if (user == null) {
            log.debug("No admin user available yet; deferring creation of the default structured importer devices.");
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }

        deviceMappingService.initializeSiteConfiguration(user);
    }
}
