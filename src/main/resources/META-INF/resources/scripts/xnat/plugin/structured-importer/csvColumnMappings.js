/*!
 * Structured Importer plugin — CSV column mappings administration UI.
 *
 * Wires the site and project "CSV Column Mappings" Spawner panels to the
 * structured importer XAPI. The panels' built-in Save button posts the editor
 * contents; this script loads the current mappings into the editor when a panel
 * is shown and, for projects, handles the Disable and Delete actions that revert
 * a project to the site-wide configuration.
 */

var XNAT = getObject(XNAT || {});

(function(factory){
    if (typeof define === 'function' && define.amd) {
        define(factory);
    } else if (typeof exports === 'object') {
        module.exports = factory();
    } else {
        return factory();
    }
}(function(){

    XNAT.plugin                    = getObject(XNAT.plugin || {});
    XNAT.plugin.structuredImporter = getObject(XNAT.plugin.structuredImporter || {});

    var csv = XNAT.plugin.structuredImporter.csvMappings =
        getObject(XNAT.plugin.structuredImporter.csvMappings || {});

    var SITE_TEXTAREA      = 'structured-importer-site-csv';
    var PROJECT_TEXTAREA   = 'structured-importer-project-csv';
    var DISABLE_BUTTON_ID  = 'structured-importer-disable-project';
    var DELETE_BUTTON_ID   = 'structured-importer-delete-project';

    function siteUrl() {
        return XNAT.url.rootUrl('/xapi/structured-importer/csv-column-mappings');
    }

    function projectUrl(projectId) {
        return XNAT.url.rootUrl('/xapi/structured-importer/projects/' + encodeURIComponent(projectId) + '/csv-column-mappings');
    }

    csv.getProjectId = function() {
        var context = (XNAT.data && XNAT.data.context) || {};
        return context.projectID || context.project || (XNAT.data && XNAT.data.projectId) || null;
    };

    // Invoke cb once an element with the given id is present in the DOM. Spawner
    // tabs may render lazily, so we poll briefly rather than assume it exists.
    function whenPresent(id, cb, tries) {
        tries = (tries == null) ? 40 : tries;
        var el = document.getElementById(id);
        if (el) {
            cb(el);
        } else if (tries > 0) {
            setTimeout(function() { whenPresent(id, cb, tries - 1); }, 150);
        }
    }

    function setEditorValue(textareaId, value) {
        var $textarea = $('#' + textareaId);
        if (!$textarea.length) {
            return;
        }
        $textarea.val(value).trigger('change');
        // If a CodeMirror editor is attached to the textarea, keep it in sync.
        var cm = $textarea.data('CodeMirror') || ($textarea[0] && $textarea[0].CodeMirror);
        if (cm && cm.setValue) {
            cm.setValue(value);
            cm.refresh();
        }
    }

    function notify(message) {
        if (XNAT.ui && XNAT.ui.banner && XNAT.ui.banner.top) {
            XNAT.ui.banner.top(2000, '<b>' + message + '</b>', 'success');
        } else {
            xmodal.message('Structured Importer', message);
        }
    }

    function reportFailure(action, error) {
        var status = (error && error.status) ? (' (status ' + error.status + ')') : '';
        xmodal.message('Error', 'Unable to ' + action + ' the project CSV column mappings' + status + '.');
    }

    csv.loadSite = function() {
        XNAT.xhr.getJSON({
            url: siteUrl(),
            success: function(data) {
                setEditorValue(SITE_TEXTAREA, (data && data.columnMappings) || '');
            },
            fail: function(error) {
                console.error('Unable to load site-wide CSV column mappings', error);
            }
        });
    };

    csv.loadProject = function() {
        var projectId = csv.getProjectId();
        if (!projectId) {
            console.warn('No project context available; cannot load project CSV column mappings.');
            return;
        }
        XNAT.xhr.getJSON({
            url: projectUrl(projectId),
            success: function(data) {
                setEditorValue(PROJECT_TEXTAREA, (data && data.columnMappings) || '');
            },
            fail: function(error) {
                console.error('Unable to load project CSV column mappings', error);
            }
        });
    };

    csv.disableProject = function() {
        var projectId = csv.getProjectId();
        if (!projectId) {
            return;
        }
        xmodal.confirm({
            title: 'Disable project CSV column mappings',
            content: 'Disable this project’s CSV column mappings? Imports into this project will use the site-wide configuration. The mappings are retained and can be restored by saving them again.',
            okAction: function() {
                XNAT.xhr.ajax({
                    url: projectUrl(projectId) + '/disable',
                    method: 'POST',
                    success: function() {
                        notify('Project CSV column mappings disabled.');
                        csv.loadProject();
                    },
                    fail: function(error) {
                        reportFailure('disable', error);
                    }
                });
            }
        });
    };

    csv.deleteProject = function() {
        var projectId = csv.getProjectId();
        if (!projectId) {
            return;
        }
        xmodal.confirm({
            title: 'Delete project CSV column mappings',
            content: 'Delete this project’s CSV column mappings? Imports into this project will use the site-wide configuration.',
            okAction: function() {
                XNAT.xhr.ajax({
                    url: projectUrl(projectId),
                    method: 'DELETE',
                    success: function() {
                        notify('Project CSV column mappings deleted.');
                        csv.loadProject();
                    },
                    fail: function(error) {
                        reportFailure('delete', error);
                    }
                });
            }
        });
    };

    // Delegated handlers, so they work regardless of when the buttons render.
    $(document).off('click.structimport', '#' + DISABLE_BUTTON_ID)
               .on('click.structimport', '#' + DISABLE_BUTTON_ID, function(e) {
                   e.preventDefault();
                   csv.disableProject();
               });
    $(document).off('click.structimport', '#' + DELETE_BUTTON_ID)
               .on('click.structimport', '#' + DELETE_BUTTON_ID, function(e) {
                   e.preventDefault();
                   csv.deleteProject();
               });

    csv.init = function() {
        whenPresent(SITE_TEXTAREA, function() { csv.loadSite(); });
        whenPresent(PROJECT_TEXTAREA, function() { csv.loadProject(); });
    };

    csv.init();
    $(document).ready(csv.init);

    return csv;
}));
