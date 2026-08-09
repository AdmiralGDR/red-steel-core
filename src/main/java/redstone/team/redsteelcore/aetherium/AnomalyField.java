/*
 * RedSteelCore — Aetherium-powered mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See <https://www.gnu.org/licenses/>.
 *
 * NOTE: this mod builds on the Aetherium Framework (AGPL-3.0). Offered to users over a network
 * (e.g. a game server), it must provide its complete corresponding source under AGPL-3.0.
 */
package redstone.team.redsteelcore.aetherium;

import org.aetherium.core.compute.StructArena;
import org.aetherium.core.compute.StructField;
import org.aetherium.core.compute.StructLayout;

/**
 * Data-Oriented (Array-of-Structs, off-heap) хранилище всех активных Аномалий — преемник прежнего
 * heap-овского {@code AnomalyStore} (SoA на Java-массивах) и {@code HashMap<BlockPos,AnomalyData>}.
 *
 * <p><b>Что изменилось при пивоте на Aetherium:</b> вместо параллельных Java-массивов примитивов в
 * куче все «горячие» поля каждой аномалии лежат <b>непрерывно в нативной памяти</b> через
 * {@link StructArena} фреймворка (FFM / Project Panama). Обход тикером идёт линейно по памяти —
 * близкий к 100% L1/L2 cache-hit, ноль Java-объектов на сущность, ноль давления на GC. Доступ к
 * полю — один bounds-checked {@code segment.get(layout, i*stride + offset)} (O(1)).</p>
 *
 * <p><b>Контракт потока:</b> {@link #spawn} вызывается на главном потоке сервера между тиками;
 * интегрирование физики ({@link AnomalyPhysics}) — на виртуальном потоке внутри Sync-барьера
 * {@code AetheriumTickEngine}, когда главный поток припаркован в {@code tick()} (см. {@link
 * AnomalyEngine}). Поэтому spawn и integrate никогда не пересекаются — блокировок не требуется.</p>
 */
public final class AnomalyField implements AutoCloseable {

    /** Максимум одновременных аномалий (фиксированная off-heap ёмкость). */
    public static final int CAPACITY = 1024;

    private final StructLayout layout;
    private final StructArena arena;

    // Дескрипторы полей (смещения вычислены builder-ом один раз) — горячий путь без хеширования.
    private final StructField fx, fy, fz;       // позиция
    private final StructField fvx, fvy, fvz;    // скорость (дрейф)
    private final StructField fIntensity;       // текущая интенсивность [0..1], модулируется физикой
    private final StructField fAge;             // прожито тиков
    private final StructField fActive;          // 0/1 — занят ли слот
    private final StructField fOwner;           // фракция-владелец (ordinal), холодное поле

    private int count;

    public AnomalyField() {
        this.layout = StructLayout.builder()
                .doubles("x").doubles("y").doubles("z")
                .doubles("vx").doubles("vy").doubles("vz")
                .floats("intensity")
                .ints("age").ints("active").ints("owner")
                .build();
        this.arena = StructArena.allocate(layout, CAPACITY);
        this.fx = layout.field("x");
        this.fy = layout.field("y");
        this.fz = layout.field("z");
        this.fvx = layout.field("vx");
        this.fvy = layout.field("vy");
        this.fvz = layout.field("vz");
        this.fIntensity = layout.field("intensity");
        this.fAge = layout.field("age");
        this.fActive = layout.field("active");
        this.fOwner = layout.field("owner");
    }

    /**
     * Резервирует слот для новой аномалии. Слоты стабильны (без swap-удаления): снос помечает
     * {@code active=0}, что сохраняет валидность индексов, удерживаемых блоками-прокси.
     *
     * @return индекс слота, либо -1 если хранилище заполнено.
     */
    public synchronized int spawn(double x, double y, double z,
                                  double vx, double vy, double vz,
                                  float intensity, int owner) {
        if (count >= CAPACITY) {
            return -1;
        }
        int i = count++;
        arena.setDouble(i, fx, x);
        arena.setDouble(i, fy, y);
        arena.setDouble(i, fz, z);
        arena.setDouble(i, fvx, vx);
        arena.setDouble(i, fvy, vy);
        arena.setDouble(i, fvz, vz);
        arena.setFloat(i, fIntensity, intensity);
        arena.setInt(i, fAge, 0);
        arena.setInt(i, fActive, 1);
        arena.setInt(i, fOwner, owner);
        return i;
    }

    /** Число занятых слотов (верхняя граница обхода тикером). */
    public int count() {
        return count;
    }

    /** Полный размер off-heap буфера в байтах (для диагностики/телеметрии). */
    public long byteSize() {
        return arena.byteSize();
    }

    // --- Горячие типизированные аксессоры (используются физикой и блоками-прокси) ---

    public boolean active(int i)      { return arena.getInt(i, fActive) != 0; }
    public void deactivate(int i)     { arena.setInt(i, fActive, 0); }

    public double x(int i)            { return arena.getDouble(i, fx); }
    public double y(int i)            { return arena.getDouble(i, fy); }
    public double z(int i)            { return arena.getDouble(i, fz); }
    public void setX(int i, double v) { arena.setDouble(i, fx, v); }
    public void setY(int i, double v) { arena.setDouble(i, fy, v); }
    public void setZ(int i, double v) { arena.setDouble(i, fz, v); }

    public double vx(int i)           { return arena.getDouble(i, fvx); }
    public double vy(int i)           { return arena.getDouble(i, fvy); }
    public double vz(int i)           { return arena.getDouble(i, fvz); }

    public float intensity(int i)            { return arena.getFloat(i, fIntensity); }
    public void setIntensity(int i, float v) { arena.setFloat(i, fIntensity, v); }

    public int age(int i)             { return arena.getInt(i, fAge); }
    public void setAge(int i, int v)  { arena.setInt(i, fAge, v); }

    public int owner(int i)           { return arena.getInt(i, fOwner); }

    @Override
    public void close() {
        arena.close();
    }
}
