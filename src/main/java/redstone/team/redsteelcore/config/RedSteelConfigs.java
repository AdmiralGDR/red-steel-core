/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.config;

import org.aetherium.config.ConfigStore;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.network.FactionNetwork;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Process-wide handle over the framework's {@link ConfigStore} for our {@link RedSteelConfig}.
 *
 * <p>EN: The framework store is an instance; the rest of the mod reads the live config from many call sites
 * (block logic, screens, restriction checks) and should not thread a reference through all of them. This
 * thin static holder keeps the single live store and exposes {@link #current()} — the only global read in
 * the mod, and it is a plain volatile pointer swap on reload, never a half-applied mutation. Off-platform
 * (tests, CLI) {@link #bootInMemory()} points it at a temp file so nothing here needs a running game.
 * RU: Фреймворковый store — инстанс; остальной мод читает живой конфиг из многих мест и не должен
 * протаскивать ссылку через все. Этот тонкий статический холдер держит единственный живой store и отдаёт
 * {@link #current()} — единственное глобальное чтение в моде, и это просто подмена volatile-указателя при
 * перезагрузке, а не применённая наполовину мутация.
 */
public final class RedSteelConfigs {

    /** Location relative to the working directory, matching every other mod's config path. */
    public static final Path DEFAULT_PATH = Path.of("config", "redsteelcore.json");

    private static volatile ConfigStore<RedSteelConfig> store;
    private static volatile RedSteelConfig current = RedSteelConfig.defaults();
    /**
     * Bumped on every adoption. A screen showing server-authoritative settings needs to know that a newer
     * snapshot arrived (from a broadcast, a hot reload, or a command) so it can refresh what it is showing;
     * comparing snapshots by identity would miss a reload that produced an equal-but-new record.
     */
    private static final java.util.concurrent.atomic.AtomicLong VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    private RedSteelConfigs() {
    }

    /**
     * Open (or create) the config at {@code file} and start watching it for external edits.
     *
     * @param onReload invoked with every freshly-loaded snapshot (including the first), on load and on each
     *                 hot reload — the natural place to push the new rules into the {@code FactionManager}
     */
    public static synchronized ConfigStore<RedSteelConfig> boot(Path file, Consumer<RedSteelConfig> onReload) {
        if (store != null) {
            return store;
        }
        // No stale-store guard here: the framework made close() a hard barrier (running is re-checked
        // after the settle sleep and listener dispatch is guarded inside reload()), so a closed store can no
        // longer deliver a final snapshot over the one a freshly-booted store installed.
        ConfigStore<RedSteelConfig> opened = ConfigStore
                .open(file, new RedSteelConfigCodec(), RedSteelConfig.defaults())
                .validate(RedSteelConfig::revalidated)
                .onReload(RedSteelConfigs::adopt)
                .onReload(onReload)
                .watch();
        store = opened;
        adopt(opened.get());
        onReload.accept(opened.get());
        return opened;
    }

    /** {@link #boot(Path, Consumer)} at {@link #DEFAULT_PATH}. */
    public static ConfigStore<RedSteelConfig> boot(Consumer<RedSteelConfig> onReload) {
        return boot(DEFAULT_PATH, onReload);
    }

    /** The live snapshot. Cheap enough to call per tick; never {@code null}. */
    public static RedSteelConfig current() {
        return current;
    }

    /** The backing store, for callers that need to persist an edit (screen "save", {@code /redsteel config}). */
    public static ConfigStore<RedSteelConfig> store() {
        return store;
    }

    /**
     * Persist {@code config} through the store (atomic write), make it live, and tell every connected client.
     *
     * <p>This is the server-side write path — the command tree and the serverbound admin handler both end
     * here — so it is also the one place a settings change can be announced. Broadcasting from here rather
     * than from each caller is what keeps the file, the rules in force, and every client's copy in agreement;
     * off-platform (and on a client, which never calls this) the send is a no-op.
     */
    public static void save(RedSteelConfig config) {
        ConfigStore<RedSteelConfig> s = store;
        if (s != null) {
            s.set(config);
        }
        adopt(config);
        FactionNetwork.broadcastConfig();
    }

    /**
     * Adopt settings received from the server, without writing them to this process's config file.
     *
     * <p>A client is a mirror of the server's rules, not a second source of truth: persisting them would
     * leak a server's configuration into the player's own single-player world.
     */
    public static void applyRemote(RedSteelConfig config) {
        adopt(config);
    }

    /**
     * Make {@code config} the live rule set everywhere.
     *
     * <p>This is the ONE place a new snapshot becomes effective, and it must push into
     * {@link FactionManager} as well as into {@link #current}. The enforcement vectors ask the manager, not
     * this holder — so an {@code adopt} that only set {@code current} left {@code /redsteel config set} and
     * the settings screen writing a file and updating what the admin is shown while the rules actually in
     * force stayed stale until the next file-watch reload happened to fire.
     */
    private static void adopt(RedSteelConfig config) {
        current = config;
        FactionManager.get().applyConfig(config);
        VERSION.incrementAndGet();
    }

    /** Monotonic counter of adopted snapshots — see {@link #VERSION}. */
    public static long version() {
        return VERSION.get();
    }

    /** Test/CLI entry point: boot against {@code file} with no game present. */
    public static ConfigStore<RedSteelConfig> bootInMemory(Path file, Consumer<RedSteelConfig> onReload) {
        return boot(file, onReload);
    }

    /** Reset the holder so a test can boot a fresh store. Closes any open store first. */
    public static synchronized void resetForTesting() {
        ConfigStore<RedSteelConfig> s = store;
        if (s != null) {
            s.close();
        }
        store = null;
        adopt(RedSteelConfig.defaults()); // the manager must be reset with the holder, or they disagree
    }
}
