/**
 * Manifest shapes the importer handles correctly, and must keep handling.
 *
 * Every case here was confirmed working against the plugin. None of them had a
 * test, which meant a regression in any of them would have been invisible, and
 * each is something a spreadsheet or a real user produces routinely:
 *
 *   - a description containing a comma, so the cell is quoted
 *   - Windows line endings
 *   - a manifest not named manifest.csv
 *   - a scan identifier that is not a number
 *
 * These are positive tests. They assert on the stored record rather than on
 * the response, because an import that accepted the file and then dropped the
 * value would return 200 either way.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, buildRawArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('tol');
const COLUMNS = ['Scan ID', 'Modality', 'Series Description', 'Session Label', 'Subject ID', 'Resource Name', 'Path'];
const HEADER = COLUMNS.join(',');

let api: StructuredImporterApi;
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

async function importManifest(archive: string): Promise<string> {
    const id = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(id);
    return id;
}

test('a quoted cell containing a comma keeps the comma and does not shift the columns', async () => {
    const session = uniqueLabel('QuoteSess');

    const archive = await buildManifestArchive('tol-quoted', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Axial, T1 weighted',
                'Session Label': session, 'Subject ID': uniqueLabel('QuoteSubj'),
                'Resource Name': 'NIFTI', Path: 'd1',
            },
            files: { 'a.nii': 'quoted' },
        },
    ]);

    const id = await importManifest(archive);

    const scan = await api.getScan(id, '1');
    expect(scan.series_description, 'the comma inside the quoted cell must survive')
        .toBe('Axial, T1 weighted');
    // If the quoting had been mishandled, the following columns would have
    // shifted by one and the modality would be wrong or absent.
    expect(scan.modality, 'a mis-parsed quote would shift every later column').toBe('MR');
});

test('a manifest with Windows line endings imports', async () => {
    const session = uniqueLabel('CrlfSess');
    const subject = uniqueLabel('CrlfSubj');

    const archive = await buildRawArchive('tol-crlf', {
        'manifest.csv': `${HEADER}\r\n1,MR,CRLF scan,${session},${subject},NIFTI,d1\r\n`,
        'd1/a.nii': 'crlf payload',
    });

    const id = await importManifest(archive);

    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
    // A stray carriage return would most likely end up on the end of the last
    // column's value, so the resource label is the one to check.
    const resources = await api.getScanResources(id, '1');
    expect(resources.map(r => r.label), 'a trailing carriage return must not reach the stored value')
        .toEqual(['NIFTI']);
});

test('the manifest does not have to be called manifest.csv', async () => {
    // The service globs *.csv rather than looking for a fixed name, so an
    // export named after the study still works. Worth pinning down because the
    // documentation shows manifest.csv and a future change could narrow this.
    const session = uniqueLabel('OddNameSess');

    const archive = await buildRawArchive('tol-odd-name', {
        'study-inventory.csv': `${HEADER}\n1,MR,Odd name,${session},${uniqueLabel('OddSubj')},NIFTI,d1\n`,
        'd1/a.nii': 'odd name payload',
    });

    const id = await importManifest(archive);
    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
});

test('a scan identifier that is not a number is kept as written', async () => {
    // Scan IDs in XNAT are strings, and naming scans after the sequence is
    // common for non-DICOM data, which is exactly this importer's audience.
    const archive = await buildManifestArchive('tol-named-scan', COLUMNS, [
        {
            columns: {
                'Scan ID': 'T1w', Modality: 'MR', 'Series Description': 'Named scan',
                'Session Label': uniqueLabel('NamedSess'), 'Subject ID': uniqueLabel('NamedSubj'),
                'Resource Name': 'NIFTI', Path: 'd1',
            },
            files: { 'a.nii': 'named' },
        },
    ]);

    const id = await importManifest(archive);

    const scans = await api.getScans(id);
    expect(scans.map(s => s.ID), 'the scan ID must not be coerced to a number or renumbered').toEqual(['T1w']);
});
