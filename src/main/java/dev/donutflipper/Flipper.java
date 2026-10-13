package dev.donutflipper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.*;
import java.util.regex.Pattern;

/** The trading bot itself (standalone Fabric version - no Meteor needed). */
public class Flipper {
    public static final Flipper I = new Flipper();

    private enum S { IDLE, AH_WAIT, CONF_WAIT, RESULT_WAIT, SELL_WAIT, ORD_WAIT }

    private record Cand(int slot, String key, Item item, int count, double price, double fair, double[] x, double ev, boolean wanted, String name) {}

    private final MinecraftClient mc = MinecraftClient.getInstance();
    public boolean enabled;

    private S state = S.IDLE;
    private long stateSince, lastCycle, lastOrders;
    private int ahSync = -1, invBefore;
    private Cand pending;
    private boolean okFlag, failFlag, sellFail;
    private int sellFailCount;

    public String status() { return state.name(); }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        Pacer.clear();
        if (on) {
            CraftCalc.load();
            state = S.IDLE;
            lastCycle = 0;
            sellFailCount = 0;
            Chat.info("Flipper ON" + (Config.C.dryRun ? " (dry-run: only logging candidates)" : ""));
        } else {
            Brain.I.save();
            Chat.info("Flipper OFF");
        }
    }

    // ---------------- main loop ----------------
    public void tick() {
        if (!enabled) return;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        Pacer.MIN_CMD_GAP = Config.C.cmdGap;
        Pacer.tick();
        Brain.I.autosave();
        if (!Pacer.idle()) return;
        long now = System.currentTimeMillis();
        switch (state) {
            case IDLE -> idle(now);
            case AH_WAIT -> ahWait(now);
            case CONF_WAIT -> confWait(now);
            case RESULT_WAIT -> resultWait(now);
            case SELL_WAIT -> sellWait(now);
            case ORD_WAIT -> ordWait(now);
        }
    }

    private void toIdle(long now) {
        state = S.IDLE;
        lastCycle = now;
        pending = null;
        if (mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen)) mc.player.closeHandledScreen();
    }

    private void idle(long now) {
        if (now - lastCycle < Config.C.scanMs) return;
        if (mc.currentScreen instanceof HandledScreen<?>) { mc.player.closeHandledScreen(); lastCycle = now; return; }
        if (mc.currentScreen != null) return; // user is in some other menu

        Brain.I.checkStale((long) (Config.C.listTimeoutH * 3.6e6));

        if (!Config.C.dryRun && Config.C.autoSell && sellFailCount < 3) {
            Brain.Holding h = Brain.I.first(0);
            if (h != null) { startSell(h); return; }
        }
        if (!Config.C.dryRun && Config.C.craftOn && tryCraft()) return;

        if (Config.C.scanOrders && now - lastOrders > Config.C.ordersEvery * 1000L) {
            lastOrders = now;
            Pacer.command(Config.C.ordersCmd);
            state = S.ORD_WAIT;
            stateSince = now;
            return;
        }
        Pacer.command(Config.C.ahCmd);
        state = S.AH_WAIT;
        stateSince = now;
    }

    // ---------------- AH scan + buy ----------------
    private boolean titleHas(GenericContainerScreen s, String needle) {
        return s.getTitle().getString().toLowerCase().contains(needle.toLowerCase());
    }

    private void ahWait(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen s && titleHas(s, Config.C.ahTitle)) {
            int sync = s.getScreenHandler().syncId;
            ahSync = sync;
            Pacer.action(() -> scanAh(sync), Config.C.clickMin, Config.C.clickMax);
            return;
        }
        if (now - stateSince > 4000) toIdle(now);
    }

    private double threshold() { return Config.C.minMargin + Brain.I.marginBoost(); }

    private void scanAh(int sync) {
        long now = System.currentTimeMillis();
        if (!(mc.currentScreen instanceof GenericContainerScreen s) || s.getScreenHandler().syncId != sync) { toIdle(now); return; }
        GenericContainerScreenHandler h = s.getScreenHandler();
        Pattern pr = pat(Config.C.priceRegex);
        String me = mc.getSession().getUsername().toLowerCase();
        double thr = threshold();
        CraftCalc.refresh(Config.C.fee);
        Set<String> wanted = Config.C.craftOn ? CraftCalc.wantedIngredients(Config.C.minCraftProfit) : Set.of();

        Cand best = null;
        int cancelSlot = -1, seen = 0;
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            List<String> lore = Keys.lore(st);
            double price = Money.find(lore, pr);
            if (Double.isNaN(price) || price <= 0) continue;
            int cnt = st.getCount();
            String key = Keys.of(st);

            boolean mine = false;
            for (String l : lore) if (l.toLowerCase().contains(me)) { mine = true; break; }
            if (mine) {
                if (Config.C.cancelStale && cancelSlot < 0 && Brain.I.isStale(key)) cancelSlot = i;
                continue;
            }
            seen++;

            double fair = Brain.I.fair(key, Config.C.minSamples);
            if (!Double.isNaN(fair)) {
                double net = fair * cnt * (1 - Config.C.fee);
                double profit = net - price;
                double margin = profit / (fair * cnt);
                boolean w = wanted.contains(key);
                double need = w ? Math.min(thr, 0.04) : thr;
                if (price >= Config.C.minPrice && price <= Config.C.maxPrice && profit >= Config.C.minProfit && margin >= need
                    && Brain.I.trust(key) > 0.35 && Brain.I.holdings() < Config.C.maxHold) {
                    double[] x = Brain.I.features(key, price, fair, margin);
                    double p = Brain.I.prob(x);
                    if (p >= Config.C.minProb) {
                        double ev = profit * p;
                        if (best == null || ev > best.ev())
                            best = new Cand(i, key, st.getItem(), cnt, price, fair, x, ev, w, st.getName().getString());
                    }
                }
            }
            Brain.I.observe(key, price / cnt); // learn AFTER judging so a listing can't vouch for itself
        }

        if (best != null) {
            if (Config.C.dryRun) {
                Chat.info("[dry-run] would buy %dx %s for %s (fair %s, p=%.2f)", best.count(), best.name(), Money.fmt(best.price()), Money.fmt(best.fair() * best.count()), Brain.I.prob(best.x()));
                toIdle(now);
                return;
            }
            pending = best;
            invBefore = countItem(best.item());
            okFlag = failFlag = false;
            clickSlot(sync, best.slot(), SlotActionType.PICKUP, 0);
            state = S.CONF_WAIT;
            stateSince = now;
            return;
        }
        if (cancelSlot >= 0 && !Config.C.dryRun) {
            String key = Keys.of(h.getSlot(cancelSlot).getStack());
            Brain.I.relistReady(key);
            clickSlot(sync, cancelSlot, SlotActionType.PICKUP, 0);
            Chat.info("Cancelling stale listing, will relist cheaper.");
            state = S.SELL_WAIT;
            stateSince = now;
            return;
        }
        if (seen == 0 && now - stateSince > 0) Chat.info("AH opened but no prices parsed - check 'price-regex' / title settings.");
        toIdle(now);
    }

    private void confWait(long now) {
        if (pending == null) { toIdle(now); return; }
        if (countItem(pending.item()) >= invBefore + pending.count()) { success(now); return; }
        if (failFlag) { fail(now, true); return; }
        if (mc.currentScreen instanceof GenericContainerScreen s && s.getScreenHandler().syncId != ahSync) {
            int sync = s.getScreenHandler().syncId;
            Pacer.action(() -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen c) || c.getScreenHandler().syncId != sync) return;
                int slot = confirmSlot(c.getScreenHandler());
                if (slot >= 0) clickSlot(sync, slot, SlotActionType.PICKUP, 0);
                state = S.RESULT_WAIT;
                stateSince = System.currentTimeMillis();
            }, Config.C.clickMin, Config.C.clickMax);
            return;
        }
        if (now - stateSince > 2500) fail(now, true);
    }

    private void resultWait(long now) {
        if (pending == null) { toIdle(now); return; }
        if (okFlag || countItem(pending.item()) >= invBefore + pending.count()) { success(now); return; }
        if (failFlag) { fail(now, true); return; }
        if (now - stateSince > 4000) fail(now, false);
    }

    private void success(long now) {
        Cand c = pending;
        boolean reserve = Config.C.craftOn && c.wanted();
        Brain.I.add(c.key(), c.name(), c.count(), c.price(), c.x(), reserve ? 3 : 0);
        Chat.info("Bought %dx %s for %s (fair %s)%s", c.count(), c.name(), Money.fmt(c.price()), Money.fmt(c.fair() * c.count()), reserve ? " [reserved for craft]" : "");
        toIdle(now);
        lastCycle = now - Config.C.scanMs + 400; // go again quickly
    }

    private void fail(long now, boolean sniped) {
        if (sniped) Brain.I.missed();
        Chat.info("Purchase did not go through%s.", sniped ? " (someone was faster?)" : "");
        toIdle(now);
    }

    // ---------------- selling ----------------
    private void startSell(Brain.Holding h) {
        Item item = Keys.item(h.itemId);
        int idx = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && st.isOf(item)) { idx = i; break; }
        }
        if (idx < 0) { Brain.I.ledger.remove(h); return; } // item is gone (sold/dropped/used)

        double fair = Brain.I.fair(h.key, 1);
        if (Double.isNaN(fair)) fair = h.cost / Math.max(h.count, 1) * 1.15;
        h.arm = Brain.I.pickArm();
        double under = Brain.I.undercut(h.arm) + 0.02 * h.relists;
        double total = fair * h.count * (1 - under);
        double floor = h.cost * 1.01 / (1 - Config.C.fee);
        boolean old = System.currentTimeMillis() - h.boughtAt > 3 * 3600_000L;
        if (!old) total = Math.max(total, floor);
        final long price = Math.max(1, Math.round(total));
        final int slot = idx;
        final int hb = slot < 9 ? slot : mc.player.getInventory().getSelectedSlot();

        if (slot >= 9) {
            Pacer.action(() -> mc.setScreen(new InventoryScreen(mc.player)), 150, 320);
            Pacer.action(() -> clickSlot(mc.player.playerScreenHandler.syncId, slot, SlotActionType.SWAP, hb), Config.C.clickMin, Config.C.clickMax);
            Pacer.action(() -> mc.player.closeHandledScreen(), Config.C.clickMin, Config.C.clickMax);
        }
        Pacer.action(() -> mc.player.getInventory().setSelectedSlot(hb), 80, 180);
        Pacer.command(Config.C.sellCmd.replace("{price}", String.valueOf(price)));
        Pacer.action(() -> {
            h.status = 1;
            h.listedAt = System.currentTimeMillis();
            h.listPrice = price;
            sellFail = false;
            Chat.info("Listed %dx %s for %s (undercut %d%%)", h.count, h.name, Money.fmt(price), (int) (under * 100));
            state = S.SELL_WAIT;
            stateSince = System.currentTimeMillis();
        }, 50, 120);
        lastCycle = System.currentTimeMillis();
    }

    private void sellWait(long now) {
        if (sellFail) {
            sellFailCount++;
            Brain.Holding h = null;
            for (Brain.Holding x : Brain.I.ledger) if (x.status == 1) h = x;
            if (h != null) { h.status = 0; h.listPrice = 0; }
            Chat.info("Listing was rejected (%d/3 before auto-sell pauses). Check 'sell-command'.", sellFailCount);
            sellFail = false;
            toIdle(now);
            return;
        }
        if (mc.currentScreen instanceof GenericContainerScreen s && s.getScreenHandler().syncId != ahSync) {
            int sync = s.getScreenHandler().syncId;
            int slot = confirmSlot(s.getScreenHandler());
            if (slot >= 0) {
                Pacer.action(() -> {
                    if (mc.currentScreen instanceof GenericContainerScreen c && c.getScreenHandler().syncId == sync)
                        clickSlot(sync, slot, SlotActionType.PICKUP, 0);
                    toIdle(System.currentTimeMillis());
                }, Config.C.clickMin, Config.C.clickMax);
                return;
            }
        }
        if (now - stateSince > 1500) toIdle(now);
    }

    // ---------------- /orders ----------------
    private void ordWait(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen s && titleHas(s, Config.C.ordersTitle)) {
            int sync = s.getScreenHandler().syncId;
            Pacer.action(() -> scanOrdersScreen(sync), Config.C.clickMin, Config.C.clickMax);
            return;
        }
        if (now - stateSince > 4000) toIdle(now);
    }

    private void scanOrdersScreen(int sync) {
        long now = System.currentTimeMillis();
        if (!(mc.currentScreen instanceof GenericContainerScreen s) || s.getScreenHandler().syncId != sync) { toIdle(now); return; }
        GenericContainerScreenHandler h = s.getScreenHandler();
        Pattern pr = pat(Config.C.priceRegex);
        int n = 0;
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            double price = Money.find(Keys.lore(st), pr);
            if (Double.isNaN(price) || price <= 0) continue;
            double unit = Config.C.ordersPerUnit ? price : price / Math.max(1, st.getCount());
            Brain.I.observeBid(Keys.of(st), unit);
            n++;
        }
        if (n == 0) Chat.info("/orders opened but no prices parsed - check the order title / price regex.");
        toIdle(now);
    }

    // ---------------- crafting (2x2 inventory grid) ----------------
    private boolean tryCraft() {
        CraftCalc.refresh(Config.C.fee);
        Map<String, Integer> inv = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty()) inv.merge(Keys.id(st), st.getCount(), Integer::sum);
        }
        for (CraftCalc.Recipe r : CraftCalc.ranked()) {
            if (r.profit < Config.C.minCraftProfit) break;
            if (r.needsTable()) continue;
            int n = Math.min(CraftCalc.canCraft(r, inv), Config.C.maxCrafts);
            if (n <= 0) continue;
            craft(r, n);
            return true;
        }
        return false;
    }

    private void craft(CraftCalc.Recipe r, int n) {
        int lo = Config.C.clickMin, hi = Config.C.clickMax;
        Map<String, List<Integer>> cells = new LinkedHashMap<>();
        for (int row = 0; row < r.pattern.size(); row++) {
            String line = r.pattern.get(row);
            for (int col = 0; col < line.length(); col++) {
                char ch = line.charAt(col);
                if (ch == ' ') continue;
                cells.computeIfAbsent(r.key.get(String.valueOf(ch)), k -> new ArrayList<>()).add(1 + row * 2 + col);
            }
        }
        final boolean[] abort = {false};
        Pacer.action(() -> mc.setScreen(new InventoryScreen(mc.player)), 150, 320);
        for (int k = 0; k < n; k++) {
            for (Map.Entry<String, List<Integer>> e : cells.entrySet()) {
                final Item item = Keys.item(e.getKey());
                final int[] src = {-1};
                Pacer.action(() -> {
                    if (abort[0]) return;
                    var hd = mc.player.playerScreenHandler;
                    for (int s = 9; s <= 44; s++) {
                        ItemStack st = hd.getSlot(s).getStack();
                        if (!st.isEmpty() && st.isOf(item)) { src[0] = s; break; }
                    }
                    if (src[0] < 0) { abort[0] = true; return; }
                    clickSlot(hd.syncId, src[0], SlotActionType.PICKUP, 0);
                }, lo, hi);
                for (int cell : e.getValue()) {
                    Pacer.action(() -> { if (!abort[0]) clickSlot(mc.player.playerScreenHandler.syncId, cell, SlotActionType.PICKUP, 1); }, lo, hi);
                }
                Pacer.action(() -> {
                    var hd = mc.player.playerScreenHandler;
                    if (src[0] >= 0 && !hd.getCursorStack().isEmpty()) clickSlot(hd.syncId, src[0], SlotActionType.PICKUP, 0);
                }, lo, hi);
            }
            Pacer.action(() -> { if (!abort[0]) clickSlot(mc.player.playerScreenHandler.syncId, 0, SlotActionType.QUICK_MOVE, 0); }, lo, hi);
        }
        Pacer.action(() -> mc.player.closeHandledScreen(), lo, hi);
        Pacer.action(() -> {
            if (abort[0]) { Chat.info("Craft aborted (missing ingredients)."); lastCycle = System.currentTimeMillis(); return; }
            double cost = 0;
            for (Map.Entry<String, Integer> e : r.need().entrySet()) cost += Brain.I.consume(e.getKey(), e.getValue() * n);
            if (cost <= 0) cost = r.cost * n;
            Item out = Keys.item(r.output);
            Brain.I.add(r.output, out.getName().getString(), r.count * n, cost, null, 0);
            Chat.info("Crafted %dx %s (cost %s, expected profit %s)", r.count * n, out.getName().getString(), Money.fmt(cost), Money.fmt(r.profit * n));
            lastCycle = System.currentTimeMillis();
        }, 50, 120);
    }

    // ---------------- chat feedback ----------------
    public void onMessage(String m) {
        if (!enabled) return;
        if (state == S.CONF_WAIT || state == S.RESULT_WAIT) {
            if (matches(Config.C.failRegex, m)) failFlag = true;
            if (matches(Config.C.boughtRegex, m)) okFlag = true;
        }
        if (matches(Config.C.sellFailRegex, m) && state == S.SELL_WAIT) sellFail = true;
        if (matches(Config.C.soldRegex, m)) {
            List<Brain.Holding> listed = Brain.I.listed();
            if (listed.isEmpty()) return;
            double price = Money.find(List.of(m), Money.GENERIC);
            Brain.Holding pick = listed.get(0);
            for (Brain.Holding h : listed) if (m.toLowerCase().contains(h.name.toLowerCase())) { pick = h; break; }
            if (Double.isNaN(price)) price = pick.listPrice;
            Chat.info("SOLD %s for %s", pick.name, Money.fmt(price));
            Brain.I.sold(pick, price, Config.C.fee);
        }
    }

    // ---------------- helpers ----------------
    private void clickSlot(int sync, int slot, SlotActionType type, int button) {
        mc.interactionManager.clickSlot(sync, slot, button, type, mc.player);
    }

    private int confirmSlot(GenericContainerScreenHandler h) {
        int lime = -1;
        String word = Config.C.confirmWord.toLowerCase();
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (!word.isEmpty() && st.getName().getString().toLowerCase().contains(word)) return i;
            if (lime < 0 && (st.isOf(Items.LIME_STAINED_GLASS_PANE) || st.isOf(Items.LIME_STAINED_GLASS)
                || st.isOf(Items.LIME_CONCRETE) || st.isOf(Items.GREEN_STAINED_GLASS_PANE))) lime = i;
        }
        return lime;
    }

    private int countItem(Item item) {
        int c = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.isOf(item)) c += st.getCount();
        }
        return c;
    }

    private static Pattern pat(String re) {
        try { return Pattern.compile(re); } catch (Exception ex) { return Pattern.compile("(?i)price\\D{0,6}([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?"); }
    }

    private static boolean matches(String re, String s) {
        try { return Pattern.compile(re).matcher(s).find(); } catch (Exception ex) { return false; }
    }
}
