package dev.donutflipper;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Single ordered queue for everything the addon does. Commands are never sent closer than
 * MIN_CMD_GAP ms apart (plus random jitter) and clicks get human-ish randomized delays.
 */
public final class Pacer {
    private record Task(String cmd, Runnable run, int min, int max) {}

    public static volatile int MIN_CMD_GAP = 300;

    private static final ArrayDeque<Task> Q = new ArrayDeque<>();
    private static final Random R = new Random();
    private static long readyAt, lastCmd;
    private static boolean armed;

    private Pacer() {}

    public static void command(String c) { Q.add(new Task(c, null, 0, 0)); }

    public static void action(Runnable r, int min, int max) { Q.add(new Task(null, r, min, Math.max(min, max))); }

    public static boolean idle() { return Q.isEmpty(); }

    public static void clear() { Q.clear(); armed = false; }

    public static void tick() {
        Task t = Q.peek();
        if (t == null) { armed = false; return; }
        long now = System.currentTimeMillis();
        if (!armed) {
            int d = t.cmd != null ? 40 + R.nextInt(120) : t.min + R.nextInt(t.max - t.min + 1);
            readyAt = now + d;
            armed = true;
        }
        if (now < readyAt) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (t.cmd != null) {
            if (now < lastCmd + Math.max(300, MIN_CMD_GAP)) return;
            if (mc.getNetworkHandler() == null) return;
            Q.poll();
            armed = false;
            mc.getNetworkHandler().sendChatCommand(t.cmd);
            lastCmd = now + R.nextInt(120);
        } else {
            Q.poll();
            armed = false;
            t.run.run();
        }
    }
}
