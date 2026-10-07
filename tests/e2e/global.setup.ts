/**
 * Runs BEFORE Playwright collects any test file.
 *
 * ## Why this exists separately from tests/global-setup.ts
 *
 * The modality matrix generates one named test per configured modality, and
 * generating tests happens at COLLECTION time. Playwright collects
 * synchronously and cannot await an HTTP call there, so the modality list has
 * to already be on disk when the spec file is loaded.
 *
 * The `setup` PROJECT cannot provide it. A project runs as part of the test
 * run, which is after collection, so on a fresh checkout the matrix would
 * collect nothing on the first run and thirty-nine tests on the second.
 * Measured: exactly that happened, 1 test collected instead of 39, and the
 * spec's own guard did not catch it because the guard is itself one of the
 * tests that failed to collect properly.
 *
 * `globalSetup` is the only hook that runs before collection, so the modality
 * fetch and the capability probe live here. The `setup` project still does the
 * browser logins, because those produce storage state that tests consume at
 * run time and have no collection-time dependency.
 *
 * Authentication here is basic auth rather than a form login: this runs before
 * any browser exists, and XNAT's REST API accepts it.
 */
import { request } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as dotenv from 'dotenv';

dotenv.config({ path: path.resolve(__dirname, '.env') });

const AUTH_DIR = path.resolve(__dirname, '.auth');

export default async function globalSetup(): Promise<void> {
    const baseURL = process.env.XNAT_URL || 'http://localhost';
    const username = process.env.ADMIN_USER || 'admin';
    const password = process.env.ADMIN_PASS || 'admin';
    const targetBranch = (process.env.TARGET_BRANCH ?? 'develop').toLowerCase();

    fs.mkdirSync(AUTH_DIR, { recursive: true });

    const ctx = await request.newContext({
        baseURL,
        ignoreHTTPSErrors: true,
        httpCredentials: { username, password },
    });

    let version = 'unknown';
    const pluginRes = await ctx.get('/xapi/plugins/StructuredImporterPlugin');
    if (pluginRes.ok()) {
        version = (await pluginRes.json()).version ?? 'unknown';
    }

    const modalitiesRes = await ctx.get('/xapi/structured-importer/modalities');
    const configApi = modalitiesRes.status() !== 404;

    if (modalitiesRes.ok()) {
        const modalities = await modalitiesRes.json();
        fs.writeFileSync(path.join(AUTH_DIR, 'modalities.json'), JSON.stringify(modalities), 'utf-8');
        console.log(`[STRUCT-IMPORT-E2E] ${modalities.length} modalities recorded before collection.`);
    } else {
        // Remove any stale copy, so a run against an instance without the API
        // does not silently generate a matrix from the last instance's list.
        fs.rmSync(path.join(AUTH_DIR, 'modalities.json'), { force: true });
        console.log(
            `[STRUCT-IMPORT-E2E] Modality configuration unavailable (HTTP ${modalitiesRes.status()}); ` +
                'the matrix will not generate.',
        );
    }

    fs.writeFileSync(
        path.join(AUTH_DIR, 'capabilities.json'),
        JSON.stringify({ version, configApi }),
        'utf-8',
    );

    await ctx.dispose();

    // A run aimed at develop that finds no configuration API is a broken
    // deployment, and failing here stops the whole run before it reports a
    // misleading green from the specs that do not need the API.
    if (!configApi && targetBranch !== 'main') {
        throw new Error(
            `StructuredImporterPlugin ${version} does not expose /xapi/structured-importer/modalities.\n` +
                `TARGET_BRANCH is "${targetBranch}". The configuration API is on the develop branch and ` +
                'has not reached main. Deploy a develop build, or set TARGET_BRANCH=main to run the ' +
                'importer coverage alone and skip the configuration tests.',
        );
    }
}
