/**
 * Project-level CSV column mappings, at the configuration endpoint.
 *
 * This file covers storage and scoping only. Whether a project mapping
 * actually GOVERNS an import is a different and more important question, and
 * it is covered in tests/import/columnMappingPrecedence.spec.ts by running
 * real imports. The endpoint agreeing with itself proves nothing about which
 * document the importer reads.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, ColumnMapping, parseColumnMappings } from '../../lib/api';
import { XNAT_URL, projectNameFor, OWNS_PROJECT } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

const PROJECT = projectNameFor('pcsv');

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; it is on develop and has not reached main`,
);

let api: StructuredImporterApi;
let siteMappings: ColumnMapping[];

const PROJECT_MAPPINGS: ColumnMapping[] = [
    { column: 'Scan ID', property: 'xnat:imageScanData/ID' },
    { column: 'Modality', property: 'xnat:imageScanData/modality' },
    { column: 'Session Label', property: 'xnat:imageSessionData/label' },
    { column: 'Subject ID', property: 'xnat:imageSessionData/subject_ID' },
    { column: 'Path', property: '' },
    { column: 'Project Only Column', property: 'xnat:imageScanData/note', required: false },
];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
    siteMappings = await api.getSiteColumnMappings();
});

test.afterAll(async () => {
    await api.clearProjectColumnMappings(PROJECT);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    await api.dispose();
});

test('a project with no mappings of its own reports empty rather than echoing the site set', async () => {
    await api.clearProjectColumnMappings(PROJECT);

    const res = await api.getProjectColumnMappingsRaw(PROJECT);
    expect(res.ok(), `GET project mappings failed: HTTP ${res.status()}`).toBeTruthy();

    const mappings = parseColumnMappings(await res.json().catch(() => null));
    expect(
        mappings,
        'an unconfigured project must be distinguishable from one configured identically to the site',
    ).toHaveLength(0);
});

test('a project override is stored and read back independently of the site configuration', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

    const stored = parseColumnMappings(await (await api.getProjectColumnMappingsRaw(PROJECT)).json());
    expect(stored.map(m => m.column)).toContain('Project Only Column');

    const site = await api.getSiteColumnMappings();
    expect(
        site.map(m => m.column),
        'a project override must not leak into the site configuration',
    ).not.toContain('Project Only Column');
    expect(site, 'the site configuration must be untouched by a project save').toEqual(siteMappings);
});

test('disabling a project override retains the mappings so a re-save restores them', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

    const disable = await api.disableProjectColumnMappingsRaw(PROJECT);
    expect(disable.ok(), `disable failed: HTTP ${disable.status()} ${await disable.text()}`).toBeTruthy();

    // The documented contract: the mappings are retained and can be restored
    // by saving them again.
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);
    const restored = parseColumnMappings(await (await api.getProjectColumnMappingsRaw(PROJECT)).json());
    expect(restored.map(m => m.column)).toContain('Project Only Column');
});

test('deleting a project override removes it, where disabling did not', async () => {
    await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

    const del = await api.deleteProjectColumnMappingsRaw(PROJECT);
    expect(del.ok(), `delete failed: HTTP ${del.status()}`).toBeTruthy();

    const after = parseColumnMappings(
        await (await api.getProjectColumnMappingsRaw(PROJECT)).json().catch(() => null),
    );
    expect(after, 'a deleted override must be gone, not merely inactive').toHaveLength(0);
});

test('an override on one project does not affect another project', async () => {
    const other = `${PROJECT}_B`.slice(0, 32);
    await api.ensureProject(other);
    try {
        await api.setProjectColumnMappings(PROJECT, PROJECT_MAPPINGS);

        const otherMappings = parseColumnMappings(
            await (await api.getProjectColumnMappingsRaw(other)).json().catch(() => null),
        );
        expect(otherMappings, 'project configuration must be scoped to its own project').toHaveLength(0);
    } finally {
        await api.clearProjectColumnMappings(other);
        if (OWNS_PROJECT) await api.deleteProjectQuietly(other);
    }
});
