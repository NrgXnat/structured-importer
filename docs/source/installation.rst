Installation
============

1. `Quick Start`_
2. `XNAT Plugin Model`_
3. `Select & Download Plugins`_
4. `Installation Environment`_
5. `Run XNAT with Docker Compose`_

-----------
Quick Start
-----------

The easiest way to get started with the structured importer is to use the Dockerized version of XNAT.
Jump to the `Run XNAT with Docker Compose`_ section below to get started.

For IT administrators who want to deploy XNAT with the structured importer in a production environment, check the
`XNAT Installation Guide page`_ for detailed instructions. The Docker Compose environment is also a good reference for
understanding the components of a production installation.

-----------------
XNAT Plugin Model
-----------------

XNAT provides a `rich set of features and functions <https://www.xnat.org/about>`_ for managing imaging data.
It is designed to be extensible and customizable and supports `plugins <https://wiki.xnat.org/documentation/developing-xnat-plugins>`_ that extend features and functions in core
XNAT:

  A plugin is a compiled, self-contained package separate from your XNAT server but installed
  into the XNAT plugins folder. Once installed, the plugin runs in the same process space with
  XNAT as a fully integrated extension to the core XNAT server.

Plugins are written and contributed by the core XNAT team and by individual sites with specific needs
that are not found in core XNAT.

-------------------------
Select & Download Plugins
-------------------------

Plugins are Java jar files that are added to the operating environment during the installation process.
You can select and download the jar files as part of preparation for installation.

The `XNAT structured importer plugin <https://github.com/nrgxnat/structured-importer/releases>`_ contains
the code to support importing non-DICOM structured data into XNAT.

------------------------
Installation Environment
------------------------

XNAT can be deployed to support different environments ranging from a single user running on a laptop to a larger, enterprise-level system.
This section provides overviews of the small and large environments.

- The Dockerized version of XNAT can run on both Docker Desktop and the professional versions of Docker.
  XNAT provides a `Docker Compose`_ file that defines the environment for running the application.
  This is an appropriate environment for individuals to run on their laptop or desktop for evaluation and can also
  serve as the basis for a larger deployment.
  Instructions for this environment are provided in the next section.

- Users with IT support who want a system that is available to a larger audience will want to understand
  the `XNAT Administration <https://wiki.xnat.org/documentation/xnat-administration>`_ documentation.
  This describes an environment where the XNAT software is running as a system service in a Linux environment and is available 24x7.
  Instructions are included for managing user authentication using an LDAP server (Windows Active Directory)
  and/or running XNAT behind a firewall with HTTPS-protected connections.
  For the steps listed on the `XNAT Installation Guide page. <https://wiki.xnat.org/documentation/xnat-installation-guide>`_

We recommend starting with the Dockerized version.
You will have a functioning system within a few minutes and can learn the administrative functions
and the application itself before investing more time with a production installation.

----------------------------
Run XNAT with Docker Compose
----------------------------

Use the following steps to quickly deploy an XNAT with the structured importer plugin using Docker Compose on your local system.
You will need to have `git`_ and `Docker`_ installed as well as `Docker Compose`_, which is typically included 
when you install Docker.

1. Clone the `xnat-docker-compose <https://github.com/NrgXnat/xnat-docker-compose>`_ repository:

  .. code-block:: bash
    
    git clone https://github.com/NrgXnat/xnat-docker-compose
    cd xnat-docker-compose

2. Put any XNAT plugin jars you want to use in the ``xnat/plugins`` folder. Note you do not need to download the XNAT war file. It will be included as part of the Docker container.
   
   The structured importer can be dowloaded with the following command:

  .. code-block:: bash
    
    wget -q -P ./xnat/plugins/ https://github.com/nrgxnat/structured-importer/releases/download/v1.0.0/structured-importer-1.0.0-xpl.jar

3. Initialize the XNAT docker compose environment variables. Review the .env file and make changes as appropriate.

  .. code-block:: bash

    cp default.env .env

4. Start XNAT with Docker Compose in detached mode:

  .. code-block:: bash

    docker compose up -d

5. Browse to http://localhost and login with username/password admin/admin. After logging in, you will be prompted to setup your XNAT instance.
   The default values are sufficient for a local installation. `XNAT Setup - First Time Configuration`_ has detailed instructions if you need them.


.. _Docker: https://www.docker.com/
.. _Docker Compose: https://docs.docker.com/compose/
.. _git: https://git-scm.com/
.. _XNAT Setup - First Time Configuration: https://wiki.xnat.org/documentation/xnat-setup-first-time-configuration
.. _XNAT Installation Guide page: https://wiki.xnat.org/documentation/xnat-installation-guide

