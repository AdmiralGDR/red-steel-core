/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.gameplay;

import org.aetherium.core.mod.AetheriumContext;
import org.aetherium.core.mod.AetheriumInit;
import org.aetherium.core.mod.Side;
import org.aetherium.edge.EdgeEvents;
import org.aetherium.edge.InteractionResult;
import org.aetherium.edge.BlockPos;
import org.aetherium.edge.Platform;
import org.aetherium.edge.PlatformBridge;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.network.TreeNode;
import org.aetherium.core.Capabilities;
import redstone.team.redsteelcore.aetherium.AnomalyEngine;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.network.FactionNetwork;

import java.util.Optional;

/**
 * The mod's zero-config entrypoint — the whole gameplay PAL wiring, discovered at compile time via
 * {@link AetheriumInit} (no {@code @Mod} class, no hand-written {@code META-INF/services}).
 *
 * <p>EN: At init it reaches {@link Platform#bridge()} and wires the {@code aetherium-edge} events instead of
 * NeoForge's {@code @SubscribeEvent}. the framework filled every gap this mod reported: the config is the
 * framework's {@code ConfigStore}; there is a {@code /redsteel} command tree; player/faction state persists
 * through {@code WorldStore} across restarts; and the two asymmetric faucets (samurai Focus, communist
 * Kinetic Resonance) run off the new block-break / entity-death / entity-damaged events. Every decision is
 * delegated to the pure {@link FactionManager}/{@link FactionProgress}; no {@code net.minecraft} /
 * {@code net.neoforged} import appears anywhere in this mod.
 * RU: При инициализации обращается к {@link Platform#bridge()} и подключает события {@code aetherium-edge}
 * вместо {@code @SubscribeEvent}. the framework закрыл все заявленные пробелы: конфиг — фреймворковый
 * {@code ConfigStore}; есть дерево команд {@code /redsteel}; состояние игроков/фракций переживает рестарты
 * через {@code WorldStore}; две асимметричные механики (Концентрация самураев, Резонанс коммунистов)
 * работают на новых событиях. Все решения — в чистых {@link FactionManager}/{@link FactionProgress}.
 */
public final class FactionRestrictions {

    /** Namespaced key under which the whole faction state is persisted in the world save. */
    private static final String STATE_KEY = "factions";

    private FactionRestrictions() {
    }

    @AetheriumInit(id = "redsteel_gameplay", side = Side.SERVER)
    public static void init(AetheriumContext ctx) {
        final PlatformBridge bridge = Platform.bridge();
        final EdgeEvents events = bridge.events();
        final FactionManager factions = FactionManager.get();
        final FactionProgress progress = FactionProgress.get();

        // (0) Config: the framework's ConfigStore owns text/atomic-write/hot-reload; we only supply the
        //     document shape. Every rule below reads the live snapshot, so an admin edit (file, GUI, or
        //     /redsteeladmin config) takes effect without a restart.
        RedSteelConfigs.boot(factions::applyConfig);

        // (1) Territory protection. The Anomaly Core's own right-click behaviour lives in
        //     AnomalyCoreLogic.onUse — the framework wired the machine system, so the block handles
        //     its own interaction and this listener no longer has to intercept every click in the world
        //     and compare block ids.
        events.onBlockInteract((player, pos) -> {
            if (factions.canInteractBlock(player.id(), pos.x(), pos.z())) {
                return InteractionResult.PASS;
            }
            player.sendMessage("Эта земля принадлежит враждебной фракции.");
            return InteractionResult.CANCEL;
        });

        // (2) Faction-item restriction, at EVERY vector — attack, break, place, block interaction and use.
        //     Wiring it to onItemUse alone (as this did) covered only a right-click in open air, which is
        //     the one thing nobody does with a weapon or a tool, so restrictions looked inert in practice.
        ItemRestrictionEnforcer.register(events);

        // (3) No friendly fire — veto attacks between members of the same faction.
        events.onEntityAttack((attacker, target) -> {
            if (factions.canAttack(attacker.id(), target.id())) {
                return InteractionResult.PASS;
            }
            attacker.sendMessage("No friendly fire within your faction.");
            return InteractionResult.CANCEL;
        });

        // (4) Kinetic Resonance (communists) — mining a non-player-placed block grows a combo; the
        //     framework's playerPlaced flag is the ready-made anti-abuse for silk-touch replanting.
        events.onBlockBreak((player, pos, blockId, playerPlaced) -> {
            if (progress.onResonantMine(player.id(), playerPlaced)) {
                player.sendMessage("Комбо резонанса завершено — эссенция начислена.");
            }
            return InteractionResult.PASS; // never veto a break; this is a reward faucet, not a gate
        });

        // (5) Focus (samurai) — an honourable kill fills the meter; any damage taken resets it.
        events.onEntityDeath((victim, killer) -> {
            if (killer instanceof org.aetherium.edge.PlayerHandle p && isHonourableKill(p.id(), victim)) {
                if (progress.onHonourableKill(p.id())) {
                    p.sendMessage("Focus complete — you are the untouchable blade. Essence banked.");
                }
            }
        });
        events.onEntityDamaged((victim, attacker, amount) -> {
            if (victim instanceof org.aetherium.edge.PlayerHandle p) {
                progress.onDamageTaken(p.id()); // resets Focus for a samurai; harmless otherwise
            }
            return InteractionResult.PASS;
        });

        // (6) Persistence — load on start, save on stop, and reset a player's transient meters on join/leave.
        events.onServerStarting(() -> loadState(bridge, ctx));
        events.onServerStopping(() -> {
            saveState(bridge, ctx);
            shutdown(ctx);
        });
        events.onPlayerJoin(player -> {
            progress.reset(player.id());
            greet(player);
            // Faction names and colours are admin-editable and player-facing, so a client that has not been
            // told the server's settings renders its own defaults — two players would read different names
            // for the same faction. the framework's send facade makes this one call.
            FactionNetwork.sendConfigTo(player);
        });
        events.onPlayerLeave(player -> {
            progress.reset(player.id());
            ItemRestrictionEnforcer.forget(player.id());
        });

        // (7) Drive the off-heap anomaly simulation + the mechanics tick clock once per server tick.
        //     onServerTickEnd is a shared hook: an exception escaping here would abort the hooks other
        //     Aetherium mods registered after us, so this mod's failure stays this mod's problem.
        events.onServerTickEnd(() -> {
            progress.tick();
            Capabilities.ffm(() -> AnomalyEngine.get().serverTick(), () -> null);
        });

        // (8) Register the mod-scoped faction-state sync channel (framework rejects duplicates now).
        FactionNetwork.register();

        // (9) The /redsteel command tree — the admin fallback that was impossible before the framework.
        FactionCommands.register(bridge.commands());

        ctx.log("[RedSteelCore] Gameplay online — PAL events (block/item/attack/break/death/damage) + tree "
                + "sync on '" + FactionNetwork.CHANNEL + "' + commands + persistence + off-heap anomalies ("
                + Capabilities.ffm(() -> AnomalyEngine.get().offHeapBytes() + " bytes",
                        () -> "off — add --enable-preview for the full rate") + ") on platform '"
                + bridge.platformName()
                + "', compute tier " + ctx.computeTier() + ".");
    }

