/*
 * RedSteelCore — Aetherium-powered mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package redstone.team.redsteelcore.aetherium;

import org.aetherium.core.tick.AetheriumAsyncTick;

/**
 * «Тяжёлая» физика Аномалий — единственный per-tick обработчик, вынесенный с главного потока сервера
 * на <b>виртуальный поток</b> через {@link AetheriumAsyncTick} (zero-boilerplate DX фреймворка).
 *
 * <p>{@code AetheriumTickEngine} запускает {@link #integrate()} на каждом тике и объединяет результат
 * Sync-барьером до конца 50-мс бюджета (см. {@link AnomalyEngine}). Метод трогает <b>только</b> off-heap
 * данные, которыми владеет {@link AnomalyField} — никакого состояния Minecraft, поэтому параллельный
 * тик свободен от {@code ConcurrentModificationException} и гонок (контракт {@code @AetheriumAsyncTick}).</p>
 *
 * <p><b>Модель:</b> позиция дрейфует по скорости; интенсивность «дышит» как {@code 0.5 + 0.5·sin(age·k)}
 * — детерминированная функция возраста, без аллокаций RNG. Блок-прокси визуализирует именно это
 * off-heap значение (см. {@code AnomalyCoreBlockEntity}).</p>
 */
public final class AnomalyPhysics {

    /** Угловая частота «дыхания» интенсивности (рад/тик). */
    private static final double BREATH = 0.05;

    private final AnomalyField field;

    public AnomalyPhysics(AnomalyField field) {
        this.field = field;
    }

    /** Параллельная фаза тика: линейный проход по непрерывной off-heap памяти (cache-friendly). */
    @AetheriumAsyncTick("anomaly-physics")
    void integrate() {
        final int n = field.count();
        for (int i = 0; i < n; i++) {
            if (!field.active(i)) {
                continue;
            }
            // 1. Дрейф позиции по скорости (интеграл Эйлера за один тик).
            field.setX(i, field.x(i) + field.vx(i));
            field.setY(i, field.y(i) + field.vy(i));
            field.setZ(i, field.z(i) + field.vz(i));

            // 2. Старение + «дыхание» интенсивности (детерминированно, без аллокаций).
            int age = field.age(i) + 1;
            field.setAge(i, age);
            float intensity = (float) (0.5 + 0.5 * Math.sin(age * BREATH));
            field.setIntensity(i, intensity);
        }
    }
}
