# Configuration file for the Sphinx documentation builder.

# -- Project information

project = 'XNAT Structured Importer Plugin'
copyright = '2026, XNAT Works, Inc.'
author = 'Rick Herrick'

release = '1.0'
version = '1.0.0'

# -- General configuration

extensions = [
    'sphinx.ext.duration',
    'sphinx.ext.doctest',
    'sphinx.ext.autodoc',
    'sphinx.ext.autosummary',
    'sphinx.ext.autosectionlabel',
    'sphinx.ext.intersphinx',
]

# Prefix autosection labels with the document name so identically-named
# sections in different pages (e.g. "Archive layout") don't collide.
autosectionlabel_prefix_document = True

intersphinx_mapping = {
    'python': ('https://docs.python.org/3/', None),
    'sphinx': ('https://www.sphinx-doc.org/en/master/', None),
}
intersphinx_disabled_domains = ['std']

templates_path = ['_templates']

# -- Options for HTML output

html_theme = 'sphinx_rtd_theme'
html_logo = 'images/xnat-logo.png'

# -- Options for EPUB output
epub_show_urls = 'footnote'
