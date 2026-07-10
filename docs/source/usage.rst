User Instructions
=================

The structured importer adds a **Structured-Zip** importer to XNAT that creates
image sessions from compressed archives of non-DICOM data. This page describes
how to prepare an upload and import it.

Overview
--------

When you upload an archive with the structured importer, XNAT:

#. extracts the archive (and any archives nested within it);
#. inspects the contents using the selected *resource identifier service* to
   determine the scans and their metadata;
#. creates the subject, image session, and scans, and stores the files as scan
   resources.

How the metadata is determined depends on the resource identifier service:

* the :doc:`directory-ris` reads the scan, modality, and resource from the folder
  layout of the archive;
* the :doc:`csv-ris` reads the subject, session, scan, and metadata from a CSV
  manifest packaged at the root of the archive.

Preparing the archive
----------------------

Package your data as a ``.zip``, ``.tar``, or ``.tgz`` archive, structured for the
resource identifier service you intend to use:

* For the directory service, lay files out as
  ``<scanId>/<modality>/<resourceName>/`` (see :doc:`directory-ris`).
* For the CSV service, place a single ``*.csv`` manifest at the root of the
  archive alongside the data it describes (see :doc:`csv-ris`).

Importing the archive
---------------------

#. From a project, open the compressed uploader and choose the **Structured-Zip**
   importer.
#. Select the **primary modality** for the session being created. The drop-down
   lists every modality configured with a session data type, most common first;
   type in its search box to filter the list (e.g. typing ``us`` jumps to
   ``US``).
#. Choose how session structure is determined:

   * **Extract From Structure (Default)** uses the CSV manifest service; the
     subject and session labels come from the manifest.
   * **Customize** lets you provide the **subject label** and **session label**
     on the form. Both values are required — the upload cannot begin until they
     are filled in. If the archive contains a CSV manifest, it is still used
     for scan metadata, but any subject or session labels in the manifest are
     **ignored** in favor of the values you enter. Archives without a manifest
     fall back to the directory structure service. Because a single pair of
     labels replaces whatever the manifest says, the archive must contain only
     a **single session**: imports whose manifest contains multiple subjects
     or sessions are rejected with an error.

#. Select the archive file and start the upload. With the Structured-Zip
   importer, the **Destination** is always **Archive** (the controls are
   locked); structured imports write directly to the archive and never pass
   through the prearchive.

Results
-------

On success, the importer reports the created session(s), which then appear in the
target project. If the archive does not yield any sessions, the import is marked
as failed. Validation problems — unsupported modalities or archive formats,
missing permissions, or a malformed CSV manifest — are reported as errors that
describe the cause.

Supported modalities
--------------------

The modalities available for sessions and scans come from the modality-to-data-type
configuration (see :doc:`administration`), which maps each modality code to the
XNAT data types used for its sessions and scans. Some modalities are scan-only
(e.g. ``SC`` secondary captures) and can appear within a session but cannot be
the primary modality; a few are session-only. The full configuration can be
retrieved from ``/xapi/structured-importer/modalities``, which is useful when
preparing a CSV manifest: every value in the manifest's modality column must be
a configured modality with a scan data type, or the import fails.
