package dev.donutflipper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DonutFlipperMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("DonutFlipper");

    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("donutflipper", "main"));
    private static KeyBinding openKey, toggleKey;
    private static boolean greeted;

    @Override
    public void onInitializeClient() {
        Config.load();
        Brain.load();
        CraftCalc.load();

        // F8 opens the menu, F9 toggles the bot (Right Shift is taken by Meteor Client). Rebind in Options > Controls > Key Binds.
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.donutflipper.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.donutflipper.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) greeted = false;
            else if (!greeted) {
                greeted = true;
                Chat.info("Loaded. Press \u00a7eF8\u00a7f for the menu, \u00a7eF9\u00a7f to toggle the bot.");
            }
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new DashboardScreen());
            }
            while (toggleKey.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) Flipper.I.toggle();
            }
            Flipper.I.tick();
        });
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) Flipper.I.onMessage(msg.getString()); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> { Brain.I.save(); Config.save(); });
    }
}
