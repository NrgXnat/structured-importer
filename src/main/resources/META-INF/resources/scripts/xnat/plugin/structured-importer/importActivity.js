/*
 * Activity tab callback for structured imports.
 *
 * Core's XNAT.app.activityTab.populateArchivalDetails renders any successful
 * final message as "N session(s) successfully uploaded to ...", which garbles
 * the importer's "uploaded, but no files were imported" outcome. This callback
 * renders that outcome as a warning and defers everything else to core.
 *
 * Loaded on every page through the screens/header extension point because the
 * activity tab resumes polling (and resolves this callback by name) on
 * whatever page the user navigates to.
 */

var XNAT = getObject(XNAT);

(function(){
    // Must match StructuredImporter.NO_FILES_IMPORTED_PREFIX.
    var NO_FILES_IMPORTED_PREFIX = 'NoFilesImported:';

    XNAT.plugin = getObject(XNAT.plugin || {});
    XNAT.plugin.structuredImporter = getObject(XNAT.plugin.structuredImporter || {});

    XNAT.plugin.structuredImporter.populateArchivalDetails = function(itemDivId, detailsTag, jsonobj, lastProgressIdx) {
        var finalMessage = jsonobj['finalMessage'] || '';
        if (jsonobj['succeeded'] !== true || finalMessage.indexOf(NO_FILES_IMPORTED_PREFIX) !== 0) {
            return XNAT.app.activityTab.populateArchivalDetails(itemDivId, detailsTag, jsonobj, lastProgressIdx);
        }

        // Let core render the progress entries, minus the terminal entry
        // carrying the prefixed message, then add the outcome ourselves.
        var payload = JSON.parse(jsonobj['payload']) || {};
        payload['entryList'] = (payload['entryList'] || []).filter(function(entry) {
            return entry.message !== finalMessage;
        });
        var rtn = XNAT.app.activityTab.populateArchivalDetails(itemDivId, detailsTag,
            $.extend({}, jsonobj, {succeeded: null, payload: JSON.stringify(payload)}), lastProgressIdx);

        $(detailsTag).append($('<div class="prog warning"></div>').text(finalMessage.substring(NO_FILES_IMPORTED_PREFIX.length)));

        // Core returns an array (not an object) when there are no entries to render.
        var idx = rtn && rtn.lastProgressIdx !== undefined ? rtn.lastProgressIdx : lastProgressIdx;
        return {succeeded: true, lastProgressIdx: idx};
    };
})();
