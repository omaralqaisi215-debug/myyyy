package dev.donutflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Everything the addon learns, persisted to config/donut-flipper/brain.json.
 *
 *  1. Price memory   - rolling window of distinct listing prices per item (median = fair value).
 *  2. Logistic model - online SGD, predicts P(flip is profitable) from margin/price/spread/samples/trust.
 *  3. UCB bandit     - learns which undercut % sells fastest and best when we list.
 *  4. Item trust     - items that lose us money get distrusted, winners get trusted.
 *  5. Adaptive bar   - if the recent win rate drops, the required margin goes up automatically.
 */
public class Brain {
    public static Brain I = new Brain();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final double[] DEFAULT_W = {-0.5, 6.0, 0.0, -1.5, 0.4, 0.5};
    static final double[] UNDERCUT = {0.0, 0.02, 0.05, 0.08, 0.12};

    public static class Item {
        public ArrayList<Double> ah = new ArrayList<>();
        public double bid;
        public long seen;
        public double trust = 1.0;
    }

    public static class Holding {
        public String key, itemId, name;
        public int count;
        public double cost, listPrice;
        public double[] x;
        public long boughtAt, listedAt;
        public int arm, relists;
        /** 0 = in inventory, 1 = listed, 2 = stale (needs cancel), 3 = reserved for crafting */
        public int status;
    }

    public Map<String, Item> items = new HashMap<>();
    public double[] w = DEFAULT_W.clone();
    public int[] armN = new int[UNDERCUT.length];
    public double[] armMean = new double[UNDERCUT.length];
    public ArrayList<Holding> ledger = new ArrayList<>();
    public ArrayList<Boolean> recent = new ArrayList<>();
    public ArrayList<String> log = new ArrayList<>();
    public double realized;
    public int wins, losses, expired, missed, buys, trained;

    private transient long lastSave = System.currentTimeMillis();
    private transient final Map<String, Long> seenListings = new HashMap<>();

    // ---------- persistence ----------
    static File dir() {
        File d = FabricLoader.getInstance().getConfigDir().resolve("donut-flipper").toFile();
        d.mkdirs();
        return d;
    }

    public static void load() {
        try {
            File f = new File(dir(), "brain.json");
            if (!f.exists()) return;
            try (Reader r = new FileReader(f)) {
                Brain b = GSON.fromJson(r, Brain.class);
                if (b != null) { I = b; I.fix(); }
            }
        } catch (Exception e) {
            DonutFlipperMod.LOG.error("Failed to load brain", e);
        }
    }

    private void fix() {
        if (items == null) items = new HashMap<>();
        if (w == null || w.length != DEFAULT_W.length) w = DEFAULT_W.clone();
        if (armN == null || armN.length != UNDERCUT.length) { armN = new int[UNDERCUT.length]; armMean = new double[UNDERCUT.length]; }
        if (ledger == null) ledger = new ArrayList<>();
        if (recent == null) recent = new ArrayList<>();
        if (log == null) log = new ArrayList<>();
    }

    public void save() {
        try {
            File f = new File(dir(), "brain.json");
            File tmp = new File(dir(), "brain.json.tmp");
            try (Writer wr = new FileWriter(tmp)) { GSON.toJson(this, wr); }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lastSave = System.currentTimeMillis();
        } catch (Exception e) {
            DonutFlipperMod.LOG.error("Failed to save brain", e);
        }
    }

    public void autosave() {
        if (System.currentTimeMillis() - lastSave > 60_000) save();
    }

    public void resetLearning() {
        w = DEFAULT_W.clone();
        armN = new int[UNDERCUT.length];
        armMean = new double[UNDERCUT.length];
        recent.clear();
        for (Item it : items.values()) it.trust = 1.0;
        trained = 0;
    }

    // ---------- price memory ----------
    /** True when this exact (item, unit price) listing wasn't already counted in the last 10 min. */
    private boolean fresh(String key, double unit) {
        long now = System.currentTimeMillis();
        String k = key + "@" + Math.round(unit);
        Long t = seenListings.get(k);
        if (t != null && now - t < 600_000) return false;
        seenListings.put(k, now);
        if (seenListings.size() > 20000) seenListings.values().removeIf(v -> now - v > 600_000);
        return true;
    }

    public void observe(String key, double unit) {
        if (!fresh(key, unit)) return;
        Item it = items.computeIfAbsent(key, k -> new Item());
        it.ah.add(unit);
        while (it.ah.size() > 60) it.ah.remove(0);
        it.seen = System.currentTimeMillis();
    }

    public void observeBid(String key, double unit) {
        Item it = items.computeIfAbsent(key, k -> new Item());
        it.bid = it.bid <= 0 ? unit : it.bid * 0.7 + unit * 0.3;
        it.seen = System.currentTimeMillis();
    }

