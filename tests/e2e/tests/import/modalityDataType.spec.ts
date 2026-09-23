/**
 * Modality to data-type resolution, end to end.
 *
 * The importer no longer hard-codes a short list of supported modalities. It
 * resolves the session and scan data types from a YAML configuration exposed
 * at /xapi/structured-importer/modalities, merging built-in defaults with any
 * classpath overrides.
 *
 * YamlBasedModalityDataTypeServiceTest covers the merge. These tests cover the
 * consequence: that the data type the endpoint advertises is the data type an
 * import actually produces. A configuration that advertises a mapping the
 * importer does not honor is worse than no configuration, because the
 * administrator has no way to see the difference.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER, ModalityMapping } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('mod');

let api: StructuredImporterApi;
let modalities: ModalityMapping[];
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
    modalities = await api.getModalities();
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test('a modality that is not in the configuration is refused', async () => {
    const archive = await buildDirectoryArchive('mod-unknown', [
        { scanId: '1', modality: 'MR', resourceName: 'DATA', files: { 'a.dat': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('ModSubjUnknown'),
        session: uniqueLabel('ModSessUnknown'),
        'primary-modality': 'NOT_A_REAL_MODALITY',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok(), 'an unconfigured modality must not create a session').toBeFalsy();
    expect(await res.text(), 'the refusal must name the modality it did not recognize').toContain(
        'NOT_A_REAL_MODALITY',
    );
});

test('the modality configuration is ordered by group and is free of duplicate modalities', async () => {
    // The endpoint documents its ordering because the UI renders the drop-down
    // straight from it. A duplicate modality would make the drop-down
    // ambiguous and the resolution order undefined.
    const groups = modalities.map(m => m.group);
    expect(groups, 'modalities must arrive grouped, most common first').toEqual(
        [...groups].sort((a, b) => a - b),
    );

    const codes = modalities.map(m => m.modality);
    expect(codes, 'a modality must appear exactly once').toHaveLength(new Set(codes).size);
});

test('every advertised mapping names at least one data type, and names it plausibly', async () => {
    // The two halves are used independently: `session` resolves the session
    // type from the primary-modality upload parameter, `scan` resolves the
    // scan type from the modality directory inside the archive. A mapping with
    // neither would be inert.
    for (const mapping of modalities) {
        expect(
            mapping.scan || mapping.session,
            `modality ${mapping.modality} names neither a scan nor a session data type`,
        ).toBeTruthy();
        if (mapping.scan) expect(mapping.scan).toMatch(/ScanData$/);
        if (mapping.session) expect(mapping.session).toMatch(/SessionData$/);
    }
});

test('a modality offered for session creation cannot fail on its own scans', async () => {
    // A modality with a session type but no scan type is offered in the
    // uploader's primary-modality drop-down, because that list is filtered on
    // hasSessionDataType alone. Picking it works right up until the archive
    // contains a scan directory of the same modality, at which point the
    // import fails with "has no scan data type configured".
    //
    // On the build this was written against, PETMR is exactly that: session
    // xnat:petmrSessionData, no scan type. SC and MRS are the mirror image
    // (scan type, no session type) and are correctly kept out of the
    // drop-down, so they are not affected.
    //
    // The test derives the offending modalities from the live configuration
    // rather than naming PETMR, so it keeps testing the real thing as the
    // configuration changes, and starts passing on its own once the mapping
    // is completed.
    const sessionOnly = modalities.filter(m => m.session && !m.scan);

    for (const mapping of sessionOnly) {
        const archive = await buildDirectoryArchive(`mod-selfscan-${mapping.modality}`, [
            { scanId: '1', modality: mapping.modality, resourceName: 'DATA', files: { 'a.dat': 'a' } },
        ]);

        const res = await api.importArchiveRaw(archive, {
            project: PROJECT,
            subject: uniqueLabel(`SelfScanSubj${mapping.modality}`),
            session: uniqueLabel(`SelfScanSess${mapping.modality}`),
            'primary-modality': mapping.modality,
            resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
        });

        const body = await res.text();
        if (res.ok()) {
            const id = body.trim().split('/').filter(Boolean).pop();
            if (id) created.push(id);
        }

        expect(
            res.ok(),
            `modality ${mapping.modality} is offered for session creation but has no scan data type, ` +
                `so an archive containing a ${mapping.modality} scan directory cannot be imported: ${body}`,
        ).toBeTruthy();
    }
});
