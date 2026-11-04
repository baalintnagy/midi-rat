export const examples = {
    performance: {
        title: 'Pedals & chords',
        files: [
            {
                name: 'network.yaml',
                text: `schemaVersion: 1
network:
  buses: [musical, sounding]
  modules:
    - name: Musical input
      type: midi-message-filter
      sources: [keyboard, controller]
      destinations: [musical]
      settings:
        whitelist: [{type: NOTE}, {type: CC}, {type: P_BEND}]
        blacklist: []
    - name: Input monitor
      type: midi-monitor
      sources: [musical]
      settings: {historyLimit: 2000, colors: true}
    - name: Sostenuto
      type: sostenuto
      sources: [musical]
      destinations: [sounding, synth]
      settings: {trigger: {cc: 22}}
    - name: Chord display
      type: midi-chord
      sources: [sounding]
      settings: {maxChords: 8}
    - name: Manual emitter
      type: midi-emitter
      destinations: [synth]
      settings: {format: hex, messages: ["90 3C 64", "80 3C 00"]}
`,
            },
            {
                name: 'application.yaml',
                text: `schemaVersion: 1
application:
  backend: winmm
  moduleDirectory: ../modules
  networkFile: ./network.yaml
  ports:
    inputs:
      keyboard: Example MIDI Keyboard
      controller: Example Pedal Controller
    outputs:
      synth: Example Synthesizer
`,
            },
        ],
    },
    transpose: {
        title: 'Octave transpose',
        files: [
            {
                name: 'transpose.yaml',
                text: `schemaVersion: 1
network:
  buses: [transposed]
  modules:
    - name: Octave up
      type: midi-remap
      sources: [keyboard]
      destinations: [transposed, synth]
      settings:
        rules:
          - match: {type: NOTE}
            action: {op: note-map, fallbackTranspose: 12}
            flow: stop
    - name: Monitor
      type: midi-monitor
      sources: [transposed]
      settings: {}
`,
            },
        ],
    },
    local: {
        title: 'No hardware',
        files: [
            {
                name: 'local.yaml',
                text: `schemaVersion: 1
network:
  buses: [performance]
  modules:
    - name: Manual emitter
      type: midi-emitter
      destinations: [performance]
      settings: {format: hex, messages: ["90 3C 64", "80 3C 00"]}
    - name: Monitor
      type: midi-monitor
      sources: [performance]
      settings: {historyLimit: 2000}
    - name: Chords
      type: midi-chord
      sources: [performance]
      settings: {maxChords: 8}
`,
            },
        ],
    },
};
