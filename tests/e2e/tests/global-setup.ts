/**
 * Authenticates the accounts the suite needs, then measures what the target
 * instance can actually do.
 *
 * Two different kinds of prerequisite are handled differently here, on purpose:
 *
 *   - The Structured-Zip import handler is the code under test. If it is
 *     missing, the run FAILS. A suite that skipped would report green while
 *     covering nothing.
 *   - The configuration REST API is a feature level. It is on `develop` and
 *     has not reached `main` yet. The run fails by default when it is absent,
 *     so nobody tests main and thinks they covered the API, but
 *     TARGET_BRANCH=main switches that to a loud skip for running the importer
 *     coverage alone against an older build.
 *
 * What is measured is written to .auth/capabilities.json, which the
 * config-dependent specs read through lib/capabilities.ts. That is what lets
 * the same suite run unchanged against develop today and main after the merge.
 */
import { test as setup, expect, request as playwrightRequest } from '@playwright/test';
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
const REQUIRE_CONFIG_API = TARGET_BRANCH !== 'main';

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

setup('target instance carries the structured importer features under test', async ({ baseURL }) => {
    const ctx = await playwrightRequest.newContext({
        baseURL,
        storageState: '.auth/admin.json',
        ignoreHTTPSErrors: true,
    });

    const pluginRes = await ctx.get('/xapi/plugins/StructuredImporterPlugin');
    expect(pluginRes.ok(), 'StructuredImporterPlugin is not installed on this instance').toBeTruthy();
    const version = (await pluginRes.json()).version;
    console.log(`[STRUCT-IMPORT-E2E] StructuredImporterPlugin version reported by the server: ${version}`);

    // One endpoint per feature area, so a partial deployment is caught here
    // rather than surfacing later as an unrelated-looking failure.
    const probes: Array<[string, string]> = [
        ['/xapi/structured-importer/modalities', 'modality-to-data-type configuration'],
        ['/xapi/structured-importer/csv-column-mappings', 'site-wide CSV column mappings'],
        ['/xapi/structured-importer/property-display-mappings', 'property display mappings'],
    ];

    const missing: string[] = [];
    for (const [path, feature] of probes) {
        const res = await ctx.get(path);
        if (res.status() === 404) missing.push(`${feature} -> ${path}`);
    }

    fs.mkdirSync('.auth', { recursive: true });
    fs.writeFileSync(
        '.auth/capabilities.json',
        JSON.stringify({ version, configApi: missing.length === 0 }),
        'utf-8',
    );

    // The modality matrix generates one named test per modality, which has to
    // happen at collection time, before any test runs. Playwright cannot await
    // an HTTP call there, so the configuration is written here and read
    // synchronously by the spec. Re-fetched every run, so the matrix always
    // reflects the instance under test rather than a checked-in copy.
    if (missing.length === 0) {
        const res = await ctx.get('/xapi/structured-importer/modalities');
        if (res.ok()) {
            const modalities = await res.json();
            fs.writeFileSync('.auth/modalities.json', JSON.stringify(modalities), 'utf-8');
            console.log(`[STRUCT-IMPORT-E2E] ${modalities.length} modalities recorded for the matrix.`);
        }
    }

    await ctx.dispose();

    if (missing.length > 0) {
        const detail =
            `This instance is running StructuredImporterPlugin ${version}, which does not expose:\n  ` +
            missing.join('\n  ');

        expect(
            REQUIRE_CONFIG_API,
            `${detail}\n\nThe configuration API is currently on the develop branch and has not ` +
                `reached main. TARGET_BRANCH is "${TARGET_BRANCH}". Deploy a develop build, or ` +
                'set TARGET_BRANCH=main to run the importer coverage alone and skip the ' +
                'configuration tests.',
        ).toBeFalsy();

        console.log(
            `[STRUCT-IMPORT-E2E] Configuration API absent on ${version}. ` +
                `TARGET_BRANCH=${TARGET_BRANCH}, so the api project and the config-dependent tests will SKIP.`,
        );
    }
});
