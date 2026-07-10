# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The site and project **CSV Column Mappings** settings are now edited in a table-based GUI that
  generates the stored JSON configuration in the background, with an **Edit as JSON** mode for
  editing the configuration directly (switching back to the table requires the JSON to parse, so
  malformed edits are never silently lost). The REST API and stored format are unchanged.
- Column mappings may now target **arbitrary XNAT property paths** (e.g.
  `xnat:mrScanData/parameters/tr`) in addition to the built-in properties. Paths rooted at
  `xnat:imageScanData`/`xnat:imageSessionData` apply to scans/sessions of any modality;
  modality-specific roots (`xnat:mrScanData`, `xnat:petScanData`, `xnat:ctScanData`,
  `xnat:srScanData`, and the corresponding session types) must match the target object, and
  `xnat:subjectData` paths apply to newly created subjects. Session- and subject-level values must
  be consistent across manifest rows.
- With **Customize** labeling, a CSV manifest at the archive root is now still used for scan
  metadata (previously Customize always used the directory-walking service). Archives without a
  manifest fall back to the directory layout as before.
- The compressed uploader disables **Begin Upload** until both custom labels are provided when
  Customize is selected, explains the Customize semantics in the form help, and renames the
  "Subject ID" field to "Subject Label".
- When the Structured Zip importer is selected, the **Destination** is forced to Archive and the
  destination controls are disabled (structured imports always write directly to the archive).
- **Configurable modality-to-data-type mappings** replace the fixed MR/PET/CT list. The new
  `ModalityDataTypeService` loads its defaults from
  `META-INF/xnat/structimport/core/modality-to-xft.yaml` and merges any other YAML files on the
  classpath matching `META-INF/xnat/structimport/**/*.yaml` on top, so deployments and other
  plugins can override or extend the configuration. Each modality maps to a session data type,
  a scan data type, or both, plus a display-precedence group. The compressed uploader's Primary
  modality drop-down now lists every session-capable modality with a built-in search box, the
  importer instantiates sessions and scans generically from the configured data types, CSV
  manifests that reference unconfigured modalities are rejected, and the merged configuration
  is available from `GET /xapi/structured-importer/modalities`. Modalities whose data types are
  not installed on the server are filtered out of the effective configuration (logged at INFO),
  so configurations can safely reference data types from newer XNAT versions or optional
  plugins.

### Changed

- **Behavior change:** when a custom subject or session label is supplied with the upload *and*
  the manifest also carries labels, the upload values now **override** the manifest values.
  Previously this was rejected as a conflict (HTTP 400). Imports whose manifest contains multiple
  distinct subjects or sessions are rejected when custom labels are supplied.

## [1.0.0] - 2026-07-31

Initial release of the XNAT structured importer plugin. Compatible with XNAT version 1.9. Add the
plugin jar file to your XNAT plugins directory and restart XNAT.

