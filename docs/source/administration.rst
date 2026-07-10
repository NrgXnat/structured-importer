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

The table generates a JSON configuration in the background; the **Show generated JSON** link
displays it. Problems such as duplicate columns or a missing path column are flagged as
warnings while editing, and the configuration is validated again when saved and at import time.

Project-level mappings, when present, override the site-wide configuration. The **Disable**
button temporarily turns off a project's mappings (they can be restored by saving them again);
the **Delete** button removes them. In both cases imports into the project fall back to the
site-wide configuration.

