/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.client;

import org.aetherium.edge.Platform;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.ui.AetheriumHud;
import org.aetherium.ui.AlignItems;
import org.aetherium.ui.Container;
import org.aetherium.ui.Insets;
import org.aetherium.ui.Justify;
import org.aetherium.ui.Rect;
import org.aetherium.ui.Ui;
import org.aetherium.ui.UiColor;
import org.aetherium.ui.Widget;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.gameplay.FactionCommands;
import redstone.team.redsteelcore.gameplay.FactionProgress;

import java.util.UUID;

/**
 * The always-on faction readout — allegiance, essence, and whichever progress meter this faction actually
 * uses, drawn in the corner of the running game.
 *
 * <p>EN: Both progression faucets are <em>invisible</em> without this. Samurai Focus resets to zero on any
 * damage taken and Kinetic Resonance decays after {@code comboWindowTicks}, so a player who cannot see the
 * meter cannot play around it — they learn that a reward happened, never that one was two kills away or that
 * a combo just lapsed. Opening a screen to check is not an option: the screen pauses the game, which is
 * exactly when neither meter can move. the framework's {@link AetheriumHud} is the first API that can
 * draw over the running game without capturing input, so this is the first build where the meters are legible.
 *
 * <p>It hides itself for an unaligned player — nothing to report, and an empty box in the corner is worse
 * than no box — and only ever renders the <em>local</em> player, read fresh each frame from the client mirror
 * that {@code FactionNetwork} keeps in sync.
 *
 * <p>RU: Обе механики прогресса без этого НЕВИДИМЫ: концентрация обнуляется от любого урона, а резонанс
 * затухает по окончании окна — не видя шкалы, играть от неё нельзя. Открыть экран нельзя: экран ставит игру
 * на паузу, а именно в этот момент шкалы и не двигаются. {@link AetheriumHud} из the framework — первый API,
 * который рисует поверх идущей игры, не перехватывая ввод. Для игрока без фракции HUD скрыт.
 */
public final class FactionHud extends AetheriumHud {

    /** Width of the meter bar, in pixels. */
    private static final int BAR_WIDTH = 96;
    /** Bar thickness, in pixels — public so the self-test can identify the bar's paint commands. */
    public static final int BAR_HEIGHT = 4;
    /** Width of the faction-colour stripe down the left edge. */
    private static final int STRIPE = 2;

    private static final Insets PAD = Insets.symmetric(3, 6);
    private static final UiColor PLATE = UiColor.rgba(0x0C, 0x0C, 0x10, 0xC8);
    private static final UiColor TRACK = UiColor.rgba(0x2A, 0x2A, 0x30, 0xFF);
    private static final UiColor DANGER = UiColor.rgb(0xE74C3C);

    @Override
    public boolean visible() {
        return faction() != Faction.NEUTRAL;
    }

    @Override
    public Widget<?> build(Rect viewport) {
        PlayerHandle viewer = local();
        Faction faction = faction();
        if (viewer == null || faction == Faction.NEUTRAL) {
            return Ui.column(); // visible() already said no; this is the belt-and-braces frame
        }
        UUID id = viewer.id();
        FactionManager factions = FactionManager.get();
        int rgb = RedSteelConfigs.current().color(faction);

        RedSteelConfig config = RedSteelConfigs.current();
        Container lines = Ui.column().gap(2).align(AlignItems.STRETCH).children(
                Ui.label(config.displayName(faction)).color(UiColor.rgb(rgb)),
                Ui.label("Эссенция: " + factions.essence(id)).color(UiTheme.TEXT));
        meter(lines, faction, id, rgb);
        territory(lines, viewer, factions, config);
        blockedItem(lines, viewer, factions, config);

        // The stripe carries the faction colour, so identity reads at a glance without spending a line on
        // it. Sized by width() + a STRETCH row, which the framework measures as max(spec, intrinsic).
        Container card = Ui.row().gap(UiTheme.GAP).align(AlignItems.STRETCH).padding(PAD)
                .background(PLATE)
                .children(Ui.spacer().width(STRIPE).background(UiColor.rgb(rgb)), lines);

        // Anchored top-left under the vanilla HUD furniture: a row that pushes the card left, inside a column
        // that pushes it up. Laying out against the real viewport keeps it in place at any window size.
        return Ui.column().align(AlignItems.START).padding(Insets.all(4)).children(
                Ui.row().justify(Justify.START).children(card),
                Ui.spacer().grow(1f));
    }

