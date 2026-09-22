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
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('mfmt');
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
    if (OWNS_PROJECT) await api.deleteProject(PROJECT);
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
    // KNOWN DEFECT, expected to fail until fixed.
    //
    // Saving as "CSV UTF-8" in Excel writes a byte order mark, and the mark is
    // read as part of the first column's name, so that column never matches.
    // The import then fails claiming a column is missing when it is plainly
    // present in the file, which is about as confusing as a message can be.
    //
    // Measured: the manifest begins EF BB BF 53 63 61 6E ("Scan"), and the
    // import fails with `missing required column(s): [Scan ID]`.
    test.fail();

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
    // KNOWN DEFECT, expected to fail until fixed. Same root cause as the
    // scanless directory archive in uploadParameters.spec.ts: the importer
    // produces no sessions, logs "didn't find anything actionable", calls
    // failed(), and still answers HTTP 200 with an empty body.
    //
    // Both halves are covered because a fix to one code path would not
    // necessarily fix the other, and a user reaching this through a manifest
    // sees exactly the same nothing.
    test.fail();

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
    const badPath = await buildManifestArchive('mfmt-bad-path', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Missing path',
                'Session Label': uniqueLabel('MissSess'), 'Subject ID': uniqueLabel('MissSubj'),
                'Resource Name': 'NIFTI', Path: 'nope/nothing',
            },
            files: {},
        },
    ]);

    const res = await importRaw(badPath);

    expect(res.ok(), 'a path that is not in the archive must not import as an empty resource').toBeFalsy();
    expect(
        await res.text(),
        'the refusal should name the path so the user can find the typo',
    ).toContain('nope/nothing');
});

test('a subject label containing a non-ASCII character is refused with an explanation', async () => {
    // KNOWN DEFECT, expected to fail until fixed.
    //
    // XNAT rejects the label, which may well be correct. The problem is that
    // the response carries NO message at all, so the user is given a 400 and
    // nothing else. The same import with a SPACE in the label does return a
    // message, so the text is being lost specifically here.
    test.fail();

    const archive = await buildManifestArchive('mfmt-nonascii', COLUMNS, [
        {
            columns: {
                'Scan ID': '1', Modality: 'MR', 'Series Description': 'Non-ASCII label',
                'Session Label': 'NonAsciiSess', 'Subject ID': 'SubjAccentÜ',
                'Resource Name': 'NIFTI', Path: 'd1',
            },
            files: { 'a.nii': 'x' },
        },
    ]);

    const res = await importRaw(archive);
    const body = (await res.text()).trim();
    if (res.ok()) created.push(body.split('/').filter(Boolean).pop() as string);

    // Either accept the label, or refuse it with something the user can act on.
    expect(
        res.ok() || body.replace(/<[^>]*>/g, '').trim().length > 0,
        `HTTP ${res.status()} with an empty body tells the user nothing about what is wrong`,
    ).toBeTruthy();
});
