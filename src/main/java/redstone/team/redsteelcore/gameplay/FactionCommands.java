/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.gameplay;

import org.aetherium.edge.EdgeCommands;
import org.aetherium.edge.InteractionResult;
import org.aetherium.edge.Platform;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.ui.AetheriumUi;
import redstone.team.redsteelcore.client.FactionScreen;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;

import java.util.List;
import java.util.Map;

/**
 * The {@code /redsteel} command tree — the way to administer a headless server, and the trigger that opens
 * the in-game UI.
 *
 * <p>EN: A single command with a greedy argument; the handler tokenizes it into sub-commands. Administrative
 * sub-commands are gated by {@link PlayerHandle#hasPermission(int)} rather than by living in a separate
 * command (the framework added the accessor — before it, the only way to express mixed privilege was to
 * split the tree, which pushed an implementation detail into the player-facing surface). Everything routes
 * through the same {@link FactionManager}/{@link RedSteelConfigs} the GUI and the config file use, so the
 * three administration surfaces can never disagree. Off-platform {@code commands()} is
 * {@link EdgeCommands#NONE}, so registering is a safe no-op.
 *
 * <p><strong>Every setting the in-game editor can change also has a command here.</strong> That is no longer
 * forced on us — the framework added the serverbound direction, so {@code ConfigScreen} now sends its
 * edits to the server over {@code redsteelcore:admin_config} instead of writing whatever config file happened
 * to be next to it. The parity is kept deliberately: a console has no GUI, and a self-test asserts that every
 * key the screen offers is settable from this tree, so the two surfaces cannot drift apart. Both mutate one
 * immutable {@link RedSteelConfig} snapshot and end at the same {@link RedSteelConfigs#save} write path.
 *
 * <p>RU: Одна команда с «жадным» аргументом; обработчик разбирает её на подкоманды. Административные
 * подкоманды закрыты через {@link PlayerHandle#hasPermission(int)}. Каждая настройка из экранного редактора
 * доступна и командой — но это не вынужденно: экран отправляет правки на сервер по каналу
 * {@code redsteelcore:admin_config}. Паритет сохранён намеренно (у консоли нет GUI), и самотест проверяет,
 * что он не разъедется.
 */
public final class FactionCommands {

    /** Vanilla-style op level required for the administrative sub-commands. */
    public static final int ADMIN_PERMISSION = 2;

    private FactionCommands() {
    }

    public static void register(EdgeCommands commands) {
        commands.register("redsteel",
                EdgeCommands.CommandSpec.of(0, "RedSteelCore: фракции, интерфейс и администрирование",
                        EdgeCommands.ArgType.GREEDY_STRING),
                FactionCommands::run);
    }

    /** Dispatch, exposed so the self-test can drive the whole tree with no game present. */
    public static InteractionResult run(PlayerHandle sender, List<String> args) {
        String[] parts = args.isEmpty() ? new String[0] : args.get(0).trim().split("\\s+");
        String sub = parts.length == 0 ? "" : parts[0].toLowerCase();

        return switch (sub) {
            case "", "help" -> help(sender);
            case "gui", "menu" -> gui(sender);
            case "faction", "info" -> info(sender);
            case "join" -> join(sender, arg(parts, 1));
            case "leave" -> leave(sender);
            case "essence" -> essence(sender, arg(parts, 1));
            case "config" -> admin(sender) ? config(sender, parts) : denied(sender);
            case "item" -> admin(sender) ? item(sender, parts) : denied(sender);
            case "claim" -> admin(sender) ? claim(sender, parts) : denied(sender);
            case "player" -> admin(sender) ? player(sender, parts) : denied(sender);
            default -> {
                sender.sendMessage("Неизвестная подкоманда «" + sub + "». Наберите /redsteel help.");
                yield InteractionResult.CANCEL;
            }
        };
    }

    private static boolean admin(PlayerHandle sender) {
        return sender.hasPermission(ADMIN_PERMISSION);
    }

