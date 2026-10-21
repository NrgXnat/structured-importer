/**
 * Builders for the upload archives the structured importer consumes.
 *
 * Two archive shapes matter, because they select different resource
 * identifier services:
 *
 *   - Directory shape, read by SimpleResourceIdentifierService:
 *         <scanId>/<modality>/<resourceName>/...
 *     Metadata comes from the folder names alone.
 *
 *   - Manifest shape, read by CsvBasedResourceIdentifierService: a *.csv at
 *     the archive root whose columns are mapped to XNAT properties by the site
 *     or project column-mapping configuration, plus a Path column pointing at
 *     the files for each row.
 *
 * Archives are written to a per-run temp directory and removed by
 * cleanupArchives() so a failed run leaves nothing behind in the repo.
 */
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import archiver from 'archiver';

const TMP_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'struct-import-e2e-'));

/**
 * Every spec file calls cleanupArchives() in its afterAll, and with a single
 * worker they all share this module instance, so the root is removed while
 * later spec files still need it. Recreating it on demand keeps each file
 * independent of the order they run in.
 */
function ensureRoot(): void {
    fs.mkdirSync(TMP_ROOT, { recursive: true });
}

/** One scan's worth of files in a directory-shaped archive. */
export interface DirectoryScan {
    scanId: string;
    modality: string;
    resourceName: string;
    /** File name to contents. Contents are arbitrary; the importer does not parse them. */
    files: Record<string, string>;
}

/** One row of a CSV manifest, plus the files the row's Path column points at. */
export interface ManifestRow {
    /** Column name to cell value, using the configured column headers verbatim. */
    columns: Record<string, string>;
    /** File name to contents, written under the row's Path directory. */
    files: Record<string, string>;
}

function uniqueDir(prefix: string): string {
    ensureRoot();
    return fs.mkdtempSync(path.join(TMP_ROOT, `${prefix}-`));
}

async function zipDirectory(sourceDir: string, zipPath: string): Promise<string> {
    await new Promise<void>((resolve, reject) => {
        const output = fs.createWriteStream(zipPath);
        const archive = archiver('zip', { zlib: { level: 9 } });
        output.on('close', () => resolve());
        output.on('error', reject);
        archive.on('error', reject);
        archive.pipe(output);
        archive.directory(sourceDir, false);
        void archive.finalize();
    });
    return zipPath;
}

/** Builds a directory-shaped archive. Returns the path to the zip. */
export async function buildDirectoryArchive(name: string, scans: DirectoryScan[]): Promise<string> {
    const root = uniqueDir(name);
    for (const scan of scans) {
        const dir = path.join(root, scan.scanId, scan.modality, scan.resourceName);
        fs.mkdirSync(dir, { recursive: true });
        for (const [fileName, contents] of Object.entries(scan.files)) {
            fs.writeFileSync(path.join(dir, fileName), contents, 'utf-8');
        }
    }
    return zipDirectory(root, path.join(TMP_ROOT, `${name}-${Date.now()}.zip`));
}

/**
 * Builds a manifest-shaped archive.
 *
 * `columnOrder` fixes the header order so a test can assert on behavior that
 * depends on it. Every row must supply a Path value; the row's files are
 * written under that path.
 */
export async function buildManifestArchive(
    name: string,
    columnOrder: string[],
    rows: ManifestRow[],
    options: { manifestName?: string } = {},
): Promise<string> {
    const root = uniqueDir(name);
    const manifestName = options.manifestName ?? 'manifest.csv';

    const lines = [columnOrder.join(',')];
    for (const row of rows) {
        lines.push(columnOrder.map(c => csvCell(row.columns[c] ?? '')).join(','));
        const rowPath = row.columns['Path'];
        if (rowPath) {
            const dir = path.join(root, rowPath);
            fs.mkdirSync(dir, { recursive: true });
            for (const [fileName, contents] of Object.entries(row.files)) {
                fs.writeFileSync(path.join(dir, fileName), contents, 'utf-8');
            }
        }
    }
    fs.writeFileSync(path.join(root, manifestName), lines.join('\n') + '\n', 'utf-8');

    return zipDirectory(root, path.join(TMP_ROOT, `${name}-${Date.now()}.zip`));
}

/**
 * Builds an archive from an explicit map of archive-relative path to contents,
 * for shapes the two helpers above deliberately cannot express (an empty
 * archive, files at the root, a manifest with no data rows).
 */
export async function buildRawArchive(name: string, entries: Record<string, string>): Promise<string> {
    const root = uniqueDir(name);
    for (const [relPath, contents] of Object.entries(entries)) {
        const full = path.join(root, relPath);
        fs.mkdirSync(path.dirname(full), { recursive: true });
        fs.writeFileSync(full, contents, 'utf-8');
    }
    return zipDirectory(root, path.join(TMP_ROOT, `${name}-${Date.now()}.zip`));
}

/** Writes a file that is not an archive at all, for the unsupported-format path. */
export function buildNonArchiveFile(name: string, contents = 'this is not an archive'): string {
    ensureRoot();
    const file = path.join(TMP_ROOT, `${name}-${Date.now()}.txt`);
    fs.writeFileSync(file, contents, 'utf-8');
    return file;
}

export function cleanupArchives(): void {
    fs.rmSync(TMP_ROOT, { recursive: true, force: true });
}

function csvCell(value: string): string {
    return /[",\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}
