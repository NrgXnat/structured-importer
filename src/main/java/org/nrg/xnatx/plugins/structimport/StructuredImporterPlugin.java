package org.nrg.xnatx.plugins.structimport;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.restlet.actions.importer.ImporterHandlerPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@XnatPlugin(value = "StructuredImporterPlugin", name = "XNAT Structured Importer Plugin",
            description = "The XNAT Structured Importer Plugin provides support for creating image sessions from non-DICOM data archives.",
            logConfigurationFile = "structured-importer-logback.xml")
@ComponentScan({"org.nrg.xnatx.plugins.structimport.initialize", "org.nrg.xnatx.plugins.structimport.services.impl"})
@Slf4j
public class StructuredImporterPlugin {
    @Bean
    public ImporterHandlerPackages structuredImporterHandlerPackages() {
        return new ImporterHandlerPackages("org.nrg.xnatx.plugins.structimport.importer");
    }
}
