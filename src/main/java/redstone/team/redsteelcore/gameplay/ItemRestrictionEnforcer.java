/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.gameplay;

import org.aetherium.edge.EdgeEvents;
import org.aetherium.edge.InteractionResult;
import org.aetherium.edge.PlayerHandle;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a faction-restricted item actually unusable, at every action a player can take with one.
 *
 * <p>EN: The restriction used to be wired to {@code onItemUse} alone, which the loader maps to NeoForge's
 * {@code PlayerInteractEvent.RightClickItem} — <strong>only</strong> a right-click with the item in open air.
 * That is the one thing nobody does with a sword. Swinging it is {@code AttackEntityEvent}, mining with a
 * restricted tool is {@code BlockEvent.BreakEvent}, placing a restricted block is
 * {@code BlockEvent.EntityPlaceEvent}, and using an item on a block is {@code RightClickBlock} — none of which
 * pass through {@code onItemUse}. So a restricted weapon still killed, a restricted pickaxe still mined, and a
 * restricted block still placed: adding an item to the table looked like it did nothing, because for every
 * realistic use of that item, it did.
 *
 * <p>This class closes all of them by checking the <em>held</em> item — the framework's
 * {@link org.aetherium.edge.InventoryAccess#heldItemId()} — on attack, break, place and block interaction, in
 * addition to the original use event. Each vector is independently cancellable in the loader, so a veto here
 * really does stop the action.
 *
 * <p>Chat is rate-limited per player: an attack event fires on every swing, and a refusal printed sixty times
 * a second is indistinguishable from a broken mod. The <em>veto</em> is not rate-limited — only the message.
 *
 * <p>RU: Раньше ограничение висело только на {@code onItemUse}, который у загрузчика — это
 * {@code RightClickItem}, то есть правый клик предметом по воздуху. Именно этого с мечом никто не делает: удар
 * — это {@code AttackEntityEvent}, добыча — {@code BreakEvent}, установка — {@code EntityPlaceEvent}, а клик по
 * блоку — {@code RightClickBlock}. Поэтому запрещённый меч продолжал убивать, кирка — копать, блок —
 * ставиться: добавление предмета в список выглядело как «ничего не изменилось», потому что для всех реальных
 * применений так и было. Здесь закрыты все векторы — по предмету В РУКЕ. Сообщение в чат ограничено по
 * частоте (удары идут пачкой), сам запрет — нет.
 */
public final class ItemRestrictionEnforcer {

    /** Minimum gap between refusal messages to one player, in milliseconds. */
    private static final long MESSAGE_COOLDOWN_MS = 1500L;

    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    private ItemRestrictionEnforcer() {
    }

    /** Wire every vector through which a restricted item could otherwise still be used. */
    public static void register(EdgeEvents events) {
        // Right-click with the item in hand (the original, and the only one that used to be covered).
        events.onItemUse((player, itemId) -> check(player, itemId));

        // Swinging it. Without this a restricted weapon is fully functional as a weapon.
        events.onEntityAttack((attacker, target) -> check(attacker, held(attacker)));

        // Mining with it. Without this a restricted tool is fully functional as a tool.
        events.onBlockBreak((player, pos, blockId, playerPlaced) -> check(player, held(player)));

        // Placing it. The placed block's own id is the restricted thing here, and it is also what is held,
        // so either would do — held() keeps one rule for every vector.
        events.onBlockPlace((player, pos, blockId) ->
                player == null ? InteractionResult.PASS : check(player, held(player)));

        // Using it on a block (right-click on a block is a different event from right-click in air).
        events.onBlockInteract((player, pos) -> check(player, held(player)));
    }

    /**
     * The decision for one action: PASS unless {@code itemId} is restricted away from this player.
     *
     * <p>Public so the self-test can assert the rule itself, rather than only the events it is wired to.
     */
    public static InteractionResult check(PlayerHandle player, String itemId) {
        if (itemId == null || !FactionCommands.isItemId(itemId)) {
            return InteractionResult.PASS; // empty hand, or an id we would never have stored
        }
        FactionManager factions = FactionManager.get();
        if (factions.canUseItem(player.id(), itemId)) {
            return InteractionResult.PASS;
        }
        Faction owner = factions.restrictionOwner(itemId);
        notifyThrottled(player, "Этот предмет принадлежит фракции «"
                + RedSteelConfigs.current().displayName(owner) + "» — вы не можете им пользоваться.");
        return InteractionResult.CANCEL;
    }

    /** The item in the player's hand, or {@code null} when their hand is empty. */
    private static String held(PlayerHandle player) {
        String id = player.inventory().heldItemId();
        return FactionCommands.isItemId(id) ? id : null;
    }

    private static void notifyThrottled(PlayerHandle player, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(player.id());
        if (last == null || now - last >= MESSAGE_COOLDOWN_MS) {
            LAST_MESSAGE.put(player.id(), now);
            player.sendMessage(message);
        }
    }

    /** Drop a player's message cooldown — called on leave so the map cannot grow without bound. */
    public static void forget(UUID player) {
        LAST_MESSAGE.remove(player);
    }
}
