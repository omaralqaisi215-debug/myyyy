package dev.donutflipper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class Chat {
    private Chat() {}

    public static void info(String fmt, Object... args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal("\u00a76[Flipper] \u00a7f" + msg), false);
        });
    }
}
