/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.block;

import org.aetherium.core.Capabilities;
import redstone.team.redsteelcore.aetherium.AnomalyEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds each placed Anomaly Core to a slot in the off-heap anomaly field, for the current session only.
 *
 * <p>EN: A slot index is an offset into a {@code StructArena} that is rebuilt every time the process starts,
 * so it is meaningless in a saved world and deliberately <em>not</em> part of {@link
 * org.aetherium.content.MachineState}. Durable data — who owns the core and what it has earned — lives in
 * the machine state and rides the block entity's NBT; this class holds only the volatile half, keyed by
 * block position, and hands out a slot lazily the first time a core ticks in a new session.
 *
 * <p>RU: Индекс слота — это смещение в {@code StructArena}, который пересоздаётся при каждом запуске
 * процесса, поэтому в сохранённом мире он бессмыслен и намеренно НЕ входит в {@code MachineState}. Долговечные
 * данные (владелец и накопленное) лежат в состоянии машины и едут в NBT блок-сущности; здесь — только
 * волатильная половина, по позиции блока, со слотом, выдаваемым лениво при первом тике в новой сессии.
 */
public final class AnomalyCoreSlots {

    private static final AnomalyCoreSlots INSTANCE = new AnomalyCoreSlots();

    public static AnomalyCoreSlots get() {
        return INSTANCE;
    }

    /** Position key → off-heap slot. {@code -1} means the field was full when we asked. */
    private final Map<String, Integer> slots = new ConcurrentHashMap<>();

    private AnomalyCoreSlots() {
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** The slot for this core, allocating one on first use in this session. */
    public int slotFor(int x, int y, int z) {
        // Capabilities.ffm runs the FFM path and falls back on ANY Throwable — including
        // UnsupportedClassVersionError, which a plain catch(RuntimeException) would miss on a JVM started
        // without --enable-preview. the framework ships this; we used to hand-roll the probe.
        return slots.computeIfAbsent(key(x, y, z), k -> Capabilities.ffm(
                () -> AnomalyEngine.get().spawn(x + 0.5, y + 0.5, z + 0.5), () -> -1));
    }

    /**
     * How hot the anomaly burns here, {@code 0} when no slot could be allocated.
     *
     * <p>This runs once a second for every placed core, so it takes the {@link Capabilities#ffmAvailable()}
     * branch (the framework) rather than {@link Capabilities#ffm}: on a JVM started without
     * {@code --enable-preview} the latter would throw and catch an {@code UnsupportedClassVersionError} on
     * every single call, which is the expensive way to keep answering "0".
     */
    public float intensityAt(int x, int y, int z) {
        if (!Capabilities.ffmAvailable()) {
            return 0f;
        }
        int slot = slotFor(x, y, z);
        return slot >= 0 ? Capabilities.ffm(() -> AnomalyEngine.get().intensity(slot), () -> 0f) : 0f;
    }

    /** Release this core's slot — called when the block is removed. */
    public void release(int x, int y, int z) {
        Integer slot = slots.remove(key(x, y, z));
        if (slot != null && slot >= 0) {
            Capabilities.ffm(() -> {
                AnomalyEngine.get().deactivate(slot);
                return null;
            }, () -> null);
        }
    }

    public int boundCount() {
        return slots.size();
    }

    /** Drop every binding — used by the self-test to isolate runs. */
    public void resetForTesting() {
        slots.clear();
    }
}
