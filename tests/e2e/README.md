# Structured importer end-to-end tests

Playwright coverage for the structured importer: importing directory- and
manifest-shaped archives, the modality-to-data-type configuration, the CSV
column-mapping and property-display-mapping APIs, the refusal paths, access
control, and the compressed uploader page including real uploads through the
form.

## Requirements

- Node 18+
- An XNAT instance running a structured importer build **from the `develop`
  branch**. Global setup checks this and fails the run if the configuration
  API is missing.
- A site admin account on that instance.
- Optionally a second, non-site-admin account (see the table below).

> The repository's default branch is `main`, and `main` does not contain the
> configuration REST API these tests exercise. Deploying the default branch
> produces a plugin this suite refuses to run against, on purpose. Set
> `TARGET_BRANCH=main` after the work merges.

## Setup

```bash
cd tests/e2e
npm ci
npx playwright install chromium
cp .env.example .env
```

| Variable | Meaning |
|---|---|
| `XNAT_URL` | The instance under test. |
| `ADMIN_USER` / `ADMIN_PASS` | A site administrator account. |
| `NON_ADMIN_USER` / `NON_ADMIN_PASS` | Optional. An existing, enabled, non-site-admin account. Unset skips the permissions project; everything else still runs. |
| `TARGET_BRANCH` | `develop` (default) or `main`. On `develop` the configuration API is required and its absence fails the run. |
| `TEST_PROJECT` | Optional. By default each spec file creates a uniquely named project and deletes it afterwards. |

## Checks

```bash
npm run lint             # eslint, including the Playwright rules
npm run format:check     # prettier
npx tsc --noEmit         # types
```

`npm run lint:fix` and `npm run format` apply what they can. Markdown is
excluded from Prettier deliberately: it pads table columns, which turns every
later edit into a large diff.

## Running

```bash
npm test                 # everything
npm run test:api         # configuration REST API
npm run test:import      # archive round trips
npm run test:validation  # refusal paths
npm run test:permissions # access control
npm run test:browser     # the uploader page and form uploads
```

Specs run sequentially on a single worker. The api, validation and permissions
suites write site-wide configuration, which is a single site-scoped document,
so two specs at once would interleave their writes and the loser would assert
against a state neither test set up.

## Layout

Tests are grouped by **kind**, and each kind is a Playwright project:

| Directory | Project | Covers |
|---|---|---|
| `tests/api/` | `api` | The plugin's configuration endpoints, no archive involved |
| `tests/import/` | `import` | Upload an archive, assert on what was really created |
| `tests/validation/` | `validation` | Refusal paths; every test asserts the importer said no |
| `tests/permissions/` | `permissions` | Role and access gating, as a non-admin |
| `tests/ui/` | `browser` | The uploader page, and real uploads through the form |

`lib/` holds the REST client, login helper, archive builders, page helpers and
the diagnostic fixture. `global.setup.ts` runs before Playwright collects any
test file, because the modality matrix generates one test per modality at
collection time.

## Coverage

| Spec | Tests | Covers |
|---|---|---|
| `import/modalityMatrix.spec.ts` | 39 | Every configured modality imported for real, checked against the session and scan data types the API advertises, plus the payload |
| `import/columnMappingPrecedence.spec.ts` | 8 | That a project column mapping actually governs an import, disable and delete falling back to the site mapping, and re-importing an existing session label |
| `import/resourceIdentifierSelection.spec.ts` | 7 | Which identifier service runs for each parameter combination, proved by feeding in an archive only one service can read |
| `import/directoryRis.spec.ts` | 4 | Directory archives becoming sessions, scans, resources and files |
| `import/modalityDataType.spec.ts` | 4 | Modality configuration shape, ordering and completeness |
| `import/csvRis.spec.ts` | 3 | Every mapped manifest column reaching its XNAT property |
| `import/duplicateScanIds.spec.ts` | 2 | Two manifest rows naming the same scan: combining resources when the modality agrees, refusing the import when it does not |
| `api/propertyDisplayMappings.spec.ts` | 8 | The property drop-down: add, rename, delete, one-display-per-property |
| `api/projectCsvMappings.spec.ts` | 5 | Project overrides stored and scoped independently of the site set |
| `api/siteCsvMappings.spec.ts` | 4 | Site-wide mappings, defaults and round trips |
| `validation/uploadParameters.spec.ts` | 6 | Missing and bad parameters, unsupported formats, empty archives |
| `validation/manifestAndLabeling.spec.ts` | 6 | Manifest refusals and custom labeling precedence |
| `ui/uploaderPage.spec.ts` | 7 | The form's controls and the modality drop-down |
| `ui/uploadThroughTheBrowser.spec.ts` | 4 | Directory and manifest archives uploaded through the real page |
| `permissions/configApiAccess.spec.ts` | 5 | Configuration API gating and importing into an inaccessible project |
| `permissions/projectOwner.spec.ts` | 2 | A project owner can import into their own project and not into another |
| `validation/manifestFormat.spec.ts` | 6 | Manifest file formats: byte order mark, empty manifest, two CSVs, bad path, label rules |
| `import/manifestFormatTolerance.spec.ts` | 4 | Quoted commas, Windows line endings, any `.csv` name, non-numeric scan IDs |

### Deliberately not covered

- Re-deriving the plugin's unit-tested internals through HTTP. The behavior
  that reaches a user is tested; a parser's internals are not re-implemented.
- The archive extractor's path-traversal guard, which `ArchiveExtractorTest`
  covers directly. Building a malicious archive to re-prove it adds risk, not
  coverage.
