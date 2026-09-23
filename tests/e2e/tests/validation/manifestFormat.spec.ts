/**
 * How the CSV manifest is read, at the file-format level.
 *
 * A manifest is a file a human produced, usually from a spreadsheet, so the
 * ways it can be malformed are the ways spreadsheet software actually writes
 * files. That makes these cases far more likely to reach a real user than most
 * of the API-shaped edge cases elsewhere in the suite.
 *
 * The positive half of this lives in tests/import/manifestFormatTolerance.spec.ts.
 * Here are the shapes the importer refuses, plus one it should not.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildManifestArchive, buildRawArchive, cleanupArchives } from '../../lib/archive';
import { COLUMNS, HEADER } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('mfmt');

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

function importRaw(archive: string) {
    return api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.CSV,
    });
}

test('a manifest saved with a byte order mark is read correctly', async () => {
    // Saving as "CSV UTF-8" in Excel writes a byte order mark, and the mark is
    // read as part of the first column's name, so that column never matches.
    // The import then fails claiming a column is missing when it is plainly
    // present in the file, which is about as confusing as a message can be.
    //
    // Measured: the manifest begins EF BB BF 53 63 61 6E ("Scan"), and the
    // import fails with `missing required column(s): [Scan ID]`.
    const session = uniqueLabel('BomSess');
    const withBom = await buildRawArchive('mfmt-bom', {
        'manifest.csv': `﻿${HEADER}\n1,MR,BOM scan,${session},${uniqueLabel('BomSubj')},NIFTI,d1\n`,
        'd1/a.nii': 'bom payload',
    });

    const res = await importRaw(withBom);
    const body = (await res.text()).trim();
    if (res.ok()) created.push(body.split('/').filter(Boolean).pop() as string);

    expect(
        res.ok(),
        `a byte order mark should not hide the first column. Response: ${body.slice(0, 300)}`,
    ).toBeTruthy();
});

test('a manifest with headers and no data rows is refused rather than silently doing nothing', async () => {
    // The manifest route to the same outcome as the scanless directory archive
    // in uploadParameters.spec.ts. Both are covered because a fix to one code
    // path would not necessarily fix the other, and a user reaching it through
    // a manifest sees exactly the same nothing.
    const headersOnly = await buildRawArchive('mfmt-empty', {
        'manifest.csv': `${HEADER}\n`,
        'd1/a.nii': 'orphan payload',
    });

    const before = (await api.listExperiments(PROJECT)).length;
    const res = await importRaw(headersOnly);
    const after = await api.listExperiments(PROJECT);

    expect(
        res.ok() === false || after.length > before,
        `the importer answered HTTP ${res.status()} and created nothing, so the user was told ` +
            'the import worked and got no data',
    ).toBeTruthy();
});

test('two CSV files at the archive root are refused rather than one being picked', async () => {
    // The service globs *.csv, so two candidates are ambiguous. Silently
    // choosing one would import a different session depending on file naming.
    const twoCsvs = await buildRawArchive('mfmt-two-csv', {
        'aaa.csv': `${HEADER}\n1,MR,First,${uniqueLabel('TwoA')},${uniqueLabel('TwoSubj')},NIFTI,d1\n`,
        'zzz.csv': `${HEADER}\n1,MR,Second,${uniqueLabel('TwoB')},${uniqueLabel('TwoSubj')},NIFTI,d1\n`,
        'd1/a.nii': 'x',
    });

    const res = await importRaw(twoCsvs);

    expect(res.ok(), 'an ambiguous archive must not be resolved by guessing').toBeFalsy();
    expect(await res.text()).toMatch(/multiple csv/i);
});

test('a row whose Path does not exist in the archive is refused, and the message names the path', async () => {
    // Built raw on purpose. buildManifestArchive() creates a directory for
    // every row's Path value, which would make the path exist and the test
    // meaningless.
    const badPath = await buildRawArchive('mfmt-bad-path', {
        'manifest.csv':
            `${HEADER}\n1,MR,Missing path,${uniqueLabel('MissSess')},${uniqueLabel('MissSubj')},NIFTI,nope/nothing\n`,
        'elsewhere/a.nii': 'this file is not where the manifest says',
    });

    const res = await importRaw(badPath);

    expect(res.ok(), 'a path that is not in the archive must not import as an empty resource').toBeFalsy();
    expect(
        await res.text(),
        'the refusal should name the path so the user can find the typo',
    ).toContain('nope/nothing');
});

test('a subject label XNAT will not accept is refused with a message that names it', async () => {
    // Non-ASCII labels are rejected, which is XNAT's own rule about what a
    // label may contain rather than anything the importer decides. What this
    // test protects is that the refusal EXPLAINS itself: the user gets a 400
    // that names the offending label, not a bare status code.
    //
    // Checked across several shapes so a regression to an empty body is caught
    // wherever it appears: the accent first, last, alone, and a space in the
    // middle. All five currently return a message.
    for (const subject of ['SubjAccent\u00DC', 'Subj\u00DCAccent', '\u00DC', 'Subj Space']) {
        const archive = await buildManifestArchive(`mfmt-label-${encodeURIComponent(subject)}`, COLUMNS, [
            {
                columns: {
                    'Scan ID': '1', Modality: 'MR', 'Series Description': 'Label check',
                    'Session Label': uniqueLabel('LabelSess'), 'Subject ID': subject,
                    'Resource Name': 'NIFTI', Path: 'd1',
                },
                files: { 'a.nii': 'x' },
            },
        ]);

        const res = await importRaw(archive);
        const body = (await res.text()).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();

        expect(res.ok(), `subject "${subject}" was accepted; XNAT rejects these labels`).toBeFalsy();
        expect(
            body.length,
            `subject "${subject}" was refused with an empty body, which tells the user nothing`,
        ).toBeGreaterThan(0);
        expect(body, 'the refusal should name the subject it could not create').toMatch(/create subject/i);
    }
});

test('a subject label with a trailing space is accepted, where one with an inner space is not', async () => {
    // An asymmetry worth pinning down rather than leaving to chance: a
    // trailing space is tolerated, presumably trimmed, while a space in the
    // middle is refused. If either side changes, the suite should say so.
    const trailing = await buildManifestArchive('mfmt-trailing-space', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Trailing space',
                'Session Label': uniqueLabel('TrailSess'), 'Subject ID': `${uniqueLabel('TrailSubj')} `,
                'Resource Name': 'NIFTI', Path: 'd1',
            },
            files: { 'a.nii': 'x' },
        },
    ]);

    const res = await importRaw(trailing);
    const body = (await res.text()).trim();
    expect(res.ok(), `a trailing space should be tolerated: ${body.slice(0, 200)}`).toBeTruthy();
    created.push(body.split('/').filter(Boolean).pop() as string);
});
