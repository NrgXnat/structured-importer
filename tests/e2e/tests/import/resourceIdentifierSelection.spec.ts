/**
 * Which resource identifier service handles an upload.
 *
 * Selection has three inputs and is easy to get subtly wrong, because every
 * wrong answer still produces a plausible-looking import:
 *
 *   - an explicit `resourceIdentifier` bean name wins outright
 *   - otherwise `toggleStructuredSessionLabeling` decides: absent or
 *     "derived" selects the CSV service, anything else selects the
 *     manifest-aware service
 *   - the manifest-aware service then picks CSV or directory walking by
 *     whether a *.csv sits at the archive root
 *
 * ResourceIdentifierSelectorTest covers the string logic. What it cannot show
 * is that the selected bean is the one that actually ran, so each test here
 * feeds in an archive that ONLY ONE service can read, and asserts on the
 * result. A directory archive sent to the CSV service must fail; a manifest
 * archive walked as directories would produce scans named after the manifest's
 * folders rather than its Scan ID column.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, buildManifestArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('sel');
const COLUMNS = ['Scan ID', 'Modality', 'Series Description', 'Session Label', 'Subject ID', 'Resource Name', 'Path'];

let api: StructuredImporterApi;
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProject(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test('with no resourceIdentifier and no toggle, a directory archive is refused because the CSV service is selected', async () => {
    const archive = await buildDirectoryArchive('sel-default', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubj'),
        session: uniqueLabel('SelSess'),
        'primary-modality': 'MR',
    });

    expect(res.ok(), 'the default path selects the CSV service, which has no manifest to read here').toBeFalsy();
    expect(await res.text()).toContain('No CSV manifest');
});

test('toggleStructuredSessionLabeling=derived selects the CSV service, same as the default', async () => {
    const archive = await buildDirectoryArchive('sel-derived', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubj'),
        session: uniqueLabel('SelSess'),
        'primary-modality': 'MR',
        toggleStructuredSessionLabeling: 'derived',
    });

    expect(res.ok()).toBeFalsy();
    expect(await res.text()).toContain('No CSV manifest');
});

test('a non-derived toggle selects the manifest-aware service, which walks directories when no manifest is present', async () => {
    const session = uniqueLabel('SelSessManual');

    const archive = await buildDirectoryArchive('sel-manual', [
        { scanId: '7', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubjManual'),
        session,
        'primary-modality': 'MR',
        toggleStructuredSessionLabeling: 'manual',
    });
    created.push(experimentId);

    const scans = await api.getScans(experimentId);
    expect(scans.map(s => s.ID), 'a directory-derived scan ID proves the directory walker ran').toEqual(['7']);
});

test('the manifest-aware service prefers the manifest when the archive has one', async () => {
    const session = uniqueLabel('SelSessBoth');
    const subject = uniqueLabel('SelSubjBoth');

    // The manifest names scan 42. If the directory walker had run instead, the
    // scan would be named after the "data" folder, so the scan ID alone
    // distinguishes which service handled the archive.
    const archive = await buildManifestArchive('sel-manifest-wins', COLUMNS, [
        {
            columns: {
                'Scan ID': '42', Modality: 'MR', 'Series Description': 'Manifest driven',
                'Session Label': session, 'Subject ID': subject,
                'Resource Name': 'NIFTI', Path: 'data/scan42',
            },
            files: { 'x.nii': 'x' },
        },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        toggleStructuredSessionLabeling: 'manual',
    });
    created.push(experimentId);

    const scans = await api.getScans(experimentId);
    expect(scans.map(s => s.ID), 'the manifest Scan ID must win over the directory name').toEqual(['42']);

    const experiment = await api.getExperiment(experimentId);
    expect(experiment.data_fields.label).toBe(session);
});

test('an explicit resourceIdentifier overrides the toggle rather than being ignored', async () => {
    const session = uniqueLabel('SelSessOverride');

    // The toggle says "derived", which alone would select the CSV service and
    // fail on this manifest-less archive. The explicit bean name must win.
    const archive = await buildDirectoryArchive('sel-override', [
        { scanId: '3', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubjOverride'),
        session,
        'primary-modality': 'MR',
        toggleStructuredSessionLabeling: 'derived',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(experimentId);

    const scans = await api.getScans(experimentId);
    expect(scans.map(s => s.ID)).toEqual(['3']);
});

test('an unknown resourceIdentifier is refused rather than silently falling back to a default', async () => {
    const archive = await buildDirectoryArchive('sel-bogus', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubjBogus'),
        session: uniqueLabel('SelSessBogus'),
        'primary-modality': 'MR',
        resourceIdentifier: 'noSuchResourceIdentifierService',
    });

    expect(res.ok(), 'an unresolvable service name must fail the import, not pick one for you').toBeFalsy();
});

test('the capitalized class name is not a bean name and is refused', async () => {
    // Guards a real trap: the services are referenced by Spring bean name,
    // which is lower-camel. The class name reads naturally and is wrong, and
    // the failure it produces is an opaque 500 rather than anything that names
    // the parameter. If this ever starts passing, the plugin has gained a
    // case-insensitive lookup and the docs should say so.
    const archive = await buildDirectoryArchive('sel-capitalized', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('SelSubjCap'),
        session: uniqueLabel('SelSessCap'),
        'primary-modality': 'MR',
        resourceIdentifier: 'SimpleResourceIdentifierService',
    });

    expect(res.ok()).toBeFalsy();
});
