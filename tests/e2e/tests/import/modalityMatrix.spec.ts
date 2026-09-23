/**
 * Every configured modality, imported for real.
 *
 * The modality drop-down is the largest set of choices a user is offered
 * anywhere in this plugin, and an earlier version of this suite exercised
 * three of them. Three out of thirty-nine is a sample, not coverage, and the
 * defects already found in this area were in modalities nobody had tried.
 *
 * This file generates ONE NAMED TEST PER MODALITY, so a failure says
 * "modality NM" rather than "the modality loop failed on iteration 14". The
 * list is read from a file global setup writes, because Playwright collects
 * tests synchronously and cannot await an HTTP call to build them.
 *
 * Two shapes are covered, because the two halves of a mapping are used
 * independently:
 *
 *   - Both halves: session of modality M holding a scan of modality M. This is
 *     the ordinary case and is what the drop-down promises.
 *   - Scan-only modalities: they have no session type, so they are tested as a
 *     scan inside an MR session, which is the only way they can be used.
 *
 * Session-only modalities are covered in modalityDataType.spec.ts as a known
 * defect and are not repeated here.
 *
 * Every test asserts the data types the API ADVERTISED, so the suite checks
 * the plugin against its own published configuration rather than against a
 * list we copied.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';
import { recordedModalities, hasConfigApi, pluginVersion } from '../../lib/capabilities';

const PROJECT = projectNameFor('matrix');
const MODALITIES = recordedModalities();

const BOTH_HALVES = MODALITIES.filter(m => m.scan && m.session);
const SCAN_ONLY = MODALITIES.filter(m => m.scan && !m.session);

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; the matrix needs it to know the modalities`,
);

let api: StructuredImporterApi;
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test.describe('session and scan data types, per modality', () => {
    for (const mapping of BOTH_HALVES) {
        test(`${mapping.modality} produces ${mapping.session} holding ${mapping.scan}`, async () => {
            const archive = await buildDirectoryArchive(`matrix-${mapping.modality}`, [
                {
                    scanId: '1',
                    modality: mapping.modality,
                    resourceName: 'DATA',
                    files: { 'file.dat': `payload for ${mapping.modality}` },
                },
            ]);

            const res = await api.importArchiveRaw(archive, {
                project: PROJECT,
                subject: uniqueLabel(`M${mapping.modality}S`),
                session: uniqueLabel(`M${mapping.modality}E`),
                'primary-modality': mapping.modality,
                resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
            });

            const body = (await res.text()).trim();
            expect(
                res.ok(),
                `importing modality ${mapping.modality}, which the API advertises as importable, failed: ${body}`,
            ).toBeTruthy();

            const id = body.split('/').filter(Boolean).pop() as string;
            created.push(id);

            expect(await api.getExperimentDataType(id), 'session data type').toBe(mapping.session);

            const scans = await api.getScans(id);
            expect(scans, `modality ${mapping.modality} produced no scans`).toHaveLength(1);
            expect(scans[0].xsiType, 'scan data type').toBe(mapping.scan);

            // A session with a scan and no files is the failure that looks
            // like success, so the matrix checks the payload too.
            const resources = await api.getScanResources(id, scans[0].ID);
            expect(resources.map(r => r.label)).toEqual(['DATA']);
            const files = await api.getScanResourceFiles(id, scans[0].ID, 'DATA');
            expect(Number(files[0]?.Size ?? 0)).toBe(`payload for ${mapping.modality}`.length);
        });
    }
});

test.describe('scan-only modalities, used inside a session of another modality', () => {
    for (const mapping of SCAN_ONLY) {
        test(`${mapping.modality} works as a scan inside an MR session`, async () => {
            // These have no session data type, so they are correctly kept out
            // of the primary-modality drop-down. They should still be usable
            // as a scan directory, which is the only route left for them, and
            // nothing else proves that in either direction.
            const archive = await buildDirectoryArchive(`matrix-scanonly-${mapping.modality}`, [
                { scanId: '1', modality: 'MR', resourceName: 'DATA', files: { 'mr.dat': 'mr' } },
                { scanId: '2', modality: mapping.modality, resourceName: 'DATA', files: { 'other.dat': 'other' } },
            ]);

            const res = await api.importArchiveRaw(archive, {
                project: PROJECT,
                subject: uniqueLabel(`SO${mapping.modality}S`),
                session: uniqueLabel(`SO${mapping.modality}E`),
                'primary-modality': 'MR',
                resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
            });

            const body = (await res.text()).trim();
            expect(
                res.ok(),
                `a ${mapping.modality} scan directory inside an MR session was refused: ${body}`,
            ).toBeTruthy();

            const id = body.split('/').filter(Boolean).pop() as string;
            created.push(id);

            const scans = await api.getScans(id);
            expect(scans, 'both the MR scan and the scan-only-modality scan should exist').toHaveLength(2);
            const byId = new Map(scans.map(s => [s.ID, s.xsiType]));
            expect(byId.get('2'), `scan 2 should take modality ${mapping.modality}'s scan type`).toBe(mapping.scan);
        });
    }
});

test('the matrix actually generated tests', () => {
    // Without this, a missing or empty modalities file would make every test
    // above vanish and the file would report a cheerful zero failures.
    expect(
        BOTH_HALVES.length,
        'no modalities were recorded, so the matrix above collected nothing',
    ).toBeGreaterThan(0);
});
