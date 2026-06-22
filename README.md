# XNAT Structured Importer Plugin

This is the repository for the XNAT structured importer plugin. The structured importer provides
the ability to upload non-DICOM data to XNAT, with the generated image session populated with metadata
taken from the upload archive. How the metadata is extracted depends on the currently selected resource
identifier service. The importer currently supports the following ways of determining session structure:

* Directory structure
* CSV import

## Build

To build the XNAT structured importer plugin, run the following command:

```bash
./gradlew clean xnatPluginJar
```

The plugin jar file will be located in the `build/libs` directory.


To build the documentation locally, run the following command:

```bash
sphinx-build sphinx-build -b html docs/source docs/build 
```

