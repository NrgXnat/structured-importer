/**
 * REST helpers for structured importer test setup, assertions and teardown.
 *
 * Owns a standalone APIRequestContext rather than borrowing Playwright's
 * per-test fixture, so the same instance serves beforeAll, the test body and
 * afterAll. Call dispose() in afterAll.
 *
 * Methods come in pairs where a test may legitimately want either outcome: the
 * `...Raw` variant returns the APIResponse untouched so a validation spec can
 * assert on a refusal, and the plain variant asserts success for use in setup.
 */
import { APIRequestContext, APIResponse, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import { readCsrfToken } from './auth';

/** The importer handler name this plugin registers with XNAT's import service. */
export const IMPORT_HANDLER = 'Structured-Zip';

/**
 * Spring bean names of the resource identifier services. These are the values
 * the `resourceIdentifier` upload parameter accepts, and they are bean names,
 * so they are lower-camel. Passing the capitalized class name is not the same
 * string and resolves to no bean.
 */
export const RESOURCE_IDENTIFIER = {
    /** Walks <scanId>/<modality>/<resourceName>. */
    SIMPLE: 'simpleResourceIdentifierService',
    /** Reads a *.csv manifest at the archive root. */
    CSV: 'csvBasedResourceIdentifierService',
    /** Uses the manifest when one is present, otherwise walks the directories. */
    MANUAL: 'manualResourceIdentifierService',
} as const;

export interface ModalityMapping {
    modality: string;
    scan?: string;
    session?: string;
    group: number;
}

export interface ColumnMapping {
    column: string;
    property: string;
    required?: boolean;
    validation?: string;
}

export interface PropertyDisplayMapping {
    display: string;
    property: string;
}

export interface ImportParams {
    project?: string;
    subject?: string;
    session?: string;
    'primary-modality'?: string;
    resourceIdentifier?: string;
    toggleStructuredSessionLabeling?: string;
    [key: string]: string | undefined;
}

export class StructuredImporterApi {
    private constructor(
        private request: APIRequestContext,
        private csrfToken: string,
    ) {}

    static async create(csrfFile: string, storageStatePath: string, baseURL: string): Promise<StructuredImporterApi> {
        const ctx = await playwrightRequest.newContext({
            baseURL,
            storageState: storageStatePath,
            ignoreHTTPSErrors: true,
        });
        return new StructuredImporterApi(ctx, readCsrfToken(csrfFile));
    }

    async dispose(): Promise<void> {
        await this.request.dispose();
    }

    private headers(): Record<string, string> {
        return { 'XNAT-CSRF': this.csrfToken };
    }

    // ------------------------------------------------------------------ import

    /**
     * Posts an archive to XNAT's import service through the structured
     * importer handler. Returns the raw response, because roughly half the
     * suite is about what the importer refuses.
     *
     * A parameter set to undefined is omitted from the multipart body
     * entirely, which is how the "missing required parameter" specs express
     * themselves. Setting it to '' sends an empty value instead, which is a
     * different case.
     */
    async importArchiveRaw(archivePath: string, params: ImportParams): Promise<APIResponse> {
        const multipart: Record<string, string | { name: string; mimeType: string; buffer: Buffer }> = {
            'import-handler': IMPORT_HANDLER,
            image_archive: {
                name: archivePath.split('/').pop() ?? 'archive.zip',
                mimeType: 'application/zip',
                buffer: fs.readFileSync(archivePath),
            },
        };
        for (const [key, value] of Object.entries(params)) {
            if (value !== undefined) multipart[key] = value;
        }

        return this.request.post('/data/services/import', {
            headers: this.headers(),
            multipart,
            timeout: 120_000,
        });
    }

    /**
     * Imports and asserts success, returning the ID of the session that was
     * created. The import service answers with the new experiment's archive
     * URI, so the ID is the last path segment.
     */
    async importArchive(archivePath: string, params: ImportParams): Promise<string> {
        const res = await this.importArchiveRaw(archivePath, params);
        const body = (await res.text()).trim();
        expect(res.ok(), `Import failed: HTTP ${res.status()}\n${body}`).toBeTruthy();
        const id = body.split('/').filter(Boolean).pop();
        expect(id, `Import returned no experiment URI, got: ${body}`).toBeTruthy();
        return id as string;
    }

    // ------------------------------------------------- modality configuration

    async getModalities(): Promise<ModalityMapping[]> {
        const res = await this.request.get('/xapi/structured-importer/modalities');
        expect(res.ok(), `GET modalities failed: HTTP ${res.status()}`).toBeTruthy();
        return res.json();
    }

    // ------------------------------------------- site-wide CSV column mappings

    async getSiteColumnMappingsRaw(): Promise<APIResponse> {
        return this.request.get('/xapi/structured-importer/csv-column-mappings');
    }

    async getSiteColumnMappings(): Promise<ColumnMapping[]> {
        const res = await this.getSiteColumnMappingsRaw();
        expect(res.ok(), `GET site column mappings failed: HTTP ${res.status()}`).toBeTruthy();
        return parseColumnMappings(await res.json());
    }

    async setSiteColumnMappingsRaw(mappings: ColumnMapping[]): Promise<APIResponse> {
        return this.request.post('/xapi/structured-importer/csv-column-mappings', {
            headers: { ...this.headers(), 'Content-Type': 'application/json' },
            data: { columnMappings: JSON.stringify(mappings) },
        });
    }

    async setSiteColumnMappings(mappings: ColumnMapping[]): Promise<void> {
        const res = await this.setSiteColumnMappingsRaw(mappings);
        expect(res.ok(), `POST site column mappings failed: HTTP ${res.status()} ${await res.text()}`).toBeTruthy();
    }

    // ---------------------------------------- project CSV column mappings

    async getProjectColumnMappingsRaw(projectId: string): Promise<APIResponse> {
        return this.request.get(`/xapi/structured-importer/projects/${encodeURIComponent(projectId)}/csv-column-mappings`);
    }

    async setProjectColumnMappingsRaw(projectId: string, mappings: ColumnMapping[]): Promise<APIResponse> {
        return this.request.post(`/xapi/structured-importer/projects/${encodeURIComponent(projectId)}/csv-column-mappings`, {
            headers: { ...this.headers(), 'Content-Type': 'application/json' },
            data: { columnMappings: JSON.stringify(mappings) },
        });
    }

    async setProjectColumnMappings(projectId: string, mappings: ColumnMapping[]): Promise<void> {
        const res = await this.setProjectColumnMappingsRaw(projectId, mappings);
        expect(res.ok(), `POST project column mappings failed: HTTP ${res.status()} ${await res.text()}`).toBeTruthy();
    }

    async disableProjectColumnMappingsRaw(projectId: string): Promise<APIResponse> {
        return this.request.post(
            `/xapi/structured-importer/projects/${encodeURIComponent(projectId)}/csv-column-mappings/disable`,
            { headers: this.headers() },
        );
    }

    async deleteProjectColumnMappingsRaw(projectId: string): Promise<APIResponse> {
        return this.request.delete(
            `/xapi/structured-importer/projects/${encodeURIComponent(projectId)}/csv-column-mappings`,
            { headers: this.headers() },
        );
    }

    /** Removes any project override. A project that has none is not an error. */
    async clearProjectColumnMappings(projectId: string): Promise<void> {
        const res = await this.deleteProjectColumnMappingsRaw(projectId);
        if (res.status() !== 404) {
            expect(res.ok(), `DELETE project column mappings failed: HTTP ${res.status()}`).toBeTruthy();
        }
    }

    // --------------------------------------------- property display mappings

    async getPropertyDisplayMappingsRaw(): Promise<APIResponse> {
        return this.request.get('/xapi/structured-importer/property-display-mappings');
    }

    async getPropertyDisplayMappings(): Promise<PropertyDisplayMapping[]> {
        const res = await this.getPropertyDisplayMappingsRaw();
        expect(res.ok(), `GET property display mappings failed: HTTP ${res.status()}`).toBeTruthy();
        return res.json();
    }

    async savePropertyDisplayMappingRaw(mapping: PropertyDisplayMapping, replaces?: string): Promise<APIResponse> {
        return this.request.post('/xapi/structured-importer/property-display-mappings', {
            headers: { ...this.headers(), 'Content-Type': 'application/json' },
            params: replaces ? { replaces } : {},
            data: mapping,
        });
    }

    async deletePropertyDisplayMappingRaw(display: string): Promise<APIResponse> {
        return this.request.delete(
            `/xapi/structured-importer/property-display-mappings/${encodeURIComponent(display)}`,
            { headers: this.headers() },
        );
    }

    // ------------------------------------------------------------ generic XNAT

    async getExperiment(experimentId: string): Promise<any> {
        const res = await this.request.get(`/data/experiments/${encodeURIComponent(experimentId)}?format=json`);
        expect(res.ok(), `GET experiment ${experimentId} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).items[0];
    }

    /** The xsi:type of a session, e.g. xnat:mrSessionData. */
    async getExperimentDataType(experimentId: string): Promise<string> {
        return (await this.getExperiment(experimentId)).meta['xsi:type'];
    }

    async getScans(experimentId: string): Promise<any[]> {
        const res = await this.request.get(`/data/experiments/${encodeURIComponent(experimentId)}/scans?format=json`);
        expect(res.ok(), `GET scans for ${experimentId} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).ResultSet.Result;
    }

    async getScan(experimentId: string, scanId: string): Promise<any> {
        const res = await this.request.get(
            `/data/experiments/${encodeURIComponent(experimentId)}/scans/${encodeURIComponent(scanId)}?format=json`,
        );
        expect(res.ok(), `GET scan ${scanId} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).items[0].data_fields;
    }

    async getScanResources(experimentId: string, scanId: string): Promise<any[]> {
        const res = await this.request.get(
            `/data/experiments/${encodeURIComponent(experimentId)}/scans/${encodeURIComponent(scanId)}/resources?format=json`,
        );
        expect(res.ok(), `GET resources for scan ${scanId} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).ResultSet.Result;
    }

    async getScanResourceFiles(experimentId: string, scanId: string, resource: string): Promise<any[]> {
        const res = await this.request.get(
            `/data/experiments/${encodeURIComponent(experimentId)}/scans/${encodeURIComponent(scanId)}` +
                `/resources/${encodeURIComponent(resource)}/files?format=json`,
        );
        expect(res.ok(), `GET files for ${resource} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).ResultSet.Result;
    }

    /** Sessions in a project, as {ID, label} rows. */
    async listExperiments(projectId: string): Promise<Array<{ ID: string; label: string }>> {
        const res = await this.request.get(`/data/projects/${encodeURIComponent(projectId)}/experiments?format=json`);
        expect(res.ok(), `GET experiments for ${projectId} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).ResultSet.Result;
    }

    async getSubject(projectId: string, subjectLabel: string): Promise<any> {
        const res = await this.request.get(
            `/data/projects/${encodeURIComponent(projectId)}/subjects/${encodeURIComponent(subjectLabel)}?format=json`,
        );
        expect(res.ok(), `GET subject ${subjectLabel} failed: HTTP ${res.status()}`).toBeTruthy();
        return (await res.json()).items[0];
    }

    async ensureProject(projectId: string): Promise<void> {
        const existing = await this.request.get(`/data/projects/${encodeURIComponent(projectId)}?format=json`);
        if (existing.ok()) return;
        const res = await this.request.put(`/data/projects/${encodeURIComponent(projectId)}`, {
            headers: this.headers(),
            params: { event_action: 'Added Project' },
        });
        expect(res.ok(), `Create project ${projectId} failed: HTTP ${res.status()} ${await res.text()}`).toBeTruthy();
    }

    /**
     * Adds a user to a project group. `group` is XNAT's group label, so
     * "Owners", "Members" or "Collaborators".
     */
    async addProjectMember(projectId: string, username: string, group: string): Promise<void> {
        const res = await this.request.put(
            `/data/projects/${encodeURIComponent(projectId)}/users/${encodeURIComponent(group)}/${encodeURIComponent(username)}`,
            { headers: this.headers() },
        );
        expect(
            res.ok(),
            `Add ${username} to ${group} of ${projectId} failed: HTTP ${res.status()} ${await res.text()}`,
        ).toBeTruthy();
    }

    /**
     * Teardown-safe project delete. A project can legitimately refuse to go
     * away when something inside it is in a state XNAT will not remove, and
     * that must not turn a passing test red. The status is reported so a
     * genuine leak is still visible in the output.
     */
    async deleteProjectQuietly(projectId: string): Promise<void> {
        try {
            const res = await this.request.delete(`/data/projects/${encodeURIComponent(projectId)}`, {
                headers: this.headers(),
                params: { removeFiles: 'true', event_reason: 'structured importer e2e cleanup' },
            });
            if (!res.ok() && res.status() !== 404) {
                console.warn(`[cleanup] could not delete project ${projectId}: HTTP ${res.status()}`);
            }
        } catch (e) {
            console.warn(`[cleanup] error deleting project ${projectId}: ${String(e)}`);
        }
    }

    /**
     * Teardown-safe delete. A cleanup failure must not fail the test that just
     * passed, and the ids collected during a run can contain duplicates or
     * experiments a later test already removed. The status is reported so a
     * genuine leak is still visible in the output.
     */
    async deleteExperimentQuietly(experimentId: string): Promise<void> {
        try {
            const res = await this.request.delete(`/data/experiments/${encodeURIComponent(experimentId)}`, {
                headers: this.headers(),
                params: { removeFiles: 'true' },
            });
            if (!res.ok() && res.status() !== 404) {
                console.warn(`[cleanup] could not delete experiment ${experimentId}: HTTP ${res.status()}`);
            }
        } catch (e) {
            console.warn(`[cleanup] error deleting experiment ${experimentId}: ${String(e)}`);
        }
    }

}

/**
 * The column-mapping endpoints wrap the mappings in a config object whose
 * `columnMappings` field is itself a JSON string rather than an array, so it
 * has to be parsed a second time.
 */
export function parseColumnMappings(config: any): ColumnMapping[] {
    if (!config || config.columnMappings == null) return [];
    return typeof config.columnMappings === 'string'
        ? JSON.parse(config.columnMappings)
        : config.columnMappings;
}
