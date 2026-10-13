package dev.donutflipper;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Keys {
    private Keys() {}

    public static String id(ItemStack st) {
        return Registries.ITEM.getId(st.getItem()).toString();
    }

    /** Identity of an item for pricing: registry id + custom name + enchantments. */
    public static String of(ItemStack st) {
        StringBuilder sb = new StringBuilder(id(st));
        Text cn = st.get(DataComponentTypes.CUSTOM_NAME);
        if (cn != null) sb.append("|n:").append(cn.getString());
        List<String> e = new ArrayList<>();
        add(e, st.get(DataComponentTypes.ENCHANTMENTS));
        add(e, st.get(DataComponentTypes.STORED_ENCHANTMENTS));
        Collections.sort(e);
        if (!e.isEmpty()) sb.append("|e:").append(String.join(",", e));
        return sb.toString();
    }

    private static void add(List<String> out, ItemEnchantmentsComponent c) {
        if (c == null) return;
        for (RegistryEntry<Enchantment> en : c.getEnchantments()) {
            en.getKey().ifPresent(k -> out.add(k.getValue().getPath() + ":" + c.getLevel(en)));
        }
    }

    public static List<String> lore(ItemStack st) {
        LoreComponent l = st.get(DataComponentTypes.LORE);
        if (l == null) return List.of();
        List<String> r = new ArrayList<>();
        for (Text t : l.lines()) r.add(t.getString());
        return r;
    }

    public static Item item(String idOrKey) {
        String id = idOrKey.contains("|") ? idOrKey.substring(0, idOrKey.indexOf('|')) : idOrKey;
        return Registries.ITEM.get(Identifier.of(id));
    }
}
