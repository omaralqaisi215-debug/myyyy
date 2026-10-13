package dev.donutflipper;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** All settings. Saved to config/donut-flipper/config.json; editable in the in-game Settings screen. */
public class Config {
    public static Config C = new Config();

    // main
    public boolean dryRun = true;
    public int scanMs = 1500;
    public boolean autoSell = true;
    public boolean cancelStale = true;
    public boolean scanOrders = true;
    public int ordersEvery = 90;
    // trading
    public double minMargin = 0.12;
    public double minProfit = 500;
    public double minPrice = 100;
    public double maxPrice = 5_000_000;
    public double fee = 0.05;
    public int minSamples = 6;
    public double minProb = 0.55;
    public double listTimeoutH = 2.0;
    public int maxHold = 6;
    // crafting
    public boolean craftOn = true;
    public double minCraftProfit = 500;
    public int maxCrafts = 16;
    // server formats
    public String ahCmd = "ah";
    public String ordersCmd = "orders";
    public String sellCmd = "ah sell {price}";
    public String ahTitle = "auction";
    public String ordersTitle = "order";
    public String confirmWord = "confirm";
    public String priceRegex = "(?i)price\\D{0,6}([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?";
    public boolean ordersPerUnit = true;
    public String boughtRegex = "(?i)(you (have )?(purchased|bought)|successfully (purchased|bought))";
    public String failRegex = "(?i)(already (been )?sold|no longer available|not enough money|can't afford|cannot afford|does not exist)";
    public String soldRegex = "(?i)(bought your|purchased your|has bought|you sold|sold your)";
    public String sellFailRegex = "(?i)(must be holding|invalid price|cannot sell|too many listings|maximum listings)";
    // pacing
    public int cmdGap = 300;
    public int clickMin = 110;
    public int clickMax = 260;

    public static File file() {
        File d = Brain.dir();
        return new File(d, "config.json");
    }

    public static void load() {
        try {
            File f = file();
            if (f.exists()) try (Reader r = new FileReader(f)) {
                Config c = Brain.GSON.fromJson(r, Config.class);
                if (c != null) C = c;
            }
        } catch (Exception e) {
            DonutFlipperMod.LOG.error("config load failed", e);
        }
        C.sanitize();
    }

    public static void save() {
        C.sanitize();
        try (Writer w = new FileWriter(file())) { Brain.GSON.toJson(C, w); }
        catch (Exception e) { DonutFlipperMod.LOG.error("config save failed", e); }
    }

    /** Keeps the safety floors (e.g. 300ms between commands) no matter what the file says. */
    public void sanitize() {
        cmdGap = Math.max(300, cmdGap);
        scanMs = Math.max(600, scanMs);
        clickMin = Math.max(50, clickMin);
        clickMax = Math.max(clickMin + 10, clickMax);
        minSamples = Math.max(3, minSamples);
        minProb = Math.max(0.1, Math.min(0.95, minProb));
        fee = Math.max(0, Math.min(0.5, fee));
        maxHold = Math.max(1, maxHold);
        ordersEvery = Math.max(20, ordersEvery);
        if (ahCmd == null || ahCmd.isBlank()) ahCmd = "ah";
    }

    /** Public instance fields in declaration order = the settings list. */
    public static List<Field> options() {
        List<Field> l = new ArrayList<>();
        for (Field f : Config.class.getDeclaredFields())
            if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) l.add(f);
        return l;
    }
}
