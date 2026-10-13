package dev.donutflipper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.util.List;

/** Paged settings editor: booleans are toggle buttons, everything else is a text box. */
public class SettingsScreen extends Screen {
    private static final int PER_PAGE = 9;
    private final Screen parent;
    private final List<Field> opts = Config.options();
    private int page;

    public SettingsScreen(Screen parent) {
        super(Text.literal("Donut Flipper - Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int pages = (opts.size() + PER_PAGE - 1) / PER_PAGE;
        int start = page * PER_PAGE;
        int fx = this.width / 2 + 10, fw = Math.min(260, this.width / 2 - 30), y = 36;
        for (int i = start; i < Math.min(opts.size(), start + PER_PAGE); i++) {
            Field f = opts.get(i);
            try {
                if (f.getType() == boolean.class) {
                    ButtonWidget btn = ButtonWidget.builder(label(f), b -> {
                        try { f.setBoolean(Config.C, !f.getBoolean(Config.C)); b.setMessage(label(f)); Config.save(); } catch (Exception ignored) {}
                    }).dimensions(fx, y, 100, 20).build();
                    addDrawableChild(btn);
                } else {
                    TextFieldWidget tf = new TextFieldWidget(textRenderer, fx, y, fw, 20, Text.literal(f.getName()));
                    tf.setMaxLength(512);
                    tf.setText(String.valueOf(f.get(Config.C)));
                    tf.setChangedListener(s -> apply(f, s));
                    addDrawableChild(tf);
                }
            } catch (Exception ignored) {}
            y += 26;
        }
        int by = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), b -> { page = Math.max(0, page - 1); clearAndInit(); }).dimensions(this.width / 2 - 155, by, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> { page = Math.min(pages - 1, page + 1); clearAndInit(); }).dimensions(this.width / 2 - 80, by, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(this.width / 2 + 5, by, 150, 20).build());
    }

    private Text label(Field f) {
        try { return Text.literal(f.getBoolean(Config.C) ? "\u00a7aON" : "\u00a7cOFF"); } catch (Exception e) { return Text.literal("?"); }
    }

    private void apply(Field f, String s) {
        try {
            if (f.getType() == int.class) f.setInt(Config.C, Integer.parseInt(s.trim()));
            else if (f.getType() == double.class) f.setDouble(Config.C, Double.parseDouble(s.trim()));
            else if (f.getType() == String.class) f.set(Config.C, s);
        } catch (Exception ignored) {} // half-typed numbers are simply ignored
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        c.drawCenteredTextWithShadow(textRenderer, "Settings  (page " + (page + 1) + "/" + ((opts.size() + PER_PAGE - 1) / PER_PAGE) + ")", this.width / 2, 14, 0xFFFFC840);
        int y = 42, start = page * PER_PAGE;
        for (int i = start; i < Math.min(opts.size(), start + PER_PAGE); i++) {
            c.drawTextWithShadow(textRenderer, opts.get(i).getName(), 20, y, 0xFFFFFFFF);
            y += 26;
        }
        c.drawCenteredTextWithShadow(textRenderer, "Command gap is locked to >= 300 ms", this.width / 2, this.height - 42, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        Config.save();
        client.setScreen(parent);
    }
}
