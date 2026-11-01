import { LineCounter, parseDocument } from '../vendor/yaml/index.js';

export const MAX_FILE_SIZE = 1_000_000;

export class ConfigurationError extends Error {
    constructor(message, source, path = [], line = null, column = null) {
        super(message);
        this.name = 'ConfigurationError';
        this.source = source;
        this.path = path.join('.');
        this.line = line;
        this.column = column;
    }
}

export function parseYaml(text, name) {
    if (new TextEncoder().encode(text).length > MAX_FILE_SIZE) {
        throw new ConfigurationError('Files must be smaller than 1 MB.', name);
    }
    const lineCounter = new LineCounter();
    const document = parseDocument(text, {
        lineCounter,
        prettyErrors: true,
        uniqueKeys: true,
        strict: true,
        stringKeys: true,
    });
    if (document.errors.length || document.warnings.length) {
        const problem = document.errors[0] || document.warnings[0];
        const position = problem.linePos?.[0];
        throw new ConfigurationError(problem.message, name, [], position?.line, position?.col);
    }
    let data;
    try {
        // Match the host: configuration aliases are not accepted.
        data = document.toJS({ maxAliasCount: 0 });
    } catch (error) {
        throw new ConfigurationError(error.message, name);
    }
    function fail(path, message) {
        const node = document.getIn(path, true);
        const offset = node?.range?.[0] ?? document.contents?.range?.[0] ?? 0;
        const position = lineCounter.linePos(offset);
        throw new ConfigurationError(message, name, path, position.line, position.col);
    }
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
        fail([], 'Expected an application or network YAML mapping.');
    }
    if (data.schemaVersion !== 1) {
        fail(['schemaVersion'], 'Expected schemaVersion: 1.');
    }
    if (Object.hasOwn(data, 'network') === Object.hasOwn(data, 'application')) {
        fail([], 'A file must contain either network or application.');
    }
    return {
        name,
        text,
        data,
        fail,
        kind: Object.hasOwn(data, 'network') ? 'network' : 'application',
    };
}

export function basename(path) {
    return path.replaceAll('\\', '/').split('/').at(-1);
}

export function selectDocuments(files, networkName = null, applicationName = null) {
    const documents = files.map((file) => (file.kind ? file : parseYaml(file.text, file.name)));
    const names = new Set();
    for (const document of documents) {
        if (names.has(document.name)) {
            document.fail([], 'Two files have the same name. Rename one.');
        }
        names.add(document.name);
    }
    const networks = documents.filter((document) => document.kind === 'network');
    const applications = documents.filter((document) => document.kind === 'application');
    const application =
        applications.find((document) => document.name === applicationName) ??
        (applicationName === '' ? null : (applications[0] ?? null));
    if (application && typeof application.data.application?.networkFile !== 'string') {
        application.fail(['application', 'networkFile'], 'Expected a networkFile path.');
    }
    let network = networks.find((document) => document.name === networkName);
    if (!network && application) {
        const reference = application.data.application?.networkFile;
        if (typeof reference !== 'string') {
            application.fail(['application', 'networkFile'], 'Expected a networkFile path.');
        }
        network = networks.find((document) => basename(document.name) === basename(reference));
        if (!network) {
            application.fail(
                ['application', 'networkFile'],
                `Add the referenced network file: ${basename(reference)}`,
            );
        }
    }
    network ??= networks[0];
    if (!network) {
        throw new ConfigurationError(
            'Add a network YAML file to draw its diagram.',
            application?.name,
        );
    }
    if (
        application &&
        basename(application.data.application.networkFile) !== basename(network.name)
    ) {
        application.fail(
            ['application', 'networkFile'],
            `This application references ${basename(application.data.application.networkFile)}, not ${network.name}.`,
        );
    }
    return { network, application, networks, applications };
}
