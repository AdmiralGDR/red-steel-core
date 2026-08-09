/*
 * RedSteelCore — Aetherium-powered mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package redstone.team.redsteelcore.aetherium;

import org.aetherium.core.compute.StructArena;
import org.aetherium.core.compute.StructField;
import org.aetherium.core.compute.StructLayout;

/**
 * Off-heap кольцевой буфер истории позиций — преемник прежнего серверного {@code LagCompensationEngine}
 * (откат хитбоксов вне зависимости от пинга), переписанный на off-heap примитивы Aetherium вместо
 * связки {@code ConcurrentHashMap<UUID,Track>} + ручной {@code Arena.ofAuto()}.
 *
 * <p><b>Раскладка:</b> один непрерывный {@link StructArena} на {@code tracks × HISTORY} слотов; каждый
 * слот хранит {@code (x,y,z)}. Адрес = {@code (track*HISTORY + slot)} — без хеширования, без объектов.
 * {@link #record} пишет позицию трека в текущий кольцевой слот; {@link #rewind} читает позицию
 * {@code ticksBack} тиков назад (отмотка на пинг = {@code latencyMs/50}).</p>
 *
 * <p>Чистый, loader-agnostic: оперирует только {@code double}-ами, никаких типов Minecraft. Серверная
 * сторона передаёт сюда уже распакованные координаты (см. {@link AnomalyEngine#serverTick()}).</p>
 */
public final class LagCompBuffer implements AutoCloseable {

    /** Глубина истории в тиках (1 секунда при 20 TPS) — перекрывает любой разумный пинг. */
    public static final int HISTORY = 20;
    private static final long MS_PER_TICK = 50L;

    private final int tracks;
    private final StructArena arena;
    private final StructField fx, fy, fz;
    private final boolean[] written;   // заполнен ли (track,slot) — защита от чтения «пустого» прошлого
    private int globalTick;

    public LagCompBuffer(int tracks) {
        this.tracks = tracks;
        StructLayout layout = StructLayout.builder().doubles("x").doubles("y").doubles("z").build();
        this.arena = StructArena.allocate(layout, (long) tracks * HISTORY);
        this.fx = layout.field("x");
        this.fy = layout.field("y");
        this.fz = layout.field("z");
        this.written = new boolean[tracks * HISTORY];
    }

    /** Продвигает глобальный тик (вызывать раз за серверный тик до серии {@link #record}). */
    public void advance() {
        globalTick++;
    }

    /** Записывает текущую позицию трека в кольцевой слот текущего тика. */
    public void record(int track, double x, double y, double z) {
        if (track < 0 || track >= tracks) {
            return;
        }
        int slot = Math.floorMod(globalTick, HISTORY);
        long idx = (long) track * HISTORY + slot;
        arena.setDouble(idx, fx, x);
        arena.setDouble(idx, fy, y);
        arena.setDouble(idx, fz, z);
        written[(int) idx] = true;
    }

    /**
     * Историческая позиция трека {@code ticksBack} тиков назад в {@code out[0..2]}.
     *
     * @return {@code true}, если слот заполнен (история есть); иначе {@code false} и {@code out} не трогается.
     */
    public boolean rewind(int track, int ticksBack, double[] out) {
        if (track < 0 || track >= tracks) {
            return false;
        }
        int back = Math.max(0, Math.min(HISTORY - 1, ticksBack));
        int slot = Math.floorMod(globalTick - back, HISTORY);
        int idx = track * HISTORY + slot;
        if (!written[idx]) {
            return false;
        }
        out[0] = arena.getDouble(idx, fx);
        out[1] = arena.getDouble(idx, fy);
        out[2] = arena.getDouble(idx, fz);
        return true;
    }

    /** Пинг в тиках отката, ограниченный глубиной истории {@code [0, HISTORY-1]}. */
    public static int latencyTicks(int latencyMs) {
        return Math.max(0, Math.min(HISTORY - 1, (int) (latencyMs / MS_PER_TICK)));
    }

    @Override
    public void close() {
        arena.close();
    }
}
