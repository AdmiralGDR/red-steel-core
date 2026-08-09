/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.gameplay;

import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The two asymmetric progression faucets from {@code docs/aboutmechanics.txt}, now implementable thanks to
 * the gameplay events.
 *
 * <p>EN: Samurai <em>Focus</em> — "be an untouchable ninja to level up": honourable kills raise a hidden
 * meter, <em>any</em> damage taken resets it, and filling it banks essence. Communist <em>Kinetic
 * Resonance</em> — "industrial chains": mining raises a combo on a timer, and the framework's
 * {@code playerPlaced} flag on {@code onBlockBreak} is the ready-made anti-abuse for silk-touch replanting.
 * State is transient (a session's meters), keyed by player {@link UUID}, and thread-safe — the edge events
 * and the tick may touch it from different threads. Rewards are granted as essence (the mod's single
 * currency), tuned via {@link RedSteelConfig.Mechanics}.
 * RU: Две асимметричные механики из {@code docs/aboutmechanics.txt}, ставшие реализуемыми благодаря
 * событиям the framework (§5). Самураи — «Концентрация»: достойные убийства растят скрытый счётчик, ЛЮБОЙ
 * полученный урон его сбрасывает, заполнение начисляет эссенцию. Коммунисты — «Кинетический Резонанс»:
 * добыча растит комбо на таймере, а флаг {@code playerPlaced} события {@code onBlockBreak} — готовый
 * анти-абуз шёлкового касания. Состояние транзиентное, на игрока, потокобезопасное.
 */
public final class FactionProgress {

    private static final FactionProgress INSTANCE = new FactionProgress();

    public static FactionProgress get() {
        return INSTANCE;
    }

    /** Monotonic server-tick counter, advanced once per tick; the combo timer is measured against it. */
    private final AtomicLong serverTick = new AtomicLong();

    private final ConcurrentHashMap<UUID, Long> focus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Combo> combo = new ConcurrentHashMap<>();

    private record Combo(long count, long deadlineTick) {
    }

    private FactionProgress() {
    }

    /** Advance the tick clock; call once per server tick. */
    public void tick() {
        serverTick.incrementAndGet();
    }

    public long focus(UUID player) {
        return focus.getOrDefault(player, 0L);
    }

    public long combo(UUID player) {
        Combo c = combo.get(player);
        return c == null ? 0L : c.count();
    }

    /** Reset transient meters for a player (on join/leave, so a session starts clean). */
    public void reset(UUID player) {
        focus.remove(player);
        combo.remove(player);
    }

    // --- Focus (samurai) -----------------------------------------------------------------------

    /**
     * A samurai took damage → the meter resets to zero. Called from {@code onEntityDamaged}. Non-samurai
     * and non-players are ignored by the caller.
     */
    public void onDamageTaken(UUID player) {
        focus.remove(player);
    }

    /**
     * A samurai scored a kill. Raises Focus; when it reaches {@code focusMax}, banks essence and resets.
     *
     * @return true if this kill completed the meter (for a caller that wants to play the gong)
     */
    public boolean onHonourableKill(UUID player) {
        FactionManager m = FactionManager.get();
        if (m.faction(player) != Faction.SAMURAI) {
            return false;
        }
        RedSteelConfig.Mechanics mech = RedSteelConfigs.current().mechanics();
        long next = focus.merge(player, mech.focusPerKill(), Long::sum);
        if (next >= mech.focusMax()) {
            focus.remove(player);
            m.addEssence(player, mech.focusReward());
            return true;
        }
        return false;
    }

    // --- Kinetic Resonance (communists) --------------------------------------------------------

    /**
     * A communist mined a block. Grows the combo if it lands within the window and the block was not
     * player-placed; on reaching {@code comboNeeded}, banks essence and resets.
     *
     * @param playerPlaced the framework's flag — a mined block the player themselves placed does not count
     * @return true if this mine completed a combo
     */
    public boolean onResonantMine(UUID player, boolean playerPlaced) {
        FactionManager m = FactionManager.get();
        if (playerPlaced || m.faction(player) != Faction.COMMUNISTS) {
            return false;
        }
        RedSteelConfig.Mechanics mech = RedSteelConfigs.current().mechanics();
        long now = serverTick.get();
        Combo existing = combo.get(player);
        long count = (existing != null && now <= existing.deadlineTick()) ? existing.count() + 1 : 1;
        if (count >= mech.comboNeeded()) {
            combo.remove(player);
            m.addEssence(player, mech.comboReward());
            return true;
        }
        combo.put(player, new Combo(count, now + mech.comboWindowTicks()));
        return false;
    }
}
