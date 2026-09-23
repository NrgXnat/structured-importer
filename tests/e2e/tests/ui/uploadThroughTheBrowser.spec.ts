/**
 * Importing through the upload form.
 *
 * The API specs prove the importer is correct; these prove a user can reach
 * it. Everything before the handler differs: the form decides which parameters
 * are sent at all, through disabled attributes, hidden rows and client-side
 * validation, and the modality control is a Chosen widget rather than the
 * native select the API sees.
 *
 * Each test asserts on what ends up in XNAT, not on a success banner.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi } from '../../lib/api';
import { buildDirectoryArchive, buildManifestArchive, cleanupArchives } from '../../lib/archive';
import { selectStructuredHandler, chooseModality } from '../../lib/uploaderPage';
import { COLUMNS } from '../../lib/manifest';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('uiup');

let api: StructuredImporterApi;
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.beforeEach(async ({ page }) => {
    await page.goto(`/app/template/CompressedUploaderPage.vm?project=${encodeURIComponent(PROJECT)}`);
    await page.waitForLoadState('load');
    await expect(page.locator('#uploadFORM')).toBeVisible();
    await selectStructuredHandler(page);
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

/**
 * Polls XNAT for a session with this label.
 *
 * The form posts and the server does the extraction, session creation and file
 * writing before responding, so the record appears shortly after submission
 * rather than instantly. Polling the exact readiness condition, with a cap, is
 * the right wait here: a fixed sleep would be either flaky or slow, and there
 * is no client-side event that means "the archive is on disk".
 */
async function waitForSession(label: string, timeoutMs = 90_000): Promise<string | undefined> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const match = (await api.listExperiments(PROJECT)).find(e => e.label === label);
        if (match) return match.ID;
        await new Promise(r => setTimeout(r, 1_000));
    }
    return undefined;
}

test('a directory archive uploaded through the form is archived correctly', async ({ page }) => {
    const subject = uniqueLabel('UiUpSubj');
    const session = uniqueLabel('UiUpSess');

    const archive = await buildDirectoryArchive('uiup-dir', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'browser uploaded' } },
    ]);

    await chooseModality(page, 'MR');

    // Custom labels, which is the only way a directory archive can name its
    // subject and session. This also exercises the radio handler and the
    // disabled attributes.
    await page.locator('input.toggleStructuredSessionLabeling[value="manual"]').check();
    await page.locator('#structured-subject-labeling input[name="subject"]').fill(subject);
    await page.locator('#structured-session-labeling input[name="session"]').fill(session);

    await page.locator('#image_archive').setInputFiles(archive);
    await page.locator('#directButton').click();

    const id = await waitForSession(session);
    expect(id, `no session "${session}" appeared after submitting the upload form`).toBeTruthy();
    created.push(id as string);

    expect(await api.getExperimentDataType(id as string)).toBe('xnat:mrSessionData');
    const scans = await api.getScans(id as string);
    expect(scans.map(s => s.ID)).toEqual(['1']);

    const files = await api.getScanResourceFiles(id as string, '1', 'NIFTI');
    expect(files.map(f => f.Name)).toEqual(['a.nii']);
    expect(Number(files[0].Size), 'the bytes must survive the browser upload too').toBe(
        'browser uploaded'.length,
    );
});

test('a manifest archive uploaded through the form applies its metadata', async ({ page }) => {
    const subject = uniqueLabel('UiCsvSubj');
    const session = uniqueLabel('UiCsvSess');

    const archive = await buildManifestArchive('uiup-csv', COLUMNS, [
        {
            columns: {
                'Scan ID': '1',
                Modality: 'MR',
                'Series Description': 'Uploaded through the browser',
                'Session Label': session,
                'Subject ID': subject,
                'Resource Name': 'NIFTI',
                Path: 'd1',
            },
            files: { 'a.nii': 'csv via browser' },
        },
    ]);

    await chooseModality(page, 'MR');
    // Leaving the labeling on "Extract From Structure" selects the CSV
    // service, which is what a manifest upload is meant to use.
    await expect(page.locator('input.toggleStructuredSessionLabeling[value="derived"]')).toBeChecked();

    await page.locator('#image_archive').setInputFiles(archive);
    await page.locator('#directButton').click();

    const id = await waitForSession(session);
    expect(id, `no session "${session}" appeared after submitting a manifest archive`).toBeTruthy();
    created.push(id as string);

    const scan = await api.getScan(id as string, '1');
    expect(scan.series_description, 'the manifest metadata must survive the browser path').toBe(
        'Uploaded through the browser',
    );
});

test('every modality the drop-down offers can actually be submitted from the form', async ({ page }) => {
    // The drop-down is server-rendered from the same service the API reads,
    // but the two paths filter independently. This asserts the page offers
    // nothing the API does not, which is the direction that would let a user
    // pick a modality the importer then rejects.
    const fromPage = (await page.locator('#primary-modality option').allTextContents())
        .map(t => t.trim())
        .filter(Boolean);
    const sessionCapable = (await api.getModalities()).filter(m => m.session).map(m => m.modality);

    expect(fromPage.length).toBeGreaterThan(0);
    for (const modality of fromPage) {
        expect(sessionCapable, `the form offers "${modality}", which has no session data type`).toContain(
            modality,
        );
    }
});

test('Begin Upload is disabled until Customize has both labels filled in', async ({ page }) => {
    // The form guards this by DISABLING the button rather than letting the
    // upload start and complaining afterwards, which is the better of the two
    // designs and worth pinning down so a refactor does not quietly swap it
    // for a modal.
    const archive = await buildDirectoryArchive('uiup-blank', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    await chooseModality(page, 'MR');
    await page.locator('#image_archive').setInputFiles(archive);

    const begin = page.locator('#directButton');
    await expect(begin, 'with derived labeling and a file chosen, upload should be available').toBeEnabled();

    await page.locator('input.toggleStructuredSessionLabeling[value="manual"]').check();
    await expect(begin, 'Customize with two blank labels must not be submittable').toBeDisabled();

    await page.locator('#structured-subject-labeling input[name="subject"]').fill(uniqueLabel('BlankSubj'));
    await expect(begin, 'one label filled is still incomplete').toBeDisabled();

    await page.locator('#structured-session-labeling input[name="session"]').fill(uniqueLabel('BlankSess'));
    await expect(begin, 'both labels filled should re-enable upload').toBeEnabled();
});