    private static InteractionResult denied(PlayerHandle sender) {
        sender.sendMessage("Этой подкоманде нужен уровень прав " + ADMIN_PERMISSION + ".");
        return InteractionResult.CANCEL;
    }

    private static InteractionResult help(PlayerHandle sender) {
        sender.sendMessage("/redsteel gui | faction | join <" + playableIds() + "> | leave | essence [n]");
        if (admin(sender)) {
            sender.sendMessage("/redsteel config get | config set <ключ> <значение> | config reload");
            sender.sendMessage("/redsteel item list | item add <id|hand> [фракция] | item remove <id>");
            sender.sendMessage("/redsteel claim <фракция> | claim here | claim remove | claim list");
            sender.sendMessage("/redsteel player <ник> info | faction <фракция> | essence <±n>");
        }
        return InteractionResult.PASS;
    }

    /**
     * Open the faction screen. {@link AetheriumUi} is a client-side service: on a dedicated server it
     * resolves to a no-op, so this reports honestly instead of pretending to have opened something.
     */
    private static InteractionResult gui(PlayerHandle sender) {
        if (!AetheriumUi.isAvailable()) {
            sender.sendMessage("Интерфейс работает только на клиенте (client-side); "
                    + "в консоли сервера используйте /redsteel faction и /redsteel config get.");
            return InteractionResult.CANCEL;
        }
        AetheriumUi.open(new FactionScreen(sender.id(), sender.name())
                .withAdminAccess(admin(sender)));
        return InteractionResult.PASS;
    }

    private static InteractionResult info(PlayerHandle sender) {
        report(sender, sender);
        return InteractionResult.PASS;
    }

    private static InteractionResult join(PlayerHandle sender, String id) {
        Faction target = Faction.byId(id);
        if (target == Faction.NEUTRAL) {
            sender.sendMessage("Выберите фракцию: " + playableIds());
            return InteractionResult.CANCEL;
        }
        FactionManager.get().join(sender.id(), target);
        sender.sendMessage("Вы вступили во фракцию «" + RedSteelConfigs.current().displayName(target) + "».");
        return InteractionResult.PASS;
    }

    private static InteractionResult leave(PlayerHandle sender) {
        FactionManager.get().leave(sender.id());
        sender.sendMessage("Вы покинули свою фракцию.");
        return InteractionResult.PASS;
    }

    private static InteractionResult essence(PlayerHandle sender, String amount) {
        FactionManager m = FactionManager.get();
        if (amount == null) {
            sender.sendMessage("Эссенция: " + m.essence(sender.id()));
            return InteractionResult.PASS;
        }
        if (!admin(sender)) {
            return denied(sender); // reading your own balance is free; changing it is not
        }
        Long delta = parseLong(amount);
        if (delta == null) {
            sender.sendMessage("«" + amount + "» — не число.");
            return InteractionResult.CANCEL;
        }
        m.addEssence(sender.id(), delta);
        sender.sendMessage("Эссенция теперь " + m.essence(sender.id()) + ".");
        return InteractionResult.PASS;
    }

    // --- admin: config ---------------------------------------------------------------------------

