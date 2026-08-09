/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore;

import org.aetherium.core.AetheriumException;
import org.aetherium.edge.InteractionResult;
import org.aetherium.network.NetworkRegistry;
import org.aetherium.network.Tree;
import org.aetherium.network.TreeNode;
import org.aetherium.network.TreeSyncPacket;
import org.aetherium.ui.Button;
import org.aetherium.ui.Keys;
import org.aetherium.ui.LaidOut;
import org.aetherium.ui.Rect;
import org.aetherium.ui.RecordingUiRenderer;
import org.aetherium.ui.ScrollPanel;
import org.aetherium.ui.Text;
import org.aetherium.ui.TextField;
import org.aetherium.ui.Ui;
import org.aetherium.ui.UiMetrics;
import org.aetherium.ui.UiRuntime;
import org.aetherium.ui.Widget;
import org.aetherium.content.MachineContext;
import org.aetherium.content.MachineState;
import redstone.team.redsteelcore.block.AnomalyCoreLogic;
import redstone.team.redsteelcore.block.AnomalyCoreSlots;
import redstone.team.redsteelcore.client.ConfigScreen;
import redstone.team.redsteelcore.client.FactionHud;
import redstone.team.redsteelcore.client.FactionScreen;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigCodec;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.gameplay.FactionCommands;
import redstone.team.redsteelcore.gameplay.FactionProgress;
import redstone.team.redsteelcore.gameplay.ItemRestrictionEnforcer;
import redstone.team.redsteelcore.network.FactionNetwork;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dependency-free verification harness, in the style of the framework's own {@code *SelfTest} classes.
 *
 * <p>EN: Lives in {@code src/test/java} so it never ships in the mod jar. The build wires a plain
 * {@code java-library} (no JUnit), so the checks run from {@code main} via {@code ./gradlew runSelfTest}.
 * It covers the the framework migration end to end: the framework config store + hot-reload, the mod-scoped
 * network channel and the framework's new duplicate-channel rejection, WorldStore-style persistence, the
 * {@code /redsteel} command tree, the Focus/Resonance mechanics, and a rigorous offline UI audit proving
 * no text overflows its box and no widget escapes its parent at several screen sizes.
 * RU: Лежит в {@code src/test/java}, поэтому не попадает в jar мода. Проверки идут из {@code main} через
 * {@code ./gradlew runSelfTest}. Покрывают миграцию на the framework целиком: конфиг-стор фреймворка + горячую
 * перезагрузку, канал сети с пространством имён и новый отказ фреймворка на дубликат канала,
 * персистентность, дерево команд {@code /redsteel}, механики Концентрации/Резонанса и строгий офлайн-аудит
 * UI: ни один текст не шире своего бокса, ни один виджет не выходит за родителя на нескольких размерах.
 */
public final class RedSteelSelfTest {

    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final Rect[] SIZES = {
            new Rect(0, 0, 320, 240),
            new Rect(0, 0, 420, 400),
            new Rect(0, 0, 640, 480),
    };

    private RedSteelSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("redsteelcore-selftest");
        try {
            configRoundTrip();
            configHotReload(sandbox);
            networkChannelIsModScoped();
            networkRejectsDuplicateChannel();
            persistenceRoundTrip();
            commandsWork();
            adminSurfaceIsComplete();
            hudShowsProgress();
            adminEditsTravelToTheServer();
            restrictedItemsAreActuallyBlocked();
            mechanicsFocusAndResonance();
            uiFitsAtEverySize();
            uiIsResponsive();
            modIsDiscoverable();
            anomalyCoreLoop();
            modSurvivesWithoutOffHeapEngine();
            screenIsOpaque();
            uiScrollingIsUsable();
            uiInteractions();
            uiIsKeyboardNavigable();
            modBootsHeadless(sandbox);
        } finally {
            RedSteelConfigs.resetForTesting();
            deleteRecursively(sandbox);
        }

