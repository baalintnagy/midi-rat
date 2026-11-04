package io.midirat.chord;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A diatonic degree retains spelling information even when pitch classes coincide. */
public record Degree(String label, int number, int alteration) {
    private static final Pattern SYNTAX = Pattern.compile("(bb|##|b|#)?(1|2|3|4|5|6|7|9|11|13)");
    private static final int[] NATURAL = { 0, 2, 4, 5, 7, 9, 11 };

    public static Degree parse(String label) {
        Matcher matcher = SYNTAX.matcher(label);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid chord degree: %s".formatted(label));
        }
        String accidental = matcher.group(1);
        int alteration = accidental == null ? 0
            : accidental.length()
                * (accidental.charAt(0) == 'b' ? -1 : 1);
        return new Degree(label, Integer.parseInt(matcher.group(2)), alteration);
    }

    public int pitchClass() {
        return Math.floorMod(NATURAL[(number - 1) % 7] + alteration, 12);
    }

    public int spellingCost(String root) {
        int step = "CDEFGAB".indexOf(root.charAt(0));
        int rootAlteration = root.length() == 1 ? 0 : root.charAt(1) == '#' ? 1 : -1;
        int target = (step + number - 1) % 7;
        int delta = Math.floorMod(NATURAL[step] + rootAlteration + pitchClass()
            - NATURAL[target] + 6, 12) - 6;
        return Math.abs(delta);
    }
}
