/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.faction;

import org.aetherium.network.Tree;
import org.aetherium.network.TreeNode;
import redstone.team.redsteelcore.config.RedSteelConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative, loader-agnostic state of the faction game — membership, essence, territory claims,
 * faction-wide skill trees, and per-faction restricted items. Pure: keyed by {@link UUID}/{@link String},
 * with <strong>no Minecraft type</strong>, so it is unit-testable and bridged into the game only through
 * the {@code aetherium-edge} PAL and the {@code aetherium-network} tree sync.
 *
 * <p>EN: This replaces the old NeoForge {@code RestrictionEngine}/territory {@code SavedData} stack with a
 * pure in-memory model. The enforcement vectors (block interaction, item use, attacks) ask this manager
 * for a yes/no; the hierarchical sync serializes a slice of it to the client.
 * RU: Заменяет прежний стек NeoForge ({@code RestrictionEngine} + территориальный {@code SavedData})
 * чистой моделью в памяти. Векторы принуждения (взаимодействие с блоком, использование предмета, атака)
 * спрашивают у менеджера да/нет; иерархическая синхронизация сериализует его срез клиенту.
 */
public final class FactionManager {

    private static final FactionManager INSTANCE = new FactionManager();

    public static FactionManager get() {
        return INSTANCE;
    }

    private final Map<UUID, Faction> membership = new ConcurrentHashMap<>();
    private final Map<UUID, Long> essence = new ConcurrentHashMap<>();
    private final Map<UUID, Long> quota = new ConcurrentHashMap<>();
    private final Map<Long, Faction> territory = new ConcurrentHashMap<>(); // chunkKey → owner
    private final Map<Faction, Map<String, Long>> skills = new ConcurrentHashMap<>();

    /**
     * The rule set in force. Swapped whole on a config reload — never edited in place — so a decision is
     * always made against one coherent snapshot. Injected via {@link #applyConfig(RedSteelConfig)} rather
     * than read from a global, which keeps this class unit-testable without touching the filesystem.
     */
    private volatile RedSteelConfig config = RedSteelConfig.defaults();

    private FactionManager() {
    }

    /** Adopt a new rule set (called by the config store on load and on every hot reload). */
    public void applyConfig(RedSteelConfig config) {
        this.config = config;
    }

    public RedSteelConfig config() {
        return config;
    }

    // --- membership & resources ---------------------------------------------------------------

    public Faction faction(UUID player) {
        return membership.getOrDefault(player, Faction.NEUTRAL);
    }

    public void join(UUID player, Faction faction) {
        membership.put(player, faction);
        essence.putIfAbsent(player, 0L);
        quota.putIfAbsent(player, 0L);
    }

    public void leave(UUID player) {
        membership.put(player, Faction.NEUTRAL);
    }

    public long essence(UUID player) {
        return essence.getOrDefault(player, 0L);
    }

    public long addEssence(UUID player, long delta) {
        return essence.merge(player, delta, Long::sum);
    }

    public void setEssence(UUID player, long value) {
        essence.put(player, value);
    }

    public long quota(UUID player) {
        return quota.getOrDefault(player, 0L);
    }

    public void setQuota(UUID player, long value) {
        quota.put(player, value);
    }

    // --- territory ----------------------------------------------------------------------------

    /** Pack a block X/Z into a 16×16 chunk key (the spatial granularity of a claim). */
    public static long chunkKey(int blockX, int blockZ) {
        return (((long) (blockX >> 4)) << 32) ^ ((blockZ >> 4) & 0xFFFFFFFFL);
    }

    public void claim(int blockX, int blockZ, Faction owner) {
        territory.put(chunkKey(blockX, blockZ), owner);
    }

    /** Release a chunk claim outright, so an operator can undo one. */
    public void unclaim(int blockX, int blockZ) {
        territory.remove(chunkKey(blockX, blockZ));
    }

