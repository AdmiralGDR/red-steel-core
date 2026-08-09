/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.block;

import org.aetherium.content.AetheriumMachineLogic;
import org.aetherium.content.MachineContext;
import org.aetherium.content.MachineState;
import org.aetherium.edge.InteractionResult;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.ui.AetheriumUi;
import redstone.team.redsteelcore.client.FactionScreen;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.gameplay.FactionCommands;

/**
 * The Anomaly Core — the mod's territory-and-income loop, and the only route into the territory system that
 * does not require operator rights.
 *
 * <p>EN: Place a core and it claims the surrounding chunk for the placer's faction. From then on it earns
 * essence on its own, faster where the anomaly burns hotter; come back, right-click, and collect. A hostile
 * faction gets nothing, and breaking it releases both the claim and the off-heap slot.
 *
 * <p>This is a real {@link AetheriumMachineLogic} again: the framework wired the machine system end to
 * end — {@code @AetheriumBlock(behavior = …)} now registers an {@code EntityBlock} with a block entity and a
 * server ticker, {@link MachineState} persists in NBT, and {@link MachineContext#placer()} is populated on
 * placement. The previous event-driven scaffolding this mod carried (a position→core registry ticked from
 * {@code onServerTickEnd} and persisted through {@code WorldStore}) is deleted: the block entity does all of
 * it correctly, including cores in chunks that are not loaded.
 *
 * <p>RU: Поставленное ядро закрепляет чанк за фракцией установившего. Дальше оно само копит эссенцию — тем
 * быстрее, чем горячее аномалия; вернулись, кликнули правой кнопкой — забрали. Враждебной фракции не достаётся
 * ничего, слом освобождает и чанк, и off-heap слот. Это снова настоящий {@link AetheriumMachineLogic}: в
 * the framework систему машин подключили целиком, {@link MachineState} сохраняется в NBT, а
 * {@link MachineContext#placer()} заполняется при установке. Прежние самодельные леса (реестр ядер на
 * {@code onServerTickEnd} + сохранение через {@code WorldStore}) удалены — блок-сущность делает это
 * правильнее, включая ядра в незагруженных чанках.
 */
public final class AnomalyCoreLogic implements AetheriumMachineLogic {

    /** Faction that owns this core; absent until someone claims it. */
    private static final String OWNER = "owner";
    /** Essence waiting to be collected. */
    private static final String STORED = "stored";

    /** Accrue once a second rather than every tick, so the number a player sees moves readably. */
    private static final long ACCRUE_EVERY_TICKS = 20L;

    @Override
    public void onPlaced(MachineContext ctx) {
        ctx.placer().ifPresent(player -> {
            Faction faction = FactionManager.get().faction(player.id());
            if (faction == Faction.NEUTRAL) {
                player.sendMessage("Ядро установлено, но не активно: вступите во фракцию и кликните по нему.");
                return;
            }
            claimFor(ctx, player, faction);
        });
    }

    @Override
    public void tick(MachineContext ctx) {
        if (ctx.isClient() || ctx.ticks() % ACCRUE_EVERY_TICKS != 0) {
            return; // accrual is authoritative and paced; the client mirror does nothing
        }
        if (!isClaimed(ctx.state())) {
            return; // an unclaimed core is inert
        }
        // Hotter anomaly, richer core. Without the off-heap engine (a JVM with no --enable-preview) the
        // intensity is 0 and the core still earns the base rate, so the feature degrades instead of dying.
        float intensity = AnomalyCoreSlots.get().intensityAt(ctx.x(), ctx.y(), ctx.z());
        long perEssence = Math.max(1L, RedSteelConfigs.current().energyPerEssence());
        long mined = 1L + Math.round(intensity * 8.0f);
        ctx.state().increment(STORED, Math.max(1L, (mined * 8L) / perEssence));
    }

    @Override
    public InteractionResult onUse(MachineContext ctx, PlayerHandle player) {
        FactionManager factions = FactionManager.get();
        Faction faction = factions.faction(player.id());
        Faction owner = owner(ctx.state());
        boolean claimed = isClaimed(ctx.state());

        if (faction == Faction.NEUTRAL) {
            player.sendMessage("Ядро не отзывается: сначала выберите фракцию.");
            openScreen(ctx, player, owner);
            return InteractionResult.CANCEL;
        }
        if (!claimed) {
            claimFor(ctx, player, faction);
            openScreen(ctx, player, faction);
            return InteractionResult.CANCEL;
        }
        if (owner != faction) {
            player.sendMessage("Это ядро принадлежит фракции «"
                    + RedSteelConfigs.current().displayName(owner) + "» и вам не подчиняется.");
            return InteractionResult.CANCEL;
        }

        long stored = ctx.state().getLong(STORED, 0L);
        if (stored > 0) {
            ctx.state().setLong(STORED, 0L);
            factions.addEssence(player.id(), stored);
            player.sendMessage("Собрано эссенции: " + stored + ".");
        } else {
            player.sendMessage("Ядро ещё ничего не накопило.");
        }
        openScreen(ctx, player, owner);
        return InteractionResult.CANCEL;
    }

    @Override
    public void onRemoved(MachineContext ctx) {
        AnomalyCoreSlots.get().release(ctx.x(), ctx.y(), ctx.z());
        if (isClaimed(ctx.state())) {
            FactionManager.get().claim(ctx.x(), ctx.z(), Faction.NEUTRAL);
            ctx.state().removeString(OWNER);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * The faction recorded in the machine state; {@link Faction#NEUTRAL} when the core is unclaimed.
     *
     * <p>{@code hasString} (the framework) is what makes "never claimed" distinguishable from "claimed
     * by nobody": before it, clearing an owner meant writing a sentinel and the two states were the same
     * value. {@link #isClaimed(MachineState)} reads the presence of the key, not its content.
     */
    public static Faction owner(MachineState state) {
        return Faction.byId(state.getString(OWNER, "neutral"));
    }

    /** Has this core ever been claimed? Absence of the key is the answer, not the value stored under it. */
    public static boolean isClaimed(MachineState state) {
        return state.hasString(OWNER) && owner(state) != Faction.NEUTRAL;
    }

    /** Essence waiting in this core. */
    public static long stored(MachineState state) {
        return state.getLong(STORED, 0L);
    }

    private static void claimFor(MachineContext ctx, PlayerHandle player, Faction faction) {
        ctx.state().setString(OWNER, faction.id());
        FactionManager.get().claim(ctx.x(), ctx.z(), faction);
        player.sendMessage("Ядро активировано. Чанк закреплён за фракцией «"
                + RedSteelConfigs.current().displayName(faction) + "».");
        player.sendMessage("Оно копит эссенцию само — вернитесь и кликните ещё раз, чтобы собрать.");
    }

    /** Show the faction screen with this core's readings; a no-op on a dedicated server. */
    private static void openScreen(MachineContext ctx, PlayerHandle player, Faction owner) {
        if (!AetheriumUi.isAvailable()) {
            return; // the chat messages above are the whole interaction on a server
        }
        AetheriumUi.open(new FactionScreen(player.id(), player.name())
                .withAdminAccess(player.hasPermission(FactionCommands.ADMIN_PERMISSION))
                .withCoreStatus(new FactionScreen.CoreStatus(ctx.x(), ctx.y(), ctx.z(),
                        RedSteelConfigs.current().displayName(owner), stored(ctx.state()))));
    }
}
