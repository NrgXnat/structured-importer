package org.nrg.xnatx.plugins.structimport.initialize;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnatx.plugins.structimport.services.CsvImportConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Creates the default site-wide CSV import column-mapping configuration at
 * start-up when none exists yet.
 */
@Component
@Slf4j
public class CreateDefaultCsvImportConfiguration extends AbstractInitializingTask {

    private final XnatAppInfo            appInfo;
    private final CsvImportConfigService configService;

    @Autowired
    public CreateDefaultCsvImportConfiguration(final XnatAppInfo appInfo,
                                               final CsvImportConfigService configService) {
        super();
        this.appInfo       = appInfo;
        this.configService = configService;
    }

    @Override
    public String getTaskName() {
        return "CreateDefaultCsvImportConfiguration";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        log.debug("Initializing default CSV import configuration.");

        if (!appInfo.isInitialized()) {
            log.debug("XnatAppInfo is not initialized; deferring creation of the default CSV import configuration.");
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }

        final UserI user = Users.getAdminUser();
        if (user == null) {
            log.debug("No admin user available yet; deferring creation of the default CSV import configuration.");
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }

        configService.initializeSiteConfiguration(user);
    }
}
