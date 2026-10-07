/**
 * Which column-mapping document governs an import.
 *
 * The configuration endpoints echo back whatever was stored; whether the
 * importer reads the project document or the site one is a separate question,
 * and it is the entire point of per-project mappings. Resolution is project
 * scope if present and enabled, then site scope, then the built-in defaults.
 *
 * The project mapping here renames every column to a PROJ_ prefix, so each
 * case is falsifiable in both directions: a PROJ_ manifest importing proves
 * the project document was used, and a site-worded manifest failing proves the
 * site document was not.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, ColumnMapping, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, cleanupArchives } from '../../lib/archive';
import { COLUMNS } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

const PROJECT = projectNameFor('prec');

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; it is on develop and has not reached main`,
);

/** Deliberately shares no column name with the site configuration. */
const PROJECT_MAPPINGS: ColumnMapping[] = [
    { column: 'PROJ_Scan', property: 'xnat:imageScanData/ID' },
    { column: 'PROJ_Modality', property: 'xnat:imageScanData/modality' },
    { column: 'PROJ_Desc', property: 'xnat:imageScanData/series_description' },
    { column: 'PROJ_Session', property: 'xnat:imageSessionData/label' },
    { column: 'PROJ_Subject', property: 'xnat:imageSessionData/subject_ID' },
    { column: 'PROJ_Resource', property: 'xnat:abstractResource/label', required: false },
    { column: 'Path', property: '' },
];

const PROJECT_COLUMNS = PROJECT_MAPPINGS.map(m => m.column);

let api: StructuredImporterApi;
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.afterAll(async () => {
    await api.clearProjectColumnMappings(PROJECT);
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

/** A manifest worded for the project mapping. */
async function projectManifest(name: string, session: string, subject: string): Promise<string> {
    return buildManifestArchive(name, PROJECT_COLUMNS, [
        {
            columns: {
                PROJ_Scan: '1',
                PROJ_Modality: 'MR',
                PROJ_Desc: 'Project mapped scan',
                PROJ_Session: session,
                PROJ_Subject: subject,
                PROJ_Resource: 'NIFTI',
                Path: 'd1',
            },
            files: { 'a.nii': 'project mapped payload' },
        },
    ]);
}

/** The same data, worded for the site mapping. */
async function siteManifest(name: string, session: string, subject: string): Promise<string> {
    return buildManifestArchive(name, COLUMNS, [
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'Site mapped scan',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'NIFTI',
                Path: 'd1',
            },
            files: { 'a.nii': 'site mapped payload' },
        },
    ]);
}

async function importManifest(archive: string) {
    return api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
}

test('a project mapping governs an import into that project', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

    const res = await importManifest(
        await projectManifest('prec-proj', uniqueLabel('PrecProj'), uniqueLabel('PrecSubj')),
    );
    const body = (await res.text()).trim();
    expect(res.ok(), `a manifest matching the PROJECT mapping should import: ${body}`).toBeTruthy();

    const id = body.split('/').filter(Boolean).pop() as string;
    created.push(id);

    const scan = await api.getScan(id, '1');
    expect(scan.series_description, 'the value came through the project mapping').toBe('Project mapped scan');
});

test('the site mapping does NOT govern an import into a project that has its own', async () => {
    // The falsifying half. Without this, the test above would still pass if
    // the importer merged both documents, or accepted any header.
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

    const res = await importManifest(
        await siteManifest('prec-site-blocked', uniqueLabel('PrecSite'), uniqueLabel('PrecSubj')),
    );

    expect(res.ok(), 'site-worded columns must not satisfy a project that overrides them').toBeFalsy();
    expect(await res.text(), 'the refusal should name the columns the PROJECT mapping requires').toContain(
        'PROJ_Scan',
    );
});

test('disabling a project mapping falls back to the site mapping at import time', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    const disable = await api.disableProjectColumnMappingsRaw(PROJECT);
    expect(disable.ok(), `disable failed: HTTP ${disable.status()}`).toBeTruthy();

    const res = await importManifest(
        await siteManifest('prec-disabled', uniqueLabel('PrecDisabled'), uniqueLabel('PrecSubj')),
    );
    const body = (await res.text()).trim();
    expect(
        res.ok(),
        `with the project mapping disabled, a site-worded manifest should import: ${body}`,
    ).toBeTruthy();

    const id = body.split('/').filter(Boolean).pop() as string;
    created.push(id);
    const scan = await api.getScan(id, '1');
    expect(scan.series_description).toBe('Site mapped scan');
});

