/**
 * Property display mappings.
 *
 * These populate the Property drop-down in the column-mapping editors, and
 * they are site-wide: the API documents that an addition made from a project's
 * settings page becomes available to every project. That scope is the thing
 * worth testing, because the UI presents the control inside a project and a
 * reasonable user would assume the change is local to it.
 *
 * The set is keyed by PROPERTY, not by display value: a second mapping onto a
 * property that already has one is refused. That is the rule that makes the
 * drop-down unambiguous, and it means each test here has to use a property of
 * its own rather than sharing one.
 *
 * Editing is documented as requiring site administrator privileges while the
 * endpoints themselves are open to any authenticated user, so the gate lives
 * inside the handler rather than in the request mapping. The non-admin half of
 * that check is in tests/permissions.
 */
import { test, expect } from '../../lib/fixtures';
import { StructuredImporterApi, PropertyDisplayMapping } from '../../lib/api';
import { XNAT_URL } from '../../lib/env';
import { hasConfigApi, pluginVersion } from '../../lib/capabilities';

test.skip(
    () => !hasConfigApi(),
    `the configuration API is not present on ${pluginVersion()}; it is on develop and has not reached main`,
);

/**
 * Display values this file may create. Everything here is removed in afterAll
 * whether or not the test that made it finished.
 */
const DISPOSABLE = [
    'E2E Added Property',
    'E2E Rename Before',
    'E2E Rename After',
    'E2E Delete Me',
    'E2E Duplicate Owner',
    'E2E Duplicate Property',
    'E2E Blank Property',
];

let api: StructuredImporterApi;
let original: PropertyDisplayMapping[];

test.beforeAll(async () => {
    api = await StructuredImporterApi.create('admin-csrf.txt', '.auth/admin.json', XNAT_URL);
    original = await api.getPropertyDisplayMappings();
});

test.afterAll(async () => {
    for (const display of DISPOSABLE) {
        await api.deletePropertyDisplayMappingRaw(display);
    }
    const restored = await api.getPropertyDisplayMappings();
    expect(
        restored.map(m => m.display).sort(),
        'the suite must leave the property display mappings as it found them',
    ).toEqual(original.map(m => m.display).sort());
    await api.dispose();
});

/** A property no existing mapping uses, so a save is not refused as a duplicate. */
function freeProperty(suffix: string): string {
    return `xnat:imageScanData/e2e_${suffix}`;
}

test('the instance ships a non-empty set of property display mappings', async () => {
    expect(original.length, 'an empty set would leave the editor drop-down unusable').toBeGreaterThan(0);
    for (const mapping of original) {
        expect(mapping.display, 'every mapping needs a display value to show in the drop-down').toBeTruthy();
        expect(mapping.property, `mapping "${mapping.display}" has no property behind it`).toBeTruthy();
    }
});

test('each property backs exactly one display value in the shipped set', async () => {
    // The invariant the duplicate check below exists to protect. If the
    // shipped configuration already broke it, the drop-down would offer two
    // labels for the same property and the editor could not round-trip.
    const properties = original.map(m => m.property);
    expect(properties.length, 'a property must appear exactly once').toBe(new Set(properties).size);
});

test('an added mapping appears in the list', async () => {
    const mapping: PropertyDisplayMapping = { display: 'E2E Added Property', property: freeProperty('added') };

    const res = await api.savePropertyDisplayMappingRaw(mapping);
    expect(res.ok(), `save failed: HTTP ${res.status()} ${await res.text()}`).toBeTruthy();

    const added = (await api.getPropertyDisplayMappings()).find(m => m.display === mapping.display);
    expect(added, 'a saved mapping must appear in the list').toBeDefined();
    expect(added?.property).toBe(mapping.property);
});

test('a second display value onto an already-mapped property is refused', async () => {
    const property = freeProperty('duplicate');
    const first = await api.savePropertyDisplayMappingRaw({ display: 'E2E Duplicate Owner', property });
    expect(first.ok(), `setup save failed: HTTP ${first.status()} ${await first.text()}`).toBeTruthy();

    const second = await api.savePropertyDisplayMappingRaw({ display: 'E2E Duplicate Property', property });
    expect(second.ok(), 'two display values for one property would make the drop-down ambiguous').toBeFalsy();

    const displays = (await api.getPropertyDisplayMappings()).map(m => m.display);
    expect(displays, 'the refused mapping must not have been stored anyway').not.toContain('E2E Duplicate Property');
});

test('the replaces parameter renames a mapping in place instead of adding a second one', async () => {
    const property = freeProperty('rename');
    const before = await api.savePropertyDisplayMappingRaw({ display: 'E2E Rename Before', property });
    expect(before.ok(), `setup save failed: HTTP ${before.status()}`).toBeTruthy();

    const countBefore = (await api.getPropertyDisplayMappings()).length;

    const res = await api.savePropertyDisplayMappingRaw(
        { display: 'E2E Rename After', property },
        'E2E Rename Before',
    );
    expect(res.ok(), `rename failed: HTTP ${res.status()} ${await res.text()}`).toBeTruthy();

    const after = await api.getPropertyDisplayMappings();
    expect(after.map(m => m.display), 'the old display value must be gone').not.toContain('E2E Rename Before');
    expect(after.map(m => m.display)).toContain('E2E Rename After');
    expect(after.length, 'an edit must not leave the original behind as a duplicate').toBe(countBefore);
});

test('a deleted mapping is gone from the list', async () => {
    const mapping: PropertyDisplayMapping = { display: 'E2E Delete Me', property: freeProperty('delete') };
    const save = await api.savePropertyDisplayMappingRaw(mapping);
    expect(save.ok(), `setup save failed: HTTP ${save.status()} ${await save.text()}`).toBeTruthy();

    const res = await api.deletePropertyDisplayMappingRaw(mapping.display);
    expect(res.ok(), `delete failed: HTTP ${res.status()}`).toBeTruthy();

    expect((await api.getPropertyDisplayMappings()).map(m => m.display)).not.toContain(mapping.display);
});

test('deleting a display value that does not exist reports not found rather than succeeding silently', async () => {
    const res = await api.deletePropertyDisplayMappingRaw('E2E Never Existed');
    expect(res.status(), 'a no-op delete reported as success hides a typo from the caller').toBe(404);
});

test('a mapping with a blank property is refused', async () => {
    // A blank property would render in the drop-down and then map a column to
    // nothing, which fails at import time rather than at configuration time.
    const res = await api.savePropertyDisplayMappingRaw({ display: 'E2E Blank Property', property: '' });
    expect(res.ok(), 'a mapping with no property behind it should not be accepted').toBeFalsy();
});