        int failures = FAILURES.get();
        System.out.println();
        System.out.println("RESULT: " + (failures == 0 ? "PASS ✓" : "FAIL ✗ (" + failures + " check(s))"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    // --- config --------------------------------------------------------------------------------

    private static void configRoundTrip() {
        RedSteelConfigCodec codec = new RedSteelConfigCodec();
        RedSteelConfig original = RedSteelConfig.defaults();
        RedSteelConfig parsed = codec.fromTree(codec.toTree(original));
        check("config round-trip preserves the snapshot", original.equals(parsed));
        check("the factions carry their plain names",
                parsed.displayName(Faction.SAMURAI).equals("Самураи")
                        && parsed.displayName(Faction.COMMUNISTS).equals("Коммунисты"),
                parsed.displayName(Faction.SAMURAI) + " / " + parsed.displayName(Faction.COMMUNISTS));

        RedSteelConfig clamped = codec.fromTree(codec.toTree(original.withEnergyPerEssence(-5)));
        check("a zero/negative exchange rate is clamped, never divides by zero",
                clamped.energyPerEssence() >= RedSteelConfig.MIN_ENERGY_PER_ESSENCE);
    }

    private static void configHotReload(Path sandbox) throws Exception {
        RedSteelConfigs.resetForTesting();
        Path file = sandbox.resolve("cfg").resolve("redsteelcore.json");
        List<RedSteelConfig> seen = new ArrayList<>();
        RedSteelConfigs.boot(file, seen::add);

        check("boot creates the config file", Files.isRegularFile(file));
        check("onReload fires with the initial snapshot", !seen.isEmpty());
        check("FactionManager adopted the config",
                FactionManager.get().config().energyPerEssence() == RedSteelConfigs.current().energyPerEssence());

        // Edit the file on disk; the framework watcher should hot-reload it.
        Files.writeString(file,
                Files.readString(file).replace("\"energy_per_essence\": 64", "\"energy_per_essence\": 1234"),
                StandardCharsets.UTF_8);
        boolean applied = false;
        for (int i = 0; i < 60 && !applied; i++) {
            Thread.sleep(100L);
            applied = RedSteelConfigs.current().energyPerEssence() == 1234L;
        }
        check("an on-disk edit hot-reloads without a restart", applied,
                "energyPerEssence=" + RedSteelConfigs.current().energyPerEssence());

        // Malformed edit must be rejected whole, keeping the last-good value live.
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        for (int i = 0; i < 10; i++) {
            Thread.sleep(60L);
        }
        check("a malformed edit keeps the last-good config live",
                RedSteelConfigs.current().energyPerEssence() == 1234L,
                "energyPerEssence=" + RedSteelConfigs.current().energyPerEssence());
        RedSteelConfigs.resetForTesting();
    }

    // --- network -------------------------------------------------------------------------------

    private static void networkChannelIsModScoped() {
        check("the sync channel is namespaced under the mod id",
                FactionNetwork.CHANNEL.equals("redsteelcore:faction_state")
                        && !FactionNetwork.CHANNEL.startsWith("aetherium:"),
                FactionNetwork.CHANNEL);

        NetworkRegistry.reset();
        org.aetherium.edge.Network.reset();
        int before = NetworkRegistry.size();
        FactionNetwork.register();
        // Two clientbound channels (faction state, settings) and one serverbound (admin edits). The
        // serverbound one lives in a separate registry because it is a separate direction.
        check("registering installs both clientbound channels", NetworkRegistry.size() == before + 2,
                "size delta " + (NetworkRegistry.size() - before));
        check("registering installs the serverbound admin channel",
                org.aetherium.edge.Network.serverboundEntries().stream()
                        .anyMatch(e -> e.channelId().equals(FactionNetwork.ADMIN_CHANNEL)),
                org.aetherium.edge.Network.serverboundEntries().toString());

        UUID player = UUID.randomUUID();
        FactionManager.get().join(player, Faction.SAMURAI);
        FactionManager.get().setEssence(player, 42L);
        TreeNode root = FactionNetwork.packetFor(player).root();
        FactionManager.get().setEssence(player, 0L);
        FactionNetwork.applyOnClient(root);
        check("a snapshot survives an apply round trip",
                FactionManager.get().essence(player) == 42L
                        && FactionManager.get().faction(player) == Faction.SAMURAI);
    }

    private static void networkRejectsDuplicateChannel() {
        boolean threw = false;
        try {
            FactionNetwork.register(); // already registered above → framework must reject the duplicate
        } catch (AetheriumException expected) {
            threw = true;
        }
        check("the framework rejects a duplicate channel (interop safety)", threw);
    }

    // --- persistence ---------------------------------------------------------------------------

    private static void persistenceRoundTrip() {
        FactionManager m = FactionManager.get();
        UUID a = UUID.randomUUID();
        m.join(a, Faction.COMMUNISTS);
        m.setEssence(a, 777L);
        m.setQuota(a, 12L);
        m.claim(200, 200, Faction.COMMUNISTS);
        m.raiseSkill(Faction.COMMUNISTS, "mining", 4L);

        TreeNode saved = m.exportState();
        // Wipe by importing an empty doc, then restore.
        m.importState(org.aetherium.network.Tree.object().build());
        check("import of an empty doc clears state", m.faction(a) == Faction.NEUTRAL);

        m.importState(saved);
        check("membership survives persistence", m.faction(a) == Faction.COMMUNISTS);
        check("essence survives persistence", m.essence(a) == 777L);
        check("quota survives persistence", m.quota(a) == 12L);
        check("territory survives persistence", m.ownerOfBlock(200, 200) == Faction.COMMUNISTS);
        check("skills survive persistence", m.skills(Faction.COMMUNISTS).get("mining") == 5L);
    }

    // --- commands ------------------------------------------------------------------------------

    private static void commandsWork() {
        RedSteelConfigs.resetForTesting();
        FakePlayer player = new FakePlayer("Player", 0);
        FakePlayer admin = new FakePlayer("Admin", FactionCommands.ADMIN_PERMISSION);
        FactionManager.get().leave(player.id());

        FactionCommands.run(player, List.of("join samurai"));
        check("/redsteel join sets the faction",
                FactionManager.get().faction(player.id()) == Faction.SAMURAI);
        FactionCommands.run(player, List.of("leave"));
        check("/redsteel leave clears the faction",
                FactionManager.get().faction(player.id()) == Faction.NEUTRAL);

        check("an unknown sub-command is refused",
                FactionCommands.run(player, List.of("wibble")) == InteractionResult.CANCEL);

        RedSteelConfigs.boot(Path.of(System.getProperty("java.io.tmpdir"),
                "rsc-cmd-" + UUID.randomUUID() + ".json"), c -> { });

        // Permission is enforced per sub-command via PlayerHandle.hasPermission, so the same command tree
        // serves both audiences. An unprivileged caller must be refused AND must not change anything.
        long before = RedSteelConfigs.current().energyPerEssence();
        check("an unprivileged player cannot change the config",
                FactionCommands.run(player, List.of("config set energy_per_essence 250"))
                        == InteractionResult.CANCEL
                        && RedSteelConfigs.current().energyPerEssence() == before);

        FactionCommands.run(admin, List.of("config set energy_per_essence 250"));
        check("an admin can change the config",
                RedSteelConfigs.current().energyPerEssence() == 250L,
                "energyPerEssence=" + RedSteelConfigs.current().energyPerEssence());

        check("an unprivileged player cannot claim territory",
                FactionCommands.run(player, List.of("claim samurai")) == InteractionResult.CANCEL);

        // /redsteel gui must not pretend to work on a dedicated server, where no UiAccess is registered.
        player.messages.clear();
        InteractionResult gui = FactionCommands.run(player, List.of("gui"));
        check("/redsteel gui reports honestly with no client present",
                gui == InteractionResult.CANCEL
                        && player.messages.stream().anyMatch(s -> s.contains("client-side")),
                player.messages.toString());
        RedSteelConfigs.resetForTesting();
    }

    /**
     * Everything the settings screen can change must also be reachable from a console, because the screen
     * writes the config of the process it runs in and the framework has no client→server channel. A gap here
     * means a dedicated server that cannot be administered at all.
     */
    private static void adminSurfaceIsComplete() {
        RedSteelConfigs.resetForTesting();
        RedSteelConfigs.boot(Path.of(System.getProperty("java.io.tmpdir"),
                "rsc-admin-" + UUID.randomUUID() + ".json"), c -> { });
        FakePlayer admin = new FakePlayer("Admin", FactionCommands.ADMIN_PERMISSION);
        FakePlayer player = new FakePlayer("Player", 0);

        // Every advertised key really writes: no key may be listed and then quietly ignored.
        List<String> unwritable = new ArrayList<>();
        for (String key : FactionCommands.CONFIG_KEYS) {
            String before = FactionCommands.readBack(RedSteelConfigs.current(), key);
            String probe = "true".equals(before) ? "false"
                    : "false".equals(before) ? "true"
                    : String.valueOf(Long.parseLong(before) + 1);
            FactionCommands.run(admin, List.of("config set " + key + " " + probe));
            if (!probe.equals(FactionCommands.readBack(RedSteelConfigs.current(), key))) {
                unwritable.add(key);
            }
        }
        check("every advertised config key is actually settable", unwritable.isEmpty(), unwritable.toString());

        // A saved snapshot has to become the rules IN FORCE, not just what the admin is shown back.
        FactionCommands.run(admin, List.of("config set friendly_fire_protection false"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FactionManager.get().join(a, Faction.SAMURAI);
        FactionManager.get().join(b, Faction.SAMURAI);
        check("disabling a rule takes effect immediately, without a reload",
                FactionManager.get().canAttack(a, b));

        RedSteelConfigs.save(RedSteelConfig.defaults()); // the probe loop toggled everything; start clean

        // A clamped value must be echoed as STORED, not as typed, or the admin walks away misinformed.
        admin.messages.clear();
        FactionCommands.run(admin, List.of("config set focus_max 99999999"));
        check("a clamped setting reports the value that was really stored",
                admin.messages.stream().anyMatch(m -> m.contains("focus_max = "
                        + RedSteelConfigs.current().mechanics().focusMax())),
                admin.messages.toString());

        // --- restricted items, the thing an admin most often edits -----------------------------
        FactionCommands.run(admin, List.of("item add minecraft:diamond_sword samurai"));
        check("/redsteel item add writes a restriction",
                RedSteelConfigs.current().restrictionOwner("minecraft:diamond_sword") == Faction.SAMURAI);
        check("the restriction is enforced against the other faction",
                !FactionManager.get().canUseItem(commieId(), "minecraft:diamond_sword"));

        admin.messages.clear();
        FactionCommands.run(admin, List.of("item list"));
        check("/redsteel item list shows what is restricted",
                admin.messages.stream().anyMatch(m -> m.contains("minecraft:diamond_sword")),
                admin.messages.toString());

        check("a malformed id is refused rather than stored",
                FactionCommands.run(admin, List.of("item add not_an_id")) == InteractionResult.CANCEL
                        && RedSteelConfigs.current().restrictionOwner("not_an_id") == Faction.NEUTRAL);

        FactionCommands.run(admin, List.of("item remove minecraft:diamond_sword"));
        check("/redsteel item remove clears the restriction",
                RedSteelConfigs.current().restrictionOwner("minecraft:diamond_sword") == Faction.NEUTRAL);

        // The no-typing path: the item in hand, named exactly as the game names it.
        admin.holding("minecraft:netherite_axe", "minecraft:shield").selecting(1);
        check("the held item is read from the selected slot, not the first one",
                "minecraft:shield".equals(FactionCommands.heldItem(admin)),
                String.valueOf(FactionCommands.heldItem(admin)));
        FactionCommands.run(admin, List.of("item add hand communists"));
        check("/redsteel item add hand restricts exactly what is held",
                RedSteelConfigs.current().restrictionOwner("minecraft:shield") == Faction.COMMUNISTS
                        && RedSteelConfigs.current().restrictionOwner("minecraft:netherite_axe")
                                == Faction.NEUTRAL);

        admin.selecting(-1);
        check("an empty hand is refused rather than restricting something arbitrary",
                FactionCommands.heldItem(admin) == null
                        && FactionCommands.run(admin, List.of("item add hand")) == InteractionResult.CANCEL);

        check("a typed id that is not namespace:path is rejected on both surfaces",
                !FactionCommands.isItemId("diamond_sword") && !FactionCommands.isItemId("minecraft:")
                        && FactionCommands.isItemId("minecraft:diamond_sword"));

        check("an unprivileged player cannot touch the item table",
                FactionCommands.run(player, List.of("item add minecraft:stick samurai"))
                        == InteractionResult.CANCEL
                        && RedSteelConfigs.current().restrictionOwner("minecraft:stick") == Faction.NEUTRAL);

        // --- territory: claim, audit, undo ------------------------------------------------------
        FakePlayer surveyor = new FakePlayer("Admin", FactionCommands.ADMIN_PERMISSION).at(1000, -2000);
        FactionCommands.run(surveyor, List.of("claim samurai"));
        check("/redsteel claim takes the chunk the admin stands in",
                FactionManager.get().ownerOfBlock(1000, -2000) == Faction.SAMURAI);

        surveyor.messages.clear();
        FactionCommands.run(surveyor, List.of("claim list"));
        String expectedChunk = (1000 >> 4) + "," + (-2000 >> 4);
        check("/redsteel claim list audits the claim by chunk coordinate",
                surveyor.messages.stream().anyMatch(m -> m.contains(expectedChunk)),
                surveyor.messages.toString());

        surveyor.messages.clear();
        FactionCommands.run(surveyor, List.of("claim here"));
        check("/redsteel claim here names the owner of the ground underfoot",
                surveyor.messages.stream().anyMatch(m -> m.contains(
                        RedSteelConfigs.current().displayName(Faction.SAMURAI))),
                surveyor.messages.toString());

        FactionCommands.run(surveyor, List.of("claim remove"));
        check("/redsteel claim remove releases the chunk",
                FactionManager.get().ownerOfBlock(1000, -2000) == Faction.NEUTRAL
                        && !FactionManager.get().claims().containsKey(expectedChunk),
                FactionManager.get().claims().toString());

        // --- other players ----------------------------------------------------------------------
        // With no platform bridge there is nobody online, so this proves the failure path reports rather
        // than throwing — the branch a console operator hits when they mistype a nickname.
        admin.messages.clear();
        check("/redsteel player refuses an unknown nickname without throwing",
                FactionCommands.run(admin, List.of("player Nobody faction samurai"))
                        == InteractionResult.CANCEL
                        && admin.messages.stream().anyMatch(m -> m.contains("Nobody")),
                admin.messages.toString());
        check("an unprivileged player cannot administer anyone",
                FactionCommands.run(player, List.of("player Admin essence 1000"))
                        == InteractionResult.CANCEL);

        // Help must advertise the admin surface, or a feature that exists is a feature nobody finds.
        admin.messages.clear();
        FactionCommands.run(admin, List.of("help"));
        String help = String.join(" | ", admin.messages);
        check("help advertises every admin sub-command",
                help.contains("item") && help.contains("claim") && help.contains("player")
                        && help.contains("config"),
                help);
        admin.messages.clear();
        FactionCommands.run(player, List.of("help"));
        check("help hides admin sub-commands from ordinary players",
                player.messages.stream().noneMatch(m -> m.contains("config set")),
                player.messages.toString());

        RedSteelConfigs.resetForTesting();
    }

    /** A player in the other faction, for asserting that a restriction actually bites. */
    private static UUID commieId() {
        UUID id = UUID.randomUUID();
        FactionManager.get().join(id, Faction.COMMUNISTS);
        return id;
    }

    /**
     * The HUD is the only place either progress meter is visible while it can still move, so it has to lay
     * out cleanly at every window size — and it must stay out of the way of a player with no faction.
     */
    private static void hudShowsProgress() {
        RedSteelConfigs.resetForTesting();
        FakePlayer viewer = new FakePlayer("Viewer", 0);
        // Platform.installForTesting (the framework) presents a local player headlessly, so the HUD is tested
        // through the same PlayerAccess.local() path it uses in game — no injected supplier to keep in sync.
        TestBridge.withLocalPlayer(viewer, () -> {
            FactionHud hud = new FactionHud();

            FactionManager.get().leave(viewer.id());
            check("the HUD hides itself for a player with no faction", !hud.visible());

            FactionManager.get().join(viewer.id(), Faction.SAMURAI);
            FactionManager.get().setEssence(viewer.id(), 42L);
            FactionProgress.get().reset(viewer.id());
            FactionProgress.get().onHonourableKill(viewer.id());
            check("the HUD appears once the player has a faction", hud.visible());

            // the framework's audit gained a sibling-overlap rule, so the meter bar overdrawing its own label —
            // which the previous build had to pin by hand — is now caught by the framework contract itself.
            List<String> violations = new ArrayList<>();
            for (Rect size : SIZES) {
                violations.addAll(UiRuntime.audit(layout(hud.build(size), size), UiMetrics.DEFAULT));
            }
            check("the HUD lays out cleanly at every window size: no escape, no clipping, no overlap",
                    violations.isEmpty(), violations.toString());

            RecordingUiRenderer paint = new RecordingUiRenderer();
            UiRuntime.render(hud.build(SIZES[0]), SIZES[0], UiMetrics.DEFAULT, paint);
            String drawn = drawnText(paint);
            long focusMax = RedSteelConfigs.current().mechanics().focusMax();
            check("the HUD names the faction, the essence and the meter with its threshold",
                    drawn.contains(RedSteelConfigs.current().displayName(Faction.SAMURAI))
                            && drawn.contains("42")
                            && drawn.contains("Концентрация")
                            && drawn.contains("/" + focusMax),
                    drawn);

            // The HUD has to explain a veto BEFORE it happens. A cancelled swing looks exactly like a
            // broken mod — which is how the restriction system was reported — so holding an item you may
            // not use has to say so while you are merely holding it.
            RedSteelConfigs.save(RedSteelConfigs.current()
                    .withRestrictedItem("minecraft:diamond_sword", Faction.COMMUNISTS));
            viewer.holding("minecraft:diamond_sword").selecting(0);
            RecordingUiRenderer blocked = new RecordingUiRenderer();
            UiRuntime.render(hud.build(SIZES[0]), SIZES[0], UiMetrics.DEFAULT, blocked);
            check("the HUD warns about an item this player may not use, before they swing it",
                    drawnText(blocked).contains("Коммунисты"), drawnText(blocked));

            // Territory protection also vetoes silently; naming the owner underfoot shows the rule's edge.
            FactionManager.get().claim(0, 0, Faction.COMMUNISTS);
            RecordingUiRenderer ground = new RecordingUiRenderer();
            UiRuntime.render(hud.build(SIZES[0]), SIZES[0], UiMetrics.DEFAULT, ground);
            check("the HUD names the owner of the ground underfoot",
                    drawnText(ground).contains("Чужая земля"), drawnText(ground));
            FactionManager.get().unclaim(0, 0);
            viewer.holding(org.aetherium.edge.InventoryAccess.AIR).selecting(-1);
            RedSteelConfigs.save(RedSteelConfig.defaults());

            // Every added line still has to obey the layout contract at every size.
            List<String> after = new ArrayList<>();
            for (Rect size : SIZES) {
                after.addAll(UiRuntime.audit(layout(hud.build(size), size), UiMetrics.DEFAULT));
            }
            check("the fuller HUD still lays out cleanly at every size", after.isEmpty(), after.toString());

            // A commune player must see their OWN faucet, not the samurai one.
            FactionManager.get().join(viewer.id(), Faction.COMMUNISTS);
            RecordingUiRenderer commune = new RecordingUiRenderer();
            UiRuntime.render(hud.build(SIZES[0]), SIZES[0], UiMetrics.DEFAULT, commune);
            check("each faction sees only its own progress meter",
                    drawnText(commune).contains("Резонанс")
                            && !drawnText(commune).contains("Концентрация"),
                    drawnText(commune));
        });

        FactionManager.get().leave(viewer.id());
        RedSteelConfigs.resetForTesting();
    }

    /**
     * The settings screen no longer writes anything: it sends the draft to the server, which is the only
     * authority. That makes the serverbound handler the single thing standing between an ordinary player and
     * the server's rules, so it is tested through the path that actually runs — real codec, real payload,
     * real sender — rather than against a mock of it.
     */
    private static void adminEditsTravelToTheServer() {
        RedSteelConfigs.resetForTesting();
        RedSteelConfigs.boot(Path.of(System.getProperty("java.io.tmpdir"),
                "rsc-net-" + UUID.randomUUID() + ".json"), c -> { });
        RedSteelConfigCodec codec = new RedSteelConfigCodec();
        FakePlayer admin = new FakePlayer("Admin", FactionCommands.ADMIN_PERMISSION);
        FakePlayer player = new FakePlayer("Player", 0);

        RedSteelConfig edited = RedSteelConfigs.current()
                .withDisplayName(Faction.SAMURAI, "Проверка")
                .withEnergyPerEssence(99L);
        TreeSyncPacket packet = new TreeSyncPacket(FactionNetwork.ADMIN_CHANNEL, codec.toTree(edited));

        // An ordinary player's packet must change nothing. Anyone can send bytes on a channel; permission is
        // the only thing that makes this safe, and it has to be checked on the SERVER, not in the screen.
        long before = RedSteelConfigs.current().energyPerEssence();
        FactionNetwork.onAdminConfig(player, packet);
        check("a non-admin's settings packet is refused and changes nothing",
                RedSteelConfigs.current().energyPerEssence() == before
                        && player.messages.stream().anyMatch(m -> m.contains("прав")),
                player.messages.toString());

        // Bytes off the wire may be anything at all. This has to be asserted against a config that is NOT at
        // its defaults, because the codec answers a junk tree with a valid factory-default config — so a
        // handler that simply trusted it would "succeed" at wiping every setting on the server, and a test
        // starting from defaults could not tell the difference.
        RedSteelConfigs.save(RedSteelConfigs.current()
                .withEnergyPerEssence(1234L)
                .withTerritoryProtection(false));
        admin.messages.clear();
        FactionNetwork.onAdminConfig(admin,
                new TreeSyncPacket(FactionNetwork.ADMIN_CHANNEL, Tree.object().put("junk", 1L).build()));
        check("a junk settings packet is refused instead of resetting the server to defaults",
                RedSteelConfigs.current().energyPerEssence() == 1234L
                        && !RedSteelConfigs.current().territoryProtection()
                        && admin.messages.stream().anyMatch(m -> m.contains("повреждён")),
                RedSteelConfigs.current().energyPerEssence() + " / " + admin.messages);

        // An empty object is the same attack with a friendlier shape.
        FactionNetwork.onAdminConfig(admin,
                new TreeSyncPacket(FactionNetwork.ADMIN_CHANNEL, Tree.object().build()));
        check("an empty settings packet is refused too",
                RedSteelConfigs.current().energyPerEssence() == 1234L);

        admin.messages.clear();
        long versionBefore = RedSteelConfigs.version();
        FactionNetwork.onAdminConfig(admin, packet);
        check("an admin's settings packet is applied on the server",
                RedSteelConfigs.current().energyPerEssence() == 99L
                        && RedSteelConfigs.current().displayName(Faction.SAMURAI).equals("Проверка"),
                RedSteelConfigs.current().displayName(Faction.SAMURAI));
        check("applying bumps the settings version, so an open screen follows the server",
                RedSteelConfigs.version() > versionBefore);
        check("the rules in force follow the packet, not just the displayed values",
                FactionManager.get().config().energyPerEssence() == 99L);

        // The client mirror must NOT write the server's rules into the player's own config file — that would
        // leak a server's configuration into their single-player world.
        RedSteelConfigs.resetForTesting();
        FactionNetwork.applyConfigOnClient(codec.toTree(edited));
        check("a client adopts the server's settings without a store to write to",
                RedSteelConfigs.current().energyPerEssence() == 99L
                        && RedSteelConfigs.store() == null);

        RedSteelConfigs.resetForTesting();
    }

    /**
     * The restricted-item table has to actually stop people using the item, which it did not.
     *
     * <p>Two independent reasons, both reproduced here: the rule asked about <em>hostility</em>, so an
     * unaligned player — which is most players, and usually the admin testing it — was refused nothing; and
     * it was wired only to "right-click in open air", which is not how anyone uses a weapon, a tool or a
     * block. These checks fail against the previous build.
     */
    private static void restrictedItemsAreActuallyBlocked() {
        RedSteelConfigs.resetForTesting();
        RedSteelConfigs.save(RedSteelConfig.defaults()
                .withRestrictedItem("minecraft:diamond_sword", Faction.SAMURAI));
        FactionManager factions = FactionManager.get();

        FakePlayer samurai = new FakePlayer("Samurai", 0);
        FakePlayer commie = new FakePlayer("Commie", 0);
        FakePlayer nobody = new FakePlayer("Nobody", 0);
        factions.join(samurai.id(), Faction.SAMURAI);
        factions.join(commie.id(), Faction.COMMUNISTS);
        factions.leave(nobody.id());

        check("the owning faction may use its own item",
                factions.canUseItem(samurai.id(), "minecraft:diamond_sword"));
        check("the enemy faction may not",
                !factions.canUseItem(commie.id(), "minecraft:diamond_sword"));
        // THE bug: hostility is only defined between two non-neutral factions, so an unaligned player used
        // to be refused nothing — and most players, including an admin testing the feature, are unaligned.
        check("an unaligned player may not use a faction's item either",
                !factions.canUseItem(nobody.id(), "minecraft:diamond_sword"));
        check("an unrestricted item is unaffected for everyone",
                factions.canUseItem(commie.id(), "minecraft:stone")
                        && factions.canUseItem(nobody.id(), "minecraft:stone"));

        // Every vector, through the enforcer's own decision, since that is what the events are wired to.
        commie.holding("minecraft:diamond_sword").selecting(0);
        check("holding a restricted item is refused (attack / break / place / interact all use this)",
                ItemRestrictionEnforcer.check(commie, "minecraft:diamond_sword") == InteractionResult.CANCEL);
        check("an empty hand is never refused",
                ItemRestrictionEnforcer.check(commie, null) == InteractionResult.PASS);
        check("the refusal names the owning faction so the player knows why",
                commie.messages.stream().anyMatch(m -> m.contains("Самураи")),
                commie.messages.toString());

        // A swing produces this call many times a second; the veto must repeat, the message must not.
        int before = commie.messages.size();
        for (int i = 0; i < 50; i++) {
            check1(ItemRestrictionEnforcer.check(commie, "minecraft:diamond_sword")
                    == InteractionResult.CANCEL);
        }
        check("every repeat is still vetoed, but the chat is not spammed",
                commie.messages.size() == before, "extra messages: " + (commie.messages.size() - before));

        // Turning the rule off must release everyone, immediately.
        RedSteelConfigs.save(RedSteelConfigs.current().withItemRestrictions(false));
        check("disabling item restrictions releases the item at once",
                factions.canUseItem(commie.id(), "minecraft:diamond_sword")
                        && ItemRestrictionEnforcer.check(commie, "minecraft:diamond_sword")
                                == InteractionResult.PASS);

        factions.leave(samurai.id());
        factions.leave(commie.id());
        RedSteelConfigs.resetForTesting();
    }

    /** A silent assertion used inside loops, where one message per iteration would drown the output. */
    private static void check1(boolean ok) {
        if (!ok) {
            check("a repeated action with a restricted item is still vetoed", false);
        }
    }

    /**
     * Every control must be reachable without the mouse wheel.
     *
     * <p>The wheel used to be the only way to move through the settings, which made the last rows of a long
     * list genuinely awkward to get to. The fix is not "add a scrollbar" — it is that the same row model
     * drives the keyboard, so these checks assert the model, not a pixel.
     */
    private static void uiIsKeyboardNavigable() {
        RedSteelConfigs.resetForTesting();
        Rect size = new Rect(0, 0, 420, 300);
        ConfigScreen screen = new ConfigScreen();
        layout(screen.build(size), size);

        check("the hub opens on the menu", screen.section() == ConfigScreen.Section.HUB);
        check("the menu has one row per section",
                screen.rowCount() == ConfigScreen.Section.menu().length,
                "rows=" + screen.rowCount());

        // A number key jumps straight to a section — the shortest possible path to a deep setting.
        screen.onKey(Keys.NUM_4, 0);
        check("a number key jumps straight into its section",
                screen.section() == ConfigScreen.Section.PROGRESS, String.valueOf(screen.section()));
        layout(screen.build(size), size);

        // Esc climbs back out; at the hub it declines to handle Esc so the platform can close the screen.
        check("Esc leaves a section", screen.onKey(Keys.ESCAPE, 0)
                && screen.section() == ConfigScreen.Section.HUB);
        layout(screen.build(size), size);
        check("Esc at the hub is left to the platform, which closes the screen",
                !screen.onKey(Keys.ESCAPE, 0));

        // Arrow keys change a value in place — no travelling to a distant pair of buttons.
        screen.onKey(Keys.NUM_2, 0); // CORE
        layout(screen.build(size), size);
        long energy = screen.draft().energyPerEssence();
        check("→ raises the selected value in place",
                screen.onKey(Keys.RIGHT, 0) && screen.draft().energyPerEssence() == energy + 1,
                "was " + energy + ", now " + screen.draft().energyPerEssence());
        check("← lowers it", screen.onKey(Keys.LEFT, 0)
                && screen.draft().energyPerEssence() == energy);
        check("Shift makes the step coarse, so a big change is not fifty keypresses",
                screen.onKey(Keys.RIGHT, 0x0001) && screen.draft().energyPerEssence() == energy + 10,
                String.valueOf(screen.draft().energyPerEssence()));

        // The cursor must never rest on a caption: pressing Enter on a line that does nothing reads as
        // broken input, so captions are skipped rather than merely ignored.
        screen.onKey(Keys.NUM_4, 0); // PROGRESS — its first line is a caption
        layout(screen.build(size), size);
        check("the cursor starts on something that can actually be used", screen.cursor() > 0,
                "cursor=" + screen.cursor());
        int seen = screen.cursor();
        boolean landedOnCaption = false;
        for (int i = 0; i < screen.rowCount() * 2; i++) {
            screen.onKey(Keys.DOWN, 0);
            layout(screen.build(size), size);
            if (screen.cursor() == seen) {
                landedOnCaption = true; // the cursor failed to move, i.e. it is stuck on a dead line
            }
            seen = screen.cursor();
        }
        check("↓ never gets stuck on a caption", !landedOnCaption);

        // End reaches the bottom of a long page in one press — the specific thing the wheel made tedious.
        screen.onKey(Keys.HOME, 0);
        layout(screen.build(size), size);
        int top = screen.cursor();
        screen.onKey(Keys.END, 0);
        layout(screen.build(size), size);
        check("End reaches the last usable row in one press, Home returns", screen.cursor() > top,
                "home=" + top + " end=" + screen.cursor());

        RedSteelConfigs.resetForTesting();
    }

    // --- mechanics -----------------------------------------------------------------------------

    private static void mechanicsFocusAndResonance() {
        RedSteelConfigs.resetForTesting();
        FactionManager m = FactionManager.get();
        FactionProgress p = FactionProgress.get();

        UUID samurai = UUID.randomUUID();
        m.join(samurai, Faction.SAMURAI);
        m.setEssence(samurai, 0L);
        p.reset(samurai);
        // focusPerKill=15, focusMax=100 → 7 kills to fill, banking focusReward=10 essence.
        boolean completed = false;
        for (int i = 0; i < 7; i++) {
            completed = p.onHonourableKill(samurai);
        }
        check("Focus fills after enough kills and banks essence", completed && m.essence(samurai) == 10L,
                "essence=" + m.essence(samurai));
        p.onHonourableKill(samurai);
        p.onDamageTaken(samurai);
        check("taking damage resets Focus", p.focus(samurai) == 0L);

        UUID communist = UUID.randomUUID();
        m.join(communist, Faction.COMMUNISTS);
        m.setEssence(communist, 0L);
        p.reset(communist);
        // comboNeeded=10 → 10 non-player-placed mines within the window.
        boolean comboDone = false;
        for (int i = 0; i < 10; i++) {
            comboDone = p.onResonantMine(communist, false);
        }
        check("Resonance combo completes and banks essence", comboDone && m.essence(communist) == 10L,
                "essence=" + m.essence(communist));

        p.reset(communist);
        m.setEssence(communist, 0L);
        for (int i = 0; i < 20; i++) {
            p.onResonantMine(communist, true); // silk-touch replant: playerPlaced → must not count
        }
        check("player-placed blocks never grow a combo (anti-abuse)",
                p.combo(communist) == 0L && m.essence(communist) == 0L);
    }

    // --- UI: the core UX requirement — text fits, nothing overflows -----------------------------

    private static void uiFitsAtEverySize() {
        RedSteelConfigs.resetForTesting();
        List<String> violations = new ArrayList<>();
        for (Rect size : SIZES) {
            for (Widget<?> screen : screensUnderTest(size)) {
                // the framework's two-argument audit reports BOTH a box escaping its parent and text clipped by
                // its own box — the whole layout contract in one call, against the framework's definition.
                violations.addAll(UiRuntime.audit(layout(screen, size), UiMetrics.DEFAULT));
            }
        }
        check("layout is clean at every size: nothing escapes, no text is cut off",
                violations.isEmpty(), violations.size() + " violation(s): " + violations);

        // The scroll region must absorb the space between the pinned title and action bar. If it collapses
        // to its intrinsic height (one line) the screen still "lays out" but is unusable.
        for (Rect size : SIZES) {
            int panelHeight = scrollPanelHeight(layout(new ConfigScreen().build(), size));
            check("the settings list fills the window at " + size.width() + "x" + size.height(),
                    panelHeight >= size.height() / 3, "scroll panel is only " + panelHeight + "px tall");
        }
    }

    private static int scrollPanelHeight(LaidOut node) {
        if (node.widget() instanceof org.aetherium.ui.ScrollPanel) {
            return node.rect().height();
        }
        for (LaidOut child : node.children()) {
            int found = scrollPanelHeight(child);
            if (found > 0) {
                return found;
            }
        }
        return 0;
    }

    /**
     * Scrolling must be usable, not merely present.
     *
     * <p>Three properties a reader depends on: the region reports something to scroll when the content is
     * taller than the view; a scroll actually moves it; and — the one that used to fail — the position
     * <em>survives the rebuild</em> that happens on the very next frame. Without the last one a list
     * silently snaps back to the top the instant anything on the screen changes.
     */
    private static void uiScrollingIsUsable() {
        RedSteelConfigs.resetForTesting();
        Rect size = new Rect(0, 0, 420, 260); // deliberately short
        ConfigScreen screen = new ConfigScreen();

        // The HUB must NOT need scrolling — that is the point of splitting the editor into sections, and a
        // hub that overflowed would have reintroduced exactly the problem this design removes.
        LaidOut hub = layout(screen.build(size), size);
        ScrollPanel hubPanel = findScrollPanel(hub);
        check("the section menu fits without scrolling, even in a short window",
                hubPanel != null && hubPanel.maxScroll() == 0,
                hubPanel == null ? "no scroll panel" : "maxScroll=" + hubPanel.maxScroll());

        // A genuinely long page (the progress mechanics) still scrolls, and must do so usably.
        screen.goTo(ConfigScreen.Section.PROGRESS);
        LaidOut first = layout(screen.build(size), size);
        ScrollPanel panel = findScrollPanel(first);
        check("a long section is scrollable when it does not fit", panel != null && panel.maxScroll() > 0,
                panel == null ? "no scroll panel" : "maxScroll=" + panel.maxScroll());
        if (panel == null) {
            return;
        }
        check("the scroll region draws a scrollbar so the player can see there is more",
                panel.scrollbar());

        // Both input routes the framework provides: the wheel and PAGE_DOWN. The mod adds no controls of
        // its own any more — the framework's built-in scrollbar replaced them.
        boolean wheel = UiRuntime.scroll(first, size.width() / 2, size.height() / 2, 40);
        check("the mouse wheel scrolls the settings list", wheel && panel.scrollOffset() > 0,
                "offset=" + panel.scrollOffset());
        int afterWheel = panel.scrollOffset();
        UiRuntime.keyPressed(first, UiRuntime.KEY_PAGE_DOWN, 0);
        check("PAGE_DOWN scrolls the settings list", panel.scrollOffset() > afterWheel,
                "before=" + afterWheel + " after=" + panel.scrollOffset());
        int afterScroll = panel.scrollOffset();

        // The rebuild that every frame performs must not lose the reader's place.
        layout(screen.build(size), size);
        check("scroll position survives a rebuild of the screen",
                panel.scrollOffset() == afterScroll,
                "before=" + afterScroll + " after=" + panel.scrollOffset());

        // And it must clamp: hammering the control cannot scroll past the end.
        for (int i = 0; i < 50; i++) {
            panel.setScrollOffset(panel.scrollOffset() + 48);
        }
        layout(screen.build(size), size);
        check("scrolling is clamped to the end of the content",
                panel.scrollOffset() == panel.maxScroll(),
                "offset=" + panel.scrollOffset() + " max=" + panel.maxScroll());
    }

    private static ScrollPanel findScrollPanel(LaidOut node) {
        if (node.widget() instanceof ScrollPanel p) {
            return p;
        }
        for (LaidOut child : node.children()) {
            ScrollPanel found = findScrollPanel(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Every screen state a user can reach, built fresh for the given viewport. */
    private static List<Widget<?>> screensUnderTest(Rect size) {
        List<Widget<?>> out = new ArrayList<>();
        out.add(factionScreen().build(size));
        out.add(new ConfigScreen().build(size));
        ConfigScreen editing = new ConfigScreen();     // the text-entry sub-screen
        openRename(editing, size);
        out.add(editing.build(size));
        return out;
    }

    private static final boolean DEBUG_UI = Boolean.getBoolean("rsc.debugUi");

    private static String describe(Widget<?> w) {
        String t = textOf(w);
        return w.getClass().getSimpleName() + (t == null ? "" : "('" + t + "')");
    }

    private static String rect(Rect r) {
        return "[" + r.x() + "," + r.y() + " " + r.width() + "x" + r.height() + "]";
    }

    private static void uiInteractions() {
        RedSteelConfigs.resetForTesting();
        // A viewport tall enough that nothing is scrolled out of view: a clipped widget is deliberately
        // NOT clickable (UiRuntime.hitTest refuses points outside a ScrollPanel), which is correct
        // behaviour, so interaction is exercised where the targets are actually visible. Fit at small
        // sizes is covered separately by uiFitsAtEverySize().
        Rect size = new Rect(0, 0, 520, 900);

        ConfigScreen screen = new ConfigScreen();
        boolean before = screen.draft().territoryProtection();
        // Reach the rule the way a player does: the hub is a menu, and "Правила" is its third entry.
        layout(screen.build(), size);
        Rect entry = findButton(layout(screen.build(), size), "3. Правила");
        check("the hub lists the sections as a menu", entry != null);
        if (entry != null) {
            click(layout(screen.build(), size), entry);
        }
        check("clicking a menu entry opens that section",
                screen.section() == ConfigScreen.Section.RULES, String.valueOf(screen.section()));
        LaidOut tree = layout(screen.build(), size);
        // The row's own label is the control: clicking anywhere on the setting flips it, rather than
        // requiring the player to hit a small ВКЛ/ВЫКЛ target at the far right of the row.
        Rect toggle = findButton(tree, "Защита территорий");
        check("the territory toggle is on screen", toggle != null);
        if (toggle != null) {
            // Assert the click actually LANDS, not just that the widget exists: a scroll region that
            // collapsed to one line still lays its rows out (they are merely clipped), so they would be
            // found by a search yet be unreachable for a player. This is the guard for that failure mode.
            check("the toggle is reachable, not clipped away", click(tree, toggle));
            check("clicking the toggle flips the draft", screen.draft().territoryProtection() != before);
        }

        // Rename via the focusable TextField: click rename → type → Enter commits.
        ConfigScreen editing = new ConfigScreen();
        openRename(editing, size);
        check("rename opened a focused text field", editing.isEditingText());
        LaidOut edit = layout(editing.build(), size);
        TextField field = editing.textEditor();
        field.setFocused(true);
        UiRuntime.charTyped(edit, 'X');
        editing.onKey(UiRuntime.KEY_ENTER, 0);
        check("typing + Enter commits the new faction name",
                editing.draft().displayName(Faction.SAMURAI).endsWith("X"),
                editing.draft().displayName(Faction.SAMURAI));

        // FactionScreen join button actually joins.
        FactionScreen fs = factionScreen();
        LaidOut ftree = layout(fs.build(), size);
        Rect join = findButton(ftree, "Вступить: " + RedSteelConfigs.current().displayName(Faction.SAMURAI));
        check("a join button is present", join != null);
        if (join != null) {
            click(ftree, join);
            check("clicking join sets the faction", FactionManager.get().faction(VIEWER) == Faction.SAMURAI);
        }
    }

    // --- headless boot (the closest honest stand-in for "it starts on a server") ------------------

    /**
     * Run the mod's real {@code @AetheriumInit} entrypoint against the no-op platform bridge.
     *
     * <p>This is not a substitute for launching Minecraft, and does not claim to be: no game is present, so
     * the edge events never fire. What it does prove is that the entrypoint the loader will call completes
     * without throwing — config opened, all event listeners registered, the network channel claimed, the
     * command tree registered, the anomaly engine allocated — which is exactly the class of failure that
     * takes a dedicated server down at start-up.
     */
    private static void modBootsHeadless(Path sandbox) {
        RedSteelConfigs.resetForTesting();
        NetworkRegistry.reset();
        org.aetherium.edge.Network.reset(); // the serverbound registry claims channel ids too
        String previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", sandbox.toAbsolutePath().toString());

        List<String> log = new ArrayList<>();
        try {
            redstone.team.redsteelcore.gameplay.FactionRestrictions.init(new org.aetherium.core.mod.AetheriumContext() {
                @Override public void log(String message) {
                    log.add(message);
                }

                @Override public org.aetherium.core.CapabilityTier computeTier() {
                    return org.aetherium.core.CapabilityTier.PURE_JAVA;
                }
            });
            check("the mod's @AetheriumInit entrypoint completes with no game present", true);
            check("init reported it came online", log.stream().anyMatch(s -> s.contains("Gameplay online")),
                    log.toString());
            check("both clientbound channels were claimed during init", NetworkRegistry.size() == 2,
                    "channels=" + NetworkRegistry.size());
            check("the serverbound admin channel was claimed during init",
                    org.aetherium.edge.Network.serverboundEntries().size() == 1,
                    org.aetherium.edge.Network.serverboundEntries().toString());
        } catch (Throwable failed) {
            check("the mod's @AetheriumInit entrypoint completes with no game present", false,
                    String.valueOf(failed));
        } finally {
            if (previousUserDir != null) {
                System.setProperty("user.dir", previousUserDir);
            }
            RedSteelConfigs.resetForTesting();
        }
    }

    /**
     * The layout must actually adapt to the window, not merely survive it.
     *
     * <p>A dense setting is one row on a wide window and two lines on a narrow one. Comparing the number of
     * laid-out rows proves the responsive branch is real: a screen that ignored the viewport would produce
     * the same tree at both sizes.
     */
    private static void uiIsResponsive() {
        RedSteelConfigs.resetForTesting();
        Rect view = new Rect(0, 0, 620, 300);
        // Same viewport height for both, so the only thing that can change the content height is the
        // responsive branch: stacked settings are taller than single-row ones.
        // Measured on a section with values, not on the hub: the hub is deliberately short enough to fit
        // at every width, so it cannot show a width-driven height difference.
        ConfigScreen narrowScreen = new ConfigScreen();
        narrowScreen.goTo(ConfigScreen.Section.PROGRESS);
        layout(narrowScreen.build(new Rect(0, 0, 340, 300)), view);
        int narrow = findScrollPanel(layout(narrowScreen.build(new Rect(0, 0, 340, 300)), view)).maxScroll();

        ConfigScreen wideScreen = new ConfigScreen();
        wideScreen.goTo(ConfigScreen.Section.PROGRESS);
        layout(wideScreen.build(view), view);
        int wide = findScrollPanel(layout(wideScreen.build(view), view)).maxScroll();

        check("the settings screen really adapts to the window width", narrow != wide,
                "narrow=" + narrow + " wide=" + wide);
        check("the narrow layout is taller (settings stack instead of being squeezed)", narrow > wide,
                "narrow=" + narrow + " wide=" + wide);
    }

    /**
     * The two things a player needs in order to find the mod at all.
     *
     * <p>There is no keybind API in the framework, so the UI is reachable only through a command or a
     * block. If the join message stops naming a way in, or the Anomaly Core stops responding to a click,
     * the mod becomes undiscoverable — that is a player-facing regression, so it is a test.
     */
    private static void modIsDiscoverable() {
        RedSteelConfigs.resetForTesting();
        FakePlayer player = new FakePlayer("Новичок", 0);
        FactionManager.get().leave(player.id());

        // /redsteel with no arguments must always point at both entry points.
        FactionCommands.run(player, List.of("help"));
        String help = String.join(" | ", player.messages);
        check("the command help names the GUI entry point", help.contains("/redsteel gui"), help);

        // The faction screen must explain the mechanic rather than only showing a number, and must offer
        // a way in for a player with no faction yet.
        LaidOut tree = layout(new FactionScreen(player.id(), player.name()).build(SIZES[1]), SIZES[1]);
        check("the faction screen tells an unaligned player what to do",
                findTextContaining(tree, "выберите сторону") != null
                        || findTextContaining(tree, "Выберите сторону") != null);
        check("the faction screen offers a join button for every playable faction",
                findButton(tree, "Вступить: " + RedSteelConfigs.current()
                        .displayName(Faction.SAMURAI)) != null
                        && findButton(tree, "Вступить: " + RedSteelConfigs.current()
                        .displayName(Faction.COMMUNISTS)) != null);

        // The Anomaly Core's readings must surface on the screen when a core was clicked.
        LaidOut withCore = layout(new FactionScreen(player.id(), player.name())
                .withCoreStatus(new FactionScreen.CoreStatus(10, 64, -20, "Орден Самураев", 512L))
                .build(SIZES[1]), SIZES[1]);
        check("clicking a core shows its coordinates and readings on the screen",
                findTextContaining(withCore, "10, 64, -20") != null
                        && findTextContaining(withCore, "512") != null
                        && findTextContaining(withCore, "Аномальное Ядро") != null);
    }

    private static String findTextContaining(LaidOut node, String needle) {
        String text = textOf(node.widget());
        if (text != null && text.contains(needle)) {
            return text;
        }
        for (LaidOut child : node.children()) {
            String found = findTextContaining(child, needle);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The Anomaly Core's whole reason to exist: claim ground, earn, collect — and deny an enemy.
     *
     * <p>Driven through the real {@link org.aetherium.content.AetheriumMachineLogic} entry points the game
     * now calls (the framework wired the machine system), against a fake {@link MachineContext} whose
     * {@link MachineState} stands in for the block entity's NBT. This is the same path a placed block takes,
     * so the test exercises what ships rather than a parallel implementation.
     */
    private static void anomalyCoreLoop() {
        RedSteelConfigs.resetForTesting();
        AnomalyCoreSlots.get().resetForTesting();
        AnomalyCoreLogic logic = new AnomalyCoreLogic();
        FactionManager m = FactionManager.get();

        FakePlayer owner = new FakePlayer("Владелец", 0);
        FakePlayer enemy = new FakePlayer("Чужак", 0);
        FakePlayer drifter = new FakePlayer("Бродяга", 0);
        m.join(owner.id(), Faction.SAMURAI);
        m.join(enemy.id(), Faction.COMMUNISTS);
        m.leave(drifter.id());
        m.setEssence(owner.id(), 0L);
        m.setEssence(enemy.id(), 0L);

        // Placing with no faction leaves the core inert and says so.
        FakeMachine unaligned = new FakeMachine(8, 64, 8, drifter);
        logic.onPlaced(unaligned);
        check("a core placed by an unaligned player stays unclaimed",
                AnomalyCoreLogic.owner(unaligned.state()) == Faction.NEUTRAL);
        check("the unaligned player is told why", unaligned.placerMessages().stream()
                .anyMatch(msg -> msg.contains("вступите во фракцию")), unaligned.placerMessages().toString());

        // Placing claims the chunk — an ordinary player's only way into the territory system.
        FakeMachine core = new FakeMachine(8, 64, 8, owner);
        logic.onPlaced(core);
        check("placing a core claims it for the placer's faction",
                AnomalyCoreLogic.owner(core.state()) == Faction.SAMURAI);
        check("placing a core claims the chunk", m.ownerOfBlock(8, 8) == Faction.SAMURAI);
        check("a hostile faction cannot use that ground", !m.canInteractBlock(enemy.id(), 8, 8));

        // It earns on its own, once a second.
        for (int t = 0; t < 200; t++) {
            core.advance();
            logic.tick(core);
        }
        long stored = AnomalyCoreLogic.stored(core.state());
        check("an active core accrues essence over time", stored > 0, "stored=" + stored);

        // An unclaimed core earns nothing.
        FakeMachine inert = new FakeMachine(30, 64, 30, null);
        for (int t = 0; t < 200; t++) {
            inert.advance();
            logic.tick(inert);
        }
        check("an unclaimed core earns nothing", AnomalyCoreLogic.stored(inert.state()) == 0L);

        // Only the owner collects.
        logic.onUse(core, enemy);
        check("an enemy collects nothing and the core keeps its essence",
                AnomalyCoreLogic.stored(core.state()) == stored && m.essence(enemy.id()) == 0L);
        logic.onUse(core, owner);
        check("the owner collects what the core stored", m.essence(owner.id()) == stored,
                m.essence(owner.id()) + " vs " + stored);
        check("collecting empties the core", AnomalyCoreLogic.stored(core.state()) == 0L);

        // Using an unclaimed core claims it for the user.
        FakeMachine free = new FakeMachine(40, 64, 40, null);
        logic.onUse(free, enemy);
        check("using an unclaimed core claims it for that player",
                AnomalyCoreLogic.owner(free.state()) == Faction.COMMUNISTS
                        && m.ownerOfBlock(40, 40) == Faction.COMMUNISTS);

        // Durable state is exactly owner + stored — what the block entity writes to NBT. A volatile slot
        // index must never be persisted: it is an offset into an arena rebuilt on every start.
        check("the core persists owner + stored and nothing volatile",
                core.state().strings().containsKey("owner")
                        && core.state().longs().keySet().stream().noneMatch(k -> k.contains("slot")),
                core.state().longs().keySet() + " / " + core.state().strings().keySet());

        // Breaking releases the ground and clears ownership outright. MachineState.removeString (framework
        // the framework) is what lets "never claimed" differ from "claimed by the neutral faction" — with only
        // a sentinel to write, those two states were the same value.
        logic.onRemoved(core);
        check("breaking a core releases the claim", m.ownerOfBlock(8, 8) == Faction.NEUTRAL);
        check("breaking a core clears ownership rather than writing a sentinel",
                !core.state().hasString("owner") && !AnomalyCoreLogic.isClaimed(core.state()));
        AnomalyCoreSlots.get().resetForTesting();
    }

    /**
     * The mod must work on a JVM started without {@code --enable-preview}.
     *
     * <p>The off-heap engine sits on FFM/Panama, so its classes are preview-compiled and throw
     * {@link UnsupportedClassVersionError} — an {@link Error}, which a plain {@code catch (RuntimeException)}
     * would not stop — on a stock launcher. Every use goes through {@code Capabilities.ffm}, so the feature
     * degrades to the base rate instead of taking the mod's init down with it. This test asserts the
     * degraded path is real: a core with no anomaly slot still earns.
     */
    private static void modSurvivesWithoutOffHeapEngine() {
        RedSteelConfigs.resetForTesting();
        AnomalyCoreSlots.get().resetForTesting();
        // Capabilities.ffm (the framework) is what makes this survivable: it falls back on ANY
        // Throwable, so UnsupportedClassVersionError — an Error, which catch(RuntimeException) misses —
        // no longer escapes. Whichever branch this JVM takes, the value must come back.
        check("the FFM capability helper always yields a value",
                org.aetherium.core.Capabilities.ffm(
                        () -> redstone.team.redsteelcore.aetherium.AnomalyEngine.get().offHeapBytes(),
                        () -> -1L) != null);

        // Whatever the verdict, a claimed core must still accrue: intensity simply falls back to 0.
        AnomalyCoreLogic logic = new AnomalyCoreLogic();
        FakePlayer player = new FakePlayer("Тестер", 0);
        FactionManager.get().join(player.id(), Faction.SAMURAI);
        FakeMachine core = new FakeMachine(70, 64, 70, player);
        logic.onPlaced(core);
        for (int t = 0; t < 100; t++) {
            core.advance();
            logic.tick(core);
        }
        check("a core still earns at the base rate whatever the engine's verdict",
                AnomalyCoreLogic.stored(core.state()) > 0,
                "stored=" + AnomalyCoreLogic.stored(core.state()));
        logic.onRemoved(core);
        AnomalyCoreSlots.get().resetForTesting();
    }

    /**
     * The screen must paint an opaque backdrop over the whole viewport.
     *
     * <p>In 1.21.1 the vanilla screen background only blurs the world behind it. If a mod's content floats
     * over that, everything reads as smudged — which is exactly what was reported from a live session. A
     * fully opaque root makes the mod immune to it regardless of what the loader's adapter does.
     */
    private static void screenIsOpaque() {
        RedSteelConfigs.resetForTesting();
        for (Rect size : SIZES) {
            for (Widget<?> screen : screensUnderTest(size)) {
                LaidOut tree = layout(screen, size);
                org.aetherium.ui.UiColor bg = tree.widget().background();
                boolean opaque = bg != null && ((bg.argb() >>> 24) & 0xFF) == 0xFF;
                boolean covers = tree.rect().width() >= size.width() && tree.rect().height() >= size.height();
                check("the screen paints an opaque full-size backdrop at "
                                + size.width() + "x" + size.height(),
                        opaque && covers,
                        "bg=" + (bg == null ? "none" : Integer.toHexString(bg.argb())) + " rect=" + rect(tree.rect()));
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static final UUID VIEWER = UUID.randomUUID();

    private static FactionScreen factionScreen() {
        return new FactionScreen(VIEWER, "Тестер").withAdminAccess(true);
    }

    private static void openRename(ConfigScreen screen, Rect size) {
        screen.goTo(ConfigScreen.Section.FACTIONS);
        LaidOut tree = layout(screen.build(), size);
        Rect rename = findButton(tree, "Название: " + Faction.SAMURAI.id());
        if (rename != null) {
            click(tree, rename);
        }
    }

    /** Every string the renderer actually painted, joined — what a player would be able to read. */
    private static String drawnText(RecordingUiRenderer renderer) {
        StringBuilder sb = new StringBuilder();
        for (RecordingUiRenderer.Cmd cmd : renderer.commands()) {
            if (cmd.text() != null) {
                sb.append(cmd.text()).append(' ');
            }
        }
        return sb.toString();
    }

    private static LaidOut layout(Widget<?> root, Rect size) {
        return UiRuntime.render(root, size, UiMetrics.DEFAULT, new RecordingUiRenderer());
    }

    /** Click a widget the way a player would — at the centre of its box. Returns whether it landed. */
    private static boolean click(LaidOut tree, Rect target) {
        return UiRuntime.click(tree, target.x() + target.width() / 2, target.y() + target.height() / 2);
    }

    private static Rect findButton(LaidOut node, String text) {
        if (node.widget() instanceof Button b && b.text().equals(text)) {
            return node.rect();
        }
        for (LaidOut child : node.children()) {
            Rect found = findButton(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String textOf(Widget<?> w) {
        if (w instanceof Text t) {
            return t.text();
        }
        if (w instanceof Button b) {
            return b.text();
        }
        if (w instanceof TextField tf) {
            return tf.text().isEmpty() ? tf.placeholderText() : tf.text();
        }
        return null;
    }

    private static boolean contains(Rect parent, Rect child) {
        // 1px slack absorbs integer rounding in the flex solver.
        return child.x() >= parent.x() - 1 && child.y() >= parent.y() - 1
                && child.right() <= parent.right() + 1 && child.bottom() <= parent.bottom() + 1;
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            FAILURES.incrementAndGet();
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /** A stand-in for the block entity the loader creates: machine state, a placer, and a tick clock. */
    private static final class FakeMachine implements MachineContext {
        private final int x;
        private final int y;
        private final int z;
        private final FakePlayer placer;
        private final MachineState state = new MachineState();
        private long ticks;

        FakeMachine(int x, int y, int z, FakePlayer placer) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.placer = placer;
        }

        void advance() {
            ticks++;
        }

        List<String> placerMessages() {
            return placer == null ? List.of() : placer.messages;
        }

        @Override public long ticks() {
            return ticks;
        }

        @Override public boolean isClient() {
            return false;
        }

        @Override public int x() {
            return x;
        }

        @Override public int y() {
            return y;
        }

        @Override public int z() {
            return z;
        }

        @Override public MachineState state() {
            return state;
        }

        @Override public java.util.Optional<org.aetherium.edge.PlayerHandle> placer() {
            return java.util.Optional.ofNullable(placer);
        }
    }

    /** Minimal {@link org.aetherium.edge.PlayerHandle} for exercising commands with no game present. */
    private static final class FakePlayer implements org.aetherium.edge.PlayerHandle {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final int permission;
        final List<String> messages = new ArrayList<>();
        /** A real hotbar, so the item picker and {@code item add hand} can be exercised offline. */
        final String[] hotbar = new String[9];
        int selected;
        double px;
        double pz;

        FakePlayer(String name, int permission) {
            this.name = name;
            this.permission = permission;
            java.util.Arrays.fill(hotbar, org.aetherium.edge.InventoryAccess.AIR);
        }

        FakePlayer at(double x, double z) {
            this.px = x;
            this.pz = z;
            return this;
        }

        FakePlayer holding(String... ids) {
            for (int i = 0; i < ids.length && i < hotbar.length; i++) {
                hotbar[i] = ids[i];
            }
            return this;
        }

        FakePlayer selecting(int slot) {
            this.selected = slot;
            return this;
        }

        @Override public boolean hasPermission(int level) {
            return permission >= level;
        }

        @Override public UUID id() {
            return id;
        }

        @Override public String name() {
            return name;
        }

        @Override public double x() {
            return px;
        }

        @Override public double y() {
            return 0;
        }

        @Override public double z() {
            return pz;
        }

        @Override public void setPosition(double x, double y, double z) {
        }

        @Override public void addVelocity(double dx, double dy, double dz) {
        }

        @Override public float health() {
            return 20f;
        }

        @Override public void setHealth(float health) {
        }

        @Override public org.aetherium.edge.InventoryAccess inventory() {
            return new org.aetherium.edge.InventoryAccess() {
                @Override public int size() {
                    return hotbar.length;
                }

                @Override public String itemId(int slot) {
                    return slot >= 0 && slot < hotbar.length ? hotbar[slot] : AIR;
                }

                @Override public int count(int slot) {
                    return AIR.equals(itemId(slot)) ? 0 : 1;
                }

                @Override public void setItem(int slot, String itemId, int count) {
                    if (slot >= 0 && slot < hotbar.length) {
                        hotbar[slot] = count <= 0 ? AIR : itemId;
                    }
                }

                @Override public int selectedSlot() {
                    return selected;
                }
            };
        }

        @Override public void sendMessage(String message) {
            messages.add(message);
        }
    }
}
