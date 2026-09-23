/**
 * A project owner, as opposed to a user with no access at all.
 *
 * configApiAccess.spec.ts tests the easy end of the range: a user with no
 * membership anywhere, who should be refused everything. That leaves the
 * interesting case untested. A project owner is a normal, non-admin user who
 * legitimately owns ONE project, and the importer has to let them import into
 * theirs while refusing the one next door.
 *
 * This is the shape of a real deployment. Site administrators are rare;
 * project owners uploading their own data are the actual users of this
 * plugin, and an authorization bug that let one owner write into another
 * owner's project would be the most serious thing this suite could find.
 *
 * The spec grants and revokes the membership itself, so it leaves the
 * instance as it found it.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, NON_ADMIN_USER, uniqueLabel } from '../../lib/env';

test.skip(
    NON_ADMIN_USER === '',
    'NON_ADMIN_USER is not set; this spec needs a non-site-admin account to make a project owner',
);

let user: StructuredImporterApi;
let admin: StructuredImporterApi;

/** The project the non-admin will own. */
const OWNED = `SI_E2E_OWNED_${Date.now().toString(36)}`.slice(0, 32);
/** The project next door, which they must not be able to write to. */
const FOREIGN = `SI_E2E_FOREIGN_${Date.now().toString(36)}`.slice(0, 32);

test.beforeAll(async () => {
    user = await StructuredImporterApi.create('nonadmin-csrf.txt', '.auth/nonadmin.json', XNAT_URL);
    admin = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);

    await admin.ensureProject(OWNED);
    await admin.ensureProject(FOREIGN);
    await admin.addProjectMember(OWNED, NON_ADMIN_USER, 'Owners');
});

test.afterAll(async () => {
    await admin.deleteProjectQuietly(OWNED);
    await admin.deleteProjectQuietly(FOREIGN);
    cleanupArchives();
    await user.dispose();
    await admin.dispose();
});

test('a project owner can import into their own project', async () => {
    const session = uniqueLabel('OwnerSess');

    const archive = await buildDirectoryArchive('owner-allowed', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'owner payload' } },
    ]);

    const res = await user.importArchiveRaw(archive, {
        project: OWNED,
        subject: uniqueLabel('OwnerSubj'),
        session,
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    const body = (await res.text()).trim();
    expect(
        res.ok(),
        `a project owner must be able to import into their own project: HTTP ${res.status()} ${body}`,
    ).toBeTruthy();

    // Asserting the permissive half matters as much as the refusal below. A
    // change that locked the importer down to site admins would make the
    // plugin useless to its actual audience, and only this test would notice.
    const created = (await admin.listExperiments(OWNED)).find(e => e.label === session);
    expect(created, 'the import reported success, so the session must exist').toBeDefined();
    await admin.deleteExperimentQuietly(created!.ID);
});

test('a project owner cannot import into a project they do not belong to', async () => {
    const archive = await buildDirectoryArchive('owner-refused', [
        { scanId: '1', modality: 'MR', resourceName: 'NIFTI', files: { 'a.nii': 'should not arrive' } },
    ]);

    const res = await user.importArchiveRaw(archive, {
        project: FOREIGN,
        subject: uniqueLabel('ForeignSubj'),
        session: uniqueLabel('ForeignSess'),
        'primary-modality': 'MR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok(), 'owning one project must not grant write access to another').toBeFalsy();
    expect(
        await admin.listExperiments(FOREIGN),
        'the refused import must leave the other project empty',
    ).toHaveLength(0);
});
