/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.block;

import org.aetherium.content.AetheriumBlock;

/**
 * The Anomaly Core — declared in <strong>one annotation</strong>, zero JSON, zero registry code.
 *
 * <p>EN: A {@link AetheriumBlock#behavior()} of {@link AnomalyCoreLogic} (an
 * {@code AetheriumMachineLogic}) makes the framework auto-register a ticking block entity for this block
 * — no {@code BlockEntityType}, no ticker, no NBT boilerplate, and no vanilla {@code BlockEntity}
 * subclass. The annotation processor also generates this block's model/blockstate/loot/lang at build
 * time. The class body is intentionally empty; all behavior lives in the pure logic class.
 * RU: {@link AetheriumBlock#behavior()} = {@link AnomalyCoreLogic} (это {@code AetheriumMachineLogic})
 * заставляет фреймворк авто-зарегистрировать тикающую блок-сущность — без {@code BlockEntityType},
 * тикера, NBT-шаблона и подкласса ванильного {@code BlockEntity}. Процессор также генерирует
 * модель/blockstate/loot/lang на сборке. Тело класса пустое; вся логика — в чистом классе поведения.
 */
@AetheriumBlock(
        name = "anomaly_core",
        behavior = AnomalyCoreLogic.class,
        hardness = 4.0f,
        resistance = 1200.0f,
        requiresTool = true,
        displayName = "Anomaly Core")
public final class AnomalyCoreBlock {
}
