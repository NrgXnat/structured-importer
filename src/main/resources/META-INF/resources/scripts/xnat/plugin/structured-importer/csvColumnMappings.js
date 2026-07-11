/*!
 * Structured Importer plugin — CSV column mappings administration UI.
 *
 * Renders a mappings editor into the site and project "CSV Column Mappings"
 * Spawner panels, with two switchable views: a table editor and a raw-JSON
 * editor. Either way the configuration ends up as a JSON array in a hidden
 * input named "columnMappings", which the panel's built-in Save button posts
 * to the structured importer XAPI (the same wire format the old JSON textarea
 * used). serializeMappings/deserializeMappings are the symmetric bridge
 * between the two views. For projects, this script also handles the Disable
 * and Delete actions that revert to the site-wide configuration.
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

    // guard against the script being included more than once on a page
    if (csv.__loaded) {
        return csv;
    }
    csv.__loaded = true;

    var SITE_INPUT        = 'structured-importer-site-csv';
    var PROJECT_INPUT     = 'structured-importer-project-csv';
    var DISABLE_BUTTON_ID = 'structured-importer-disable-project';
    var DELETE_BUTTON_ID  = 'structured-importer-delete-project';

    var CUSTOM_PROPERTY  = '__custom__';
    var DISPLAY_TABLE_ID = 'structured-importer-display-mappings';

    // Display-value -> object-property mappings that populate the Property
    // drop-downs. Maintained site-wide via the XAPI; loaded before the editors
    // render and refreshed whenever a mapping is added, edited, or deleted.
    csv.displayMappings = [];

    // Root elements that are valid for property paths, lowercased: the generic
    // roots plus every scan/session data type from the modality configuration.
    // Used only for soft warnings; empty when the modality config isn't loaded.
    csv.validRoots = [];

    var GENERIC_ROOTS = ['xnat:imagescandata', 'xnat:imagesessiondata', 'xnat:subjectdata', 'xnat:abstractresource'];

    function displayMappingsUrl() {
        return XNAT.url.rootUrl('/xapi/structured-importer/property-display-mappings');
    }

    function modalitiesUrl() {
        return XNAT.url.rootUrl('/xapi/structured-importer/modalities');
    }

    // The current Property drop-down options: the display mappings plus the
    // special Path locator (blank property).
    csv.propertyOptions = function() {
        var options = (csv.displayMappings || []).map(function(mapping) {
            return { value: mapping.property, label: mapping.display };
        });
        options.push({ value: '', label: 'Path (file locator)' });
        return options;
    };

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
            '<li><b>Property</b> &ndash; the XNAT property the column populates. Choose a property from the ' +
            'drop-down, <b>Path (file locator)</b> for the special column that locates the file or directory ' +
            'within the archive (exactly one mapping must be the path column), or <b>Custom&hellip;</b> to define ' +
            'a new display-value-to-property mapping. Custom mappings are shared site-wide and can be managed ' +
            'under the Property Display Mappings tab in the site settings.</li>' +
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
            '<p>Mappings can be edited in the table or directly as JSON &ndash; use the <b>Edit as Table</b> / ' +
            '<b>Edit as JSON</b> buttons to switch views. In the JSON view, the configuration is saved exactly ' +
            'as entered.</p>' +
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

    function hasPropertyOption(value) {
        return csv.propertyOptions().some(function(option) {
            return option.value.toLowerCase() === (value || '').toLowerCase();
        });
    }

    function canonicalProperty(value) {
        var match = csv.propertyOptions().filter(function(option) {
            return option.value.toLowerCase() === (value || '').toLowerCase();
        })[0];
        return match ? match.value : value;
    }

    function hasValidRoot(property) {
        if (!csv.validRoots.length) {
            return true; // modality configuration unavailable; leave it to the server
        }
        var slash = property.indexOf('/');
        if (slash < 1) {
            return false;
        }
        return csv.validRoots.indexOf(property.substring(0, slash).toLowerCase()) >= 0;
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
            if (!hasPropertyOption(property) && !hasValidRoot(property)) {
                warnings.push('The property "' + property + '" does not start with a supported root element (xnat:imageScanData, xnat:imageSessionData, xnat:subjectData, or a data type configured as a scan or session type for a modality).');
            }
        });
        if (pathCount !== 1) {
            warnings.push('Exactly one mapping must use "Path (file locator)" — there ' + (pathCount === 1 ? 'is' : 'are') + ' currently ' + pathCount + '.');
        }
        return warnings;
    };

    /**
     * The mappings editor bound to one panel (site or project). It has two
     * modes: a table view and a raw-JSON view, switchable at any time. In table
     * mode the rows are the source of truth and every edit is serialized into
     * the hidden columnMappings input; in JSON mode the textarea is the source
     * of truth and its raw text is copied into the hidden input as typed.
     * Switching JSON -> table requires the JSON to parse as an array.
     */
    function MappingsEditor(opts) {
        this.tableContainer = document.getElementById(opts.tableContainerId);
        this.hiddenInput    = document.getElementById(opts.hiddenInputId);
        this.jsonContainer  = document.getElementById(opts.jsonContainerId);
        this.emptyHint      = opts.emptyHint || '';
        this.rows           = [];
        this.mode           = 'table';
        this.build();
    }

    MappingsEditor.prototype.build = function() {
        var editor = this;
        var container = this.tableContainer;
        if (!container) {
            return;
        }
        container.innerHTML = '';

        var modeBar = document.createElement('div');
        modeBar.style.cssText = 'margin-bottom:8px;';
        this.tableModeButton = buildModeButton('Edit as Table', function() { editor.setMode('table'); });
        this.jsonModeButton  = buildModeButton('Edit as JSON', function() { editor.setMode('json'); });
        modeBar.appendChild(this.tableModeButton);
        modeBar.appendChild(this.jsonModeButton);
        container.appendChild(modeBar);

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

        this.addButton = document.createElement('button');
        this.addButton.type = 'button';
        this.addButton.className = 'btn btn-sm';
        this.addButton.style.marginTop = '8px';
        this.addButton.textContent = 'Add Mapping';
        this.addButton.addEventListener('click', function(e) {
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
        container.appendChild(this.addButton);

        this.buildJsonEditor();
        this.updateModeButtons();
    };

    function buildModeButton(label, action) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-sm';
        button.style.marginRight = '6px';
        button.textContent = label;
        button.addEventListener('click', function(e) {
            e.preventDefault();
            action();
        });
        return button;
    }

    MappingsEditor.prototype.buildJsonEditor = function() {
        var editor = this;
        var container = this.jsonContainer;
        if (!container) {
            return;
        }
        container.innerHTML = '';
        container.style.display = 'none';

        this.jsonErrorEl = document.createElement('div');
        this.jsonErrorEl.style.cssText = 'display:none;margin-bottom:8px;padding:6px 10px;border:1px solid #c66;background:#fbeeee;border-radius:3px;';

        // No "name" attribute: only the hidden columnMappings input may be
        // serialized by the surrounding Spawner panel.form on Save.
        this.jsonTextarea = document.createElement('textarea');
        this.jsonTextarea.rows = 20;
        this.jsonTextarea.spellcheck = false;
        this.jsonTextarea.style.cssText = 'width:100%;font-family:monospace;font-size:12px;box-sizing:border-box;';
        this.jsonTextarea.addEventListener('input', function() {
            editor.syncFromJson();
        });

        var jsonHint = document.createElement('div');
        jsonHint.className = 'description';
        jsonHint.style.cssText = 'margin-top:4px;';
        jsonHint.textContent = 'The configuration as a JSON array, saved exactly as entered. Switch back to the table view to edit the parsed mappings.';

        container.appendChild(this.jsonErrorEl);
        container.appendChild(this.jsonTextarea);
        container.appendChild(jsonHint);
    };

    /**
     * Switches between the table and JSON views. Entering JSON mode serializes
     * the current rows into the textarea; returning to table mode requires the
     * JSON to parse (otherwise an error is shown and the editor stays in JSON
     * mode so nothing is lost).
     */
    MappingsEditor.prototype.setMode = function(mode) {
        if (mode === this.mode) {
            return;
        }
        if (mode === 'json') {
            this.jsonTextarea.value = this.hiddenInput ? this.hiddenInput.value : csv.serializeMappings(this.rows);
            this.mode = 'json';
            this.syncFromJson();
        } else {
            var rows = csv.deserializeMappings(this.jsonTextarea.value);
            if (rows === null) {
                this.showJsonError('The text is not a valid JSON array of mappings; fix it before switching to the table view. (The switch was blocked so nothing is lost.)');
                return;
            }
            this.mode = 'table';
            this.setMappings(rows);
        }
        this.updateModeButtons();
    };

    MappingsEditor.prototype.updateModeButtons = function() {
        var isTable = this.mode === 'table';
        this.tableModeButton.disabled = isTable;
        this.jsonModeButton.disabled  = !isTable;
        this.table.style.display     = isTable ? '' : 'none';
        this.addButton.style.display = isTable ? '' : 'none';
        this.emptyEl.style.display   = isTable && !this.rows.length ? 'block' : 'none';
        if (!isTable) {
            this.warningsEl.style.display = 'none';
        }
        if (this.jsonContainer) {
            this.jsonContainer.style.display = isTable ? 'none' : '';
        }
    };

    /**
     * JSON-mode counterpart of sync(): copies the textarea's raw text into the
     * hidden input (what you type is what Save posts) and refreshes the parse
     * error / soft warnings.
     */
    MappingsEditor.prototype.syncFromJson = function() {
        var text = this.jsonTextarea.value;
        if (this.hiddenInput) {
            this.hiddenInput.value = text;
            $(this.hiddenInput).trigger('change');
        }
        var rows = csv.deserializeMappings(text);
        if (rows === null) {
            this.showJsonError('This is not a valid JSON array of mappings; saving it will be rejected.');
        } else {
            var warnings = csv.computeWarnings(rows);
            if (warnings.length) {
                this.showJsonError(warnings.map(function(warning) {
                    return '⚠ ' + warning;
                }).join('\n'));
            } else {
                this.jsonErrorEl.style.display = 'none';
            }
        }
    };

    MappingsEditor.prototype.showJsonError = function(message) {
        this.jsonErrorEl.innerHTML = message.replace(/</g, '&lt;').replace(/\n/g, '<br>');
        this.jsonErrorEl.style.display = 'block';
    };

    MappingsEditor.prototype.setMappings = function(rows) {
        this.rows = rows || [];
        this.render();
    };

    /**
     * Shown when the stored configuration isn't a parseable JSON array: open
     * directly in JSON mode with the raw text so it can be repaired in place.
     */
    MappingsEditor.prototype.showRawFallback = function(raw) {
        this.mode = 'json';
        this.jsonTextarea.value = raw || '';
        this.syncFromJson();
        this.updateModeButtons();
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
        select.title = row.property || '';
        csv.propertyOptions().forEach(function(option) {
            var el = document.createElement('option');
            el.value = option.value;
            el.textContent = option.label;
            el.title = option.value;
            select.appendChild(el);
        });
        // a stored property with no display mapping still has to render: give
        // it an ad-hoc option labeled with the raw property path
        if (!hasPropertyOption(row.property)) {
            var adHoc = document.createElement('option');
            adHoc.value = row.property;
            adHoc.textContent = row.property;
            select.appendChild(adHoc);
        } else {
            row.property = canonicalProperty(row.property);
        }
        var customOption = document.createElement('option');
        customOption.value = CUSTOM_PROPERTY;
        customOption.textContent = 'Custom…';
        select.appendChild(customOption);
        select.value = row.property;

        select.addEventListener('change', function() {
            if (select.value === CUSTOM_PROPERTY) {
                // reset immediately so closing the dialog without saving
                // leaves the row unchanged
                select.value = row.property;
                csv.showPropertyMappingDialog({
                    onSaved: function(mapping) {
                        row.property = mapping.property;
                        editor.render();
                    }
                });
                return;
            }
            row.property = select.value;
            select.title = row.property;
            editor.sync();
        });
        propertyCell.appendChild(select);

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
    };

    /**
     * Dialog for creating or editing a display-value -> object-property
     * mapping. Validation is authoritative on the server; its error messages
     * (duplicates with the currently configured mapping, unsupported roots)
     * are shown inside the dialog.
     *
     * opts: { display, property, replaces, onSaved(mapping) }
     */
    csv.showPropertyMappingDialog = function(opts) {
        opts = opts || {};
        var content = document.createElement('div');

        var errorEl = document.createElement('div');
        errorEl.style.cssText = 'display:none;margin-bottom:10px;padding:6px 10px;border:1px solid #c66;background:#fbeeee;border-radius:3px;white-space:pre-wrap;';

        function field(labelText, value, placeholder) {
            var wrapper = document.createElement('div');
            wrapper.style.marginBottom = '10px';
            var label = document.createElement('label');
            label.style.cssText = 'display:block;font-weight:bold;margin-bottom:3px;';
            label.textContent = labelText;
            var input = document.createElement('input');
            input.type = 'text';
            input.value = value || '';
            input.placeholder = placeholder || '';
            input.style.cssText = 'width:100%;box-sizing:border-box;';
            wrapper.appendChild(label);
            wrapper.appendChild(input);
            content.appendChild(wrapper);
            return input;
        }

        content.appendChild(errorEl);
        var displayInput  = field('Display Value', opts.display, 'e.g. Repetition Time');
        var propertyInput = field('XFT Object Property', opts.property, 'e.g. xnat:mrScanData/parameters/tr');

        var hint = document.createElement('div');
        hint.className = 'description';
        hint.textContent = 'The property must be a data type followed by a property path. Valid data types are '
                         + 'xnat:imageScanData, xnat:imageSessionData, xnat:subjectData, or any data type configured '
                         + 'as a scan or session type for a modality. Mappings are shared site-wide.';
        content.appendChild(hint);

        XNAT.dialog.open({
            title: opts.replaces ? 'Edit Property Display Mapping' : 'New Property Display Mapping',
            width: 520,
            content: content,
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(dialog) {
                        var payload = {
                            display: displayInput.value.trim(),
                            property: propertyInput.value.trim()
                        };
                        XNAT.xhr.ajax({
                            url: displayMappingsUrl() + (opts.replaces ? '?replaces=' + encodeURIComponent(opts.replaces) : ''),
                            method: 'POST',
                            contentType: 'application/json',
                            data: JSON.stringify(payload),
                            success: function() {
                                dialog.close();
                                notify('Property display mapping saved.');
                                csv.refreshDisplayMappings(function() {
                                    if (opts.onSaved) {
                                        opts.onSaved(payload);
                                    }
                                });
                            },
                            fail: function(error) {
                                errorEl.textContent = (error && error.responseText) || 'Unable to save the property display mapping.';
                                errorEl.style.display = 'block';
                            }
                        });
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        });
    };

    /** Reloads the display mappings, then re-renders every dependent view. */
    csv.refreshDisplayMappings = function(callback) {
        XNAT.xhr.getJSON({
            url: displayMappingsUrl(),
            success: function(data) {
                csv.displayMappings = Array.isArray(data) ? data : [];
                Object.keys(csv.editors).forEach(function(scope) {
                    csv.editors[scope].render();
                });
                csv.renderDisplayMappingsManager();
                if (callback) {
                    callback();
                }
            },
            fail: function(error) {
                console.error('Unable to load property display mappings', error);
                if (callback) {
                    callback();
                }
            }
        });
    };

    /**
     * The site-level Property Display Mappings management table: one row per
     * mapping with Edit and Delete actions, plus an Add button. Renders into
     * the container spawned by the Property Display Mappings tab; a no-op on
     * pages without that container.
     */
    csv.renderDisplayMappingsManager = function() {
        var container = document.getElementById(DISPLAY_TABLE_ID);
        if (!container) {
            return;
        }
        container.innerHTML = '';

        var table = document.createElement('table');
        table.className = 'xnat-table';
        table.style.width = '100%';
        table.innerHTML =
            '<thead><tr>' +
            '<th style="text-align:left;">Display Value</th>' +
            '<th style="text-align:left;">XFT Object Property</th>' +
            '<th style="width:80px;"></th>' +
            '</tr></thead>';
        var tbody = document.createElement('tbody');
        table.appendChild(tbody);

        (csv.displayMappings || []).forEach(function(mapping) {
            var tr = document.createElement('tr');

            var displayCell = document.createElement('td');
            displayCell.textContent = mapping.display;
            tr.appendChild(displayCell);

            var propertyCell = document.createElement('td');
            var code = document.createElement('code');
            code.textContent = mapping.property;
            propertyCell.appendChild(code);
            tr.appendChild(propertyCell);

            var actionsCell = document.createElement('td');
            actionsCell.style.cssText = 'text-align:center;white-space:nowrap;';

            var editButton = document.createElement('button');
            editButton.type = 'button';
            editButton.className = 'btn btn-sm';
            editButton.title = 'Edit this mapping';
            editButton.innerHTML = '<i class="fa fa-pencil"></i>';
            editButton.addEventListener('click', function(e) {
                e.preventDefault();
                csv.showPropertyMappingDialog({
                    display:  mapping.display,
                    property: mapping.property,
                    replaces: mapping.display
                });
            });
            actionsCell.appendChild(editButton);

            var deleteButton = document.createElement('button');
            deleteButton.type = 'button';
            deleteButton.className = 'btn btn-sm';
            deleteButton.title = 'Delete this mapping';
            deleteButton.style.marginLeft = '6px';
            deleteButton.innerHTML = '<i class="fa fa-trash"></i>';
            deleteButton.addEventListener('click', function(e) {
                e.preventDefault();
                XNAT.xhr.ajax({
                    url: displayMappingsUrl() + '/' + encodeURIComponent(mapping.display),
                    method: 'DELETE',
                    success: function() {
                        notify('Property display mapping deleted.');
                        csv.refreshDisplayMappings();
                    },
                    fail: function(error) {
                        xmodal.message('Error', 'Unable to delete the property display mapping'
                                       + ((error && error.responseText) ? ': ' + error.responseText : '.'));
                    }
                });
            });
            actionsCell.appendChild(deleteButton);

            tr.appendChild(actionsCell);
            tbody.appendChild(tr);
        });

        var addButton = document.createElement('button');
        addButton.type = 'button';
        addButton.className = 'btn btn-sm';
        addButton.style.marginTop = '8px';
        addButton.textContent = 'Add Mapping';
        addButton.addEventListener('click', function(e) {
            e.preventDefault();
            csv.showPropertyMappingDialog({});
        });

        container.appendChild(table);
        container.appendChild(addButton);
    };

    /**
     * Loads the display mappings and the modality configuration (for soft root
     * warnings) before the editors render. The callback fires once the display
     * mappings request settles; a failed modalities request only disables the
     * root warnings.
     */
    function loadReferenceData(callback) {
        XNAT.xhr.getJSON({
            url: modalitiesUrl(),
            success: function(data) {
                var roots = GENERIC_ROOTS.slice();
                (Array.isArray(data) ? data : []).forEach(function(modality) {
                    if (modality.scan) {
                        roots.push(modality.scan.toLowerCase());
                    }
                    if (modality.session) {
                        roots.push(modality.session.toLowerCase());
                    }
                });
                csv.validRoots = roots;
            },
            fail: function(error) {
                console.warn('Unable to load the modality configuration; property root warnings are disabled', error);
            }
        });
        XNAT.xhr.getJSON({
            url: displayMappingsUrl(),
            success: function(data) {
                csv.displayMappings = Array.isArray(data) ? data : [];
                callback();
            },
            fail: function(error) {
                console.error('Unable to load property display mappings', error);
                callback();
            }
        });
    }

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
            tableContainerId: SITE_INPUT + '-table',
            hiddenInputId:    SITE_INPUT,
            jsonContainerId:  SITE_INPUT + '-json',
            emptyHint:        'No mappings are configured. Click "Add Mapping" to begin.'
        }, siteUrl());
    };

    csv.loadProject = function() {
        var projectId = csv.getProjectId();
        if (!projectId) {
            console.warn('No project context available; cannot load project CSV column mappings.');
            return;
        }
        initEditor('project', {
            tableContainerId: PROJECT_INPUT + '-table',
            hiddenInputId:    PROJECT_INPUT,
            jsonContainerId:  PROJECT_INPUT + '-json',
            emptyHint:        'No project override is configured; the site-wide mappings apply. Add mappings and save to create a project-level override.'
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

    var initialized = false;

    csv.init = function() {
        if (initialized) {
            return;
        }
        initialized = true;
        loadReferenceData(function() {
            whenPresent(SITE_INPUT + '-table', function() { csv.loadSite(); });
            whenPresent(PROJECT_INPUT + '-table', function() { csv.loadProject(); });
            whenPresent(DISPLAY_TABLE_ID, function() { csv.renderDisplayMappingsManager(); });
        });
    };

    csv.init();
    $(document).ready(csv.init);

    return csv;
}));
