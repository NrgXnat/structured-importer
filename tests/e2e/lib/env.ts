/**
 * Environment-derived settings shared by every spec.
 *
 * The suite creates and deletes its own project by default so it never touches
 * data it did not make. Point TEST_PROJECT at an existing project only when
 * the account under test cannot create projects; in that case the suite
 * deletes the sessions it created but leaves the project alone.
 */
export const XNAT_URL = process.env.XNAT_URL || 'http://localhost';

export const ADMIN_USER = process.env.ADMIN_USER || 'admin';

/** Empty when the instance has no second account; the permissions project then skips. */
export const NON_ADMIN_USER = process.env.NON_ADMIN_USER || '';

/**
 * When set, the suite uses this project and does not delete it. When unset,
 * each spec file creates a uniquely named project and removes it afterwards.
 */
export const TEST_PROJECT = process.env.TEST_PROJECT || '';

/** True when the suite owns (and must clean up) the project it uses. */
export const OWNS_PROJECT = TEST_PROJECT === '';

/** A project name unique to this run, so two runs never collide. */
export function projectNameFor(suite: string): string {
    if (TEST_PROJECT) return TEST_PROJECT;
    const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;
    return `SI_E2E_${suite}_${stamp}`.slice(0, 32);
}

/** A label unique within a run. */
export function uniqueLabel(prefix: string): string {
    return `${prefix}_${Date.now().toString(36)}${Math.floor(Math.random() * 1e3)}`;
}
