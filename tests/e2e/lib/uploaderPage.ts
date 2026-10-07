/**
 * Helpers for driving the compressed uploader page.
 */
import { Page, expect } from '@playwright/test';

/**
 * Selects the structured importer handler and confirms it stuck.
 *
 * The page resets `#import-handler` to its default shortly after load, so a
 * single selection does not survive. Retrying until it does is the only way
 * any browser test can reach the structured controls.
 */
export async function selectStructuredHandler(page: Page): Promise<void> {
    // The Chosen widget is built in a jQuery ready handler, so its container
    // existing means the page's setup has run.
    await expect(page.locator('#primary-modality + .chosen-container')).toHaveCount(1);

    await expect(async () => {
        await page.locator('#import-handler').selectOption('Structured-Zip');
        await page.waitForTimeout(400);
        await expect(page.locator('#import-handler')).toHaveValue('Structured-Zip');
        await expect(page.locator('#structured-primary-modality')).toBeVisible();
    }).toPass({ timeout: 20_000, intervals: [250, 500, 1_000] });
}

/**
 * Picks a modality the way a user does.
 *
 * Chosen hides the native select and renders its own control beside it, so
 * `selectOption` targets an invisible element and times out. The sibling
 * selector is used because Chosen rewrites the id it derives its own from.
 */
export async function chooseModality(page: Page, modality: string): Promise<void> {
    const chosen = page.locator('#primary-modality + .chosen-container');
    await expect(chosen).toBeVisible();
    await chosen.click();
    await chosen
        .locator('.chosen-results li', { hasText: new RegExp(`^${modality}$`) })
        .first()
        .click();
    await expect(chosen.locator('.chosen-single span')).toHaveText(modality);
}
