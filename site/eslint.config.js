export default [
    { ignores: ['vendor/**', '_site/**', '.preview/**'] },
    {
        files: ['**/*.js', '**/*.mjs'],
        rules: {
            curly: ['error', 'all'],
            'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none' }],
        },
    },
];
