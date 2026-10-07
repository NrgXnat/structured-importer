/**
 * Access control on the configuration API and on importing, as a non-admin.
 *
 * This project runs with the non-admin storage state, so every request here is
 * made as an ordinary user.
 *
 * Two different gates are in play and they are worth keeping straight:
 *
 *   - The site-wide CSV column mapping endpoints are declared `restrictTo =
 *     Admin`, so the framework refuses a non-admin before the handler runs.
 *   - The property display mapping endpoints are declared `restrictTo =
 *     Authenticated`, and the admin requirement for editing is enforced
 *     inside the handler. A non-admin can read them and must not be able to
 *     change them.
 *
 * A test that only checked the first kind would miss the second entirely,
 * because the second looks open from the request mapping alone.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, NON_ADMIN_USER, uniqueLabel } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

// Every spec in this file needs the second account. Without it there is
// nothing to test, and running as the admin would assert the opposite of what
// these tests mean.
test.skip(
    NON_ADMIN_USER === '',
    'NON_ADMIN_USER is not set; the permissions project needs a non-site-admin account',
);

let user: StructuredImporterApi;
let admin: StructuredImporterApi;

/** A project the non-admin user has no access to at all. */
const PRIVATE_PROJECT = `SI_E2E_PRIV_${Date.now().toString(36)}`.slice(0, 32);

test.beforeAll(async () => {
    user = await StructuredImporterApi.create('nonadmin-csrf.txt', '.auth/nonadmin.json', XNAT_URL);
    admin = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await admin.ensureProject(PRIVATE_PROJECT);
});

test.afterAll(async () => {
    await admin.deleteProjectQuietly(PRIVATE_PROJECT);
    cleanupArchives();
    await user.dispose();
    await admin.dispose();
});

test('a non-admin cannot read the site-wide CSV column mappings', async () => {
    test.skip(!hasConfigApi(), `configuration API absent on ${pluginVersion()}`);

    const res = await user.getSiteColumnMappingsRaw();
    expect(res.status(), 'the site mapping endpoints are admin-only').toBeGreaterThanOrEqual(400);
});

test('a non-admin cannot change the site-wide CSV column mappings', async () => {
    test.skip(!hasConfigApi(), `configuration API absent on ${pluginVersion()}`);

    const before = await admin.getSiteColumnMappings();

    const res = await user.setSiteColumnMappingsRaw([
        { column: 'Injected By Non Admin', property: 'xnat:imageScanData/note' },
    ]);
    expect(res.status()).toBeGreaterThanOrEqual(400);

    // The refusal is only worth anything if nothing changed. A 403 that still
    // wrote would be the worst of both.
    expect(await admin.getSiteColumnMappings(), 'a refused write must not have taken effect').toEqual(before);
});

test('a non-admin can read the property display mappings', async () => {
    test.skip(!hasConfigApi(), `configuration API absent on ${pluginVersion()}`);

    // Deliberately asserting the permissive half. These endpoints are declared
    // Authenticated on purpose so the editor drop-down renders for a project
    // owner, and a change that locked them down would break that silently.
    const res = await user.getPropertyDisplayMappingsRaw();
    expect(
        res.ok(),
        `a non-admin should be able to read the drop-down contents: HTTP ${res.status()}`,
    ).toBeTruthy();
});

test('a non-admin cannot delete a property display mapping', async () => {
    test.skip(!hasConfigApi(), `configuration API absent on ${pluginVersion()}`);

    const existing = await admin.getPropertyDisplayMappings();
    test.skip(
        existing.length === 0,
        'no property display mappings on this instance to attempt a delete against',
    );

    const target = existing[0].display;
    const res = await user.deletePropertyDisplayMappingRaw(target);
    expect(res.status(), 'deleting is admin-only even though reading is not').toBeGreaterThanOrEqual(400);

    const after = await admin.getPropertyDisplayMappings();
    expect(
        after.map(m => m.display),
        'the mapping must still be there',
    ).toContain(target);
});

test('a non-admin cannot import into a project they have no access to', async () => {
    const archive = await buildDirectoryArchive('perm-private', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'a' } },
    ]);

    const res = await user.importArchiveRaw(archive, {
        project: PRIVATE_PROJECT,
        subject: uniqueLabel('PermSubj'),
        session: uniqueLabel('PermSess'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(
        res.ok(),
        `${NON_ADMIN_USER} must not be able to import into a project they cannot edit`,
    ).toBeFalsy();
    expect(
        await admin.listExperiments(PRIVATE_PROJECT),
        'a refused import must leave the project empty',
    ).toHaveLength(0);
});
