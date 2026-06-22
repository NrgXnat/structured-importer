XNAT Structured Importer Plugin
==================================================

The **XNAT Structured Importer** is a free and open source XNAT plugin 
that provides the ability to import non-DICOM data into XNAT as a standard
image session. Because the metadata found in DICOM isn't available, the 
structured importer builds the session based on metadata taken from the
upload context. *How* the importer extracts the metadata depends on the
selected *resource identifier service*. Currently the structured importer
provides two resource identifier services:

* The directory structure resource identifier service uses the folder
  structure within the uploaded archive
* CSV resource identifier service uses a CSV-based manifest at the root
  of the uploaded archive

These services and how they expect import archives to be structured are 
described in the corresponding pages below.

Contents
--------

.. toctree::

   installation
   administration
   usage
   directory-ris
   csv-ris
