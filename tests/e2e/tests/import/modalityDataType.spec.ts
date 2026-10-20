/**
 * Modality to data-type resolution, end to end.
 *
 * The importer no longer hard-codes a short list of supported modalities. It
 * resolves the session and scan data types from a YAML configuration exposed
 * at /xapi/structured-importer/modalities, merging built-in defaults with any
 * classpath overrides.
 *
 * YamlBasedModalityDataTypeServiceTest covers the merge. These tests cover the
 * consequence: that the data type the endpoint advertises is the data type an
 * import actually produces. A configuration that advertises a mapping the
 * importer does not honor is worse than no configuration, because the
 * administrator has no way to see the difference.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, RESOURCE_IDENTIFIER, ModalityMapping } from '../../lib/api';
import { buildDirectoryArchive, cleanupArchives } from '../../lib/archive';
import { XNAT_URL, projectNameFor, uniqueLabel, OWNS_PROJECT } from '../../lib/env';

const PROJECT = projectNameFor('mod');

let api: StructuredImporterApi;
let modalities: ModalityMapping[];
const created: string[] = [];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    await api.ensureProject(PROJECT);
    modalities = await api.getModalities();
});

test.afterAll(async () => {
    for (const id of new Set(created)) await api.deleteExperimentQuietly(id);
    if (OWNS_PROJECT) await api.deleteProjectQuietly(PROJECT);
    cleanupArchives();
    await api.dispose();
});

test('a modality that is not in the configuration is refused', async () => {
    const archive = await buildDirectoryArchive('mod-unknown', [
        { scanId: '1', modality: 'MR', resourceName: 'DATA', files: { 'a.dat': 'a' } },
    ]);

    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel('ModSubjUnknown'),
        session: uniqueLabel('ModSessUnknown'),
        'primary-modality': 'NOT_A_REAL_MODALITY',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });

    expect(res.ok(), 'an unconfigured modality must not create a session').toBeFalsy();
    expect(await res.text(), 'the refusal must name the modality it did not recognize').toContain(
        'NOT_A_REAL_MODALITY',
    );
});

/** Imports a directory archive expected to be refused, and returns the response and any session it left. */
async function importExpectingRefusal(name: string, scanModality: string, primaryModality: string) {
    const session = uniqueLabel(`Refused${name}`);
    const archive = await buildDirectoryArchive(`mod-${name}`, [
        { scanId: '1', modality: scanModality, resourceName: 'DATA', files: { 'a.dat': 'a' } },
    ]);
    const res = await api.importArchiveRaw(archive, {
        project: PROJECT,
        subject: uniqueLabel(`Refused${name}Subj`),
        session,
        'primary-modality': primaryModality,
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    const body = (await res.text())
        .replace(/<[^>]*>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    const leftBehind = (await api.listExperiments(PROJECT)).find(e => e.label === session);
    if (leftBehind) created.push(leftBehind.ID);
    return { res, body, leftBehind };
}

test('a scan directory with an unconfigured modality is refused and leaves no session behind', async () => {
    const { res, body, leftBehind } = await importExpectingRefusal(
        'ScanUnknown',
        'NOT_A_REAL_MODALITY',
        'MR',
    );

    expect(res.status(), `the archive should be refused as a client error: ${body.slice(0, 200)}`).toBe(400);
    expect(body, 'the refusal must name the modality it did not recognize').toContain('NOT_A_REAL_MODALITY');
    expect(leftBehind, 'a refused import must not leave an empty session in the project').toBeUndefined();
});

test('the modality configuration is ordered by group and is free of duplicate modalities', async () => {
    // The endpoint documents its ordering because the UI renders the drop-down
    // straight from it. A duplicate modality would make the drop-down
    // ambiguous and the resolution order undefined.
    const groups = modalities.map(m => m.group);
    expect(groups, 'modalities must arrive grouped, most common first').toEqual(
        [...groups].sort((a, b) => a - b),
    );

    const codes = modalities.map(m => m.modality);
    expect(codes, 'a modality must appear exactly once').toHaveLength(new Set(codes).size);
});

test('every advertised mapping names at least one data type, and names it plausibly', async () => {
    // The two halves are used independently: `session` resolves the session
    // type from the primary-modality upload parameter, `scan` resolves the
    // scan type from the modality directory inside the archive. A mapping with
    // neither would be inert.
    for (const mapping of modalities) {
        expect(
            mapping.scan || mapping.session,
            `modality ${mapping.modality} names neither a scan nor a session data type`,
        ).toBeTruthy();
        if (mapping.scan) expect(mapping.scan).toMatch(/ScanData$/);
        if (mapping.session) expect(mapping.session).toMatch(/SessionData$/);
    }
});

test('a PETMR session holds its PET and MR scans as separate scans', async () => {
    // PETMR is a session type with no scan type of its own: a PETMR session is
    // made of PET scans and MR scans. So the archive carries PET and MR scan
    // directories, and primary-modality PETMR decides the session type.
    const petmr = modalities.find(m => m.modality === 'PETMR');
    expect(petmr, 'PETMR is part of the default modality configuration').toBeDefined();
    expect(petmr!.scan, 'PETMR is a session type only, with no scan type').toBeUndefined();
    const pet = modalities.find(m => m.modality === 'PET')!;
    const mr = modalities.find(m => m.modality === 'MR')!;

    const archive = await buildDirectoryArchive('mod-petmr', [
        { scanId: '1', modality: 'PET', resourceName: 'DATA', files: { 'pet.dat': 'pet' } },
        { scanId: '2', modality: 'MR', resourceName: 'DATA', files: { 'mr.dat': 'mr' } },
    ]);
    const id = await api.importArchive(archive, {
        project: PROJECT,
        subject: uniqueLabel('PetmrSubj'),
        session: uniqueLabel('PetmrSess'),
        'primary-modality': 'PETMR',
        resourceIdentifier: RESOURCE_IDENTIFIER.SIMPLE,
    });
    created.push(id);

    expect(await api.getExperimentDataType(id)).toBe(petmr!.session);
    const scans = await api.getScans(id);
    expect(
        scans.map(s => [s.ID, s.xsiType]).sort(),
        'each scan keeps the data type of its own modality',
    ).toEqual([
        ['1', pet.scan],
        ['2', mr.scan],
    ]);
});

test('a scan directory labeled with a session-only modality is refused, says why, and leaves no session behind', async () => {
    // A session-only modality such as PETMR names a kind of session, not a
    // kind of scan, so a scan directory labeled with it cannot be imported.
    // The importer should say so plainly and leave the project as it was.
    // Derived from the live configuration so it covers every such modality.
    const sessionOnly = modalities.filter(m => m.session && !m.scan);
    expect(
        sessionOnly.length,
        'the default configuration has at least one session-only modality',
    ).toBeGreaterThan(0);

    for (const mapping of sessionOnly) {
        const { res, body, leftBehind } = await importExpectingRefusal(
            `SessionOnly${mapping.modality}`,
            mapping.modality,
            mapping.modality,
        );

        expect
            .soft(
                res.status(),
                `${mapping.modality}: should be refused as a client error: ${body.slice(0, 200)}`,
            )
            .toBe(400);
        expect
            .soft(body, `${mapping.modality}: the refusal must say the modality has no scan type`)
            .toMatch(new RegExp(`${mapping.modality}.*no scan data type`, 'i'));
        expect
            .soft(leftBehind, `${mapping.modality}: a refused import must not leave an empty session`)
            .toBeUndefined();
    }
});
