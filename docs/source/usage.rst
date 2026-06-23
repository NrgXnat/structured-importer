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
#. Select the **primary modality** for the session being created (``MR``, ``PET``,
   or ``CT``).
#. Choose how session structure is determined:

   * **Extract From Structure (Default)** uses the CSV manifest service.
   * **Customize** uses the directory structure service; with this option you
     must provide the **subject** and **session** labels on the form, since the
     folder layout does not supply them.

#. Select the archive file and start the upload.

When the manifest (or your upload entries) supplies the subject and session,
leave the corresponding fields on the form blank. Specifying the same value both
on the form and in the manifest is treated as a conflict and rejected; provide
each value in only one place.

Results
-------

On success, the importer reports the created session(s), which then appear in the
target project. If the archive does not yield any sessions, the import is marked
as failed. Validation problems — unsupported modalities or archive formats,
missing permissions, or a malformed CSV manifest — are reported as errors that
describe the cause.

Supported modalities
--------------------

Sessions can be created for the **MR**, **PET**, and **CT** modalities. Within a
session, individual scans may also use the **SR** (structured report) modality.
