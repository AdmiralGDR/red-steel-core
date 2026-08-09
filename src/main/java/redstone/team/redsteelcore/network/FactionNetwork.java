/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.network;

import org.aetherium.edge.Network;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.network.NetworkRegistry;
import org.aetherium.network.Tree;
import org.aetherium.network.TreeNode;
import org.aetherium.network.TreeSyncCodec;
import org.aetherium.network.TreeSyncPacket;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigCodec;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.gameplay.FactionCommands;

import java.util.Map;
import java.util.UUID;

/**
 * Every packet this mod speaks: faction state and server settings out to clients, and admin edits back in.
 *
 * <p>EN: Three channels, in two directions — the directions only became expressible in the framework.
 *
 * <ul>
 *   <li>{@link #CHANNEL} (server → client) — a player's own faction state: membership, essence, quota, and
 *       the faction-wide skill tree. Irregular and nested, so a {@link TreeNode} rather than the flat
 *       {@code StructArenaDelta}.</li>
 *   <li>{@link #CONFIG_CHANNEL} (server → client) — the authoritative settings. Faction <em>names</em> and
 *       <em>colours</em> are player-facing and admin-editable, so a client that has not been told the
 *       server's values renders its own defaults: two players in the same faction could read different
 *       names for it. Sent on join and after every change.</li>
 *   <li>{@link #ADMIN_CHANNEL} (client → server) — a settings edit made in the in-game editor. The server
 *       is the only authority: it re-validates the payload, checks the sender's permission, saves, and
 *       broadcasts the result. A client never writes the server's config, and never assumes its own edit
 *       took effect — the screen updates when the broadcast comes back.</li>
 * </ul>
 *
 * <p>The network was once clientbound-only, so the settings screen could only write the config
 * file of whichever process it happened to run in — the server only in single-player. On a dedicated server
 * it edited the operator's own client and reported success. That is what this class now fixes.
 *
 * <p>The serverbound handler treats its payload as hostile: framework-side it is size-capped before decode,
 * rate-limited per sender, and carries an unspoofable sender identity; mod-side we still check permission
 * before acting and refuse a tree we cannot parse rather than throwing on the server's network thread.
 *
 * <p>RU: Три канала в двух направлениях (направления появились только теперь): состояние фракции и
 * настройки — на клиент, правки администратора — на сервер. Раньше сеть была только клиентской, поэтому экран
 * настроек правил конфиг своего процесса — то есть сервера лишь в одиночной игре, а на выделенном сервере
 * менял конфиг самого администратора и рапортовал об успехе. Сервер — единственный источник истины: он
 * перепроверяет пакет, проверяет права, сохраняет и рассылает результат.
 */
public final class FactionNetwork {

    /** Server → client: the viewer's own faction state. */
    public static final String CHANNEL = "redsteelcore:faction_state";

    /** Server → client: the authoritative settings snapshot (names, colours, rules, mechanics, items). */
    public static final String CONFIG_CHANNEL = "redsteelcore:config_sync";

    /** Client → server: an administrator's settings edit, applied only after a permission check. */
    public static final String ADMIN_CHANNEL = "redsteelcore:admin_config";

    private FactionNetwork() {
    }

    /**
     * Register all three channels. Registering the same channel twice throws {@code AE-NET-CHANNEL-DUP} by
     * design, so this is called exactly once from init.
     */
    public static void register() {
        NetworkRegistry.register(new TreeSyncCodec(CHANNEL), packet -> applyOnClient(packet.root()));
        NetworkRegistry.register(new TreeSyncCodec(CONFIG_CHANNEL), packet -> applyConfigOnClient(packet.root()));
        Network.registerServerbound(new TreeSyncCodec(ADMIN_CHANNEL), FactionNetwork::onAdminConfig);
    }

    // --- server → client ---------------------------------------------------------------------------

    /** Build the nested faction-state tree for {@code player} (server side). */
    public static TreeNode snapshot(UUID player) {
        FactionManager m = FactionManager.get();
        Faction faction = m.faction(player);

        Tree.ObjectBuilder skills = Tree.object();
        for (Map.Entry<String, Long> e : m.skills(faction).entrySet()) {
            skills.put(e.getKey(), e.getValue());
        }

        return Tree.object()
                .put("player", player.toString())
                .put("faction", faction.id())
                .put("faction_name", RedSteelConfigs.current().displayName(faction))
                .put("essence", m.essence(player))
                .put("quota", m.quota(player))
                .put("skills", skills.build())
                .build();
    }

    /** The ready-to-send faction-state packet for {@code player}. */
    public static TreeSyncPacket packetFor(UUID player) {
        return new TreeSyncPacket(CHANNEL, snapshot(player));
    }

    /** The current settings, ready to send to a client. */
    public static TreeSyncPacket configPacket() {
        return new TreeSyncPacket(CONFIG_CHANNEL, new RedSteelConfigCodec().toTree(RedSteelConfigs.current()));
    }