test('a disabled project mapping stops governing, rather than staying in force', async () => {
    // The other half of the disable behavior, and the one that would catch a
    // disable that only changed the read endpoint.
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    await api.disableProjectColumnMappingsRaw(PROJECT);

    const res = await importManifest(
        await projectManifest('prec-disabled-proj', uniqueLabel('PrecX'), uniqueLabel('PrecSubj')),
    );

    expect(res.ok(), 'PROJ_ columns must stop working once the project mapping is disabled').toBeFalsy();
    expect(await res.text(), 'the refusal should now name the SITE columns').toContain('Scan ID');
});

test('a disabled project mapping reads back empty but is restored by saving it again', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    await api.disableProjectColumnMappingsRaw(PROJECT);

    const whileDisabled = await (await api.getProjectColumnMappingsRaw(PROJECT)).json();
    const parsed =
        typeof whileDisabled?.columnMappings === 'string'
            ? JSON.parse(whileDisabled.columnMappings)
            : (whileDisabled?.columnMappings ?? []);
    expect(parsed, 'a disabled configuration reads back as empty, not as its stored contents').toHaveLength(
        0,
    );

    // Documented as retained and restorable. Proved by behavior, not by the
    // read endpoint: after re-saving, PROJ_ columns govern again.
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    const res = await importManifest(
        await projectManifest('prec-restored', uniqueLabel('PrecRestored'), uniqueLabel('PrecSubj')),
    );
    const body = (await res.text()).trim();
    expect(res.ok(), `re-saving should restore the project mapping: ${body}`).toBeTruthy();
    created.push(body.split('/').filter(Boolean).pop() as string);
});

test('deleting a project mapping falls back to the site mapping at import time', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    const del = await api.deleteProjectColumnMappingsRaw(PROJECT);
    expect(del.ok(), `delete failed: HTTP ${del.status()}`).toBeTruthy();

    const res = await importManifest(
        await siteManifest('prec-deleted', uniqueLabel('PrecDeleted'), uniqueLabel('PrecSubj')),
    );
    const body = (await res.text()).trim();
    expect(
        res.ok(),
        `after deleting the project mapping, the site mapping should apply: ${body}`,
    ).toBeTruthy();
    created.push(body.split('/').filter(Boolean).pop() as string);
});

test('a project mapping does not govern imports into a different project', async () => {
    const other = `${PROJECT}_B`.slice(0, 32);
    await api.ensureProject(other);
    try {
        await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

        const res = await api.importArchiveRaw(
            await siteManifest('prec-other', uniqueLabel('PrecOther'), uniqueLabel('PrecSubj')),
            { project: other, 'primary-modality': 'MR', resourceIdentifier: RESOURCE_IDENTIFIER.CSV },
        );
        const body = (await res.text()).trim();
        expect(res.ok(), `the other project should still use the site mapping: ${body}`).toBeTruthy();

        await api.deleteExperimentQuietly(body.split('/').filter(Boolean).pop() as string);
    } finally {
        await api.clearProjectColumnMappings(other);
        if (OWNS_PROJECT) await api.deleteProjectQuietly(other);
    }
});

test('re-importing a session label that already exists is refused, and the original is untouched', async () => {
    // What happens every time somebody re-runs an upload they think failed.
    // The importer answers 409 Conflict rather than merging into or
    // overwriting the existing session.
    await api.clearProjectColumnMappings(PROJECT);

    const session = uniqueLabel('PrecDup');
    const subject = uniqueLabel('PrecDupSubj');

    const first = await importManifest(await siteManifest('prec-dup-1', session, subject));
    const firstBody = (await first.text()).trim();
    expect(first.ok(), `first import should succeed: ${firstBody}`).toBeTruthy();
    const id = firstBody.split('/').filter(Boolean).pop() as string;
    created.push(id);

    const scansBefore = await api.getScans(id);

    const second = await importManifest(await siteManifest('prec-dup-2', session, subject));
    expect(second.status(), 'a duplicate session label is a conflict, not a merge').toBe(409);
    expect(await second.text()).toMatch(/duplicate/i);

    expect(
        await api.getScans(id),
        'the refused re-import must not have altered the existing session',
    ).toHaveLength(scansBefore.length);
});
