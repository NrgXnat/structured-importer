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
* **Property** — the XNAT property the column populates. Choose a built-in property,
  **Path (file locator)** for the special column that locates files within the archive
  (exactly one mapping must be the path column), or **Custom…** to enter any XNAT property
  path such as ``xnat:mrScanData/parameters/tr``.
* **Required** — whether the column must be present in the manifest and have a value.
* **Validation** — an optional regular expression that each non-blank value must match.

The table generates a JSON configuration in the background. The **Edit as JSON** button switches
to a raw-JSON view where the configuration can be edited directly and is saved exactly as entered;
**Edit as Table** switches back (the JSON must parse as an array to return to the table, so
malformed edits are never silently lost). Problems such as duplicate columns or a missing path
column are flagged as warnings in both views, and the configuration is validated again when
saved and at import time.

The **About CSV column mappings** link above the table opens a dialog describing the mapping
fields and the supported custom property roots. The dialog includes a sample configuration
(the built-in default) with a **Copy Sample JSON** button that copies it to the clipboard.

Project-level mappings, when present, override the site-wide configuration. The **Disable**
button temporarily turns off a project's mappings (they can be restored by saving them again);
the **Delete** button removes them. In both cases imports into the project fall back to the
site-wide configuration.

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

