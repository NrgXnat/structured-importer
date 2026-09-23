/**
 * Refusal paths for the CSV manifest and for custom labeling.
 *
 * Custom labeling is the interesting one. When an upload supplies a subject or
 * session parameter AND the manifest names its own, the two can disagree.
 * CustomLabelingValidator refuses an archive whose manifest describes more
 * than one subject or session while custom labels are in force, because the
 * alternative is collapsing several sessions into one under a single label,
 * which loses data in a way that is very hard to notice afterwards.
 *
 * LabelResolver's precedence (the upload parameter wins over the manifest) is
 * asserted here too, because it is the rule a user is most likely to get
 * backwards, and because getting it backwards produces a working import with
 * the wrong labels rather than an error.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, buildRawArchive, cleanupArchives } from '../../lib/archive';
import { COLUMNS } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('man');

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

test('an archive with no CSV at the root is refused by the CSV service, and the message says what is missing', async () => {
    const noManifest = await buildRawArchive('man-none', { 'data/scan1/a.nii': 'a' });

    const res = await api.importArchiveRaw(noManifest, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    expect(res.ok()).toBeFalsy();
    expect(await res.text()).toContain('No CSV manifest');
});

test('a manifest missing a required column is refused', async () => {
    // Modality is required by the default site configuration. Dropping the
    // column entirely is the case a user hits when they build a manifest by
    // hand from an older template.
    const withoutModality = await buildManifestArchive(
        'man-missing-column',
        ['Scan ID', 'Series Description', 'Session Label', 'Subject ID', 'Resource Name', 'Path'],
        [
            {
                columns: {
                    'Scan ID': '1', 'Series Description': 'No modality column',
                    'Session Label': uniqueLabel('ManSess'), 'Subject ID': uniqueLabel('ManSubj'),
                    'Resource Name': 'NIFTI', Path: 'data/scan1',
                },
                files: { 'a.nii': 'a' },
            },
        ],
    );

    const res = await api.importArchiveRaw(withoutModality, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    expect(res.ok(), 'a manifest missing a required column must not import').toBeFalsy();
});

test('a manifest cell failing its configured validation pattern is refused', async () => {
    // Start Date carries a MM/DD/YYYY validation regex in the default
    // configuration. An ISO date is the most natural wrong answer, and it is
    // the one a user is most likely to supply.
    const badDate = await buildManifestArchive(
        'man-bad-date',
        [...COLUMNS, 'Start Date'],
        [
            {
                columns: {
                    'Scan ID': '1', Modality: 'MR', 'Series Description': 'Bad date',
                    'Session Label': uniqueLabel('ManSess'), 'Subject ID': uniqueLabel('ManSubj'),
                    'Resource Name': 'NIFTI', Path: 'data/scan1',
                    'Start Date': '2020-11-02',
                },
                files: { 'a.nii': 'a' },
            },
        ],
    );

    const res = await api.importArchiveRaw(badDate, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    expect(res.ok(), 'a cell that fails its validation pattern must not be silently dropped').toBeFalsy();
});

test('a manifest naming two subjects is refused when a custom subject label is also supplied', async () => {
    const archive = await buildManifestArchive('man-two-subjects', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Subject one',
                'Session Label': 'SessOne', 'Subject ID': 'SubjectOne',
                'Resource Name': 'NIFTI', Path: 'one/scan1',
            },
            files: { 'a.nii': 'a' },
        },
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Subject two',
                'Session Label': 'SessTwo', 'Subject ID': 'SubjectTwo',
                'Resource Name': 'NIFTI', Path: 'two/scan1',
            },
            files: { 'b.nii': 'b' },
        },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('CustomSubj'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    expect(res.ok(), 'collapsing two subjects under one custom label would lose data').toBeFalsy();
    const body = await res.text();
    expect(body).toContain('SubjectOne');
    expect(body).toContain('SubjectTwo');
});

test('the same multi-subject manifest imports fine with no custom label supplied', async () => {
    // The falsifying half of the test above: it must be the custom label that
    // causes the refusal, not the manifest simply having two subjects. Without
    // this, the previous test would still pass if multi-subject archives were
    // broken outright.
    const archive = await buildManifestArchive('man-two-subjects-ok', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Subject one',
                'Session Label': uniqueLabel('OkSessOne'), 'Subject ID': uniqueLabel('OkSubjOne'),
                'Resource Name': 'NIFTI', Path: 'one/scan1',
            },
            files: { 'a.nii': 'a' },
        },
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Subject two',
                'Session Label': uniqueLabel('OkSessTwo'), 'Subject ID': uniqueLabel('OkSubjTwo'),
                'Resource Name': 'NIFTI', Path: 'two/scan1',
            },
            files: { 'b.nii': 'b' },
        },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    expect(res.ok(), `a multi-subject manifest with no custom label should import: ${await res.text()}`).toBeTruthy();

    for (const e of await api.listExperiments(PROJECT)) {
        if (e.label.startsWith('OkSess')) created.push(e.ID);
    }
});

test('the upload parameter wins over the manifest when both name a session', async () => {
    const manifestSession = uniqueLabel('FromManifest');
    const parameterSession = uniqueLabel('FromParameter');

    const archive = await buildManifestArchive('man-precedence', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Precedence check',
                'Session Label': manifestSession, 'Subject ID': uniqueLabel('PrecSubj'),
                'Resource Name': 'NIFTI', Path: 'data/scan1',
            },
            files: { 'a.nii': 'a' },
        },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        session: parameterSession,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(experimentId);

    const experiment = await api.getExperiment(experimentId);
    expect(experiment.data_fields.label, 'the upload parameter takes precedence over the manifest')
        .toBe(parameterSession);

    const labels = (await api.listExperiments(PROJECT)).map(e => e.label);
    expect(labels, 'the manifest label must not also appear as a second session')
        .not.toContain(manifestSession);
});
