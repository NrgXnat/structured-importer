/**
 * Site-wide CSV column mappings.
 *
 * These are the document that decides which manifest column sets which XNAT
 * property, so every CSV import on the instance depends on them. The tests
 * cover the round trip and the defaults, and they restore whatever the
 * instance started with, because leaving a modified mapping behind would
 * change the behavior of every later spec and of the instance itself.
 *
 * The restore is in afterAll rather than afterEach on purpose: a failure
 * partway through a test must still be followed by a restore, and afterAll
 * runs even when a test fails.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, ColumnMapping } from '../../lib/api';
import { XNAT_URL } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; it is on develop and has not reached main`,
);

let api: StructuredImporterApi;
let original: ColumnMapping[];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    original = await api.getSiteColumnMappings();
    expect(original.length, 'the instance should ship a default column mapping').toBeGreaterThan(0);
});

test.afterAll(async () => {
    await api.setSiteColumnMappings(original);
    const restored = await api.getSiteColumnMappings();
    expect(restored, 'the suite must leave the site configuration as it found it').toEqual(original);
    await api.dispose();
});

test('the default configuration maps the columns the importer documents', async () => {
    const byColumn = new Map(original.map(m => [m.column, m]));

    // These five are the columns an import cannot do without: they carry the
    // scan identity, the session and subject the scan belongs to, and where
    // its files live.
    for (const column of ['Scan ID', 'Modality', 'Session Label', 'Subject ID', 'Path']) {
        expect(byColumn.has(column), `default configuration is missing the "${column}" column`).toBeTruthy();
    }

    expect(byColumn.get('Scan ID')?.property).toBe('xnat:imageScanData/ID');
    expect(byColumn.get('Modality')?.property).toBe('xnat:imageScanData/modality');
    expect(byColumn.get('Session Label')?.property).toBe('xnat:imageSessionData/label');
    expect(byColumn.get('Subject ID')?.property).toBe('xnat:imageSessionData/subject_ID');
});

test('a saved mapping is returned verbatim on the next read', async () => {
    await api.setSiteColumnMappings([
        ...original,
        { column: 'E2E Probe Column', property: 'xnat:imageScanData/note', required: false },
    ]);

    const probe = (await api.getSiteColumnMappings()).find(m => m.column === 'E2E Probe Column');
    expect(probe, 'a saved column must survive the round trip').toBeDefined();
    expect(probe?.property).toBe('xnat:imageScanData/note');
    expect(probe?.required).toBe(false);
});

test('a validation pattern is stored with the column rather than discarded', async () => {
    // The pattern is what makes a required column meaningful; a configuration
    // that accepted it and dropped it would let malformed data through while
    // appearing to be configured correctly.
    const pattern = '^[A-Z]{3}-\\d{4}$';
    await api.setSiteColumnMappings([
        ...original,
        {
            column: 'E2E Pattern Column',
            property: 'xnat:imageScanData/note',
            required: true,
            validation: pattern,
        },
    ]);

    const probe = (await api.getSiteColumnMappings()).find(m => m.column === 'E2E Pattern Column');
    expect(probe?.validation).toBe(pattern);
    expect(probe?.required).toBe(true);
});

test('saving an empty mapping set does not silently wipe the configuration', async () => {
    // Compare against the state immediately before this test, not against the
    // set captured in beforeAll: earlier tests in this file deliberately leave
    // probe columns in place, and the restore happens once in afterAll.
    const before = await api.getSiteColumnMappings();

    const res = await api.setSiteColumnMappingsRaw([]);

    if (res.ok()) {
        // If an empty save is accepted it must be a deliberate reset, not a
        // state in which every CSV import fails with no explanation.
        expect(
            await api.getSiteColumnMappings(),
            'an accepted empty save left the instance unable to import any manifest',
        ).not.toHaveLength(0);
    } else {
        expect(await api.getSiteColumnMappings(), 'a rejected save must not change anything').toEqual(before);
    }
});
