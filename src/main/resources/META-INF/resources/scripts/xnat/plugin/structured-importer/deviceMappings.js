/*!
 * Structured Importer plugin — device mappings administration UI.
 *
 * Renders the device list into the site and project "Device Mappings"
 * Spawner tabs. Each device has a mapping CSV whose XNAT Column, XNAT Property,
 * Required and Validation columns become the column mappings an upload naming
 * the device reads its CSV manifest with. Site administrators add, edit and
 * delete site devices; a project can add its own devices or customize a site
 * device, which replaces it for imports into that project. Also used by the
 * compressed uploader for its manifest template downloads.
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

    var devices = XNAT.plugin.structuredImporter.devices =
        getObject(XNAT.plugin.structuredImporter.devices || {});

    // guard against the script being included more than once on a page
    if (devices.__loaded) {
        return devices;
    }
    devices.__loaded = true;

    var SITE_CONTAINER_ID    = 'structured-importer-site-devices';
    var PROJECT_CONTAINER_ID = 'structured-importer-project-devices';

    // A starting point for a new device: the default site-wide column mappings as device rows.
    var NEW_DEVICE_CSV = [
        '"Source Parameter","Source Section","XNAT Column","XNAT Property",Required,Validation,Transform,Notes',
        '"(user input)",,"Scan ID",xnat:imageScanData/ID,yes,,,',
        '"(user input)",,Modality,xnat:imageScanData/modality,yes,,,',
        '"(user input)",,"Series Description",xnat:imageScanData/series_description,yes,,,',
        '"(user input)",,"Session Label",xnat:imageSessionData/label,yes,,,',
        '"(user input)",,"Subject ID",xnat:imageSessionData/subject_ID,yes,,,',
        '"(user input)",,"Start Date",xnat:imageScanData/start_date,no,,"format MM/dd/yyyy",',
        '"(user input)",,"Start Time",xnat:imageScanData/startTime,no,,"format h:mm a",',
        '"(user input)",,"Resource Name",xnat:abstractResource/label,no,,,',
        '"(user input)",,Path,,yes,,"path of the files in the archive",'
    ].join('\r\n') + '\r\n';

    function siteUrl(deviceId) {
        return XNAT.url.rootUrl('/xapi/structured-importer/device-mappings' + (deviceId ? '/' + encodeURIComponent(deviceId) : ''));
    }

    function projectUrl(projectId, deviceId) {
        return XNAT.url.rootUrl('/xapi/structured-importer/projects/' + encodeURIComponent(projectId) + '/device-mappings' +
                                (deviceId ? '/' + encodeURIComponent(deviceId) : ''));
    }

    devices.projectUrl = projectUrl;

    devices.getProjectId = function() {
        var context = (XNAT.data && XNAT.data.context) || {};
        return context.projectID || context.project || (XNAT.data && XNAT.data.projectId) || null;
    };

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function element(tag, attributes, text) {
        var el = document.createElement(tag);
        Object.keys(attributes || {}).forEach(function(name) {
            if (name === 'style') {
                el.style.cssText = attributes[name];
            } else if (name === 'className') {
                el.className = attributes[name];
            } else {
                el.setAttribute(name, attributes[name]);
            }
        });
        if (text != null) {
            el.textContent = text;
        }
        return el;
    }

    function button(label, title, action, html) {
        var el = element('button', { type: 'button', className: 'btn btn-sm', title: title || '', style: 'margin-right:4px;' });
        if (html) {
            el.innerHTML = html;
        } else {
            el.textContent = label;
        }
        el.addEventListener('click', function(e) {
            e.preventDefault();
            action(el);
        });
        return el;
    }

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
            XNAT.ui.banner.top(2000, '<b>' + escapeHtml(message) + '</b>', 'success');
        } else {
            xmodal.message('Structured Importer', escapeHtml(message));
        }
    }

    function failureText(error, fallback) {
        return (error && error.responseText) || fallback;
    }

    /**
     * Fetches a CSV and saves it, rather than letting the browser follow a
     * download link, so a failure (such as a device whose mapping can't build a
     * template, or an expired session redirected to the login page) is
     * reported instead of being saved as the .csv file.
     */
    devices.download = function(url, fallbackName, done) {
        fetch(url, { credentials: 'same-origin' })
            .then(function(response) {
                var contentType = response.headers.get('Content-Type') || '';
                if (!response.ok || contentType.indexOf('text/csv') !== 0) {
                    return response.text().then(function(text) {
                        var message;
                        if (response.ok || response.status === 401) {
                            message = 'The download failed. Your session may have expired; reload the page and log in again.';
                        } else if (text && contentType.indexOf('text/html') !== 0) {
                            // the server explains why, e.g. a mapping that can't build a template
                            message = text;
                        } else {
                            message = 'The download failed (status ' + response.status + ').';
                        }
                        throw new Error(message);
                    });
                }
                var disposition = response.headers.get('Content-Disposition') || '';
                var match = /filename="([^"]+)"/.exec(disposition);
                return response.blob().then(function(blob) {
                    saveBlob(blob, match ? match[1] : fallbackName);
                });
            })
            .catch(function(error) {
                console.error('Unable to download ' + url, error);
                xmodal.message('Error', escapeHtml((error && error.message) || 'The download failed.').replace(/\n/g, '<br>'));
            })
            .then(function() {
                if (done) {
                    done();
                }
            });
    };

    function saveBlob(blob, filename) {
        var objectUrl = URL.createObjectURL(blob);
        var link = element('a', { href: objectUrl, download: filename, style: 'display:none;' });
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        // revoke after the click has been handled so the download can start
        setTimeout(function() {
            URL.revokeObjectURL(objectUrl);
        }, 0);
    }

    /**
     * The device list for one scope. For a project, the list is the devices
     * available to the project: site devices can be customized (copied into
     * the project) and project devices edited or deleted.
     */
    function DeviceManager(container, projectId) {
        this.container = container;
        this.projectId = projectId || null;
        this.isProject = !!projectId;
    }

    DeviceManager.prototype.url = function(deviceId) {
        return this.isProject ? projectUrl(this.projectId, deviceId) : siteUrl(deviceId);
    };

    DeviceManager.prototype.load = function() {
        var manager = this;
        this.container.innerHTML = '';
        this.container.appendChild(element('div', { className: 'description' }, 'Loading devices…'));
        XNAT.xhr.getJSON({
            url: this.url(),
            success: function(data) {
                manager.render(Array.isArray(data) ? data : []);
            },
            fail: function(error) {
                manager.container.innerHTML = '';
                manager.container.appendChild(element('div', { className: 'description' },
                    'Unable to load the devices' + (error && error.status ? ' (status ' + error.status + ')' : '') + '.'));
            }
        });
    };

    DeviceManager.prototype.render = function(list) {
        var manager = this;
        var container = this.container;
        container.innerHTML = '';

        var table = element('table', { className: 'xnat-table', style: 'width:100%;' });
        table.innerHTML =
            '<thead><tr>' +
            '<th style="text-align:left;">Device</th>' +
            '<th style="text-align:left;">Category</th>' +
            '<th style="text-align:left;">Modality</th>' +
            '<th style="text-align:left;">Status</th>' +
            (this.isProject ? '<th style="text-align:left;">Defined In</th>' : '') +
            '<th style="text-align:left;width:250px;"></th>' +
            '</tr></thead>';
        var tbody = element('tbody');
        table.appendChild(tbody);

        if (!list.length) {
            var empty = element('tr');
            var cell = element('td', { colspan: this.isProject ? '6' : '5', className: 'description' },
                'No devices are configured. Click "Add Device" to add one.');
            empty.appendChild(cell);
            tbody.appendChild(empty);
        }

        list.forEach(function(device) {
            var tr = element('tr');

            var nameCell = element('td');
            nameCell.appendChild(element('b', {}, device.name || device.id));
            nameCell.appendChild(element('div', { className: 'description', style: 'font-size:11px;' },
                [device.manufacturer, device.model].filter(Boolean).join(' · ') + ' (' + device.id + ')'));
            tr.appendChild(nameCell);
            tr.appendChild(element('td', {}, device.category || ''));
            tr.appendChild(element('td', {}, device.modality || ''));
            tr.appendChild(element('td', { title: device.status === 'draft' ? 'Field names have not been checked against a real file from this device' : '' },
                device.status === 'draft' ? 'Draft' : 'Verified'));
            if (manager.isProject) {
                tr.appendChild(element('td', {}, device.scope === 'project' ? (device.overridesSite ? 'Project (customized)' : 'Project') : 'Site'));
            }

            var actions = element('td', { style: 'white-space:nowrap;' });
            actions.appendChild(button('View', 'View the mapping and the manifest columns', function() {
                manager.view(device);
            }, '<i class="fa fa-eye"></i>'));
            var editable = !manager.isProject || device.scope === 'project';
            actions.appendChild(button(editable ? 'Edit' : 'Customize',
                editable ? 'Edit this device' : 'Copy this site device into the project and edit the copy; the copy replaces it for imports into this project',
                function() {
                    manager.edit(device, !editable);
                }, editable ? '<i class="fa fa-pencil"></i>' : '<i class="fa fa-pencil"></i> Customize'));
            actions.appendChild(button('Download CSV', 'Download the mapping CSV', function(el) {
                el.disabled = true;
                devices.download(manager.url(device.id) + '/csv', device.id + '-mapping.csv', function() { el.disabled = false; });
            }, '<i class="fa fa-download"></i> CSV'));
            actions.appendChild(button('Download Template', 'Download a blank CSV manifest with this device\'s columns', function(el) {
                el.disabled = true;
                devices.download(manager.url(device.id) + '/template', 'struct-import-template-' + device.id + '.csv', function() { el.disabled = false; });
            }, '<i class="fa fa-download"></i> Template'));
            if (editable) {
                actions.appendChild(button('Delete', device.overridesSite ? 'Delete the customization; the site device applies again' : 'Delete this device', function() {
                    manager.remove(device);
                }, '<i class="fa fa-trash"></i>'));
            }
            tr.appendChild(actions);
            tbody.appendChild(tr);
        });

        container.appendChild(table);
        container.appendChild(button('Add Device', '', function() {
            manager.edit(null, false);
        }));
    };

    /** Shows a device's parsed mapping: its manifest columns, problems and warnings. */
    DeviceManager.prototype.view = function(device) {
        XNAT.xhr.getJSON({
            url: this.url(device.id),
            success: function(detail) {
                XNAT.dialog.open({
                    title: (device.manufacturer ? device.manufacturer + ' ' : '') + (device.name || device.id),
                    width: 900,
                    content: detailContent(detail || {}),
                    buttons: [{ label: 'Close', isDefault: true, close: true }]
                });
            },
            fail: function(error) {
                xmodal.message('Error', escapeHtml(failureText(error, 'Unable to load the device.')));
            }
        });
    };

    function messageBox(kind, intro, messages) {
        if (!messages || !messages.length) {
            return '';
        }
        var style = kind === 'error'
            ? 'border:1px solid #c66;background:#fbeeee;'
            : 'border:1px solid #e0c060;background:#fdf6e3;';
        return '<div style="margin-bottom:8px;padding:6px 10px;border-radius:3px;' + style + '">' + escapeHtml(intro) +
            '<ul style="margin:4px 0 0 18px;">' + messages.map(function(message) {
                return '<li>' + escapeHtml(message) + '</li>';
            }).join('') + '</ul></div>';
    }

    function detailContent(detail) {
        var device = detail.device || {};
        var facts = [
            ['XNAT scan type', device.xsiType],
            ['Metadata comes from', device.metadataSource],
            ['Native formats', (device.nativeFormats || []).join(', ')]
        ].filter(function(fact) { return fact[1]; }).map(function(fact) {
            return '<b>' + escapeHtml(fact[0]) + ':</b> ' + escapeHtml(fact[1]);
        }).join('<br>');
        var imported = {};
        (detail.columnMappings || []).forEach(function(mapping) {
            imported[mapping.column] = true;
        });
        var rows = (detail.rows || []).map(function(row) {
            var used = imported[row.xnatColumn];
            return '<tr' + (used ? '' : ' style="color:#999;" title="Ignored on import"') + '>' +
                '<td>' + escapeHtml(row.xnatColumn) + '</td>' +
                '<td>' + (row.xnatProperty ? '<code>' + escapeHtml(row.xnatProperty) + '</code>' : '<i>Path (file locator)</i>') + '</td>' +
                '<td style="text-align:center;">' + (row.required || !row.xnatProperty ? 'Yes' : 'No') + '</td>' +
                '<td>' + escapeHtml(row.sourceParameter) + (row.sourceSection ? '<div class="description" style="font-size:11px;">' + escapeHtml(row.sourceSection) + '</div>' : '') + '</td>' +
                '<td>' + escapeHtml(row.transform) + '</td>' +
                '<td>' + escapeHtml(row.notes) + '</td>' +
                '</tr>';
        }).join('');
        return '<div>' +
            (device.notes ? '<p>' + escapeHtml(device.notes) + '</p>' : '') +
            (facts ? '<p>' + facts + '</p>' : '') +
            messageBox('error', 'This mapping can\'t be used for imports until these problems are fixed:', detail.problems) +
            messageBox('warning', 'Warnings:', detail.warnings) +
            '<p class="description">Uploads that name this device read the CSV manifest with these columns instead of the CSV column mappings. ' +
            'The source parameter, transform and notes describe where each value comes from on the device; they are not applied on import.</p>' +
            '<div style="max-height:420px;overflow:auto;"><table class="xnat-table" style="width:100%;">' +
            '<thead><tr><th style="text-align:left;">Manifest Column</th><th style="text-align:left;">XNAT Property</th><th>Required</th>' +
            '<th style="text-align:left;">Source Parameter</th><th style="text-align:left;">Transform</th><th style="text-align:left;">Notes</th></tr></thead>' +
            '<tbody>' + rows + '</tbody></table></div>' +
            '</div>';
    }

    /**
     * Opens the add/edit dialog. With copyFromSite, the site device is saved as
     * a project device with the same ID, which then replaces it in the project.
     */
    DeviceManager.prototype.edit = function(device, copyFromSite) {
        var manager = this;
        var isNew = !device;
        device = device || { status: 'draft', csv: NEW_DEVICE_CSV };

        var content = element('div');
        var errorEl = element('div', { style: 'display:none;margin-bottom:10px;padding:6px 10px;border:1px solid #c66;background:#fbeeee;border-radius:3px;white-space:pre-wrap;' });
        content.appendChild(errorEl);
        if (copyFromSite) {
            content.appendChild(element('div', { className: 'description', style: 'margin-bottom:10px;' },
                'Saving creates a copy of this site device in the project. The copy replaces the site device for imports into this project; delete it to use the site device again.'));
        }

        var grid = element('div', { style: 'display:flex;flex-wrap:wrap;gap:0 16px;' });
        content.appendChild(grid);

        function field(labelText, value, placeholder, readOnly) {
            var wrapper = element('div', { style: 'flex:1 1 260px;margin-bottom:10px;' });
            wrapper.appendChild(element('label', { style: 'display:block;font-weight:bold;margin-bottom:3px;' }, labelText));
            var input = element('input', { type: 'text', placeholder: placeholder || '', style: 'width:100%;box-sizing:border-box;' });
            input.value = value || '';
            input.readOnly = !!readOnly;
            wrapper.appendChild(input);
            grid.appendChild(wrapper);
            return input;
        }

        var idInput           = field('ID', device.id, 'e.g. vendor-model (letters, digits, _ or -)', !isNew);
        var nameInput         = field('Name', device.name, 'e.g. SkyScan 1172');
        var manufacturerInput = field('Manufacturer', device.manufacturer, 'e.g. Bruker');
        var modelInput        = field('Model', device.model, 'e.g. SkyScan 1172');
        var categoryInput     = field('Category', device.category, 'e.g. Micro-CT');
        var modalityInput     = field('Modality', device.modality, 'e.g. CT; preselected as the primary modality');
        var xsiTypeInput      = field('XNAT Scan Type', device.xsiType, 'e.g. xnat:ctScanData');
        var sourceInput       = field('Metadata Source', device.metadataSource, 'e.g. NRecon reconstruction log');
        var formatsInput      = field('Native Formats', (device.nativeFormats || []).join(', '), 'Comma-separated, e.g. TIFF, LOG');

        var statusWrapper = element('div', { style: 'flex:1 1 260px;margin-bottom:10px;' });
        statusWrapper.appendChild(element('label', { style: 'display:block;font-weight:bold;margin-bottom:3px;' }, 'Status'));
        var statusSelect = element('select');
        [['verified', 'Verified'], ['draft', 'Draft (not checked against a real file)']].forEach(function(option) {
            var el = element('option', { value: option[0] }, option[1]);
            statusSelect.appendChild(el);
        });
        statusSelect.value = device.status === 'verified' ? 'verified' : 'draft';
        statusWrapper.appendChild(statusSelect);
        grid.appendChild(statusWrapper);

        var notesWrapper = element('div', { style: 'margin-bottom:10px;' });
        notesWrapper.appendChild(element('label', { style: 'display:block;font-weight:bold;margin-bottom:3px;' }, 'Notes'));
        var notesInput = element('input', { type: 'text', style: 'width:100%;box-sizing:border-box;' });
        notesInput.value = device.notes || '';
        notesWrapper.appendChild(notesInput);
        content.appendChild(notesWrapper);

        var csvLabel = element('div', { style: 'display:flex;align-items:center;justify-content:space-between;margin-bottom:3px;' });
        csvLabel.appendChild(element('b', {}, 'Mapping CSV'));
        var fileInput = element('input', { type: 'file', accept: '.csv,text/csv', style: 'display:none;' });
        csvLabel.appendChild(fileInput);
        csvLabel.appendChild(button('Load CSV File', 'Replace the text below with a CSV file', function() {
            fileInput.click();
        }, '<i class="fa fa-upload"></i> Load CSV File'));
        content.appendChild(csvLabel);
        var csvInput = element('textarea', { rows: '14', spellcheck: 'false', style: 'width:100%;box-sizing:border-box;font-family:monospace;font-size:12px;white-space:pre;' });
        csvInput.value = device.csv || '';
        content.appendChild(csvInput);
        fileInput.addEventListener('change', function() {
            var file = fileInput.files[0];
            if (!file) {
                return;
            }
            var reader = new FileReader();
            reader.onload = function() {
                csvInput.value = reader.result;
            };
            reader.onerror = function() {
                showError('The file could not be read. Check that it is a plain-text CSV.');
            };
            reader.readAsText(file, 'UTF-8');
            fileInput.value = '';
        });
        content.appendChild(element('div', { className: 'description', style: 'margin-top:4px;' },
            'One row per manifest column. Required headers: Source Parameter, XNAT Column, XNAT Property. Optional: Source Section, ' +
            'Required (yes/no), Validation (a regular expression), Transform, Notes. Exactly one row must have an empty XNAT Property: ' +
            'the manifest column holding each file\'s path in the archive. Rows whose property the importer can\'t set are ignored on import.'));

        function showError(message) {
            errorEl.textContent = message;
            errorEl.style.display = 'block';
        }

        XNAT.dialog.open({
            title: isNew ? 'Add Device' : (copyFromSite ? 'Customize Device for This Project' : 'Edit Device'),
            width: 820,
            content: content,
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(dialog) {
                        var id = idInput.value.trim();
                        if (!id) {
                            showError('The device needs an ID.');
                            return;
                        }
                        var payload = {
                            id:             id,
                            name:           nameInput.value.trim(),
                            manufacturer:   manufacturerInput.value.trim(),
                            model:          modelInput.value.trim(),
                            category:       categoryInput.value.trim(),
                            modality:       modalityInput.value.trim(),
                            xsiType:        xsiTypeInput.value.trim(),
                            metadataSource: sourceInput.value.trim(),
                            nativeFormats:  formatsInput.value.split(',').map(function(format) { return format.trim(); }).filter(Boolean),
                            status:         statusSelect.value,
                            notes:          notesInput.value.trim(),
                            csv:            csvInput.value
                        };
                        XNAT.xhr.ajax({
                            url: manager.url(id),
                            method: 'PUT',
                            contentType: 'application/json',
                            data: JSON.stringify(payload),
                            success: function() {
                                dialog.close();
                                notify('Device saved.');
                                manager.load();
                            },
                            fail: function(error) {
                                showError(failureText(error, 'Unable to save the device' + (error && error.status ? ' (status ' + error.status + ')' : '') + '.'));
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

    DeviceManager.prototype.remove = function(device) {
        var manager = this;
        var content = this.isProject && device.overridesSite
            ? 'Delete this project\'s customization of "' + escapeHtml(device.name) + '"? Imports into this project will use the site device again.'
            : 'Delete the device "' + escapeHtml(device.name) + '"? Uploads can no longer name it.' +
              (this.isProject ? '' : ' Projects that customized it keep their copies.');
        xmodal.confirm({
            title: 'Delete device',
            content: content,
            okAction: function() {
                XNAT.xhr.ajax({
                    url: manager.url(device.id),
                    method: 'DELETE',
                    success: function() {
                        notify('Device deleted.');
                        manager.load();
                    },
                    fail: function(error) {
                        xmodal.message('Error', escapeHtml(failureText(error, 'Unable to delete the device' + (error && error.status ? ' (status ' + error.status + ')' : '') + '.')));
                    }
                });
            }
        });
    };

    devices.managers = {};

    devices.loadSite = function(container) {
        (devices.managers.site = new DeviceManager(container, null)).load();
    };

    devices.loadProject = function(container) {
        var projectId = devices.getProjectId();
        if (!projectId) {
            console.warn('No project context available; cannot load the project devices.');
            return;
        }
        (devices.managers.project = new DeviceManager(container, projectId)).load();
    };

    var initialized = false;

    devices.init = function() {
        if (initialized) {
            return;
        }
        initialized = true;
        whenPresent(SITE_CONTAINER_ID, devices.loadSite);
        whenPresent(PROJECT_CONTAINER_ID, devices.loadProject);
    };

    devices.init();
    $(document).ready(devices.init);

    return devices;
}));
