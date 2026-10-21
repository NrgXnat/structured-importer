Administration
==============

Site Settings
-------------

Plugin settings for the structured importer plugin or any other XNAT plugin can be modified
by administrators. From any view, select Administer -> Plugin Settings in the menu bar.
Plugins are listed vertically on the left side of the display. Please see the instructions
below for various configuration settings.

Project Settings
----------------

Project-specific settings for the structured importer plugin can be modified by administrators
and project owners. On the project page, click on `Project Settings` on the `Actions` menu
on the right-hand side of the page.

CSV Column Mappings
-------------------

The **CSV Column Mappings** tab (at both the site and project level) configures how the columns
of a CSV import manifest map to XNAT metadata (see :doc:`csv-ris`). Mappings are edited in a
table; each row defines:

* **CSV Column** — the exact header text of the column in the manifest.
* **Property** — the XNAT property the column populates, chosen by its display value (see
  `Property Display Mappings`_ below). **Path (file locator)** marks the special column that
  locates files within the archive (exactly one mapping must be the path column), and
  **Custom…** opens a dialog for defining a new display-value-to-property mapping.
* **Required** — whether the column must be present in the manifest and have a value.
* **Validation** — an optional regular expression that each non-blank value must match.

The table generates a JSON configuration in the background. The **Edit as JSON** button switches
to a raw-JSON view where the configuration can be edited directly and is saved exactly as entered;
**Edit as Table** switches back (the JSON must parse as an array to return to the table, so
malformed edits are never silently lost). Problems such as duplicate columns or a missing path
column are flagged as warnings in both views, and the configuration is validated again when
saved and at import time.

**Download Template**, at the right of the view buttons, downloads a blank CSV manifest whose
header row holds the saved column names in configuration order, ready to fill in. In a project
without its own mappings, the template uses the site-wide mappings, since those are what imports
into the project use. Unsaved edits are not included, so save first to get a template for them.
The templates are also available from the XAPI at
``GET /xapi/structured-importer/csv-column-mappings/template`` (site, administrators only) and
``GET /xapi/structured-importer/projects/{projectId}/csv-column-mappings/template``.

The **About CSV column mappings** link above the table opens a dialog describing the mapping
fields and the supported custom property roots. The dialog includes a sample configuration
(the built-in default) with a **Copy Sample JSON** button that copies it to the clipboard.

Property Display Mappings
-------------------------

The display values shown in the Property drop-down (e.g. "Scan ID" for
``xnat:imageScanData/ID``) are themselves configurable. They are maintained **site-wide**: a
mapping added while configuring one project — via the **Custom…** option in any column-mapping
editor — becomes available to every project. The built-in properties (Scan ID, Scan Modality,
Series Description, and so on) are created as the initial mappings.

The **Property Display Mappings** tab in the site settings lists all mappings in a table with
the display value, the XFT object property, and Edit/Delete actions. Editing opens the same
dialog as the **Custom…** option, prefilled; deleting removes the mapping (stored column
mappings that reference the property are not changed — the property simply renders by its raw
path afterwards). Editing and deleting require site administrator privileges; any authenticated
user may add mappings.

When a mapping is saved, it is validated:

* the display value and the object property must each be unique — the error message names the
  currently configured mapping on a collision;
* the property's root data type must be one of the generic roots (``xnat:imageScanData``,
  ``xnat:imageSessionData``, ``xnat:subjectData``, ``xnat:abstractResource``) or a data type
  configured as a scan or session type in the modality configuration (see
  `Modality Data Types`_).

The mappings are stored through the same configuration service as the CSV column mappings and
can be retrieved from ``GET /xapi/structured-importer/property-display-mappings``.

Project-level mappings, when present, override the site-wide configuration. The **Disable**
button temporarily turns off a project's mappings (they can be restored by saving them again);
the **Delete** button removes them. In both cases imports into the project fall back to the
site-wide configuration.

Device Mappings
---------------

The **Device Mappings** tab (at both the site and project level) lists the acquisition devices
uploads can name. Each device has a **mapping CSV** with one row per manifest column, saying which
device parameter the value comes from and which XNAT property it fills. When an upload names a
device, its CSV manifest is read with the device's mapping **instead of** the CSV column mappings.

