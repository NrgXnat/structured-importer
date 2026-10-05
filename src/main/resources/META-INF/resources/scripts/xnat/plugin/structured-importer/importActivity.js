/*
 * Activity tab callback for structured imports.
 *
 * This replaces core's XNAT.app.activityTab.populateArchivalDetails for
 * structured imports. Core builds its HTML by concatenating status messages,
 * which can carry user-supplied text such as the archive's filename or values
 * from a CSV manifest, so this renders every message as text instead. It also
 * renders the importer's "uploaded, but no files were imported" outcome as a
 * warning, where core would garble it into "N session(s) successfully
 * uploaded to ...".
 *
 * Loaded on every page through the screens/header extension point because the
 * activity tab resumes polling (and resolves this callback by name) on
 * whatever page the user navigates to.
 */

var XNAT = getObject(XNAT);

(function(){
    // Must match StructuredImporter.NO_FILES_IMPORTED_PREFIX.
    var NO_FILES_IMPORTED_PREFIX = 'NoFilesImported:';

    var ENTRY_CLASSES = {
        Waiting: 'info',
        InProgress: 'info',
        Warning: 'warning',
        Failed: 'error',
        Completed: 'success'
    };

    XNAT.plugin = getObject(XNAT.plugin || {});
    XNAT.plugin.structuredImporter = getObject(XNAT.plugin.structuredImporter || {});

    function progressDiv(clazz) {
        return $('<div class="prog"></div>').addClass(clazz);
    }

    // The success message has the form "Archive:/archive/experiments/ID;/archive/experiments/ID...".
    function renderSessionLinks(finalMessage) {
        var separator = finalMessage.indexOf(':');
        var dest = finalMessage.substring(0, separator);
        var urls = finalMessage.substring(separator + 1).split(';');
        var div = progressDiv('success').text(urls.length + ' session(s) successfully uploaded to ' + dest + ': ');
        urls.forEach(function(url, i) {
            if (i > 0) {
                div.append(document.createTextNode(', '));
            }
            div.append($('<a target="_blank"></a>').attr('href', XNAT.url.rootUrl('/data' + url)).text(url.replace(/.*\//, '')));
        });
        return div;
    }

    XNAT.plugin.structuredImporter.populateArchivalDetails = function(itemDivId, detailsTag, jsonobj, lastProgressIdx) {
        var succeeded = jsonobj['succeeded'];
        var finalMessage = jsonobj['finalMessage'] || '';
        var payload = jsonobj['payload'] ? JSON.parse(jsonobj['payload']) : null;
        var entryList = payload ? (payload['entryList'] || []) : [];
        var noFilesImported = succeeded === true && finalMessage.indexOf(NO_FILES_IMPORTED_PREFIX) === 0;

        var divs = [];
        entryList.forEach(function(entry, i) {
            if (i <= lastProgressIdx) {
                return;
            }
            lastProgressIdx = i;
            var message = entry.message || '';
            // The terminal entry carrying the prefixed message is replaced by the warning below.
            if (noFilesImported && message === finalMessage) {
                return;
            }
            divs.push(progressDiv(ENTRY_CLASSES[entry.status]).text(message.charAt(0).toUpperCase() + message.substr(1)));
        });

        if (noFilesImported) {
            divs.push(progressDiv('warning').text(finalMessage.substring(NO_FILES_IMPORTED_PREFIX.length)));
        } else if (succeeded === true) {
            divs.push(renderSessionLinks(finalMessage));
        } else if (succeeded === false) {
            divs.push(progressDiv('error').text('Import failed: ' + finalMessage));
        }

        $(detailsTag).append(divs);
        return {succeeded: succeeded === undefined ? null : succeeded, lastProgressIdx: lastProgressIdx};
    };
})();