    private static double quant(List<Double> l, double q) {
        ArrayList<Double> c = new ArrayList<>(l);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) Math.floor(q * (c.size() - 1) + 0.5)));
    }

    public double fair(String key, int minSamples) {
        Item it = items.get(key);
        if (it == null || it.ah.size() < minSamples) return Double.NaN;
        return quant(it.ah, 0.5);
    }

    public double spread(String key) {
        Item it = items.get(key);
        if (it == null || it.ah.size() < 4) return 0.5;
        double m = quant(it.ah, 0.5);
        return m <= 0 ? 0.5 : (quant(it.ah, 0.75) - quant(it.ah, 0.25)) / m;
    }

    public int samples(String key) {
        Item it = items.get(key);
        return it == null ? 0 : it.ah.size();
    }

    public double bid(String key) {
        Item it = items.get(key);
        return it == null ? 0 : it.bid;
    }

    public double trust(String key) {
        Item it = items.get(key);
        return it == null ? 1.0 : it.trust;
    }

    // ---------- logistic model ----------
    public double[] features(String key, double price, double fair, double margin) {
        return new double[]{1.0, margin, Math.log10(Math.max(price, 1)) / 6.0, spread(key),
            Math.min(samples(key), 40) / 40.0, trust(key) - 1.0};
    }

    public double prob(double[] x) {
        double z = 0;
        for (int i = 0; i < w.length; i++) z += w[i] * x[i];
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public void train(double[] x, boolean win) {
        if (x == null || x.length != w.length) return;
        double err = (win ? 1.0 : 0.0) - prob(x);
        for (int i = 0; i < w.length; i++) w[i] += 0.05 * (err * x[i] - 0.001 * w[i]);
        trained++;
    }

    // ---------- bandit for listing undercut ----------
    public int pickArm() {
        int total = 0;
        for (int n : armN) total += n;
        for (int i = 0; i < armN.length; i++) if (armN[i] == 0) return i;
        int best = 0;
        double bs = -1e9;
        for (int i = 0; i < armN.length; i++) {
            double s = armMean[i] + 0.5 * Math.sqrt(2 * Math.log(total) / armN[i]);
            if (s > bs) { bs = s; best = i; }
        }
        return best;
    }

    public double undercut(int arm) { return UNDERCUT[Math.max(0, Math.min(arm, UNDERCUT.length - 1))]; }

    private void reward(int arm, double r) {
        if (arm < 0 || arm >= armN.length) return;
        armN[arm]++;
        double a = Math.max(1.0 / armN[arm], 0.05);
        armMean[arm] += a * (r - armMean[arm]);
    }

    // ---------- adaptive risk ----------
    public double recentWinRate() {
        if (recent.size() < 6) return 1.0;
        int win = 0;
        for (boolean b : recent) if (b) win++;
        return win / (double) recent.size();
    }

    public double marginBoost() { return recentWinRate() < 0.5 ? 0.05 : 0.0; }

    private void push(boolean win) {
        recent.add(win);
        while (recent.size() > 20) recent.remove(0);
    }

    private void note(String s) {
        log.add(s);
        while (log.size() > 40) log.remove(0);
    }

    // ---------- ledger ----------
    public int holdings() { return ledger.size(); }

    public Holding add(String key, String name, int count, double cost, double[] x, int status) {
        Holding h = new Holding();
        h.key = key; h.itemId = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
        h.name = name; h.count = count; h.cost = cost; h.x = x; h.boughtAt = System.currentTimeMillis(); h.status = status;
        ledger.add(h);
        buys++;
        return h;
    }

    public Holding first(int status) {
        for (Holding h : ledger) if (h.status == status) return h;
        return null;
    }

    public List<Holding> listed() {
        List<Holding> r = new ArrayList<>();
        for (Holding h : ledger) if (h.status == 1) r.add(h);
        return r;
    }

    public boolean isStale(String key) {
        for (Holding h : ledger) if (h.status == 2 && h.key.equals(key)) return true;
        return false;
    }

    public void relistReady(String key) {
        for (Holding h : ledger) if (h.status == 2 && h.key.equals(key)) { h.status = 0; h.relists++; return; }
    }

    public void checkStale(long timeoutMs) {
        long now = System.currentTimeMillis();
        for (Holding h : ledger) {
            if (h.status == 1 && now - h.listedAt > timeoutMs) {
                h.status = 2;
                expired++;
                reward(h.arm, -0.3);
                train(h.x, false);
                Item it = items.get(h.key);
                if (it != null) it.trust = Math.max(0, it.trust - 0.1);
                note("EXPIRED " + h.name + " (undercut " + (int) (undercut(h.arm) * 100) + "%)");
            }
        }
    }

    public void sold(Holding h, double price, double fee) {
        double net = price * (1 - fee);
        double profit = net - h.cost;
        realized += profit;
        boolean win = profit > 0;
        if (win) wins++; else losses++;
        push(win);
        double hours = (System.currentTimeMillis() - h.listedAt) / 3.6e6;
        double r = Math.max(-1, Math.min(1, profit / Math.max(h.cost, 1))) * (1.0 / (1.0 + hours / 6.0));
        reward(h.arm, r);
        train(h.x, win);
        Item it = items.get(h.key);
        if (it != null) it.trust = Math.max(0, Math.min(1.5, it.trust + (win ? 0.05 : -0.15)));
        note((win ? "WIN " : "LOSS ") + h.name + " " + Money.fmt(profit));
        ledger.remove(h);
    }

    /** A purchase attempt that failed because someone else was faster. */
    public void missed() { missed++; }

    public double consume(String itemId, int qty) {
        double cost = 0;
        int rem = qty;
        Iterator<Holding> it = ledger.iterator();
        while (it.hasNext() && rem > 0) {
            Holding h = it.next();
            if (h.status != 3 || !h.itemId.equals(itemId)) continue;
            int take = Math.min(h.count, rem);
            double share = h.cost * take / Math.max(h.count, 1);
            h.cost -= share; h.count -= take; rem -= take; cost += share;
            if (h.count <= 0) it.remove();
        }
        return cost;
    }
}
