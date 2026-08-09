/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.config;

import org.aetherium.config.ConfigStore;
import org.aetherium.network.Tree;
import org.aetherium.network.TreeNode;
import redstone.team.redsteelcore.faction.Faction;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects {@link RedSteelConfig} to and from the framework's {@link TreeNode} document model.
 *
 * <p>EN: The mod no longer ships its own JSON reader/writer/watcher — the framework's {@code aetherium-config}
 * {@link ConfigStore} owns the text, atomic writes and hot-reload. This
 * class is all that remains mod-specific: the shape of our document. Reading is defensive — a missing or
 * wrong-typed field falls back to its default, an unknown faction
 * id is dropped, so a partly-broken file still yields a usable configuration rather than a crash.
 * RU: Мод больше не возит свой JSON-читатель/писатель/наблюдатель — этим владеет {@code aetherium-config}
 * {@link ConfigStore} (текст, атомарная запись, горячая перезагрузка; отзыв §7, реализовано теперь).
 * Здесь остаётся только специфика мода — форма документа. Чтение защитное: отсутствующее или неверно
 * типизированное поле заменяется дефолтом, неизвестная фракция отбрасывается.
 */
public final class RedSteelConfigCodec implements ConfigStore.Codec<RedSteelConfig> {

    /** Schema version, so a future migration can tell old files apart. */
    public static final long SCHEMA_VERSION = 2L;

    private static final String HEADER =
            "RedSteelCore server configuration. Edit and save — the running server reloads it atomically. "
            + "An invalid file is rejected whole and the previous values stay live. You can also edit this "
            + "in-game (faction screen → server settings) or with /redsteel config.";

    @Override
    public TreeNode toTree(RedSteelConfig config) {
        Tree.ObjectBuilder factions = Tree.object();
        for (Faction f : Faction.values()) {
            factions.put(f.id(), Tree.object()
                    .put("display_name", config.displayName(f))
                    .put("color", toHex(config.color(f)))
                    .build());
        }

        Tree.ObjectBuilder items = Tree.object();
        for (Map.Entry<String, Faction> e : config.restrictedItems().entrySet()) {
            items.put(e.getKey(), e.getValue().id());
        }

        RedSteelConfig.Mechanics m = config.mechanics();
        return Tree.object()
                .put("_comment", HEADER)
                .put("version", SCHEMA_VERSION)
                .put("factions", factions.build())
                .put("anomaly_core", Tree.object()
                        .put("energy_per_essence", config.energyPerEssence())
                        .build())
                .put("rules", Tree.object()
                        .put("territory_protection", config.territoryProtection())
                        .put("item_restrictions", config.itemRestrictions())
                        .put("friendly_fire_protection", config.friendlyFireProtection())
                        .build())
                .put("mechanics", Tree.object()
                        .put("focus_per_kill", m.focusPerKill())
                        .put("focus_max", m.focusMax())
                        .put("focus_reward", m.focusReward())
                        .put("combo_needed", m.comboNeeded())
                        .put("combo_window_ticks", m.comboWindowTicks())
                        .put("combo_reward", m.comboReward())
                        .put("honourable_kills_only", m.honourableKillsOnly())
                        .build())
                .put("restricted_items", items.build())
                .build();
    }

    @Override
    public RedSteelConfig fromTree(TreeNode root) {
        RedSteelConfig defaults = RedSteelConfig.defaults();
        if (!(root instanceof TreeNode.Obj o)) {
            return defaults;
        }

        Map<Faction, String> names = new EnumMap<>(Faction.class);
        Map<Faction, Integer> colors = new EnumMap<>(Faction.class);
        TreeNode factionsNode = o.get("factions");
        for (Faction f : Faction.values()) {
            names.put(f, f.defaultDisplayName());
            colors.put(f, f.defaultColor());
            if (factionsNode instanceof TreeNode.Obj factions
                    && factions.get(f.id()) instanceof TreeNode.Obj entry) {
                names.put(f, entry.getString("display_name", f.defaultDisplayName()));
                colors.put(f, readColor(entry.get("color"), f));
            }
        }

        long energy = defaults.energyPerEssence();
        if (o.get("anomaly_core") instanceof TreeNode.Obj core) {
            energy = core.getLong("energy_per_essence", energy);
        }

        boolean territory = defaults.territoryProtection();
        boolean items = defaults.itemRestrictions();
        boolean friendlyFire = defaults.friendlyFireProtection();
        if (o.get("rules") instanceof TreeNode.Obj rules) {
            territory = rules.getBool("territory_protection", territory);
            items = rules.getBool("item_restrictions", items);
            friendlyFire = rules.getBool("friendly_fire_protection", friendlyFire);
        }

        RedSteelConfig.Mechanics mech = readMechanics(o.get("mechanics"));

        Map<String, Faction> restricted = new LinkedHashMap<>();
        if (o.get("restricted_items") instanceof TreeNode.Obj table) {
            for (Map.Entry<String, TreeNode> e : table.entries().entrySet()) {
                if (e.getValue() instanceof TreeNode.Str owner) {
                    Faction faction = Faction.byId(owner.value());
                    if (faction != Faction.NEUTRAL) {
                        restricted.put(e.getKey(), faction);
                    }
                }
            }
        } else {
            restricted.putAll(defaults.restrictedItems());
        }

        return new RedSteelConfig(names, colors, energy, restricted, territory, items, friendlyFire, mech);
    }

    private static RedSteelConfig.Mechanics readMechanics(TreeNode node) {
        RedSteelConfig.Mechanics d = RedSteelConfig.Mechanics.defaults();
        if (!(node instanceof TreeNode.Obj m)) {
            return d;
        }
        return new RedSteelConfig.Mechanics(
                m.getLong("focus_per_kill", d.focusPerKill()),
                m.getLong("focus_max", d.focusMax()),
                m.getLong("focus_reward", d.focusReward()),
                m.getLong("combo_needed", d.comboNeeded()),
                m.getLong("combo_window_ticks", d.comboWindowTicks()),
                m.getLong("combo_reward", d.comboReward()),
                m.getBool("honourable_kills_only", d.honourableKillsOnly()));
    }

    private static int readColor(TreeNode node, Faction faction) {
        if (node instanceof TreeNode.I64 i) {
            return (int) (i.value() & 0xFFFFFF);
        }
        if (node instanceof TreeNode.Str s) {
            String text = s.value().trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            try {
                return (int) (Long.parseLong(text, 16) & 0xFFFFFF);
            } catch (NumberFormatException malformed) {
                return faction.defaultColor();
            }
        }
        return faction.defaultColor();
    }

    private static String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
