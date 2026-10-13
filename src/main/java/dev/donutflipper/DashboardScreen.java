package dev.donutflipper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DashboardScreen extends Screen {
    private static final int GOLD = 0xFFFFC840, WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
    private ButtonWidget toggleBtn, dryBtn;

    public DashboardScreen() { super(Text.literal("Donut Flipper")); }

    @Override
    protected void init() {
        int bw = 110, bh = 20, y = this.height - 28, x = this.width / 2 - (bw * 3 + 20) / 2;
        toggleBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { Flipper.I.toggle(); refreshLabels(); }).dimensions(x, y - 24, bw, bh).build());
        dryBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { Config.C.dryRun = !Config.C.dryRun; Config.save(); refreshLabels(); }).dimensions(x + bw + 10, y - 24, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), b -> client.setScreen(new SettingsScreen(this))).dimensions(x + 2 * (bw + 10), y - 24, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset learner"), b -> Brain.I.resetLearning()).dimensions(x, y, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> { Brain.I.save(); Config.save(); }).dimensions(x + bw + 10, y, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(x + 2 * (bw + 10), y, bw, bh).build());
        refreshLabels();
    }

    private void refreshLabels() {
        toggleBtn.setMessage(Text.literal("Bot: " + (Flipper.I.enabled ? "\u00a7aON" : "\u00a7cOFF")));
        dryBtn.setMessage(Text.literal("Dry-run: " + (Config.C.dryRun ? "\u00a7eON" : "\u00a7aOFF")));
    }

    private int line(DrawContext c, String s, int x, int y, int col, int maxW) {
        c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(s, maxW), x, y, col);
        return y + 11;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        Brain b = Brain.I;
        int colW = this.width / 2 - 30;
        int lx = 20, rx = this.width / 2 + 10, y = 14;

        c.drawCenteredTextWithShadow(textRenderer, "Donut Flipper", this.width / 2, 4, GOLD);

        // ---- left column ----
        y = 20;
        y = line(c, "OVERVIEW", lx, y, GOLD, colW);
        y = line(c, "Realized profit: " + Money.fmt(b.realized), lx, y, b.realized >= 0 ? GREEN : RED, colW);
        y = line(c, "Wins " + b.wins + "  Losses " + b.losses + "  Expired " + b.expired + "  Missed " + b.missed, lx, y, WHITE, colW);
        y = line(c, String.format("Recent win rate %.0f%%%s   Bot state: %s", b.recentWinRate() * 100, b.marginBoost() > 0 ? " (margin +5%)" : "", Flipper.I.status()), lx, y, WHITE, colW);
        y = line(c, "Items tracked: " + b.items.size() + "   Buys: " + b.buys, lx, y, WHITE, colW);
        y += 4;
        y = line(c, "LEARNER", lx, y, GOLD, colW);
        y = line(c, "Model updates: " + b.trained, lx, y, WHITE, colW);
        for (int i = 0; i < b.armN.length; i++)
            y = line(c, "Undercut " + (int) (b.undercut(i) * 100) + "%  n=" + b.armN[i] + "  reward " + String.format("%.3f", b.armMean[i]), lx, y, GREY, colW);
        y += 4;
        y = line(c, "HOLDINGS", lx, y, GOLD, colW);
        String[] st = {"inv", "listed", "stale", "craft"};
        int shown = 0;
        for (Brain.Holding h : new ArrayList<>(b.ledger)) {
            if (shown++ >= 6) break;
            y = line(c, h.count + "x " + h.name + " [" + st[Math.max(0, Math.min(3, h.status))] + "] cost " + Money.fmt(h.cost) + (h.listPrice > 0 ? " list " + Money.fmt(h.listPrice) : ""), lx, y, WHITE, colW);
        }
        if (shown == 0) y = line(c, "none", lx, y, GREY, colW);

        // ---- right column ----
        y = 20;
        y = line(c, "BEST CRAFTS (2x2 runs automatically)", rx, y, GOLD, colW);
        CraftCalc.refresh(Config.C.fee);
        int n = 0;
        for (CraftCalc.Recipe r : CraftCalc.ranked()) {
            if (n++ >= 6) break;
            y = line(c, r.output.replace("minecraft:", "") + (r.needsTable() ? " (3x3)" : "") + "  profit " + Money.fmt(r.profit), rx, y, r.profit > 0 ? GREEN : RED, colW);
        }
        if (n == 0) y = line(c, "need more price data - let it scan the AH", rx, y, GREY, colW);
        y += 4;
        y = line(c, "MOST TRACKED ITEMS", rx, y, GOLD, colW);
        List<Map.Entry<String, Brain.Item>> es = new ArrayList<>(b.items.entrySet());
        es.sort((p, q) -> Integer.compare(q.getValue().ah.size(), p.getValue().ah.size()));
        for (int i = 0; i < Math.min(6, es.size()); i++) {
            String k = es.get(i).getKey();
            y = line(c, k.replace("minecraft:", "") + "  fair " + Money.fmt(b.fair(k, 1)) + "  bid " + Money.fmt(b.bid(k)) + "  n=" + b.samples(k), rx, y, WHITE, colW);
        }
        y += 4;
        y = line(c, "RECENT RESULTS", rx, y, GOLD, colW);
        for (int i = Math.max(0, b.log.size() - 6); i < b.log.size(); i++) {
            String s = b.log.get(i);
            y = line(c, s, rx, y, s.startsWith("WIN") ? GREEN : s.startsWith("LOSS") ? RED : GREY, colW);
        }
    }
}