    /**
     * Every claimed chunk, as {@code chunkX,chunkZ → owner}.
     *
     * <p>An administrator has to be able to audit what has been claimed; without this the territory system
     * is write-only — claims can be made and never listed or reviewed.
     */
    public Map<String, Faction> claims() {
        Map<String, Faction> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, Faction> e : territory.entrySet()) {
            long key = e.getKey();
            out.put((int) (key >> 32) + "," + (int) key, e.getValue());
        }
        return out;
    }

    public Faction ownerOfBlock(int blockX, int blockZ) {
        return territory.getOrDefault(chunkKey(blockX, blockZ), Faction.NEUTRAL);
    }

    // --- skills (faction-wide tree synced as a hierarchical TreeNode) --------------------------

    public Map<String, Long> skills(Faction faction) {
        return skills.computeIfAbsent(faction, k -> new ConcurrentHashMap<>(Map.of(
                "mining", 1L, "combat", 1L, "smithing", 1L)));
    }

    public long raiseSkill(Faction faction, String skill, long delta) {
        return skills(faction).merge(skill, delta, Long::sum);
    }

    // --- persistence (WorldStore, the framework) -----------------------------------------

    /**
     * Serialize the whole authoritative state to a {@link TreeNode} for {@code WorldStore}.
     *
     * <p>Territory keys are the packed {@code long} chunk key rendered as a decimal string, because a
     * {@link TreeNode.Obj} is keyed by {@link String}; {@link #importState(TreeNode)} parses them back.
     */
    public TreeNode exportState() {
        Tree.ObjectBuilder members = Tree.object();
        for (Map.Entry<UUID, Faction> e : membership.entrySet()) {
            members.put(e.getKey().toString(), e.getValue().id());
        }
        Tree.ObjectBuilder essenceObj = Tree.object();
        for (Map.Entry<UUID, Long> e : essence.entrySet()) {
            essenceObj.put(e.getKey().toString(), e.getValue());
        }
        Tree.ObjectBuilder quotaObj = Tree.object();
        for (Map.Entry<UUID, Long> e : quota.entrySet()) {
            quotaObj.put(e.getKey().toString(), e.getValue());
        }
        Tree.ObjectBuilder territoryObj = Tree.object();
        for (Map.Entry<Long, Faction> e : territory.entrySet()) {
            territoryObj.put(Long.toString(e.getKey()), e.getValue().id());
        }
        Tree.ObjectBuilder skillsObj = Tree.object();
        for (Map.Entry<Faction, Map<String, Long>> byFaction : skills.entrySet()) {
            Tree.ObjectBuilder tree = Tree.object();
            for (Map.Entry<String, Long> s : byFaction.getValue().entrySet()) {
                tree.put(s.getKey(), s.getValue());
            }
            skillsObj.put(byFaction.getKey().id(), tree.build());
        }
        return Tree.object()
                .put("version", 1L)
                .put("membership", members.build())
                .put("essence", essenceObj.build())
                .put("quota", quotaObj.build())
                .put("territory", territoryObj.build())
                .put("skills", skillsObj.build())
                .build();
    }

    /**
     * Replace the live state from a {@link #exportState()} document. Defensive: malformed keys/values are
     * skipped rather than throwing, so a partly-corrupt save still loads what it can.
     */
    public void importState(TreeNode root) {
        if (!(root instanceof TreeNode.Obj o)) {
            return;
        }
        membership.clear();
        essence.clear();
        quota.clear();
        territory.clear();
        skills.clear();

        if (o.get("membership") instanceof TreeNode.Obj members) {
            for (Map.Entry<String, TreeNode> e : members.entries().entrySet()) {
                UUID id = parseUuid(e.getKey());
                if (id != null && e.getValue() instanceof TreeNode.Str f) {
                    membership.put(id, Faction.byId(f.value()));
                }
            }
        }
        readLongMap(o.get("essence"), essence);
        readLongMap(o.get("quota"), quota);
        if (o.get("territory") instanceof TreeNode.Obj terr) {
            for (Map.Entry<String, TreeNode> e : terr.entries().entrySet()) {
                Long key = parseLong(e.getKey());
                if (key != null && e.getValue() instanceof TreeNode.Str f) {
                    territory.put(key, Faction.byId(f.value()));
                }
            }
        }
        if (o.get("skills") instanceof TreeNode.Obj byFaction) {
            for (Map.Entry<String, TreeNode> fe : byFaction.entries().entrySet()) {
                Faction faction = Faction.byId(fe.getKey());
                if (faction == Faction.NEUTRAL || !(fe.getValue() instanceof TreeNode.Obj tree)) {
                    continue;
                }
                Map<String, Long> target = skills(faction);
                target.clear();
                for (Map.Entry<String, TreeNode> s : tree.entries().entrySet()) {
                    if (s.getValue() instanceof TreeNode.I64 level) {
                        target.put(s.getKey(), level.value());
                    }
                }
            }
        }
    }

    private static void readLongMap(TreeNode node, Map<UUID, Long> target) {
        if (node instanceof TreeNode.Obj o) {
            for (Map.Entry<String, TreeNode> e : o.entries().entrySet()) {
                UUID id = parseUuid(e.getKey());
                if (id != null && e.getValue() instanceof TreeNode.I64 v) {
                    target.put(id, v.value());
                }
            }
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    // --- restriction queries (the three enforcement vectors) ----------------------------------

    public Faction restrictionOwner(String itemId) {
        return config.restrictionOwner(itemId);
    }

    /**
     * A hostile faction cannot disturb claimed territory; neutral land is free for all.
     *
     * <p>Each rule can be switched off by the server admin, in which case the answer is always "allowed" —
     * a disabled rule must never leak a veto into another mod's interaction.
     */
    public boolean canInteractBlock(UUID player, int blockX, int blockZ) {
        if (!config.territoryProtection()) {
            return true;
        }
        Faction owner = ownerOfBlock(blockX, blockZ);
        return owner == Faction.NEUTRAL || !owner.isHostileTo(faction(player));
    }

    /**
     * A restricted item may be used <strong>only</strong> by the faction it belongs to.
     *
     * <p>This used to ask {@code !owner.isHostileTo(faction(player))}, and hostility is defined as "two
     * different non-neutral factions" — so an <em>unaligned</em> player was hostile to nobody and could use
     * every restricted item in the game. Since most players are unaligned most of the time, and an admin
     * testing the feature is usually unaligned too, restricting an item appeared to do nothing at all. The
     * rule an admin is expressing when they add a row to that table is exclusivity, not enmity: this item
     * belongs to these people. Everyone else — the other faction and the unaligned alike — is refused.
     *
     * <p>RU: Раньше правило спрашивало про враждебность, а враждебность определена как «две разные не-нейтральные
     * фракции» — поэтому игрок БЕЗ фракции не враждебен никому и мог пользоваться любым ограниченным предметом.
     * Большинство игроков (и админ, который проверяет функцию) как раз без фракции, поэтому ограничение
     * выглядело как полностью нерабочее. Админ, добавляя строку в таблицу, выражает исключительность, а не
     * вражду: предмет принадлежит этим. Всем остальным — отказ.
     */
    public boolean canUseItem(UUID player, String itemId) {
        if (!config.itemRestrictions()) {
            return true;
        }
        Faction owner = restrictionOwner(itemId);
        return owner == Faction.NEUTRAL || owner == faction(player);
    }

    /** No friendly fire: same non-neutral faction members cannot attack each other. */
    public boolean canAttack(UUID attacker, UUID target) {
        if (!config.friendlyFireProtection()) {
            return true;
        }
        Faction a = faction(attacker);
        Faction t = faction(target);
        if (a == Faction.NEUTRAL || t == Faction.NEUTRAL) {
            return true;
        }
        return a != t;
    }
}
