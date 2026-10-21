/**
 * Archives made by compressing a folder rather than its contents.
 *
 * Zipping a folder wraps everything, manifest included, in one top-level
 * folder. The importer looks for the manifest at the archive root, and also
 * one level down when the root holds nothing but a single folder; manifest
 * paths are then relative to that folder. It looks no deeper, so a data CSV
 * inside a one-scan directory layout (1/MR/NIFTI/physio.csv) is never taken
 * for a manifest. Files operating systems add to zips (__MACOSX, .DS_Store,
 * Thumbs.db, desktop.ini) are ignored while looking.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildRawArchive, cleanupArchives } from '../../lib/archive';
import { HEADER } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('wrap');

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

function manifestRow(session: string, path: string): string {
    return `1,MR,Wrapped,${session},${uniqueLabel('WrapSubj')},NIFTI,${path}`;
}

async function importWith(archive: string, resourceIdentifier: string): Promise<string> {
    const id = await api.importArchive(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier,
    });
    created.push(id);
    return id;
}

/** Imports expecting a refusal; returns status and the message with HTML stripped. */
async function importRefused(archive: string) {
    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
    const body = (await res.text())
        .replace(/<[^>]*>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    if (res.ok()) {
        const id = body.split('/').filter(Boolean).pop();
        if (id) created.push(id);
    }
    return { status: res.status(), body };
}

test('a manifest inside a single top-level folder is found, and its paths are relative to that folder', async () => {
    const session = uniqueLabel('WrapSess');
    const archive = await buildRawArchive('wrap-basic', {
        'Study/manifest.csv': `${HEADER}\n${manifestRow(session, 'd1')}\n`,
        'Study/d1/a.nii': 'wrapped payload',
    });

    const id = await importWith(archive, RESOURCE_IDENTIFIER.CSV);
    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
    expect((await api.getScanResourceFiles(id, '1', 'NIFTI')).map(f => f.Name)).toEqual(['a.nii']);
});

test('files macOS and Windows add to a zip do not stop the manifest being found', async () => {
    const session = uniqueLabel('WrapClutter');
    const archive = await buildRawArchive('wrap-clutter', {
        '__MACOSX/Study/._manifest.csv': '',
        '.DS_Store': '',
        'Thumbs.db': '',
        'desktop.ini': '',
        'Study/manifest.csv': `${HEADER}\n${manifestRow(session, 'd1')}\n`,
        'Study/d1/a.nii': 'wrapped payload',
    });

    const id = await importWith(archive, RESOURCE_IDENTIFIER.CSV);
    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
});

test('the manifest-aware service also finds a manifest inside a single top-level folder', async () => {
    // The manual resource identifier chooses between the manifest and the
    // directory services by whether it finds a manifest, so a wrapped
    // manifest must count there too.
    const session = uniqueLabel('WrapManual');
    const archive = await buildRawArchive('wrap-manual', {
        'Study/manifest.csv': `${HEADER}\n${manifestRow(session, 'd1')}\n`,
        'Study/d1/a.nii': 'wrapped payload',
    });

    const id = await importWith(archive, RESOURCE_IDENTIFIER.MANUAL);
    expect((await api.getExperiment(id)).data_fields.label, 'the session label came from the manifest').toBe(
        session,
    );
});

test('a data CSV inside a one-scan directory layout is not mistaken for a manifest', async () => {
    // 1/MR/NIFTI is a chain of single folders. Its CSV is scan data, so the
    // archive must import as a directory layout, CSV included as a file.
    const subject = uniqueLabel('WrapDirSubj');
    const session = uniqueLabel('WrapDirSess');
    const archive = await buildRawArchive('wrap-dir-csv', {
        '1/MR/NIFTI/img.nii': 'image',
        '1/MR/NIFTI/physio.csv': 'time,value\n0,1\n',
    });

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject,
        session,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.MANUAL,
    });
    const body = (await res.text()).trim();
    expect(
        res.ok(),
        `the directory layout should import: HTTP ${res.status()} ${body.slice(0, 200)}`,
    ).toBeTruthy();
    const id = body.split('/').filter(Boolean).pop()!;
    created.push(id);

    expect((await api.getExperiment(id)).data_fields.label).toBe(session);
    expect((await api.getScanResourceFiles(id, '1', 'NIFTI')).map(f => f.Name).sort()).toEqual([
        'img.nii',
        'physio.csv',
    ]);
});

test('a manifest two folders down is not found, and the refusal says so', async () => {
    const archive = await buildRawArchive('wrap-deep', {
        'Outer/Inner/manifest.csv': `${HEADER}\n${manifestRow(uniqueLabel('WrapDeep'), 'd1')}\n`,
        'Outer/Inner/d1/a.nii': 'too deep',
    });
    const { status, body } = await importRefused(archive);
    expect(status).toBe(400);
    expect(body).toMatch(/No CSV manifest/i);
});

test('a top-level folder beside other files is not searched, and the refusal says so', async () => {
    const archive = await buildRawArchive('wrap-sibling', {
        'README.txt': 'not a manifest',
        'Study/manifest.csv': `${HEADER}\n${manifestRow(uniqueLabel('WrapSib'), 'd1')}\n`,
        'Study/d1/a.nii': 'beside a readme',
    });
    const { status, body } = await importRefused(archive);
    expect(status).toBe(400);
    expect(body).toMatch(/No CSV manifest/i);
});

test('two CSV files in the top-level folder are refused, naming the folder and both files', async () => {
    const archive = await buildRawArchive('wrap-two', {
        'Study/aaa.csv': `${HEADER}\n${manifestRow(uniqueLabel('WrapTwoA'), 'd1')}\n`,
        'Study/zzz.csv': `${HEADER}\n${manifestRow(uniqueLabel('WrapTwoB'), 'd1')}\n`,
        'Study/d1/a.nii': 'ambiguous',
    });
    const { status, body } = await importRefused(archive);
    expect(status).toBe(400);
    expect(body).toMatch(/Multiple CSV files/i);
    expect(body).toContain('Study');
    expect(body).toContain('aaa.csv');
    expect(body).toContain('zzz.csv');
});
