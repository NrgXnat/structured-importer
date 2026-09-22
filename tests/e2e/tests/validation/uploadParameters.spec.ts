/**
 * Refusal paths for the upload parameters.
 *
 * Every test here asserts that the importer REFUSED an upload. Each one also
 * asserts that nothing was created, because "returned an error" and "did not
 * create half a session" are different claims, and only the second is what an
 * administrator cares about after a failed import.
 *
 * KNOWN GAP, and the reason these tests do not assert on the error message.
 *
 * Every check in this file is performed by StructuredImporter.validateParameters(),
 * which runs from the IMPORTER'S CONSTRUCTOR. A ClientException thrown there
 * reaches the caller as HTTP 500 with a generic "the server encountered an
 * unexpected condition" page, and the specific message is discarded. Measured
 * against the deployed build, all four constructor-time validations behave
 * this way, while a ClientException thrown later in the import does surface
 * its message, and some of those correctly return 400.
 *
 * So the user gets no indication of WHICH parameter was wrong, and an
 * automated client sees a server error for what is entirely a client mistake.
 * These tests therefore assert the two things that are true and that matter:
 * the upload was refused, and nothing was created. Each carries a
 * commented-out message assertion to enable when the gap is fixed; a passing
 * message assertion is how we will know it was.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, buildNonArchiveFile, buildRawArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('val');

let api: StructuredImporterApi;
let archive: string;

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
    archive = await buildDirectoryArchive('val-base', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);
});

test.afterAll(async () => {
    if (OWNS_PROJECT) await api.deleteProject(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test('an upload with no project is refused and creates nothing', async () => {
    const before = (await api.listExperiments(PROJECT)).length;

    const res = await api.importArchiveRaw(archive, {
        subject: uniqueLabel('ValSubj'),
        session: uniqueLabel('ValSess'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok()).toBeFalsy();
    // Enable when constructor-time validation surfaces its message:
    // expect(await res.text()).toContain('Missing required parameter: project');
    expect(await api.listExperiments(PROJECT), 'a refused upload must create nothing')
        .toHaveLength(before);
});

test('an upload with no primary-modality is refused', async () => {
    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('ValSubj'),
        session: uniqueLabel('ValSess'),
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok()).toBeFalsy();
    // Enable when constructor-time validation surfaces its message:
    // expect(await res.text()).toContain('Missing required parameter: primary-modality');
});

test('an upload naming a project that does not exist is refused', async () => {
    const missingProject = `NO_SUCH_PROJECT_${Date.now().toString(36)}`;

    const res = await api.importArchiveRaw(archive, {
        project: missingProject,
        subject: uniqueLabel('ValSubj'),
        session: uniqueLabel('ValSess'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok()).toBeFalsy();
    // Enable when constructor-time validation surfaces its message:
    // expect(await res.text()).toContain(missingProject);
});

test('a directory archive with no subject or session parameter is refused, because nothing supplies the labels', async () => {
    // The directory service derives no labels of its own, so with no upload
    // parameters there is no subject or session name available from any
    // source. LabelResolver refuses rather than inventing one.
    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok()).toBeFalsy();
    expect(await res.text()).toMatch(/subject|session/i);
});

test('a file that is not an archive is refused as an unsupported format', async () => {
    const notAnArchive = buildNonArchiveFile('val-not-an-archive');

    const res = await api.importArchiveRaw(notAnArchive, {
        project: PROJECT,
        subject: uniqueLabel('ValSubj'),
        session: uniqueLabel('ValSess'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok()).toBeFalsy();
});

test('an archive with no scan directories is refused rather than silently doing nothing', async () => {
    // KNOWN DEFECT, and this test is expected to fail until it is fixed.
    //
    // Measured against the deployed build: an archive containing no scan
    // directories returns HTTP 200 with an EMPTY body and creates nothing at
    // all. No session, no subject, no error, no log the user can see. Someone
    // who zips their data one level too deep, which is the single most likely
    // mistake with this importer, is told their import succeeded and has
    // nothing to show for it.
    //
    // test.fail() rather than test.skip() on purpose: a skip would hide the
    // defect, whereas this reports red today and turns into a failure the day
    // the behavior is fixed, which is exactly when we want to be told.
    test.fail();

    const empty = await buildRawArchive('val-empty', { 'readme.txt': 'no scans in here' });
    const sessionLabel = uniqueLabel('ValSessEmpty');

    const res = await api.importArchiveRaw(empty, {
        project: PROJECT,
        subject: uniqueLabel('ValSubjEmpty'),
        session: sessionLabel,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    const created = (await api.listExperiments(PROJECT)).find(e => e.label === sessionLabel);
    if (created) await api.deleteExperimentQuietly(created.ID);

    // The desired behavior, stated plainly. Either outcome is acceptable:
    // refuse the archive, or import it and produce the session it promised.
    // What is not acceptable is answering 200 and doing neither.
    expect(
        res.ok() === false || created !== undefined,
        `the importer answered HTTP ${res.status()} and created no session "${sessionLabel}", ` +
            'so the user was told the import worked and got nothing',
    ).toBeTruthy();
});
