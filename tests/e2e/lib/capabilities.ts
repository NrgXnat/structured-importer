/**
 * What the instance under test can actually do, as measured by global setup.
 *
 * The configuration REST API is on `develop` and has not reached `main`. A
 * spec that needs it consults hasConfigApi() and skips when it is absent,
 * rather than failing in a way that looks like a product bug. Global setup has
 * already decided whether an absent API is acceptable at all; by the time a
 * spec asks, the answer is simply whether the endpoints are there.
 */
import * as fs from 'fs';
import * as path from 'path';

interface Capabilities {
    version: string;
    configApi: boolean;
}

let cached: Capabilities | undefined;

function read(): Capabilities {
    if (cached) return cached;
    const file = path.resolve(__dirname, '..', '.auth', 'capabilities.json');
    try {
        cached = JSON.parse(fs.readFileSync(file, 'utf-8')) as Capabilities;
    } catch {
        // Running a spec directly without setup. Assume the full feature set
        // so the spec fails on the real assertion rather than on a skip.
        cached = { version: 'unknown', configApi: true };
    }
    return cached;
}

export function hasConfigApi(): boolean {
    return read().configApi;
}

export function pluginVersion(): string {
    return read().version;
}

export interface ModalityMappingRecord {
    modality: string;
    scan?: string;
    session?: string;
    group: number;
}

/**
 * The modality configuration recorded by global setup, read synchronously so
 * the matrix spec can generate one test per modality at collection time.
 *
 * Returns an empty list when setup has not run, which makes the matrix spec
 * collect nothing rather than fail confusingly. The suite always runs setup
 * first through the project dependency, so an empty list in a real run means
 * the configuration API was absent.
 */
export function recordedModalities(): ModalityMappingRecord[] {
    const file = path.resolve(__dirname, '..', '.auth', 'modalities.json');
    try {
        return JSON.parse(fs.readFileSync(file, 'utf-8')) as ModalityMappingRecord[];
    } catch {
        return [];
    }
}
