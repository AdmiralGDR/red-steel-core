/*
 * RedSteelCore — Aetherium-powered mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package redstone.team.redsteelcore.aetherium;

import org.aetherium.core.tick.AetheriumTickEngine;
import org.aetherium.core.tick.TickReport;

/**
 * Фасад «100% Aetherium-логики» мода: владеет off-heap состоянием ({@link AnomalyField}),
 * движком асинхронного тика ({@link AetheriumTickEngine}) и буфером lag-compensation
 * ({@link LagCompBuffer}). Это единственный мост, который дёргает <b>тонкая NeoForge-обёртка</b>
 * (блок-прокси + серверный драйвер тика) — сам класс не ссылается ни на один тип Minecraft.
 *
 * <p><b>Поток данных за один серверный тик</b> ({@link #serverTick()}, главный поток):
 * (1) продвинуть кольцо lag-comp и записать текущие позиции активных аномалий (история для отката);
 * (2) {@code engine.tick()} — физика {@link AnomalyPhysics} уходит на виртуальный поток и
 * объединяется Sync-барьером в пределах 50 мс; главный поток припаркован, поэтому spawn/integrate
 * не пересекаются. Итог тика фиксируется в {@link #lastReport()} для телеметрии.</p>
 */
public final class AnomalyEngine {

    private static volatile AnomalyEngine instance;

    private final AnomalyField field;
    private final LagCompBuffer lagComp;
    private final AetheriumTickEngine tickEngine;

    private long serverTicks;
    private TickReport lastReport;

    private AnomalyEngine() {
        this.field = new AnomalyField();
        this.lagComp = new LagCompBuffer(AnomalyField.CAPACITY);
        // Zero-boilerplate: рефлексивно регистрируем все @AetheriumAsyncTick-методы держателя.
        this.tickEngine = new AetheriumTickEngine().registerAnnotated(new AnomalyPhysics(field));
    }

    /** Лениво-инициализируемый синглтон (double-checked). */
    public static AnomalyEngine get() {
        AnomalyEngine local = instance;
        if (local == null) {
            synchronized (AnomalyEngine.class) {
                local = instance;
                if (local == null) {
                    local = new AnomalyEngine();
                    instance = local;
                }
            }
        }
        return local;
    }

    // --- API для тонкой NeoForge-обёртки (главный поток сервера, между тиками) ---

    /**
     * Привязывает новую аномалию к блоку-манифестации со стартовой позицией {@code (x,y,z)}.
     * Задаёт лёгкий детерминированный дрейф (чтобы история lag-comp менялась наглядно).
     *
     * @return стабильный индекс слота в off-heap поле, либо -1 если буфер полон.
     */
    public int spawn(double x, double y, double z) {
        // Малый детерминированный дрейф из позиции — без аллокаций RNG.
        long h = Double.doubleToLongBits(x) * 31 ^ Double.doubleToLongBits(z);
        double vx = ((h & 0xFF) / 255.0 - 0.5) * 0.01;
        double vz = (((h >> 8) & 0xFF) / 255.0 - 0.5) * 0.01;
        return field.spawn(x, y, z, vx, 0.0, vz, 1.0f, /*owner*/ 0);
    }

    /** Освобождает слот (блок снесён). Индексы прочих слотов остаются валидными. */
    public void deactivate(int slot) {
        if (slot >= 0) {
            field.deactivate(slot);
        }
    }

    /** Текущая off-heap интенсивность слота [0..1] — её читает блок-прокси для визуала. */
    public float intensity(int slot) {
        if (slot < 0 || slot >= field.count() || !field.active(slot)) {
            return 0f;
        }
        return field.intensity(slot);
    }

    /** Историческая позиция слота {@code ticksBack} тиков назад (откат хитбоксов). */
    public boolean rewind(int slot, int ticksBack, double[] out) {
        return lagComp.rewind(slot, ticksBack, out);
    }

    /** Один серверный тик: снимок истории → асинхронная физика под Sync-барьером. Не бросает. */
    public TickReport serverTick() {
        serverTicks++;
        // 1. Снимок текущих позиций в off-heap кольцо lag-comp (до интегрирования).
        lagComp.advance();
        int n = field.count();
        for (int i = 0; i < n; i++) {
            if (field.active(i)) {
                lagComp.record(i, field.x(i), field.y(i), field.z(i));
            }
        }
        // 2. Тяжёлая физика на виртуальном потоке + Sync-барьер (главный поток ждёт здесь).
        lastReport = tickEngine.tick();
        return lastReport;
    }

    public AnomalyField field()       { return field; }
    public long serverTicks()         { return serverTicks; }
    public TickReport lastReport()    { return lastReport; }
    public int taskCount()            { return tickEngine.taskCount(); }
    public long offHeapBytes()        { return field.byteSize(); }

    /** Освобождение нативной памяти на остановке сервера (детерминированно). */
    public synchronized void shutdown() {
        field.close();
        lagComp.close();
        instance = null;
    }
}
