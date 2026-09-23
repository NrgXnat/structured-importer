/**
 * Two manifest rows that name the same scan.
 *
 * A manifest addresses a scan by subject, session and Scan ID. Nothing stops
 * two rows carrying the same three values, and what should happen then is not
 * obvious from the file format alone:
 *
 *   - When the rows AGREE on the modality, combining their resources onto one
 *     scan is useful. It is how a caller attaches, say, a NIFTI and a BIDS
 *     sidecar to the same scan from separate directories.
 *   - When the rows DISAGREE on the modality, there is no correct answer. The
 *     scan can only have one XNAT data type, so honoring one row means
 *     silently discarding the other. The import should fail instead.
 *
 * The second case is the one that matters, because the failure is invisible:
 * the import returns success, and the scan quietly takes the last row's
 * modality along with its data type.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, cleanupArchives } from '../../lib/archive';
import { COLUMNS } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('dupscan');

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

test('two rows naming the same scan with the same modality combine their resources', async () => {
    const session = uniqueLabel('DupOkSess');
    const subject = uniqueLabel('DupOkSubj');

    const archive = await buildManifestArchive('dupscan-agree', COLUMNS, [
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'Axial T1',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'NIFTI',
                Path: 'nifti',
            },
            files: { 'image.nii': 'nifti payload' },
        },
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'Axial T1',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'BIDS',
                Path: 'bids',
            },
            files: { 'sidecar.json': '{}' },
        },
    ]);

    const experimentId = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    created.push(experimentId);

    const scans = await api.getScans(experimentId);
    expect(
        scans.map(s => s.ID),
        'rows naming the same scan should produce one scan',
    ).toEqual(['1']);
    expect(scans[0].xsiType).toBe('xnat:mrScanData');

    const resources = await api.getScanResources(experimentId, '1');
    expect(resources.map(r => r.label).sort(), 'both rows contribute a resource to the same scan').toEqual([
        'BIDS',
        'NIFTI',
    ]);

    expect((await api.getScanResourceFiles(experimentId, '1', 'NIFTI')).map(f => f.Name)).toEqual([
        'image.nii',
    ]);
    expect((await api.getScanResourceFiles(experimentId, '1', 'BIDS')).map(f => f.Name)).toEqual([
        'sidecar.json',
    ]);
});

test('two rows naming the same scan with different modalities are refused', async () => {
    // A scan carries exactly one XNAT data type, so two rows claiming
    // different modalities for it cannot both be honored. Accepting the import
    // means discarding one row without telling anyone, and the scan silently
    // becomes whichever modality happened to be read last.
    const session = uniqueLabel('DupBadSess');
    const subject = uniqueLabel('DupBadSubj');

    const archive = await buildManifestArchive('dupscan-conflict', COLUMNS, [
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'First row, MR',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'NIFTI',
                Path: 'nifti',
            },
            files: { 'image.nii': 'nifti payload' },
        },
        {
            columns: {
                'Scan ID': '1',
                Modality: 'CT',
                'Series Description': 'Second row, CT',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'DICOM',
                Path: 'dicom',
            },
            files: { 'image.dcm': 'dicom payload' },
        },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });

    const survivor = (await api.listExperiments(PROJECT)).find(e => e.label === session);
    if (survivor) created.push(survivor.ID);

    expect(
        res.ok(),
        'a scan cannot be both MR and CT, so the conflict must fail the import rather than ' +
            'silently keeping whichever row was read last',
    ).toBeFalsy();
    expect(survivor, 'a refused import must not leave a session behind').toBeUndefined();
});
