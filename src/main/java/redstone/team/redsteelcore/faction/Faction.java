/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.faction;

/**
 * The two warring powers of Red Steel Core — a loader-agnostic, pure enum (no Minecraft type).
 *
 * <p>EN: {@link #SAMURAI} (the Order — tradition, precision, timing) and {@link #COMMUNISTS} (the Cyber
 * Commune — industry, machines, throughput), plus {@link #NEUTRAL} for the unaligned. Hostility is
 * symmetric and exists only between the two non-neutral factions; it drives the territory- and
 * item-restriction rules enforced through the {@code aetherium-edge} gameplay PAL.
 * RU: {@link #SAMURAI} (Орден — традиция, точность, тайминг) и {@link #COMMUNISTS} (Кибер-Коммуна —
 * индустрия, машины, поток), плюс {@link #NEUTRAL} для непримкнувших. Враждебность симметрична и
 * существует только между двумя не-нейтральными фракциями; она управляет правилами защиты территорий и
 * блокировки предметов через геймплейный PAL {@code aetherium-edge}.
 *
 * <p><strong>The set of factions is fixed in the type system</strong> (invalid states are unrepresentable)
 * — only the <em>labels</em> (display name, accent colour)
 * are server-configurable, and those live in {@code config/RedSteelConfig}, never here. This enum stays a
 * pure, dependency-free value so it is trivially unit-testable and free of hidden global reads.
 */
public enum Faction {

    NEUTRAL("neutral", "Без фракции", 0x95A5A6),
    SAMURAI("samurai", "Самураи", 0xE74C3C),
    COMMUNISTS("communists", "Коммунисты", 0xC0392B);

    private final String id;
    private final String defaultDisplayName;
    private final int defaultColor;

    Faction(String id, String defaultDisplayName, int defaultColor) {
        this.id = id;
        this.defaultDisplayName = defaultDisplayName;
        this.defaultColor = defaultColor;
    }

    /** Stable, persistence- and network-safe identifier. Never localized, never configurable. */
    public String id() {
        return id;
    }

    /** Factory label shown when the server admin has not overridden it in the config. */
    public String defaultDisplayName() {
        return defaultDisplayName;
    }

    /** Factory accent colour, packed {@code 0xRRGGBB}, when not overridden in the config. */
    public int defaultColor() {
        return defaultColor;
    }

    /** Symmetric hostility — true only between the two distinct non-neutral factions. */
    public boolean isHostileTo(Faction other) {
        if (this == NEUTRAL || other == NEUTRAL) {
            return false;
        }
        return this != other;
    }

    /** The two playable sides, in menu order (excludes {@link #NEUTRAL}). */
    public static Faction[] playable() {
        return new Faction[] {SAMURAI, COMMUNISTS};
    }

    /** Resolve by stable id; unknown/blank → {@link #NEUTRAL}. */
    public static Faction byId(String id) {
        if (id != null) {
            for (Faction f : values()) {
                if (f.id.equals(id)) {
                    return f;
                }
            }
        }
        return NEUTRAL;
    }
}
