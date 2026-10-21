/**
 * CSV manifest templates: a blank manifest whose header row is the column
 * names an import will expect, for the site and for a project.
 *
 * A template is only useful if it matches what the importer reads, so the
 * last test fills one in and imports it. Only project mappings are changed
 * here; the site mappings are read, never written.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, ColumnMapping, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildRawArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

const PROJECT = projectNameFor('tmpl');

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; it is on develop and has not reached main`,
);

let api: StructuredImporterApi;
let siteColumns: string[];
const created: string[] = [];

/** Splits a header line the way a spreadsheet would read it, honoring quotes. */
function parseHeader(line: string): string[] {
    const cells: string[] = [];
    let cell = '';
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (quoted && c === '"' && line[i + 1] === '"') {
            cell += '"';
            i++;
        } else if (c === '"') {
            quoted = !quoted;
        } else if (c === ',' && !quoted) {
            cells.push(cell);
            cell = '';
        } else {
            cell += c;
        }
    }
    cells.push(cell);
    return cells;
}

/** The template's lines, with the line ending removed. */
function lines(body: string): string[] {
    return body.split(/\r?\n/).filter(l => l !== '');
}

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
    siteColumns = (await api.getSiteColumnMappings()).map(m => m.column);
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    await api.clearProjectColumnMappings(PROJECT);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test('the site template is a CSV download whose only row is the site column names, in order', async () => {
    const res = await api.getSiteCsvTemplateRaw();
    expect(res.ok(), `HTTP ${res.status()}`).toBeTruthy();
    expect(res.headers()['content-type']).toMatch(/^text\/csv/);
    expect(res.headers()['content-type'], 'the importer reads UTF-8, so the template is UTF-8').toMatch(
        /charset=utf-8/i,
    );
    expect(res.headers()['content-disposition']).toMatch(
        /attachment; filename="struct-import-template-\d{8}_\d{6}\.csv"/,
    );

    const rows = lines(await res.text());
    expect(rows, 'a blank template has a header and no data rows').toHaveLength(1);
    expect(parseHeader(rows[0])).toEqual(siteColumns);
});

test('a project with no mappings of its own gets the site columns, in a file named for the project', async () => {
    await api.clearProjectColumnMappings(PROJECT);
    const res = await api.getProjectCsvTemplateRaw(PROJECT);
    expect(res.ok(), `HTTP ${res.status()}`).toBeTruthy();
    expect(res.headers()['content-disposition']).toContain(`struct-import-template-${PROJECT}-`);
    expect(parseHeader(lines(await res.text())[0])).toEqual(siteColumns);
});

test('a project with its own mappings gets its own columns', async () => {
    const own: ColumnMapping[] = [
        { column: 'Scan ID', property: 'xnat:imageScanData/ID' },
        { column: 'Modality', property: 'xnat:imageScanData/modality' },
        { column: 'Session Label', property: 'xnat:imageSessionData/label' },
        { column: 'Subject ID', property: 'xnat:imageSessionData/subject_ID' },
        { column: 'Path', property: '' },
        { column: 'Project Note', property: 'xnat:imageScanData/note', required: false },
    ];
    await api.setProjectColumnMappings(PROJECT, own);
    try {
        const res = await api.getProjectCsvTemplateRaw(PROJECT);
        expect(parseHeader(lines(await res.text())[0])).toEqual(own.map(m => m.column));
    } finally {
        await api.clearProjectColumnMappings(PROJECT);
    }
});

test('a project whose mappings are disabled gets the site columns, which is what an import would use', async () => {
    await api.setProjectColumnMappings(PROJECT, [
        { column: 'Scan ID', property: 'xnat:imageScanData/ID' },
        { column: 'Path', property: '' },
        { column: 'Disabled Only', property: 'xnat:imageScanData/note', required: false },
    ]);
    try {
        const disabled = await api.disableProjectColumnMappingsRaw(PROJECT);
        expect(disabled.ok(), `disable: HTTP ${disabled.status()}`).toBeTruthy();
        const res = await api.getProjectCsvTemplateRaw(PROJECT);
        expect(parseHeader(lines(await res.text())[0])).toEqual(siteColumns);
    } finally {
        await api.clearProjectColumnMappings(PROJECT);
    }
});

test('a column name with a comma or a quote comes back as the same name', async () => {
    const tricky = ['Scan ID', 'Modality', 'Notes, free text', 'The "real" label', 'Path'];
    await api.setProjectColumnMappings(PROJECT, [
        { column: tricky[0], property: 'xnat:imageScanData/ID' },
        { column: tricky[1], property: 'xnat:imageScanData/modality' },
        { column: tricky[2], property: 'xnat:imageScanData/note', required: false },
        { column: tricky[3], property: 'xnat:imageSessionData/label', required: false },
        { column: tricky[4], property: '' },
    ]);
    try {
        const res = await api.getProjectCsvTemplateRaw(PROJECT);
        expect(parseHeader(lines(await res.text())[0])).toEqual(tricky);
    } finally {
        await api.clearProjectColumnMappings(PROJECT);
    }
});

test('the template for a project that does not exist is 404', async () => {
    expect((await api.getProjectCsvTemplateRaw('NO_SUCH_PROJECT_E2E')).status()).toBe(404);
});

test('a downloaded template, filled in with one row, imports', async () => {
    await api.clearProjectColumnMappings(PROJECT);
    const header = lines(await (await api.getProjectCsvTemplateRaw(PROJECT)).text())[0];
    const columns = parseHeader(header);

    const session = uniqueLabel('TmplSess');
    const values: Record<string, string> = {
        'Scan ID': '1',
        Modality: 'MR',
        'Series Description': 'From template',
        'Session Label': session,
        'Subject ID': uniqueLabel('TmplSubj'),
        'Resource Name': 'NIFTI',
        Path: 'd1',
    };
    const row = columns.map(c => values[c] ?? '').join(',');
    const archive = await buildRawArchive('tmpl-filled', {
        'manifest.csv': `${header}\n${row}\n`,
        'd1/a.nii': 'template payload',
    });

    const id = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(id);
    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
});
