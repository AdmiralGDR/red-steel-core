/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.config;

import redstone.team.redsteelcore.faction.Faction;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of every server-tunable value in the mod.
 *
 * <p>EN: The single source of truth an administrator edits — by hand in {@code config/redsteelcore.json},
 * in-game through {@code client/ConfigScreen}, or via {@code /redsteel config}. All three produce a new
 * {@code RedSteelConfig} and hand it to the framework {@code org.aetherium.config.ConfigStore}; nothing
 * mutates a live instance, so a reload is never observed half-applied (no implicit mutation, ACID). The
 * canonical constructor is the validation boundary: every field is copied, clamped, or replaced by its
 * default, so an invalid {@code RedSteelConfig} cannot exist — invalid states are unrepresentable.
 * RU: Единственный источник истины для администратора — файл {@code config/redsteelcore.json}, экран
 * {@code client/ConfigScreen} или {@code /redsteel config}. Все три создают НОВЫЙ {@code RedSteelConfig} и
 * отдают его фреймворковому {@code ConfigStore}; живой экземпляр не мутируется. Канонический конструктор —
 * граница валидации: любое поле копируется, ограничивается диапазоном или заменяется дефолтом.
 *
 * @param displayNames           per-faction label shown in the UI
 * @param colors                 per-faction accent colour, packed {@code 0xRRGGBB}
 * @param energyPerEssence       anomaly-core energy required to mint one Essence
 * @param restrictedItems        item id → the only faction allowed to wield it
 * @param territoryProtection    enforce claimed-chunk protection against hostile factions
 * @param itemRestrictions       enforce the {@code restrictedItems} table
 * @param friendlyFireProtection veto attacks between members of the same faction
 * @param mechanics              tuning for the Focus (samurai) and Kinetic Resonance (communist) faucets
 */