    /** The block id the content processor registers for {@code AnomalyCoreBlock}. */
    private static final String ANOMALY_CORE = "redsteelcore:anomaly_core";

    /**
     * Is the clicked block our Anomaly Core?
     *
     * <p>The block itself cannot answer a right-click: {@code AetheriumMachineLogic} has
     * {@code tick/onPlaced/onRemoved} but no {@code onUse}, so the only way is to catch the global
     * interaction event and compare the block id at that position.
     */
    private static boolean isAnomalyCore(PlatformBridge bridge, BlockPos pos) {
        return bridge.levels().primary()
                .map(level -> ANOMALY_CORE.equals(level.blockAt(pos).blockId()))
                .orElse(false);
    }

    /** A short orientation message on join — otherwise a new player has no way to discover the mod. */
    private static void greet(PlayerHandle player) {
        FactionManager m = FactionManager.get();
        Faction faction = m.faction(player.id());
        player.sendMessage("[Red Steel Core] Ваша фракция: "
                + RedSteelConfigs.current().displayName(faction)
                + " · эссенция: " + m.essence(player.id()));
        player.sendMessage(faction == Faction.NEUTRAL
                ? "Выберите сторону: /redsteel gui — или кликните правой кнопкой по Аномальному Ядру."
                : "Откройте интерфейс: /redsteel gui — или кликните правой кнопкой по Аномальному Ядру.");
    }

    /** A kill counts toward Focus if the victim is a member of a hostile faction (or, if the admin turned
     * {@code honourableKillsOnly} off, any kill). A non-player victim with no faction is a wild mob. */
    private static boolean isHonourableKill(java.util.UUID killer, org.aetherium.edge.EntityHandle victim) {
        if (!RedSteelConfigs.current().mechanics().honourableKillsOnly()) {
            return true;
        }
        FactionManager m = FactionManager.get();
        Faction victimFaction = m.faction(victim.id());
        return victimFaction != Faction.NEUTRAL && victimFaction.isHostileTo(m.faction(killer));
    }

    private static void loadState(PlatformBridge bridge, AetheriumContext ctx) {
        try {
            Optional<TreeNode> saved = bridge.worldStore().read("redsteelcore", STATE_KEY);
            // Cores are NOT here: their owner and stored essence live in the block entity's NBT, which
            // survives a restart on its own and covers cores in chunks that are not loaded.
            saved.ifPresent(root -> FactionManager.get().importState(
                    root instanceof TreeNode.Obj o && o.get("factions") != null ? o.get("factions") : root));
            ctx.log("[RedSteelCore] faction state " + (saved.isPresent() ? "restored" : "fresh") + ".");
        } catch (RuntimeException failed) {
            ctx.log("[RedSteelCore] could not restore faction state (starting fresh): " + failed);
        }
    }

    private static void saveState(PlatformBridge bridge, AetheriumContext ctx) {
        try {
            bridge.worldStore().write("redsteelcore", STATE_KEY, FactionManager.get().exportState());
            ctx.log("[RedSteelCore] faction state saved.");
        } catch (RuntimeException failed) {
            ctx.log("[RedSteelCore] could not save faction state: " + failed);
        }
    }

    private static void shutdown(AetheriumContext ctx) {
        try {
            if (RedSteelConfigs.store() != null) {
                RedSteelConfigs.store().close();
            }
            Capabilities.ffm(() -> {
                AnomalyEngine.get().shutdown();
                return null;
            }, () -> null);
        } catch (RuntimeException failed) {
            ctx.log("[RedSteelCore] shutdown cleanup issue: " + failed);
        }
    }
}