    private static InteractionResult config(PlayerHandle sender, String[] parts) {
        String action = arg(parts, 1);
        if (action == null || "get".equalsIgnoreCase(action)) {
            RedSteelConfig c = RedSteelConfigs.current();
            RedSteelConfig.Mechanics m = c.mechanics();
            sender.sendMessage("energy_per_essence=" + c.energyPerEssence()
                    + " territory_protection=" + c.territoryProtection()
                    + " item_restrictions=" + c.itemRestrictions()
                    + " friendly_fire_protection=" + c.friendlyFireProtection());
            sender.sendMessage("focus_per_kill=" + m.focusPerKill()
                    + " focus_max=" + m.focusMax()
                    + " focus_reward=" + m.focusReward()
                    + " honourable_kills_only=" + m.honourableKillsOnly());
            sender.sendMessage("combo_needed=" + m.comboNeeded()
                    + " combo_window_ticks=" + m.comboWindowTicks()
                    + " combo_reward=" + m.comboReward());
            return InteractionResult.PASS;
        }
        if ("reload".equalsIgnoreCase(action)) {
            if (RedSteelConfigs.store() == null) {
                sender.sendMessage("Конфиг не подключён.");
                return InteractionResult.CANCEL;
            }
            var result = RedSteelConfigs.store().reload();
            if (result.ok()) {
                sender.sendMessage("Конфиг перечитан с диска.");
                return InteractionResult.PASS;
            }
            // A failed reload keeps the last-good config live — say so rather than implying a reset.
            sender.sendMessage("Файл конфига повреждён, оставлены прежние значения. "
                    + result.diagnostic().map(Object::toString).orElse(""));
            return InteractionResult.CANCEL;
        }
        if ("set".equalsIgnoreCase(action)) {
            return configSet(sender, arg(parts, 2), arg(parts, 3));
        }
        sender.sendMessage("/redsteel config get | set <ключ> <значение> | reload");
        return InteractionResult.CANCEL;
    }