    /** Tell one client (typically on join) what the server's settings actually are. */
    public static void sendConfigTo(PlayerHandle player) {
        Network.sendToClient(player, configPacket());
    }

    /** Tell every client the settings changed. Called from {@link RedSteelConfigs#save} — one choke point. */
    public static void broadcastConfig() {
        Network.sendToAllClients(configPacket());
    }

    /** Apply a decoded faction tree into the (shared) client mirror — pure, no Minecraft type. */
    public static void applyOnClient(TreeNode root) {
        if (!(root instanceof TreeNode.Obj o)) {
            return;
        }
        UUID player;
        try {
            player = UUID.fromString(o.getString("player", ""));
        } catch (IllegalArgumentException malformed) {
            return;
        }
        FactionManager m = FactionManager.get();
        Faction faction = Faction.byId(o.getString("faction", "neutral"));
        m.join(player, faction);
        m.setEssence(player, o.getLong("essence", 0L));
        m.setQuota(player, o.getLong("quota", 0L));

        if (o.get("skills") instanceof TreeNode.Obj skills) {
            for (Map.Entry<String, TreeNode> e : skills.entries().entrySet()) {
                if (e.getValue() instanceof TreeNode.I64 level) {
                    m.skills(faction).put(e.getKey(), level.value());
                }
            }
        }
    }

    /**
     * Adopt the server's settings on a client — <em>without</em> writing them to the client's own config
     * file. The client is a mirror, not a second source of truth; persisting the server's rules locally
     * would mean a player's single-player world silently inherited them the next time they launched.
     */
    public static void applyConfigOnClient(TreeNode root) {
        RedSteelConfig incoming = parse(root);
        if (incoming != null) {
            RedSteelConfigs.applyRemote(incoming);
        }
    }

    // --- client → server ---------------------------------------------------------------------------

    /** Ask the server to adopt {@code config}. No-op off-platform, where no transport is installed. */
    public static void requestConfigChange(RedSteelConfig config) {
        Network.sendToServer(new TreeSyncPacket(ADMIN_CHANNEL, new RedSteelConfigCodec().toTree(config)));
    }

    /**
     * Server side of {@link #requestConfigChange}: validate, authorize, apply, broadcast.
     *
     * <p>Exposed so the self-test can drive the real handler with a real payload and a real sender, rather
     * than asserting on a mock of it — the permission check is the only thing standing between an ordinary
     * player and the server's rules, so it is tested through the path that actually runs.
     */
    public static void onAdminConfig(PlayerHandle sender, TreeSyncPacket packet) {
        if (!sender.hasPermission(FactionCommands.ADMIN_PERMISSION)) {
            sender.sendMessage("Недостаточно прав для изменения настроек сервера.");
            return;
        }
        RedSteelConfig incoming = parse(packet.root());
        if (incoming == null) {
            sender.sendMessage("Настройки не приняты: пакет повреждён.");
            return;
        }
        RedSteelConfigs.save(incoming); // atomic write + adopt + broadcast to everyone
        sender.sendMessage("Настройки применены на сервере и разосланы игрокам.");
    }

    /**
     * Decode a settings tree, or {@code null} if it is not one.
     *
     * <p><strong>The shape is checked before decoding, on purpose.</strong> {@code RedSteelConfigCodec} is
     * deliberately forgiving — a missing section means "use the default" — which is right for a file a human
     * edits by hand, and dangerous for bytes off the wire: handed an empty or junk tree it returns a perfectly
     * valid <em>factory-default</em> config, so accepting its output unconditionally would let one malformed
     * packet silently reset every setting on the server. A settings document must therefore carry the sections
     * the codec writes; anything else is refused rather than defaulted.
     *
     * <p>Everything that survives this gate is still normalized by {@code RedSteelConfig}'s canonical
     * constructor, which clamps every number and drops junk entries — so an accepted payload is in range even
     * when the sender's values were not.
     *
     * <p>RU: Форма проверяется ДО декодирования: кодек намеренно снисходителен (нет секции — берём значение по
     * умолчанию), что правильно для файла, который правят руками, и опасно для байтов из сети — на пустом или
     * мусорном дереве он вернёт валидный конфиг ПО УМОЛЧАНИЮ, то есть один битый пакет сбросил бы все
     * настройки сервера. Поэтому документ обязан содержать секции, которые пишет кодек.
     */
    private static RedSteelConfig parse(TreeNode root) {
        if (!(root instanceof TreeNode.Obj o)
                || !(o.get("factions") instanceof TreeNode.Obj)
                || !(o.get("rules") instanceof TreeNode.Obj)
                || !(o.get("anomaly_core") instanceof TreeNode.Obj)) {
            return null;
        }
        try {
            return new RedSteelConfigCodec().fromTree(root);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
