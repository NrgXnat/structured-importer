/**
 * CSV manifest resource identifier service, end to end.
 *
 * The unit tests cover parsing a manifest and validating its columns. These
 * tests cover the thing only a running XNAT can answer: whether a value in a
 * CSV cell reaches the XNAT property the column mapping points at.
 *
 * That is the whole purpose of the CSV service, and it is also the easiest
 * thing to get wrong invisibly, because an import that ignores every optional
 * column still returns 200 and still creates a session that looks right in a
 * listing. So each optional column is asserted on the stored record, not on
 * the response.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('csv');

// The column headers of the default site-wide mapping. api/siteCsvMappings
// asserts these ARE the defaults; here they are simply used.
const COLUMNS = [
    'Scan ID',
    'Modality',
    'Series Description',
    'Session Label',
    'Subject ID',
    'Start Date',
    'Start Time',
    'Subject Weight (g)',
    'Resource Name',
    'Path',
];

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

test('every mapped manifest column reaches the XNAT property it is mapped to', async () => {
    const subject = uniqueLabel('CsvSubj');
    const session = uniqueLabel('CsvSess');

    const archive = await buildManifestArchive('csv-full', COLUMNS, [
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'Axial T1 from manifest',
                'Session Label': session,
                'Subject ID': subject,
                'Start Date': '11/02/2020',
                'Start Time': '10:30 am',
                'Subject Weight (g)': '70000',
                'Resource Name': 'NIFTI',
                Path: 'data/scan1',
            },
            files: { 'img1.nii': 'scan one' },
        },
        {
            columns: {
                'Scan ID': '2',
                Modality: 'MR',
                'Series Description': 'Coronal T2 from manifest',
                'Session Label': session,
                'Subject ID': subject,
                'Start Date': '11/02/2020',
                'Start Time': '10:45 am',
                'Subject Weight (g)': '70000',
                'Resource Name': 'NIFTI',
                Path: 'data/scan2',
            },
            files: { 'img2.nii': 'scan two' },
        },
    ]);

    // No subject or session upload parameter: the manifest is the sole source
    // of those labels, which is the mode the CSV service exists for.
    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(experimentId);

    const experiment = await api.getExperiment(experimentId);
    expect(experiment.data_fields.label, 'Session Label column must set the session label').toBe(session);

    const subjectRecord = await api.getSubject(PROJECT, subject);
    expect(experiment.data_fields.subject_ID, 'Subject ID column must set the session owner')
        .toBe(subjectRecord.data_fields.ID);

    const scans = await api.getScans(experimentId);
    expect(scans.map(s => s.ID).sort()).toEqual(['1', '2']);

    const scanOne = await api.getScan(experimentId, '1');
    expect(scanOne.series_description, 'Series Description column').toBe('Axial T1 from manifest');
    expect(scanOne.modality, 'Modality column').toBe('MR');
    // start_date is stored ISO-formatted, not in the MM/DD/YYYY shape the
    // manifest uses and the column's validation pattern enforces.
    expect(scanOne.start_date, 'Start Date column, reformatted to ISO on storage').toBe('2020-11-02');
    expect(scanOne.startTime, 'Start Time column').toBe('10:30:00');

    const demographics = subjectRecord.children
        ?.find((c: any) => c.field === 'demographics')?.items?.[0]?.data_fields;
    expect(demographics?.weight, 'Subject Weight (g) column must reach subject demographics').toBe(70000);
});

test('the Resource Name column names the resource, and the Path column selects its files', async () => {
    const session = uniqueLabel('CsvSessRes');
    const subject = uniqueLabel('CsvSubjRes');

    const archive = await buildManifestArchive('csv-resource', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'With resource name',
                'Session Label': session, 'Subject ID': subject,
                'Resource Name': 'DERIVED', Path: 'files/one',
            },
            files: { 'only.nii': 'the only file' },
        },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(experimentId);

    const resources = await api.getScanResources(experimentId, '1');
    expect(resources.map(r => r.label)).toEqual(['DERIVED']);

    const files = await api.getScanResourceFiles(experimentId, '1', 'DERIVED');
    expect(files.map(f => f.Name)).toEqual(['only.nii']);
    expect(Number(files[0].Size)).toBe('the only file'.length);
});

test('one manifest describing two sessions creates both, not just the first row', async () => {
    const subject = uniqueLabel('CsvSubjTwo');
    const sessionA = uniqueLabel('CsvSessA');
    const sessionB = uniqueLabel('CsvSessB');

    const archive = await buildManifestArchive('csv-two-sessions', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Session A scan',
                'Session Label': sessionA, 'Subject ID': subject,
                'Resource Name': 'NIFTI', Path: 'a/scan1',
            },
            files: { 'a.nii': 'a' },
        },
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Session B scan',
                'Session Label': sessionB, 'Subject ID': subject,
                'Resource Name': 'NIFTI', Path: 'b/scan1',
            },
            files: { 'b.nii': 'b' },
        },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    const body = (await res.text()).trim();
    expect(res.ok(), `Multi-session manifest import failed: HTTP ${res.status()}\n${body}`).toBeTruthy();

    // The import service answers with a single experiment URI. Whether a
    // multi-session archive reports every session it created, or only the
    // last, is behavior this test pins down rather than assumes: a manifest
    // that silently discarded the second session would otherwise pass.
    const experiments = await api.listExperiments(PROJECT);
    const labels = experiments.map(e => e.label);
    expect(labels, 'both sessions named in the manifest must exist')
        .toEqual(expect.arrayContaining([sessionA, sessionB]));

    for (const label of [sessionA, sessionB]) {
        const match = experiments.find(e => e.label === label);
        if (match) created.push(match.ID);
    }
});
