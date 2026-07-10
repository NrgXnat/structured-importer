CSV Resource Identifier Service
===============================

This service identifies subjects, sessions, and scans in an upload archive by
reading a **CSV manifest** and applying the metadata from each row to the
subject, session(s), and scans it creates. It is the default resource identifier
service.

Archive layout
--------------

The archive must contain exactly one CSV file (``*.csv``) at its **root**,
alongside the data it describes::

    archive.zip
    ├── manifest.csv
    ├── scan1/
    │   ├── file1.nii
    │   └── file2.nii
    └── scan2/
        └── file1.nii

Each row of the manifest names a source file or directory (relative to the
archive root) and the scan, session, and subject context it belongs to.

The manifest
------------

The columns the manifest is expected to contain are defined by the column-mapping
configuration (see :doc:`administration`). With the default configuration, a
manifest looks like this::

    Scan ID,Modality,Series Description,Session Label,Subject ID,Start Date,Start Time,Subject Weight (g),Resource Name,Path
    1,MR,T1,SES_A,SUBJ_A,01/02/2026,10:30 AM,25.5,NIFTI,scan1/file1.nii
    1,MR,T1,SES_A,SUBJ_A,01/02/2026,10:30 AM,25.5,NIFTI,scan1/file2.nii
    2,PET,FDG,SES_A,SUBJ_A,01/02/2026,11:00 AM,25.5,,scan2/file1.nii

Each column maps to a property on the subject, session, or scan:

.. list-table::
   :header-rows: 1
   :widths: 25 50 15

   * - Column
     - Sets
     - Required
   * - Scan ID
     - The scan identifier.
     - Yes
   * - Modality
     - The scan modality. Must be configured with a scan data type in the
       modality configuration (see :doc:`administration`); the import fails
       otherwise.
     - Yes
   * - Series Description
     - The scan's series description.
     - Yes
   * - Session Label
     - The label of the session the scan belongs to.
     - Yes
   * - Subject ID
     - The label of the subject the session belongs to.
     - Yes
   * - Start Date
     - The scan start date (``MM/DD/YYYY`` or ISO-8601 ``YYYY-MM-DD``).
     - No
   * - Start Time
     - The scan start time (``H:MM AM/PM`` or ISO-8601 ``HH:MM[:SS]``).
     - No
   * - Subject Weight (g)
     - The subject's weight.
     - No
   * - Resource Name
     - The resource label the files are stored under (defaults to ``NIFTI``).
     - No
   * - Path
     - The file or directory within the archive that holds the scan's files.
     - Yes

The **Path** column is special: it locates content within the archive rather than
setting an XNAT property. Paths must be relative to the archive root, must not
point outside it, and must exist in the archive.

Custom properties
-----------------

Beyond the built-in properties above, columns can be mapped to **any XNAT property
path** (see :doc:`administration`). A custom path is a supported root element
followed by the path of the property within that data type — for example,
``xnat:mrScanData/parameters/tr`` sets the repetition time on MR scans.

.. list-table::
   :header-rows: 1
   :widths: 20 45 35

   * - Applies to
     - Roots
     - Notes
   * - Scan
     - ``xnat:imageScanData``; ``xnat:mrScanData``, ``xnat:petScanData``,
       ``xnat:ctScanData``, ``xnat:srScanData``
     - The generic root applies to scans of any modality; a modality-specific
       root must match the scan's modality or the import fails.
   * - Session
     - ``xnat:imageSessionData``; ``xnat:mrSessionData``, ``xnat:petSessionData``,
       ``xnat:ctSessionData``
     - Values must agree across all rows of the same session.
   * - Subject
     - ``xnat:subjectData``
     - Values must agree across all rows of the same subject, and apply only to
       subjects the import creates (existing subjects are not modified).

Custom scan properties participate in row aggregation: rows combine into the same
scan resource only when all of their custom values agree.

How rows become scans
---------------------

* **Aggregation.** Rows that share the same subject, session, scan, modality, and
  resource name are combined into a single scan resource that contains all of
  their paths. The first two rows in the example above describe one ``NIFTI``
  resource on scan ``1`` made up of two files.
* **Multiple sessions and subjects.** Because every row carries its own subject
  and session, a single archive can populate several subjects and sessions at
  once. If custom subject and session labels are supplied with the upload
  (**Customize** labeling), they override the manifest's labels; this is only
  allowed for archives whose manifest contains a single subject and session.
* **Default resource name.** If the Resource Name column is blank, ``NIFTI`` is
  used (as in the third example row).

Validation
----------

The manifest is validated as it is read. An import is rejected with a descriptive
error — including the offending row and column where applicable — when:

* a required column is missing from the header, or a required value is blank;
* a value does not match the validation pattern configured for its column;
* a date, time, or number cannot be parsed;
* a modality is not configured with a scan data type;
* a path is absolute, escapes the archive root, or does not exist;
* the same subject is given inconsistent subject-weight values across rows, or a
  session- or subject-level custom property is given inconsistent values;
* no CSV manifest, or more than one, is found at the archive root.

When to use it
--------------

Use the CSV service when you need to import multiple subjects or sessions from a
single archive, or when you want to set per-scan metadata (series description,
start date and time) or subject weight. If your data already follows a simple
``scanId/modality/resourceName`` folder layout for a single, known subject and
session, the :doc:`directory-ris` may be simpler.
