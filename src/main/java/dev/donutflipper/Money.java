package dev.donutflipper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Money {
    public static final Pattern GENERIC = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbBtT])?");

    private Money() {}

    public static double mult(String s) {
        if (s == null || s.isEmpty()) return 1;
        switch (Character.toLowerCase(s.charAt(0))) {
            case 'k': return 1e3;
            case 'm': return 1e6;
            case 'b': return 1e9;
            case 't': return 1e12;
            default: return 1;
        }
    }

    /** First line matching the pattern (group 1 = number, optional group 2 = K/M/B/T suffix). */
    public static double find(List<String> lines, Pattern p) {
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1).replace(",", ""));
                    String suf = m.groupCount() >= 2 ? m.group(2) : null;
                    return v * mult(suf);
                } catch (Exception ignored) {}
            }
        }
        return Double.NaN;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "?";
        double a = Math.abs(v);
        if (a >= 1e12) return String.format("%.2fT", v / 1e12);
        if (a >= 1e9) return String.format("%.2fB", v / 1e9);
        if (a >= 1e6) return String.format("%.2fM", v / 1e6);
        if (a >= 1e3) return String.format("%.1fK", v / 1e3);
        return String.format("%.0f", v);
    }
}