    /** Every key the settings screen can change is settable here too — see the class comment for why. */
    private static InteractionResult configSet(PlayerHandle sender, String key, String value) {
        if (key == null || value == null) {
            sender.sendMessage("Использование: /redsteel config set <ключ> <значение>");
            sender.sendMessage("Ключи: " + String.join(", ", CONFIG_KEYS));
            return InteractionResult.CANCEL;
        }
        RedSteelConfig c = RedSteelConfigs.current();
        RedSteelConfig.Mechanics m = c.mechanics();
        RedSteelConfig updated;
        switch (key.toLowerCase()) {
            case "energy_per_essence" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withEnergyPerEssence(v);
            }
            case "territory_protection" -> updated = c.withTerritoryProtection(parseBool(value));
            case "item_restrictions" -> updated = c.withItemRestrictions(parseBool(value));
            case "friendly_fire_protection" -> updated = c.withFriendlyFireProtection(parseBool(value));
            case "focus_per_kill" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(v, m.focusMax(), m.focusReward(),
                        m.comboNeeded(), m.comboWindowTicks(), m.comboReward(), m.honourableKillsOnly()));
            }
            case "focus_max" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(m.focusPerKill(), v, m.focusReward(),
                        m.comboNeeded(), m.comboWindowTicks(), m.comboReward(), m.honourableKillsOnly()));
            }
            case "focus_reward" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(m.focusPerKill(), m.focusMax(), v,
                        m.comboNeeded(), m.comboWindowTicks(), m.comboReward(), m.honourableKillsOnly()));
            }
            case "combo_needed" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(m.focusPerKill(), m.focusMax(),
                        m.focusReward(), v, m.comboWindowTicks(), m.comboReward(), m.honourableKillsOnly()));
            }
            case "combo_window_ticks" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(m.focusPerKill(), m.focusMax(),
                        m.focusReward(), m.comboNeeded(), v, m.comboReward(), m.honourableKillsOnly()));
            }
            case "combo_reward" -> {
                Long v = number(sender, value);
                if (v == null) {
                    return InteractionResult.CANCEL;
                }
                updated = c.withMechanics(new RedSteelConfig.Mechanics(m.focusPerKill(), m.focusMax(),
                        m.focusReward(), m.comboNeeded(), m.comboWindowTicks(), v, m.honourableKillsOnly()));
            }
            case "honourable_kills_only" -> updated = c.withMechanics(new RedSteelConfig.Mechanics(
                    m.focusPerKill(), m.focusMax(), m.focusReward(), m.comboNeeded(), m.comboWindowTicks(),
                    m.comboReward(), parseBool(value)));
            default -> {
                sender.sendMessage("Неизвестный ключ «" + key + "». Ключи: " + String.join(", ", CONFIG_KEYS));
                return InteractionResult.CANCEL;
            }
        }
        RedSteelConfigs.save(updated);
        // Echo the STORED value, not the typed one: every numeric field is clamped in the record's canonical
        // constructor, so "focus_max 9999999" silently becomes 100000 — the admin has to see that.
        sender.sendMessage("Установлено " + key.toLowerCase() + " = "
                + readBack(RedSteelConfigs.current(), key.toLowerCase()) + " (сохранено).");
        return InteractionResult.PASS;
    }

    /** Keys accepted by {@code config set}, in the order the settings screen presents them. */
    public static final List<String> CONFIG_KEYS = List.of(
            "energy_per_essence", "territory_protection", "item_restrictions", "friendly_fire_protection",
            "focus_per_kill", "focus_max", "focus_reward", "honourable_kills_only",
            "combo_needed", "combo_window_ticks", "combo_reward");

    /** The persisted value of a key, so a clamped or rejected edit is visible instead of assumed. */
    public static String readBack(RedSteelConfig c, String key) {
        RedSteelConfig.Mechanics m = c.mechanics();
        return switch (key) {
            case "energy_per_essence" -> String.valueOf(c.energyPerEssence());
            case "territory_protection" -> String.valueOf(c.territoryProtection());
            case "item_restrictions" -> String.valueOf(c.itemRestrictions());
            case "friendly_fire_protection" -> String.valueOf(c.friendlyFireProtection());
            case "focus_per_kill" -> String.valueOf(m.focusPerKill());
            case "focus_max" -> String.valueOf(m.focusMax());
            case "focus_reward" -> String.valueOf(m.focusReward());
            case "honourable_kills_only" -> String.valueOf(m.honourableKillsOnly());
            case "combo_needed" -> String.valueOf(m.comboNeeded());
            case "combo_window_ticks" -> String.valueOf(m.comboWindowTicks());
            case "combo_reward" -> String.valueOf(m.comboReward());
            default -> "?";
        };
    }

    // --- admin: restricted items ------------------------------------------------------------------

    /**
     * The restricted-item table, from a console.
     *
     * <p>{@code item add hand} restricts exactly the item the operator is holding, so a standing-in-the-world
     * admin never types an id at all; on a console, where there is no inventory, the explicit id form works.
     * the framework added {@link org.aetherium.edge.InventoryAccess#heldItemId()} — before it the best we
     * could express was "everything in the hotbar", which made the operator choose a second time from a list
     * they had already narrowed by picking the item up.
     */
    private static InteractionResult item(PlayerHandle sender, String[] parts) {
        String action = arg(parts, 1);
        RedSteelConfig c = RedSteelConfigs.current();

        if (action == null || "list".equalsIgnoreCase(action)) {
            if (c.restrictedItems().isEmpty()) {
                sender.sendMessage("Ограниченных предметов нет.");
                return InteractionResult.PASS;
            }
            sender.sendMessage("Ограниченные предметы (" + c.restrictedItems().size() + "):");
            RedSteelConfig listed = c;
            listed.restrictedItems().forEach((id, owner) ->
                    sender.sendMessage("  " + id + " → " + listed.displayName(owner)));
            return InteractionResult.PASS;
        }
        if ("add".equalsIgnoreCase(action)) {
            String id = arg(parts, 2);
            if ("hand".equalsIgnoreCase(id)) {
                id = heldItem(sender);
                if (id == null) {
                    sender.sendMessage("В руке ничего нет — возьмите предмет или укажите id явно.");
                    return InteractionResult.CANCEL;
                }
            }
            List<String> ids = List.of(id == null ? "" : id);
            Faction owner = Faction.byId(arg(parts, 3));
            if (owner == Faction.NEUTRAL) {
                owner = Faction.playable()[0]; // sensible default; `item add <id> <faction>` overrides it
            }
            int added = 0;
            for (String candidate : ids) {
                if (!isItemId(candidate)) {
                    continue;
                }
                c = c.withRestrictedItem(candidate, owner);
                sender.sendMessage("  + " + candidate + " → " + c.displayName(owner));
                added++;
            }
            if (added == 0) {
                sender.sendMessage("Нужен id вида namespace:path, например minecraft:diamond_sword.");
                return InteractionResult.CANCEL;
            }
            RedSteelConfigs.save(c);
            sender.sendMessage("Ограничений добавлено: " + added + " (сохранено).");
            return InteractionResult.PASS;
        }
        if ("remove".equalsIgnoreCase(action)) {
            String id = arg(parts, 2);
            if (id == null || c.restrictionOwner(id) == Faction.NEUTRAL) {
                sender.sendMessage("Такого ограничения нет: " + id);
                return InteractionResult.CANCEL;
            }
            RedSteelConfigs.save(c.withRestrictedItem(id, Faction.NEUTRAL));
            sender.sendMessage("Ограничение снято: " + id + " (сохранено).");
            return InteractionResult.PASS;
        }
        sender.sendMessage("/redsteel item list | add <id|hand> [фракция] | remove <id>");
        return InteractionResult.CANCEL;
    }

    /** The item {@code sender} is holding, or {@code null} when their hand is empty. */
    public static String heldItem(PlayerHandle sender) {
        String id = sender.inventory().heldItemId();
        return isItemId(id) ? id : null;
    }

    /** An id is usable only if it is a real {@code namespace:path} and not the air placeholder. */
    public static boolean isItemId(String id) {
        return id != null && !id.isBlank()
                && !"minecraft:air".equals(id)
                && id.indexOf(':') > 0
                && id.indexOf(':') < id.length() - 1;
    }

    // --- admin: territory ------------------------------------------------------------------------

    /**
     * Territory: claim, release and audit.
     *
     * <p>Claiming with no way to list or undo made the territory system write-only — an operator could hand
     * out ground and never see or take back what had been handed out.
     */
    private static InteractionResult claim(PlayerHandle sender, String[] parts) {
        String action = arg(parts, 1);
        FactionManager factions = FactionManager.get();
        int x = (int) Math.floor(sender.x());
        int z = (int) Math.floor(sender.z());

        if ("list".equalsIgnoreCase(action)) {
            Map<String, Faction> claims = factions.claims();
            if (claims.isEmpty()) {
                sender.sendMessage("Закреплённых чанков нет.");
                return InteractionResult.PASS;
            }
            sender.sendMessage("Закреплённые чанки (" + claims.size() + "), координаты чанка:");
            claims.forEach((chunk, owner) ->
                    sender.sendMessage("  " + chunk + " → " + RedSteelConfigs.current().displayName(owner)));
            return InteractionResult.PASS;
        }
        if ("here".equalsIgnoreCase(action)) {
            Faction owner = factions.ownerOfBlock(x, z);
            sender.sendMessage(owner == Faction.NEUTRAL
                    ? "Чанк " + (x >> 4) + "," + (z >> 4) + " свободен."
                    : "Чанк " + (x >> 4) + "," + (z >> 4) + " закреплён за фракцией «"
                            + RedSteelConfigs.current().displayName(owner) + "».");
            return InteractionResult.PASS;
        }
        if ("remove".equalsIgnoreCase(action)) {
            factions.unclaim(x, z);
            sender.sendMessage("Закрепление снято с чанка " + (x >> 4) + "," + (z >> 4) + ".");
            return InteractionResult.PASS;
        }

        Faction owner = Faction.byId(action);
        if (owner == Faction.NEUTRAL) {
            sender.sendMessage("/redsteel claim <" + playableIds() + "> | here | remove | list");
            return InteractionResult.CANCEL;
        }
        factions.claim(x, z, owner);
        sender.sendMessage("Чанк " + (x >> 4) + "," + (z >> 4) + " закреплён за фракцией «"
                + RedSteelConfigs.current().displayName(owner) + "».");
        return InteractionResult.PASS;
    }

    // --- admin: other players ---------------------------------------------------------------------

    /**
     * Administering someone else — the moderation action that was missing entirely: every other sub-command
     * acts on the sender, so an operator could inspect their own state and nobody else's.
     */
    private static InteractionResult player(PlayerHandle sender, String[] parts) {
        String name = arg(parts, 1);
        String action = arg(parts, 2);
        if (name == null) {
            List<PlayerHandle> online = Platform.bridge().players().online();
            sender.sendMessage("В сети (" + online.size() + "): "
                    + (online.isEmpty() ? "—" : String.join(", ", online.stream().map(PlayerHandle::name).toList())));
            sender.sendMessage("/redsteel player <ник> info | faction <фракция> | essence <±n>");
            return InteractionResult.PASS;
        }
        PlayerHandle target = Platform.bridge().players().byName(name).orElse(null);
        if (target == null) {
            sender.sendMessage("Игрок «" + name + "» не в сети.");
            return InteractionResult.CANCEL;
        }
        FactionManager factions = FactionManager.get();

        if (action == null || "info".equalsIgnoreCase(action)) {
            report(sender, target);
            return InteractionResult.PASS;
        }
        if ("faction".equalsIgnoreCase(action)) {
            String value = arg(parts, 3);
            Faction faction = Faction.byId(value);
            if (faction == Faction.NEUTRAL && !"neutral".equalsIgnoreCase(value)) {
                sender.sendMessage("Фракция: " + playableIds() + ", neutral");
                return InteractionResult.CANCEL;
            }
            if (faction == Faction.NEUTRAL) {
                factions.leave(target.id());
            } else {
                factions.join(target.id(), faction);
            }
            String shown = RedSteelConfigs.current().displayName(faction);
            sender.sendMessage(name + " → «" + shown + "».");
            target.sendMessage("Администратор перевёл вас во фракцию «" + shown + "».");
            return InteractionResult.PASS;
        }
        if ("essence".equalsIgnoreCase(action)) {
            Long delta = number(sender, arg(parts, 3));
            if (delta == null) {
                return InteractionResult.CANCEL;
            }
            factions.addEssence(target.id(), delta);
            long now = factions.essence(target.id());
            sender.sendMessage(name + ": эссенция теперь " + now + ".");
            target.sendMessage("Администратор изменил вашу эссенцию: " + now + ".");
            return InteractionResult.PASS;
        }
        sender.sendMessage("/redsteel player <ник> info | faction <фракция> | essence <±n>");
        return InteractionResult.CANCEL;
    }

    /** One line of everything the mod knows about {@code subject}, sent to {@code to}. */
    private static void report(PlayerHandle to, PlayerHandle subject) {
        FactionManager m = FactionManager.get();
        FactionProgress p = FactionProgress.get();
        to.sendMessage(subject.name() + " — " + RedSteelConfigs.current().displayName(m.faction(subject.id()))
                + " | эссенция " + m.essence(subject.id()) + " | квота " + m.quota(subject.id())
                + " | концентрация " + p.focus(subject.id()) + " | комбо " + p.combo(subject.id()));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static String playableIds() {
        StringBuilder sb = new StringBuilder();
        for (Faction f : Faction.playable()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.id());
        }
        return sb.toString();
    }

    private static String arg(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    /** Parse, complaining to the sender on failure — so every numeric argument reports the same way. */
    private static Long number(PlayerHandle sender, String value) {
        Long parsed = parseLong(value);
        if (parsed == null) {
            sender.sendMessage("«" + value + "» — не число.");
        }
        return parsed;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException | NullPointerException malformed) {
            return null;
        }
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s) || "1".equals(s)
                || "да".equalsIgnoreCase(s) || "вкл".equalsIgnoreCase(s);
    }
}