public record RedSteelConfig(
        Map<Faction, String> displayNames,
        Map<Faction, Integer> colors,
        long energyPerEssence,
        Map<String, Faction> restrictedItems,
        boolean territoryProtection,
        boolean itemRestrictions,
        boolean friendlyFireProtection,
        Mechanics mechanics) {

    /** Hard bounds for {@link #energyPerEssence} — a 0 or negative value would divide-by-zero the core. */
    public static final long MIN_ENERGY_PER_ESSENCE = 1L;
    public static final long MAX_ENERGY_PER_ESSENCE = 1_000_000L;

    /** Display names are labels, not essays — long strings would overflow every UI row. */
    public static final int MAX_DISPLAY_NAME = 48;

    /** A sane ceiling on the restriction table, so a malformed file cannot exhaust memory. */
    public static final int MAX_RESTRICTED_ITEMS = 512;

    /**
     * Tuning for the two asymmetric progression faucets from {@code docs/aboutmechanics.txt}.
     *
     * <p>Samurai <em>Focus</em>: +{@link #focusPerKill} on an honourable kill, reset to 0 on any damage
     * taken; at {@link #focusMax} the player earns {@link #focusReward} essence and the meter resets.
     * Communist <em>Kinetic Resonance</em>: mining a non-player-placed block grows a combo within
     * {@link #comboWindowTicks} ticks; at {@link #comboNeeded} the player earns {@link #comboReward} essence.
     *
     * @param focusPerKill     focus gained per honourable kill
     * @param focusMax         focus needed to bank a reward (and the meter ceiling)
     * @param focusReward      essence granted when Focus fills
     * @param comboNeeded      resonance combo length needed to bank a reward
     * @param comboWindowTicks server ticks allowed between mines before the combo resets (20/s)
     * @param comboReward      essence granted when a combo completes
     * @param honourableKillsOnly if true, only killing another faction's member counts toward Focus
     */
    public record Mechanics(
            long focusPerKill,
            long focusMax,
            long focusReward,
            long comboNeeded,
            long comboWindowTicks,
            long comboReward,
            boolean honourableKillsOnly) {

        public Mechanics {
            focusPerKill = clamp(focusPerKill, 1, 100);
            focusMax = clamp(focusMax, 1, 100_000);
            focusReward = clamp(focusReward, 0, 1_000_000);
            comboNeeded = clamp(comboNeeded, 1, 100_000);
            comboWindowTicks = clamp(comboWindowTicks, 1, 1_200_000);
            comboReward = clamp(comboReward, 0, 1_000_000);
        }

        public static Mechanics defaults() {
            return new Mechanics(15, 100, 10, 10, 300, 10, true);
        }

        private static long clamp(long v, long lo, long hi) {
            return Math.min(hi, Math.max(lo, v));
        }
    }

    public RedSteelConfig {
        Objects.requireNonNull(displayNames, "displayNames");
        Objects.requireNonNull(colors, "colors");
        Objects.requireNonNull(restrictedItems, "restrictedItems");
        if (mechanics == null) {
            mechanics = Mechanics.defaults();
        }

        Map<Faction, String> names = new EnumMap<>(Faction.class);
        Map<Faction, Integer> palette = new EnumMap<>(Faction.class);
        for (Faction f : Faction.values()) {
            names.put(f, sanitizeName(displayNames.get(f), f.defaultDisplayName()));
            Integer raw = colors.get(f);
            palette.put(f, raw == null ? f.defaultColor() : (raw & 0xFFFFFF));
        }
        displayNames = Collections.unmodifiableMap(names);
        colors = Collections.unmodifiableMap(palette);

        energyPerEssence = Math.min(MAX_ENERGY_PER_ESSENCE, Math.max(MIN_ENERGY_PER_ESSENCE, energyPerEssence));

        Map<String, Faction> items = new LinkedHashMap<>();
        for (Map.Entry<String, Faction> e : restrictedItems.entrySet()) {
            if (items.size() >= MAX_RESTRICTED_ITEMS) {
                break;
            }
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (!key.isEmpty() && e.getValue() != null && e.getValue() != Faction.NEUTRAL) {
                items.put(key, e.getValue());
            }
        }
        restrictedItems = Collections.unmodifiableMap(items);
    }

    private static String sanitizeName(String candidate, String fallback) {
        if (candidate == null) {
            return fallback;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return trimmed.length() <= MAX_DISPLAY_NAME ? trimmed : trimmed.substring(0, MAX_DISPLAY_NAME);
    }

    /** The factory configuration written out on first launch. */
    public static RedSteelConfig defaults() {
        Map<Faction, String> names = new EnumMap<>(Faction.class);
        Map<Faction, Integer> colors = new EnumMap<>(Faction.class);
        for (Faction f : Faction.values()) {
            names.put(f, f.defaultDisplayName());
            colors.put(f, f.defaultColor());
        }
        Map<String, Faction> items = new LinkedHashMap<>();
        items.put("redsteelcore:samurai_spirit_katana", Faction.SAMURAI);
        items.put("redsteelcore:commune_plasma_blade", Faction.COMMUNISTS);
        return new RedSteelConfig(names, colors, 64L, items, true, true, true, Mechanics.defaults());
    }

    /**
     * Re-run validation over an already-built snapshot. Handed to the framework
     * {@code ConfigStore.validate(...)} as its normalizer; because the canonical constructor is the
     * validation boundary, this is a defensive round-trip that guarantees a loaded snapshot is in range.
     */
    public RedSteelConfig revalidated() {
        return new RedSteelConfig(displayNames, colors, energyPerEssence, restrictedItems,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }

    // --- reads ---------------------------------------------------------------------------------

    public String displayName(Faction faction) {
        return displayNames.getOrDefault(faction, faction.defaultDisplayName());
    }

    public int color(Faction faction) {
        return colors.getOrDefault(faction, faction.defaultColor());
    }

    /** The only faction allowed to wield {@code itemId}; {@link Faction#NEUTRAL} means "unrestricted". */
    public Faction restrictionOwner(String itemId) {
        return restrictedItems.getOrDefault(itemId, Faction.NEUTRAL);
    }

    // --- copy-on-write edits (used by the in-game editor and /redsteel config) ------------------

    public RedSteelConfig withDisplayName(Faction faction, String name) {
        Map<Faction, String> names = new EnumMap<>(displayNames);
        names.put(faction, name);
        return new RedSteelConfig(names, colors, energyPerEssence, restrictedItems,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withColor(Faction faction, int rgb) {
        Map<Faction, Integer> palette = new EnumMap<>(colors);
        palette.put(faction, rgb & 0xFFFFFF);
        return new RedSteelConfig(displayNames, palette, energyPerEssence, restrictedItems,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withEnergyPerEssence(long value) {
        return new RedSteelConfig(displayNames, colors, value, restrictedItems,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withTerritoryProtection(boolean on) {
        return new RedSteelConfig(displayNames, colors, energyPerEssence, restrictedItems,
                on, itemRestrictions, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withItemRestrictions(boolean on) {
        return new RedSteelConfig(displayNames, colors, energyPerEssence, restrictedItems,
                territoryProtection, on, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withFriendlyFireProtection(boolean on) {
        return new RedSteelConfig(displayNames, colors, energyPerEssence, restrictedItems,
                territoryProtection, itemRestrictions, on, mechanics);
    }

    public RedSteelConfig withRestrictedItem(String itemId, Faction owner) {
        Map<String, Faction> items = new LinkedHashMap<>(restrictedItems);
        if (owner == null || owner == Faction.NEUTRAL) {
            items.remove(itemId);
        } else {
            items.put(itemId, owner);
        }
        return new RedSteelConfig(displayNames, colors, energyPerEssence, items,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }

    public RedSteelConfig withMechanics(Mechanics mechanics) {
        return new RedSteelConfig(displayNames, colors, energyPerEssence, restrictedItems,
                territoryProtection, itemRestrictions, friendlyFireProtection, mechanics);
    }
}
