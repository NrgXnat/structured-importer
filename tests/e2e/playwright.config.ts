import { defineConfig } from '@playwright/test';
import * as path from 'path';
import * as dotenv from 'dotenv';

dotenv.config({ path: path.resolve(__dirname, '.env') });

const BASE_URL = process.env.XNAT_URL || 'http://localhost';

const ADMIN_STATE = path.resolve(__dirname, '.auth/admin.json');
const NON_ADMIN_STATE = path.resolve(__dirname, '.auth/nonadmin.json');

export default defineConfig({
    testDir: './tests',

    // An import extracts an archive, creates a subject, a session and its
    // scans, then writes the files into the archive. On a loaded server that
    // is comfortably slower than a plain REST call.
    timeout: 120_000,
    expect: { timeout: 15_000 },

    // The api, validation and permissions suites write site-wide
    // configuration, which is a single site-scoped document. Two specs running
    // at once would interleave those writes, and the loser's assertions would
    // describe a state neither test set up. Sequential execution is a
    // correctness requirement here, not a performance choice.
    fullyParallel: false,
    workers: 1,
    retries: 0,

    reporter: [['html', { open: 'never' }], ['list']],

    use: {
        baseURL: BASE_URL,
        ignoreHTTPSErrors: true,
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure',
        actionTimeout: 15_000,
        navigationTimeout: 30_000,
    },

    // Projects are the suite's organizing axis: one per KIND of test, matching
    // the directory layout under tests/. `npm run test:import` runs only the
    // round trips, `npm run test:validation` only the refusal paths, and so on.
    projects: [
        {
            name: 'setup',
            testMatch: /global-setup\.ts$/,
        },
        {
            // Configuration REST API: reads and writes of the plugin's own
            // settings, with no archive involved.
            name: 'api',
            testDir: './tests/api',
            dependencies: ['setup'],
            use: { storageState: ADMIN_STATE },
        },
        {
            // End-to-end round trips: upload an archive, assert on what was
            // actually created in the archive.
            name: 'import',
            testDir: './tests/import',
            dependencies: ['setup'],
            use: { storageState: ADMIN_STATE },
        },
        {
            // Refusal paths. Every test here asserts the importer REJECTED
            // something.
            name: 'validation',
            testDir: './tests/validation',
            dependencies: ['setup'],
            use: { storageState: ADMIN_STATE },
        },
        {
            // Role and access gating, driven as the non-admin account.
            name: 'permissions',
            testDir: './tests/permissions',
            dependencies: ['setup'],
            use: { storageState: NON_ADMIN_STATE },
        },
        {
            // The browser-driven suite: the uploader page, and real uploads
            // submitted through the form.
            name: 'browser',
            testDir: './tests/ui',
            dependencies: ['setup'],
            use: { storageState: ADMIN_STATE },
        },
    ],
});
