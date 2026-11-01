export function createYamlEditor(ace, element, fit) {
    const editor = ace.edit('source-text', {
        mode: 'ace/mode/yaml',
        theme: 'ace/theme/tomorrow_night',
        tabSize: 2,
        useSoftTabs: true,
        showPrintMargin: false,
        fontSize: 14,
    });
    editor.session.setUseWorker(false);

    function showPanel(yaml) {
        for (const name of ['diagram', 'yaml']) {
            const active = (name === 'yaml') === yaml;
            element(`${name}-panel`).hidden = !active;
            element(`${name}-tab`).setAttribute('aria-selected', String(active));
            element(`${name}-tab`).tabIndex = active ? 0 : -1;
        }
        if (yaml) {
            editor.resize();
        } else {
            requestAnimationFrame(fit);
        }
    }
    for (const name of ['diagram', 'yaml']) {
        element(`${name}-tab`).addEventListener('click', () => showPanel(name === 'yaml'));
        element(`${name}-tab`).addEventListener('keydown', (event) => {
            if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
                event.preventDefault();
                const yaml = event.key === 'End' || (event.key !== 'Home' && name === 'diagram');
                showPanel(yaml);
                element(`${yaml ? 'yaml' : 'diagram'}-tab`).focus();
            }
        });
    }

    element('editor-zoom').addEventListener('input', (event) => {
        const size = Number(event.target.value);
        editor.setFontSize(size);
        editor.resize();
        element('editor-zoom-label').textContent = `${size}px`;
    });

    return {
        getValue: () => editor.getValue(),
        setValue: (text) => editor.setValue(text, -1),
        showDiagram: () => showPanel(false),
    };
}