The mapping CSV's headers are matched by name, ignoring case and order:

* **Source Parameter** (required) — the parameter in the device's files. ``(constant)``,
  ``(user input)`` and ``(to confirm)`` mark values that don't come from the files.
* **XNAT Column** (required) — the manifest column header.
* **XNAT Property** (required, may be empty) — the XNAT property the column fills, as in the CSV
  column mappings. Exactly one row must leave it empty: that row is the manifest's path column.
* **Required** — ``yes`` or ``no`` (default ``no``; the path column is always required).
* **Validation** — an optional regular expression each non-blank value must match.
* **Source Section**, **Transform**, **Notes** — documentation only; not applied on import.

Rows whose property the importer can't set (for example ``xnat:resourceCatalog/format``) are
listed as warnings and ignored on import, so manifests may still carry those columns. A device
can't be saved while its CSV has problems: a missing required header, a blank XNAT Column, a
malformed property, a column mapped to two properties, a property filled from two columns, or
other than one path column. ``xnat:imageScanData/startTime`` is read as the built-in scan start
time.

Site administrators add, edit and delete site devices; site devices are available to every
project. The devices bundled with the plugin are stored as the site devices the first time XNAT
starts with this version of the plugin. If the deprecated Device Mapping plugin replaced a bundled
device's CSV, the replacement is used. In project settings, **Customize** copies a site device into
the project; the copy replaces the site device for imports into that project, and deleting it
makes the site device apply again. Projects can also add devices of their own. Saving a project
device requires project edit access; deleting one requires project owner access.

Each device row has **View** (the parsed mapping, with problems and warnings), **Edit** or
**Customize**, **CSV** (download the mapping CSV), **Template** (a blank manifest with the
device's columns) and **Delete** actions. The devices are stored through the same configuration
service as the CSV column mappings and are available from the XAPI:

* ``GET /xapi/structured-importer/device-mappings`` and
  ``GET|PUT|DELETE /xapi/structured-importer/device-mappings/{deviceId}`` (site, administrators only)
* ``GET /xapi/structured-importer/projects/{projectId}/device-mappings`` (the devices available to
  the project) and ``GET|PUT|DELETE /xapi/structured-importer/projects/{projectId}/device-mappings/{deviceId}``
* ``.../{deviceId}/csv`` and ``.../{deviceId}/template`` downloads at both levels.

``PUT`` takes the device as JSON (``id``, ``name``, ``manufacturer``, ``model``, ``category``,
``modality``, ``xsiType``, ``metadataSource``, ``nativeFormats``, ``status`` — ``verified`` or
``draft`` — ``notes`` and ``csv``) and returns HTTP 400 listing the problems when it is invalid.

Modality Data Types
-------------------

The modalities the structured importer understands — and the XNAT data types it creates for
sessions and scans of each — are configured in YAML. The built-in defaults ship with the plugin
at ``META-INF/xnat/structimport/core/modality-to-xft.yaml``; each top-level key is a modality
code with up to three properties::

    MR:
      scan: xnat:mrScanData
      session: xnat:mrSessionData
      group: 0

* **scan** — the data type used for scans of this modality. Omitted for session-only
  modalities (e.g. ``PETMR``).
* **session** — the data type used for sessions of this modality. Omitted for scan-only
  modalities (e.g. ``SC``, ``MRS``).
* **group** — display precedence: group 0 modalities are the most commonly used and sort to
  the top of the primary-modality list; higher groups sort later.

To override or extend the defaults, ship a YAML file with the same structure anywhere on the
classpath matching ``META-INF/xnat/structimport/**/*.yaml`` (for example in another plugin
jar). Override files are merged over the defaults per-field: a field specified in a later file
replaces the earlier value, and an explicit blank string (``session: ""``) clears an inherited
value. Changes require an XNAT restart.

The configuration may safely reference data types that only exist in newer XNAT versions or in
optional plugins: modalities whose data types are not installed on the server are filtered out
of the effective configuration, and an INFO-level log message reports which modalities were
removed and which data types were missing.

The fully merged configuration can be retrieved by any authenticated user from
``GET /xapi/structured-importer/modalities``, ordered by group and then modality code — useful
as a reference when preparing a CSV manifest.

