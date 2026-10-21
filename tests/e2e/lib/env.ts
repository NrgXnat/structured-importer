/**
 * Environment-derived settings shared by every spec.
 *
 * Each spec creates and deletes its own project by default, so the suite never
 * touches data it did not make. Set TEST_PROJECT to reuse an existing project
 * when the account cannot create them.
 */
export const XNAT_URL = process.env.XNAT_URL || 'http://localhost';

export const ADMIN_USER = process.env.ADMIN_USER || 'admin';

/** Empty when the instance has no second account; the permissions project then skips. */
export const NON_ADMIN_USER = process.env.NON_ADMIN_USER || '';

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