    /**
     * The faction's own progress meter: Focus for the samurai, Kinetic Resonance for the commune.
     *
     * <p>Each faction sees only its own faucet — showing a player a bar that their faction can never fill
     * teaches them the wrong loop.
     */
    private void meter(Container card, Faction faction, UUID id, int rgb) {
        FactionProgress progress = FactionProgress.get();
        var mechanics = RedSteelConfigs.current().mechanics();

        long value;
        long max;
        String label;
        if (faction == Faction.SAMURAI) {
            value = progress.focus(id);
            max = Math.max(1L, mechanics.focusMax());
            label = "Концентрация";
        } else {
            value = progress.combo(id);
            max = Math.max(1L, mechanics.comboNeeded());
            label = "Резонанс";
        }
        int filled = (int) Math.min(BAR_WIDTH, (BAR_WIDTH * Math.max(0L, value)) / max);

        card.add(Ui.label(label + " " + value + "/" + max).color(UiTheme.MUTED));
        // Two abutting blocks rather than a partially-drawn one, so the bar is exact at every scale with no
        // per-pixel drawing. Plain size() again: the framework measures a child as max(spec, intrinsic),
        // so a fixed-size segment now reserves its own row instead of measuring as 0 and overdrawing the
        // label above it. The padding trick that worked around that is deleted.
        Container bar = Ui.row().align(AlignItems.STRETCH);
        if (filled > 0) {
            bar.add(Ui.spacer().size(filled, BAR_HEIGHT).background(UiColor.rgb(rgb)));
        }
        if (filled < BAR_WIDTH) {
            bar.add(Ui.spacer().size(BAR_WIDTH - filled, BAR_HEIGHT).background(TRACK));
        }
        card.add(bar);
    }

    /**
     * Whose ground you are standing on.
     *
     * <p>Territory protection vetoes block interaction with no warning beforehand. Naming the owner while
     * you walk turns "this mod randomly stops me opening chests" into a rule you can see the edge of.
     */
    private void territory(Container card, PlayerHandle viewer, FactionManager factions,
                           RedSteelConfig config) {
        if (!config.territoryProtection()) {
            return; // the rule is off; a line about it would be noise
        }
        Faction owner = factions.ownerOfBlock((int) Math.floor(viewer.x()), (int) Math.floor(viewer.z()));
        if (owner == Faction.NEUTRAL) {
            return; // free land is the default; only the exception is worth a line
        }
        boolean mine = owner == factions.faction(viewer.id());
        card.add(Ui.label((mine ? "Ваша земля: " : "Чужая земля: ") + config.displayName(owner))
                .color(mine ? UiColor.rgb(config.color(owner)) : DANGER));
    }

    /**
     * A warning that the item in hand is one this player may not use.
     *
     * <p>This is the piece that makes the restriction system legible. The rule cancels an attack, a mine or
     * a placement, and a cancelled action looks exactly like a mod that does not work — which is precisely
     * how it was reported. Saying so while the item is merely <em>held</em> puts the explanation before the
     * failure instead of after it.
     */
    private void blockedItem(Container card, PlayerHandle viewer, FactionManager factions,
                             RedSteelConfig config) {
        String held = viewer.inventory().heldItemId();
        if (!FactionCommands.isItemId(held) || factions.canUseItem(viewer.id(), held)) {
            return;
        }
        Faction owner = factions.restrictionOwner(held);
        card.add(Ui.label("✖ Предмет фракции «" + config.displayName(owner) + "»").color(DANGER));
    }

    private static PlayerHandle local() {
        // Straight off the bridge again: the framework's Platform.installForTesting lets a headless test present a
        // local player, so the injected supplier this class used to carry for testability is deleted.
        return Platform.bridge().players().local().orElse(null);
    }

    private static Faction faction() {
        PlayerHandle viewer = local();
        return viewer == null ? Faction.NEUTRAL : FactionManager.get().faction(viewer.id());
    }
}
