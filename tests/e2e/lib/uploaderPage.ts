/**
 * Helpers for driving the compressed uploader page.
 *
 * ## The page-initialization race, and why these helpers exist
 *
 * The page finishes initializing AFTER the browser's `load` event, and part of
 * that late initialization RESETS `#import-handler` back to its default,
 * `DICOM-zip`, hiding every structured row with it.
 *
 * Measured directly: selecting `Structured-Zip` immediately after `load` reads
 * back as `Structured-Zip` at t+0 and as `DICOM-zip` by t+150ms, with
 * `structured-primary-modality` hidden again. Letting the page settle first and
 * then selecting leaves it selected indefinitely.
 *
 * A human is unlikely to hit this, because clicking a drop-down takes longer
 * than the window. Automation hits it every time, and an earlier version of
 * this suite mistook it for a defect in the Customize toggle.
 *
 * So the wait below is a readiness probe for the page's own setup, not a
 * workaround for slowness: it selects the handler and confirms the selection
 * SURVIVED, retrying a bounded number of times. The condition being polled is
 * exactly the condition that matters, which is the highest rung available here
 * given the page emits no event when its setup completes.
 */
import { Page, expect } from '@playwright/test';

/** Selects the structured importer handler and confirms it stuck. */
export async function selectStructuredHandler(page: Page): Promise<void> {
    // The Chosen widget is built in a jQuery ready handler, so its container
    // existing means that much of the page's setup has run.
    await expect(page.locator('#primary-modality + .chosen-container')).toHaveCount(1);

    await expect(async () => {
        await page.locator('#import-handler').selectOption('Structured-Zip');
        // Long enough to outlast the reset observed at ~150ms.
        await page.waitForTimeout(400);
        await expect(page.locator('#import-handler')).toHaveValue('Structured-Zip');
        await expect(page.locator('#structured-primary-modality')).toBeVisible();
    }).toPass({ timeout: 20_000, intervals: [250, 500, 1_000] });
}

/**
 * Picks a modality the way a user does.
 *
 * The page runs the Chosen jQuery widget over `#primary-modality`, which sets
 * the native select to `display:none` and renders its own control beside it.
 * Playwright's selectOption targets the native element and times out, so the
 * real widget is both the only thing that works and the only thing that
 * resembles a user. Chosen derives its container id by replacing non-word
 * characters, so the adjacent-sibling selector is used instead of that id.
 */
export async function chooseModality(page: Page, modality: string): Promise<void> {
    const chosen = page.locator('#primary-modality + .chosen-container');
    await expect(chosen).toBeVisible();
    await chosen.click();
    await chosen.locator('.chosen-results li', { hasText: new RegExp(`^${modality}$`) }).first().click();
    await expect(chosen.locator('.chosen-single span')).toHaveText(modality);
}
