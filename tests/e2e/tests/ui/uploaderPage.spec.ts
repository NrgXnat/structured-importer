/**
 * The compressed uploader page, as a form.
 *
 * This file covers the page's controls. Actually uploading through it is in
 * uploadThroughTheBrowser.spec.ts.
 *
 * What is unique to the page, and unreachable from the REST API:
 *
 *   - The modality drop-down is rendered server-side from
 *     ModalityDataTypeService.getSessionModalities(). It must agree with what
 *     /xapi/structured-importer/modalities advertises, or a user can pick a
 *     modality the importer will then reject.
 *   - The structured labeling radios show and hide the custom subject/session
 *     fields, and those fields start disabled. A disabled input submits no
 *     value, so if the toggle failed to enable them the form would silently
 *     post an import with no custom labels rather than failing.
 *   - The page carries TWO inputs named `subject` and two named `session`,
 *     one pair for the DICOM handler and one for the structured handler.
 *     Selecting them by name alone picks up the wrong one, which is why every
 *     locator here is scoped to its row id.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi } from '../../lib/api';
import { selectStructuredHandler } from '../../lib/uploaderPage';
import { XNAT_URL, projectNameFor, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('ui');

let api: StructuredImporterApi;

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.afterAll(async () => {
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    await api.dispose();
});

test.beforeEach(async ({ page }) => {
    await page.goto(`/app/template/CompressedUploaderPage.vm?project=${encodeURIComponent(PROJECT)}`);
    await page.waitForLoadState('load');
    await expect(page.locator('#uploadFORM')).toBeVisible();
});

test('the structured importer is offered as an import handler', async ({ page }) => {
    const options = await page.locator('#import-handler option').allTextContents();
    expect(
        options.join('|'),
        'the plugin registers the Structured-Zip handler, so the page must offer it',
    ).toMatch(/structured/i);
});

test('the modality drop-down matches the modalities the API advertises', async ({ page }) => {
    await selectStructuredHandler(page);

    // The page renders from the service directly and the API renders from the
    // same service, so a divergence means one of the two paths is filtering
    // and the other is not. A user picking a modality the importer rejects is
    // the visible symptom.
    const fromPage = (await page.locator('#primary-modality option').allTextContents())
        .map(t => t.trim())
        .filter(Boolean);

    const fromApi = (await api.getModalities()).map(m => m.modality);

    expect(fromPage.length, 'the modality drop-down must not be empty').toBeGreaterThan(0);
    for (const modality of fromPage) {
        expect(fromApi, `the page offers "${modality}", which the API does not advertise`).toContain(modality);
    }
});

test('the custom labeling fields start hidden and disabled under Extract From Structure', async ({ page }) => {
    await selectStructuredHandler(page);

    await expect(page.locator('input.toggleStructuredSessionLabeling[value="derived"]')).toBeChecked();
    await expect(page.locator('#structured-subject-labeling')).toBeHidden();
    await expect(page.locator('#structured-session-labeling')).toBeHidden();
    await expect(page.locator('#structured-subject-labeling input[name="subject"]')).toBeDisabled();
    await expect(page.locator('#structured-session-labeling input[name="session"]')).toBeDisabled();
});

test('selecting Customize enables the custom fields, so their values are actually submitted', async ({ page }) => {
    await selectStructuredHandler(page);

    // Both inputs carry the `disabled` attribute in the template, and a
    // disabled input contributes nothing to the POST. Enabled is also not
    // sufficient on its own: an enabled input inside a display:none row still
    // cannot be typed into, so visibility is asserted too.
    const subjectInput = page.locator('#structured-subject-labeling input[name="subject"]');
    const sessionInput = page.locator('#structured-session-labeling input[name="session"]');

    await expect(subjectInput).toBeDisabled();
    await expect(sessionInput).toBeDisabled();

    await page.locator('input.toggleStructuredSessionLabeling[value="manual"]').check();

    await expect(subjectInput).toBeEnabled();
    await expect(sessionInput).toBeEnabled();
    await expect(subjectInput).toBeVisible();
    await expect(sessionInput).toBeVisible();

    await subjectInput.fill('UiSubject');
    await sessionInput.fill('UiSession');
    await expect(subjectInput).toHaveValue('UiSubject');
    await expect(sessionInput).toHaveValue('UiSession');
});

test('switching back to Extract From Structure hides and disables the custom fields again', async ({ page }) => {
    await selectStructuredHandler(page);

    const subjectInput = page.locator('#structured-subject-labeling input[name="subject"]');

    await page.locator('input.toggleStructuredSessionLabeling[value="manual"]').check();
    await expect(subjectInput).toBeEnabled();
    await expect(subjectInput).toBeVisible();
    await subjectInput.fill('WillBeAbandoned');

    await page.locator('input.toggleStructuredSessionLabeling[value="derived"]').check();

    await expect(page.locator('#structured-subject-labeling')).toBeHidden();
    // Leaving it enabled would submit the abandoned value alongside a
    // derived-labeling import, which is the combination the importer refuses.
    await expect(subjectInput).toBeDisabled();
});

test('the structured labeling controls belong to the structured handler, not the DICOM one', async ({ page }) => {
    await selectStructuredHandler(page);

    // Guards the duplicate-name trap described at the top of this file. The
    // DICOM rows use #subject-labeling and #session-labeling; the structured
    // rows use the #structured- prefixed ids. Toggling one pair must not move
    // the other.
    await page.locator('input.toggleStructuredSessionLabeling[value="manual"]').check();

    await expect(page.locator('#structured-subject-labeling')).toBeVisible();
    await expect(page.locator('#subject-labeling'), 'the DICOM labeling row must be unaffected').toBeHidden();
});

test('selecting the structured handler persists without having to retry', async ({ page }) => {
    // Every other test in this suite reaches the structured controls through
    // selectStructuredHandler(), which selects the handler and RETRIES until
    // the selection survives. That helper is necessary, because without it no
    // browser test can get past the first step. It also completely hides the
    // behavior it is working around, which is why this test exists: it selects
    // the handler once, the way a caller reasonably would, and asserts the
    // selection is still there a moment later.
    //
    // Measured on the deployed build: the page finishes initializing after the
    // load event and that late initialization resets #import-handler to its
    // default, hiding the structured rows with it.
    //   t+0    #import-handler = Structured-Zip, modality row visible
    //   t+150  #import-handler = DICOM-zip,      modality row hidden
    //
    // A person clicking will not notice, since that takes longer than the
    // window. Any automated caller hits it every time.
    await expect(page.locator('#primary-modality + .chosen-container')).toHaveCount(1);
    await page.locator('#import-handler').selectOption('Structured-Zip');

    // Long enough to outlast the reset observed at ~150ms, short enough that a
    // passing run stays quick once this is fixed.
    await page.waitForTimeout(500);

    await expect(
        page.locator('#import-handler'),
        'the handler selection was reset by the page after it was made',
    ).toHaveValue('Structured-Zip');
    await expect(page.locator('#structured-primary-modality')).toBeVisible();
});
