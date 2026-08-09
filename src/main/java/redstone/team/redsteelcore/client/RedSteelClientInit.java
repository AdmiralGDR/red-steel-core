/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.client;

import org.aetherium.core.mod.AetheriumContext;
import org.aetherium.core.mod.AetheriumInit;
import org.aetherium.core.mod.Side;
import org.aetherium.edge.Platform;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.ui.AetheriumUi;
import org.aetherium.ui.Keys;
import redstone.team.redsteelcore.gameplay.FactionCommands;

/**
 * Everything that only exists on a client: the faction screen's keybind and the always-on HUD.
 *
 * <p>EN: Declared {@code side = }{@link Side#CLIENT}, so the framework's generated entrypoint simply
 * does not call it on a dedicated server. Previously this code sat inside the gameplay init and each call was
 * individually defensive — {@code AetheriumUi.isAvailable()}, a no-op {@code UiAccess}, a HUD registry that
 * discards on a server. That worked, but it made every client feature carry a runtime "am I allowed to exist"
 * check, and one missed check would have been a dedicated-server crash. The side model replaces a convention
 * with a guarantee, and it is the difference between a mod that <em>tolerates</em> a server and one that
 * <em>declares</em> where it runs.
 *
 * <p>The split also states the architecture out loud: the server owns state and rules
 * ({@code redsteel_gameplay}, {@link Side#SERVER}), the client owns presentation. Nothing here writes game
 * state — the screens send their edits to the server over {@code redsteelcore:admin_config} and wait to be
 * told what was accepted.
 *
 * <p>RU: Всё, что существует только на клиенте: горячая клавиша экрана фракций и постоянный HUD. Объявлено
 * {@code side = CLIENT}, поэтому сгенерированная точка входа просто не вызывает это на выделенном сервере.
 * Раньше этот код жил в геймплейной инициализации, и каждый вызов защищался отдельно
 * ({@code AetheriumUi.isAvailable()} и т. п.) — работало, но одна забытая проверка означала бы падение
 * выделенного сервера. Модель сторон заменяет договорённость гарантией. Разделение также проговаривает
 * архитектуру: сервер владеет состоянием и правилами, клиент — представлением.
 */
public final class RedSteelClientInit {

    private RedSteelClientInit() {
    }

    @AetheriumInit(id = "redsteel_client", side = Side.CLIENT)
    public static void init(AetheriumContext ctx) {
        // The discoverable route into the UI: the mod appears in vanilla Controls, so a player finds the
        // screen without knowing a command and without having to own a block. PlayerAccess.local() names the
        // client's own player, so the screen is about whoever pressed the key.
        AetheriumUi.registerKeybind("key.redsteelcore.factions", "key.categories.redsteelcore",
                Keys.G, () -> {
            PlayerHandle viewer = Platform.bridge().players().local().orElse(null);
            AetheriumUi.open(viewer == null
                    ? new FactionScreen(null, null)
                    : new FactionScreen(viewer.id(), viewer.name())
                            .withAdminAccess(viewer.hasPermission(FactionCommands.ADMIN_PERMISSION)));
        });

        // The always-on faction readout. Both progress meters are unreadable without it — a screen pauses the
        // game, which is exactly when neither meter moves.
        AetheriumUi.addHud(new FactionHud());

        ctx.log("[RedSteelCore] Client online — faction screen on the configured key + faction HUD.");
    }
}
