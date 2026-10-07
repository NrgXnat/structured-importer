import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import playwright from 'eslint-plugin-playwright';
import prettier from 'eslint-config-prettier';

export default tseslint.config(
    { ignores: ['node_modules/', 'test-results/', 'playwright-report/', '.auth/'] },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    {
        files: ['tests/**/*.ts'],
        ...playwright.configs['flat/recommended'],
        rules: {
            ...playwright.configs['flat/recommended'].rules,

            // The suite asserts on refusals as often as on successes, and the
            // message a refusal carries is part of what is being tested, so
            // several specs legitimately read a response before asserting.
            'playwright/no-conditional-in-test': 'off',

            // Specs that generate one test per modality build them in a loop
            // at collection time. That is the point of the matrix.
            'playwright/no-conditional-expect': 'off',

            // The rule exists to stop skipped tests being forgotten. Every
            // skip here is a conditional environment gate: the permissions
            // specs need a second account the suite cannot create, and the
            // configuration specs need an API that is not on every build.
            // Each states its reason and reports as skipped rather than
            // passing, which is the behavior the rule is protecting.
            'playwright/no-skipped-test': 'off',
        },
    },
    {
        // The setup project's logins are steps, not tests. They produce the
        // storage state the real tests consume, and the assertions that matter
        // live inside the login helper.
        files: ['tests/global-setup.ts'],
        rules: { 'playwright/expect-expect': 'off' },
    },
    {
        rules: {
            // Server responses are untyped JSON. Narrowing every read would
            // add noise without adding safety, since the assertion that
            // follows is what actually checks the shape.
            '@typescript-eslint/no-explicit-any': 'off',
            '@typescript-eslint/no-non-null-assertion': 'off',
            'no-empty': ['error', { allowEmptyCatch: true }],
        },
    },
    prettier,
);
