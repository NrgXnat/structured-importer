/**
 * Authenticates the accounts the suite needs.
 *
 * The capability probe is NOT here. It runs in global.setup.ts, before
 * Playwright collects any test file, because the modality matrix generates its
 * tests at collection time from what the probe records. See that file for why
 * a setup project cannot do that job.
 *
 * What remains here is the browser logins, which produce storage state that
 * tests consume at run time and so have no collection-time dependency.
 */
import { test as setup, expect } from '@playwright/test';
import * as fs from 'fs';
import { login } from '../lib/auth';

const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.ADMIN_PASS || 'admin';
const NON_ADMIN_USER = process.env.NON_ADMIN_USER || '';
const NON_ADMIN_PASS = process.env.NON_ADMIN_PASS || '';

/**
 * Which branch of the plugin this run is aimed at.
 *
 * DEFAULT IS `develop`, because that is where the plugin's current work lives
 * and `main` has not caught up. Set TARGET_BRANCH=main once the work merges;
 * at that point the API will be on main too and this variable can go away.
 */
const TARGET_BRANCH = (process.env.TARGET_BRANCH ?? 'develop').toLowerCase();

setup('authenticate as admin', async ({ page }) => {
    await login(page, ADMIN_USER, ADMIN_PASS, 'admin-csrf.txt');
    await page.context().storageState({ path: '.auth/admin.json' });
});

/**
 * The permissions project needs a second, non-site-admin account. Leaving
 * NON_ADMIN_USER unset skips that project and runs everything else.
 *
 * It is a skip rather than a failure because the account is an environmental
 * prerequisite the suite cannot create for itself, unlike the plugin check
 * below, which is about the code under test. The skip is loud: the permissions
 * specs report as skipped rather than silently passing.
 */
setup('authenticate as non-admin', async ({ page }) => {
    setup.skip(
        NON_ADMIN_USER === '' || NON_ADMIN_PASS === '',
        'NON_ADMIN_USER / NON_ADMIN_PASS are not set, so the permissions project cannot run',
    );
    await login(page, NON_ADMIN_USER, NON_ADMIN_PASS, 'nonadmin-csrf.txt');
    await page.context().storageState({ path: '.auth/nonadmin.json' });
});

setup('report what global setup measured', async () => {
    // The capability probe itself runs in global.setup.ts, before collection,
    // because the modality matrix needs its output at collection time. This
    // test surfaces the result in the run output and fails if that file is
    // missing, which would mean globalSetup did not run.
    const raw = fs.readFileSync('.auth/capabilities.json', 'utf-8');
    const capabilities = JSON.parse(raw) as { version: string; configApi: boolean };

    console.log(
        `[STRUCT-IMPORT-E2E] StructuredImporterPlugin ${capabilities.version}, ` +
            `configuration API ${capabilities.configApi ? 'present' : 'ABSENT'}, ` +
            `TARGET_BRANCH=${TARGET_BRANCH}.`,
    );

    expect(capabilities.version, 'globalSetup did not record a plugin version').toBeTruthy();
});
