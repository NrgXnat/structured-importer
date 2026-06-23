Directory Structure Resource Identifier Service
===============================================

This service identifies scans in an upload archive from the **folder structure**
of the archive itself. It does not read any manifest file; instead it expects the
extracted archive to follow a fixed three-level convention.

Archive layout
--------------

Files must be organized as ``<scanId>/<modality>/<resourceName>/``::

    archive.zip
    ├── 1/
    │   └── MR/
    │       └── NIFTI/
    │           ├── image.nii
    │           └── ...
    └── 2/
        └── PET/
            └── NIFTI/
                └── series.nii

In this example the archive contains two scans:

* Scan ``1``, an ``xnat:mrScanData``, with a ``NIFTI`` resource.
* Scan ``2``, an ``xnat:petScanData``, with a ``NIFTI`` resource.

Everything inside a resource directory — including any subdirectories — is
treated as content of that resource.

Metadata
--------

Because there is no manifest, this service can only derive the **scan ID**,
**scan modality**, and **resource name** from the folder names. It cannot
determine the subject or session, so those **must** be supplied as upload
parameters (the ``subject`` and ``session`` fields on the upload form). The
session modality is taken from the ``primary-modality`` upload parameter.

The scan, modality, and resource directories are each processed in name order,
so the order in which scans and resources are created is deterministic.

When to use it
--------------

Use the directory structure service when your data already follows the
``scanId/modality/resourceName`` layout and every scan in the upload belongs to a
single subject and session that you specify at upload time. If you need to import
multiple subjects or sessions in one archive, or you want to set per-scan
metadata such as series description, start date, start time, or subject weight,
use the :doc:`csv-ris` instead.
