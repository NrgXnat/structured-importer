/**
 * Directory-structure resource identifier service, end to end.
 *
 * The unit tests already cover how SimpleResourceIdentifierService walks an
 * extracted directory tree. What they cannot cover, and what these tests are
 * for, is that the walk's output actually becomes XNAT data: a session of the
 * right type, scans with the right IDs, resources with the right labels, and
 * the uploaded bytes readable back out of the archive.
 *
 * The assertion that earns this file its place is the last one in the first
 * test. An importer that created the session and scans but silently dropped
 * the files would satisfy every other check here, and would look like a
 * successful import to anyone reading the response.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('dir');

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

test('a directory archive becomes a session whose scans, resources and files all survive the import', async () => {
    const subject = uniqueLabel('DirSubj');
    const session = uniqueLabel('DirSess');

    const archive = await buildDirectoryArchive('dir-basic', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'scan1.nii': 'scan one payload' } },
        { scanId: '2', modality: 'MR', resourceName: 'NIFTI', files: { 'scan2.nii': 'scan two payload' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject,
        session,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(experimentId);

    const experiment = await api.getExperiment(experimentId);
    expect(experiment.meta['xsi:type'], 'primary-modality MR must produce an MR session').toBe('xnat:mrSessionData');
    expect(experiment.data_fields.label).toBe(session);
    expect(experiment.data_fields.project).toBe(PROJECT);

    const scans = await api.getScans(experimentId);
    expect(scans.map(s => s.ID).sort(), 'scan IDs come from the top-level directory names').toEqual(['1', '2']);
    for (const scan of scans) {
        expect(scan.xsiType, 'an MR scan directory must produce an MR scan').toBe('xnat:mrScanData');
    }

    const resources = await api.getScanResources(experimentId, '1');
    expect(resources.map(r => r.label), 'the resource label comes from the third directory level').toEqual(['NIFTI']);

    // The check that distinguishes a real import from a convincing empty one.
    const files = await api.getScanResourceFiles(experimentId, '1', 'NIFTI');
    expect(files.map(f => f.Name)).toEqual(['scan1.nii']);
    expect(Number(files[0].Size), 'an imported file with zero bytes is a failed import that reported success')
        .toBe('scan one payload'.length);
});

test('the subject named in the upload parameters is created and owns the session', async () => {
    const subject = uniqueLabel('DirSubjOwn');
    const session = uniqueLabel('DirSessOwn');

    const archive = await buildDirectoryArchive('dir-subject', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject,
        session,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(experimentId);

    const subjectRecord = await api.getSubject(PROJECT, subject);
    expect(subjectRecord.data_fields.label).toBe(subject);

    const experiment = await api.getExperiment(experimentId);
    expect(experiment.data_fields.subject_ID).toBe(subjectRecord.data_fields.ID);
});

test('several resources under one scan all arrive, rather than only the first', async () => {
    const archive = await buildDirectoryArchive('dir-multi-resource', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'img.nii': 'nifti' } },
        { scanId: '1', modality: 'MR', resourceName: 'BIDS', files: { 'sidecar.json': '{}' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject: uniqueLabel('DirSubjMulti'),
        session: uniqueLabel('DirSessMulti'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(experimentId);

    const resources = await api.getScanResources(experimentId, '1');
    expect(resources.map(r => r.label).sort()).toEqual(['BIDS', 'NIFTI']);
});

test('two scans each keep their own files rather than sharing one resource', async () => {
    const archive = await buildDirectoryArchive('dir-per-scan', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'one.nii': 'first scan' } },
        { scanId: '2', modality: 'MR', resourceName: 'NIFTI', files: { 'two.nii': 'second scan' } },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        subject: uniqueLabel('DirSubjSplit'),
        session: uniqueLabel('DirSessSplit'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(experimentId);

    expect((await api.getScanResourceFiles(experimentId, '1', 'NIFTI')).map(f => f.Name)).toEqual(['one.nii']);
    expect((await api.getScanResourceFiles(experimentId, '2', 'NIFTI')).map(f => f.Name)).toEqual(['two.nii']);
});
