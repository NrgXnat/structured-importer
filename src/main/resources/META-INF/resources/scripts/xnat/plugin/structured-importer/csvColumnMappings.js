/*!
 * Structured Importer plugin — CSV column mappings administration UI.
 *
 * Renders a table-based editor into the site and project "CSV Column Mappings"
 * Spawner panels. The table is the source of truth: every edit is serialized to
 * a JSON array in a hidden input named "columnMappings", which the panel's
 * built-in Save button posts to the structured importer XAPI (the same wire
 * format the old JSON textarea used). A collapsible read-only preview shows the
 * generated JSON; serializeMappings/deserializeMappings are symmetric so an
 * editable-JSON mode can be added later. For projects, this script also handles
 * the Disable and Delete actions that revert to the site-wide configuration.
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

    var SITE_INPUT        = 'structured-importer-site-csv';
    var PROJECT_INPUT     = 'structured-importer-project-csv';
    var DISABLE_BUTTON_ID = 'structured-importer-disable-project';
    var DELETE_BUTTON_ID  = 'structured-importer-delete-project';

    var CUSTOM_PROPERTY = '__custom__';

    // Built-in properties the importer understands, plus the special Path
    // locator (blank property). Anything else is entered as a custom XNAT
    // property path via the "Custom…" option.
    csv.PROPERTY_OPTIONS = [
        { value: 'xnat:imageScanData/ID',                                              label: 'Scan ID' },
        { value: 'xnat:imageScanData/modality',                                        label: 'Scan Modality' },
        { value: 'xnat:imageScanData/series_description',                              label: 'Series Description' },
        { value: 'xnat:imageScanData/start_date',                                      label: 'Scan Start Date' },
        { value: 'xnat:imageScanData/start_time',                                      label: 'Scan Start Time' },
        { value: 'xnat:imageSessionData/label',                                        label: 'Session Label' },
        { value: 'xnat:imageSessionData/subject_ID',                                   label: 'Subject Label' },
        { value: 'xnat:subjectData/demographics[@xsi:type=xnat:demographicData]/weight', label: 'Subject Weight' },
        { value: 'xnat:abstractResource/label',                                        label: 'Resource Name' },
        { value: '',                                                                   label: 'Path (file locator)' }
    ];

    // Roots accepted for custom property paths; mirrors PropertyTargets.java.
    var CUSTOM_ROOT_PATTERN = /^(xnat:imageScanData|xnat:mrScanData|xnat:petScanData|xnat:ctScanData|xnat:srScanData|xnat:imageSessionData|xnat:mrSessionData|xnat:petSessionData|xnat:ctSessionData|xnat:subjectData)\/.+/i;

    // The built-in default configuration, shown in the help dialog as a sample.
    csv.SAMPLE_CONFIG = [
        { column: 'Scan ID',            property: 'xnat:imageScanData/ID' },
        { column: 'Modality',           property: 'xnat:imageScanData/modality' },
        { column: 'Series Description', property: 'xnat:imageScanData/series_description' },
        { column: 'Session Label',      property: 'xnat:imageSessionData/label' },
        { column: 'Subject ID',         property: 'xnat:imageSessionData/subject_ID' },
        { column: 'Start Date',         property: 'xnat:imageScanData/start_date', required: false },
        { column: 'Start Time',         property: 'xnat:imageScanData/start_time', required: false },
        { column: 'Subject Weight (g)', property: 'xnat:subjectData/demographics[@xsi:type=xnat:demographicData]/weight', required: false },
        { column: 'Resource Name',      property: 'xnat:abstractResource/label', required: false },
        { column: 'Path',               property: '', required: true }
    ];

    function sampleJson() {
        return JSON.stringify(csv.SAMPLE_CONFIG, null, 2);
    }

    function helpContent(scope) {
        var intro = scope === 'project'
            ? 'These mappings define how columns in a CSV import manifest are matched to XNAT metadata for ' +
              'imports into <b>this project</b>. When set, they <b>override</b> the site-wide configuration; ' +
              'use the Disable or Delete buttons to fall back to the site-wide mappings.'
            : 'The structured importer’s CSV resource identifier service builds image sessions from a CSV ' +
              'manifest packaged with the upload. The mappings define how columns in that manifest are matched ' +
              'to XNAT metadata. This configuration applies to every project that does not define its own ' +
              'project-level mappings.';
        return '<div class="structured-importer-mappings-help-content">' +
            '<p>' + intro + '</p>' +
            '<p>Each mapping has the following fields:</p>' +
            '<ul>' +
            '<li><b>CSV Column</b> &ndash; the exact header text of the column in the CSV manifest.</li>' +
            '<li><b>Property</b> &ndash; the XNAT property the column populates. Choose a built-in property, ' +
            '<b>Path (file locator)</b> for the special column that locates the file or directory within ' +
            'the archive (exactly one mapping must be the path column), or <b>Custom&hellip;</b> to enter ' +
            'any XNAT property path.</li>' +
            '<li><b>Required</b> &ndash; whether the column must be present in the manifest and have a value.</li>' +
            '<li><b>Validation</b> &ndash; an optional regular expression that each non-blank value must match.</li>' +
            '</ul>' +
            '<p>Custom property paths must start with one of the supported root elements, followed by the path ' +
            'of the property within that data type:</p>' +
            '<ul>' +
            '<li>Scan properties: <code>xnat:imageScanData</code> (any modality), or <code>xnat:mrScanData</code>, ' +
            '<code>xnat:petScanData</code>, <code>xnat:ctScanData</code>, <code>xnat:srScanData</code> ' +
            '(must match the scan’s modality)</li>' +
            '<li>Session properties: <code>xnat:imageSessionData</code> (any modality), or ' +
            '<code>xnat:mrSessionData</code>, <code>xnat:petSessionData</code>, <code>xnat:ctSessionData</code></li>' +
            '<li>Subject properties: <code>xnat:subjectData</code></li>' +
            '</ul>' +
            '<p>For example, <code>xnat:mrScanData/parameters/tr</code> sets the repetition time on MR scans. ' +
            'Values for session- and subject-level properties must agree across all manifest rows for the same ' +
            'session or subject.</p>' +
            '<p style="margin-bottom:4px;"><b>Sample configuration (the built-in default):</b></p>' +
            '<div style="border:1px solid #ddd;border-radius:3px;background:#f7f7f7;max-height:260px;overflow:auto;">' +
            '<pre class="structured-importer-sample-json" style="margin:0;padding:8px;font-size:11px;">' + sampleJson() + '</pre>' +
            '</div>' +
            '<button type="button" class="btn btn-sm structured-importer-copy-sample" style="margin-top:8px;">Copy Sample JSON</button>' +
            '</div>';
    }

    csv.showHelp = function(scope) {
        // Build the dialog body as a DOM node so the copy button's handler can
        // be attached directly (document-level delegation has proven unreliable
        // on the settings pages).
        var content = document.createElement('div');
        content.innerHTML = helpContent(scope);
        var copyButton = content.querySelector('.structured-importer-copy-sample');
        copyButton.addEventListener('click', function(e) {
            e.preventDefault();
            copyToClipboard(sampleJson(), function() {
                var label = copyButton.textContent;
                copyButton.textContent = 'Copied!';
                copyButton.disabled = true;
                setTimeout(function() {
                    copyButton.textContent = label;
                    copyButton.disabled = false;
                }, 1500);
            });
        });
        XNAT.dialog.open({
            title: 'CSV Column Mappings',
            width: 640,
            content: content,
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true
                }
            ]
        });
    };

    // Wire the "About CSV column mappings" links with listeners attached
    // directly to the elements. Safe to call repeatedly; each link is only
    // bound once.
    function bindHelpLinks() {
        var links = document.querySelectorAll('.structured-importer-mappings-help');
        for (var i = 0; i < links.length; i++) {
            (function(link) {
                if (link.structImportHelpBound) {
                    return;
                }
                link.structImportHelpBound = true;
                link.addEventListener('click', function(e) {
                    e.preventDefault();
                    csv.showHelp(link.getAttribute('data-scope') || 'site');
                });
            })(links[i]);
        }
    }

    function copyToClipboard(text, done) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, function() {
                legacyCopy(text, done);
            });
        } else {
            legacyCopy(text, done);
        }
    }

    // Fallback for browsers/contexts (e.g. plain http) without the async clipboard API.
    function legacyCopy(text, done) {
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.cssText = 'position:fixed;top:-1000px;left:-1000px;';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            done();
        } catch (e) {
            console.error('Unable to copy sample JSON to the clipboard', e);
        }
        document.body.removeChild(textarea);
    }

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

    /**
     * Parses a stored JSON configuration into editor rows. Returns null when the
     * string is not a JSON array (the caller falls back to showing the raw text).
     * Tolerates missing fields: required defaults to true, validation to ''.
     */
    csv.deserializeMappings = function(jsonString) {
        if (!jsonString || !jsonString.trim()) {
            return [];
        }
        var parsed;
        try {
            parsed = JSON.parse(jsonString);
        } catch (e) {
            return null;
        }
        if (!Array.isArray(parsed)) {
            return null;
        }
        return parsed.map(function(item) {
            item = item || {};
            return {
                column:     item.column != null ? String(item.column) : '',
                property:   item.property != null ? String(item.property) : '',
                required:   item.required !== false,
                validation: item.validation != null ? String(item.validation) : ''
            };
        });
    };

    /**
     * Serializes editor rows to the JSON array the XAPI stores. Omits
     * required when true (the server default) and blank validations.
     */
    csv.serializeMappings = function(rows) {
        var mappings = (rows || []).map(function(row) {
            var mapping = { column: (row.column || '').trim(), property: (row.property || '').trim() };
            if (row.required === false) {
                mapping.required = false;
            }
            if (row.validation && row.validation.trim()) {
                mapping.validation = row.validation.trim();
            }
            return mapping;
        });
        return JSON.stringify(mappings, null, 2);
    };

    function isBuiltInProperty(value) {
        return csv.PROPERTY_OPTIONS.some(function(option) {
            return option.value.toLowerCase() === (value || '').toLowerCase();
        });
    }

    function canonicalProperty(value) {
        var match = csv.PROPERTY_OPTIONS.filter(function(option) {
            return option.value.toLowerCase() === (value || '').toLowerCase();
        })[0];
        return match ? match.value : value;
    }

    /**
     * Non-blocking sanity checks rendered as inline warnings; the server remains
     * authoritative and repeats these checks (and more) at save/import time.
     */
    csv.computeWarnings = function(rows) {
        var warnings  = [];
        var columns   = {};
        var props     = {};
        var pathCount = 0;
        (rows || []).forEach(function(row) {
            var column = (row.column || '').trim();
            if (!column) {
                warnings.push('A mapping has a blank column name.');
            } else if (columns[column]) {
                warnings.push('The column "' + column + '" is mapped more than once.');
            } else {
                columns[column] = true;
            }
            var property = (row.property || '').trim();
            if (!property) {
                pathCount++;
                return;
            }
            var normalized = property.toLowerCase();
            if (props[normalized]) {
                warnings.push('The property "' + property + '" is mapped from more than one column.');
            } else {
                props[normalized] = true;
            }
            if (!isBuiltInProperty(property) && !CUSTOM_ROOT_PATTERN.test(property)) {
                warnings.push('The custom property "' + property + '" does not start with a supported root element (xnat:imageScanData, xnat:mrScanData, xnat:petScanData, xnat:ctScanData, xnat:srScanData, xnat:imageSessionData, xnat:mrSessionData, xnat:petSessionData, xnat:ctSessionData, or xnat:subjectData).');
            }
        });
        if (pathCount !== 1) {
            warnings.push('Exactly one mapping must use "Path (file locator)" — there ' + (pathCount === 1 ? 'is' : 'are') + ' currently ' + pathCount + '.');
        }
        return warnings;
    };

    /**
     * The table editor bound to one panel (site or project). The table is the
     * source of truth; sync() regenerates the hidden input and the preview.
     */
    function MappingsEditor(opts) {
        this.tableContainer   = document.getElementById(opts.tableContainerId);
        this.hiddenInput      = document.getElementById(opts.hiddenInputId);
        this.previewContainer = document.getElementById(opts.previewContainerId);
        this.emptyHint        = opts.emptyHint || '';
        this.rows             = [];
        this.previewOpen      = false;
        this.build();
    }

    MappingsEditor.prototype.build = function() {
        var editor = this;
        var container = this.tableContainer;
        if (!container) {
            return;
        }
        container.innerHTML = '';

        this.warningsEl = document.createElement('div');
        this.warningsEl.className = 'structured-importer-mapping-warnings';
        this.warningsEl.style.cssText = 'display:none;margin-bottom:8px;padding:6px 10px;border:1px solid #e0c060;background:#fdf6e3;border-radius:3px;';

        this.emptyEl = document.createElement('div');
        this.emptyEl.className = 'description';
        this.emptyEl.style.cssText = 'display:none;margin-bottom:8px;';
        this.emptyEl.textContent = this.emptyHint;

        this.table = document.createElement('table');
        this.table.className = 'xnat-table structured-importer-mappings-table';
        this.table.style.width = '100%';
        this.table.innerHTML =
            '<thead><tr>' +
            '<th style="text-align:left;">CSV Column</th>' +
            '<th style="text-align:left;">Property</th>' +
            '<th style="text-align:center;width:70px;">Required</th>' +
            '<th style="text-align:left;">Validation (regex)</th>' +
            '<th style="width:36px;"></th>' +
            '</tr></thead>';
        this.tbody = document.createElement('tbody');
        this.table.appendChild(this.tbody);

        var addButton = document.createElement('button');
        addButton.type = 'button';
        addButton.className = 'btn btn-sm';
        addButton.style.marginTop = '8px';
        addButton.textContent = 'Add Mapping';
        addButton.addEventListener('click', function(e) {
            e.preventDefault();
            editor.rows.push({ column: '', property: '', required: true, validation: '' });
            editor.render();
            var inputs = editor.tbody.querySelectorAll('tr:last-child input[type=text]');
            if (inputs.length) {
                inputs[0].focus();
            }
        });

        container.appendChild(this.warningsEl);
        container.appendChild(this.emptyEl);
        container.appendChild(this.table);
        container.appendChild(addButton);

        this.buildPreview();
    };

    MappingsEditor.prototype.buildPreview = function() {
        var editor = this;
        var container = this.previewContainer;
        if (!container) {
            return;
        }
        container.innerHTML = '';
        this.previewToggle = document.createElement('a');
        this.previewToggle.href = '#!';
        this.previewToggle.style.cssText = 'display:inline-block;margin-top:10px;font-size:12px;';
        this.previewPre = document.createElement('pre');
        this.previewPre.style.cssText = 'display:none;margin-top:6px;padding:8px;background:#f7f7f7;border:1px solid #ddd;border-radius:3px;max-height:320px;overflow:auto;font-size:11px;';
        this.previewToggle.addEventListener('click', function(e) {
            e.preventDefault();
            editor.previewOpen = !editor.previewOpen;
            editor.updatePreview();
        });
        container.appendChild(this.previewToggle);
        container.appendChild(this.previewPre);
        this.updatePreview();
    };

    MappingsEditor.prototype.updatePreview = function() {
        if (!this.previewToggle) {
            return;
        }
        this.previewToggle.textContent = (this.previewOpen ? '▾ Hide' : '▸ Show') + ' generated JSON';
        this.previewPre.style.display = this.previewOpen ? 'block' : 'none';
        this.previewPre.textContent = this.hiddenInput ? this.hiddenInput.value : '';
    };

    MappingsEditor.prototype.setMappings = function(rows) {
        this.rows = rows || [];
        this.render();
    };

    /** Shown when the stored configuration isn't a parseable JSON array. */
    MappingsEditor.prototype.showRawFallback = function(raw) {
        if (this.hiddenInput) {
            this.hiddenInput.value = raw || '';
        }
        this.tbody.innerHTML = '';
        this.table.style.display = 'none';
        this.emptyEl.style.display = 'block';
        this.emptyEl.textContent = 'The stored configuration could not be parsed as a JSON array, so it cannot be edited here. The raw value is shown in the JSON preview below; use Delete (project) or the REST API to replace it.';
        this.previewOpen = true;
        this.updatePreview();
    };

    MappingsEditor.prototype.render = function() {
        var editor = this;
        this.table.style.display = '';
        this.tbody.innerHTML = '';
        this.rows.forEach(function(row, index) {
            editor.tbody.appendChild(editor.buildRow(row, index));
        });
        this.emptyEl.style.display = this.rows.length ? 'none' : 'block';
        this.emptyEl.textContent = this.rows.length ? '' : this.emptyHint;
        this.sync();
    };

    MappingsEditor.prototype.buildRow = function(row, index) {
        var editor = this;
        var tr = document.createElement('tr');

        // None of the editor inputs get a "name" attribute: the surrounding
        // Spawner panel.form serializes named inputs on Save, and only the
        // hidden columnMappings input may be submitted.
        function cell(style) {
            var td = document.createElement('td');
            if (style) {
                td.style.cssText = style;
            }
            tr.appendChild(td);
            return td;
        }

        var columnInput = document.createElement('input');
        columnInput.type = 'text';
        columnInput.style.width = '95%';
        columnInput.value = row.column;
        columnInput.addEventListener('input', function() {
            row.column = columnInput.value;
            editor.sync();
        });
        cell().appendChild(columnInput);

        var propertyCell = cell('min-width:220px;');
        var select = document.createElement('select');
        select.style.width = '95%';
        csv.PROPERTY_OPTIONS.forEach(function(option) {
            var el = document.createElement('option');
            el.value = option.value;
            el.textContent = option.label;
            select.appendChild(el);
        });
        var customOption = document.createElement('option');
        customOption.value = CUSTOM_PROPERTY;
        customOption.textContent = 'Custom…';
        select.appendChild(customOption);

        var customInput = document.createElement('input');
        customInput.type = 'text';
        customInput.placeholder = 'e.g. xnat:mrScanData/parameters/tr';
        customInput.style.cssText = 'width:95%;margin-top:4px;display:none;';

        if (isBuiltInProperty(row.property)) {
            row.property = canonicalProperty(row.property);
            select.value = row.property;
        } else {
            select.value = CUSTOM_PROPERTY;
            customInput.value = row.property;
            customInput.style.display = 'block';
        }
        select.addEventListener('change', function() {
            if (select.value === CUSTOM_PROPERTY) {
                customInput.style.display = 'block';
                row.property = customInput.value.trim();
                customInput.focus();
            } else {
                customInput.style.display = 'none';
                row.property = select.value;
            }
            editor.sync();
        });
        customInput.addEventListener('input', function() {
            row.property = customInput.value.trim();
            editor.sync();
        });
        propertyCell.appendChild(select);
        propertyCell.appendChild(customInput);

        var requiredInput = document.createElement('input');
        requiredInput.type = 'checkbox';
        requiredInput.checked = row.required !== false;
        requiredInput.addEventListener('change', function() {
            row.required = requiredInput.checked;
            editor.sync();
        });
        cell('text-align:center;').appendChild(requiredInput);

        var validationInput = document.createElement('input');
        validationInput.type = 'text';
        validationInput.style.width = '95%';
        validationInput.value = row.validation;
        validationInput.addEventListener('input', function() {
            row.validation = validationInput.value;
            editor.sync();
        });
        cell().appendChild(validationInput);

        var removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'btn btn-sm';
        removeButton.title = 'Remove this mapping';
        removeButton.textContent = '✕';
        removeButton.addEventListener('click', function(e) {
            e.preventDefault();
            editor.rows.splice(index, 1);
            editor.render();
        });
        cell('text-align:center;').appendChild(removeButton);

        return tr;
    };

    MappingsEditor.prototype.sync = function() {
        if (this.hiddenInput) {
            this.hiddenInput.value = csv.serializeMappings(this.rows);
            $(this.hiddenInput).trigger('change');
        }
        var warnings = csv.computeWarnings(this.rows);
        if (warnings.length) {
            this.warningsEl.innerHTML = warnings.map(function(warning) {
                return '<div>⚠ ' + warning.replace(/</g, '&lt;') + '</div>';
            }).join('');
            this.warningsEl.style.display = 'block';
        } else {
            this.warningsEl.style.display = 'none';
        }
        this.updatePreview();
    };

    csv.editors = {};

    function initEditor(scope, opts, loadUrl) {
        bindHelpLinks();
        var editor = csv.editors[scope] = new MappingsEditor(opts);
        XNAT.xhr.getJSON({
            url: loadUrl,
            success: function(data) {
                var raw  = (data && data.columnMappings) || '';
                var rows = csv.deserializeMappings(raw);
                if (rows === null) {
                    editor.showRawFallback(raw);
                } else {
                    editor.setMappings(rows);
                }
            },
            fail: function(error) {
                console.error('Unable to load ' + scope + ' CSV column mappings', error);
            }
        });
        return editor;
    }

    csv.loadSite = function() {
        initEditor('site', {
            tableContainerId:   SITE_INPUT + '-table',
            hiddenInputId:      SITE_INPUT,
            previewContainerId: SITE_INPUT + '-preview',
            emptyHint:          'No mappings are configured. Click "Add Mapping" to begin.'
        }, siteUrl());
    };

    csv.loadProject = function() {
        var projectId = csv.getProjectId();
        if (!projectId) {
            console.warn('No project context available; cannot load project CSV column mappings.');
            return;
        }
        initEditor('project', {
            tableContainerId:   PROJECT_INPUT + '-table',
            hiddenInputId:      PROJECT_INPUT,
            previewContainerId: PROJECT_INPUT + '-preview',
            emptyHint:          'No project override is configured; the site-wide mappings apply. Add mappings and save to create a project-level override.'
        }, projectUrl(projectId));
        bindProjectButtons();
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

    // Attach listeners directly to the manage buttons (bound once each).
    function bindProjectButtons() {
        bindButton(DISABLE_BUTTON_ID, csv.disableProject);
        bindButton(DELETE_BUTTON_ID, csv.deleteProject);
    }

    function bindButton(id, action) {
        var button = document.getElementById(id);
        if (!button || button.structImportBound) {
            return;
        }
        button.structImportBound = true;
        button.addEventListener('click', function(e) {
            e.preventDefault();
            action();
        });
    }

    csv.init = function() {
        whenPresent(SITE_INPUT + '-table', function() { csv.loadSite(); });
        whenPresent(PROJECT_INPUT + '-table', function() { csv.loadProject(); });
    };

    csv.init();
    $(document).ready(csv.init);

    return csv;
}));
