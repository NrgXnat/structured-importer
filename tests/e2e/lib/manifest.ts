/**
 * The manifest column names the default site configuration ships with.
 *
 * Shared because eight specs were carrying identical copies of this array, and
 * a change to the shipped defaults would otherwise have to be chased through
 * every one of them.
 */

/** The columns every manifest in this suite needs. */
export const COLUMNS = [
    'Scan ID',
    'Modality',
    'Series Description',
    'Session Label',
    'Subject ID',
    'Resource Name',
    'Path',
];

/** The optional columns, for specs that assert metadata beyond the essentials. */
export const OPTIONAL_COLUMNS = ['Start Date', 'Start Time', 'Subject Weight (g)'];

/** A header row built from `COLUMNS`, for specs that write a manifest by hand. */
export const HEADER = COLUMNS.join(',');
