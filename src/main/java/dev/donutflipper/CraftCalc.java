package dev.donutflipper;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;

/** Crafting profit calculator. Recipes: bundled recipes.json, overridable at config/donut-flipper/recipes.json */
public class CraftCalc {
    public static class Recipe {
        public String output;
        public int count = 1;
        public List<String> pattern;
        public Map<String, String> key;
        public transient double cost = Double.NaN, revenue = Double.NaN, profit = Double.NaN;

        public int width() { int w = 0; for (String r : pattern) w = Math.max(w, r.length()); return w; }
        public int height() { return pattern.size(); }
        public boolean needsTable() { return width() > 2 || height() > 2; }

        /** item id -> amount needed per craft */
        public Map<String, Integer> need() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String row : pattern) for (char c : row.toCharArray()) {
                if (c == ' ') continue;
                m.merge(key.get(String.valueOf(c)), 1, Integer::sum);
            }
            return m;
        }
    }

    public static List<Recipe> recipes = new ArrayList<>();

    public static void load() {
        try {
            File f = new File(Brain.dir(), "recipes.json");
            Reader r = f.exists() ? new FileReader(f)
                : new InputStreamReader(CraftCalc.class.getResourceAsStream("/donutflipper/recipes.json"));
            try (r) {
                List<Recipe> l = Brain.GSON.fromJson(r, new TypeToken<List<Recipe>>() {}.getType());
                if (l != null) recipes = l;
            }
        } catch (Exception e) {
            DonutFlipperMod.LOG.error("Failed to load recipes", e);
        }
    }

    public static void refresh(double fee) {
        for (Recipe r : recipes) {
            double cost = 0;
            for (Map.Entry<String, Integer> e : r.need().entrySet()) {
                double f = Brain.I.fair(e.getKey(), 3);
                if (Double.isNaN(f)) { cost = Double.NaN; break; }
                cost += f * e.getValue();
            }
            double out = Brain.I.fair(r.output, 3);
            r.cost = cost;
            r.revenue = Double.isNaN(out) ? Double.NaN : out * r.count * (1 - fee);
            r.profit = (Double.isNaN(cost) || Double.isNaN(r.revenue)) ? Double.NaN : r.revenue - cost;
        }
    }

    public static List<Recipe> ranked() {
        List<Recipe> l = new ArrayList<>();
        for (Recipe r : recipes) if (!Double.isNaN(r.profit)) l.add(r);
        l.sort((a, b) -> Double.compare(b.profit, a.profit));
        return l;
    }

    /** Ingredient ids of recipes that are currently profitable - sniping these is worth it even at lower margins. */
    public static Set<String> wantedIngredients(double minProfit) {
        Set<String> s = new HashSet<>();
        for (Recipe r : ranked()) if (r.profit >= minProfit && !r.needsTable()) s.addAll(r.need().keySet());
        return s;
    }

    public static int canCraft(Recipe r, Map<String, Integer> inv) {
        int n = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : r.need().entrySet())
            n = Math.min(n, inv.getOrDefault(e.getKey(), 0) / e.getValue());
        return n == Integer.MAX_VALUE ? 0 : n;
    }
}
