/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore;

import org.aetherium.edge.EdgeEvents;
import org.aetherium.edge.EntityAccess;
import org.aetherium.edge.EntityHandle;
import org.aetherium.edge.LevelAccess;
import org.aetherium.edge.LevelContext;
import org.aetherium.edge.Platform;
import org.aetherium.edge.PlatformBridge;
import org.aetherium.edge.PlayerAccess;
import org.aetherium.edge.PlayerHandle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A headless {@link PlatformBridge} that can present a local player, installed through the framework's
 * {@link Platform#installForTesting(PlatformBridge)}.
 *
 * <p>Anything that reads {@code players().local()} — the HUD, the settings screen's held-item button — was
 * previously only testable in its <em>absent</em> branch, because the real bridge is resolved once through
 * {@code ServiceLoader} and the no-op fallback has no players. Registering a {@code META-INF/services} entry
 * would have been the alternative, and it would have changed the bridge for every other test in this JVM,
 * including the ones asserting honest behaviour when no game is present. This override is per-test and
 * reverted in a {@code finally}.
 */
final class TestBridge implements PlatformBridge {

    private final PlayerHandle local;

    private TestBridge(PlayerHandle local) {
        this.local = local;
    }

    /** Run {@code body} with {@code local} presented as the client's own player, then restore the real bridge. */
    static void withLocalPlayer(PlayerHandle local, Runnable body) {
        Platform.installForTesting(new TestBridge(local));
        try {
            body.run();
        } finally {
            Platform.installForTesting(null);
        }
    }

    @Override
    public String platformName() {
        return "test";
    }

    @Override
    public PlayerAccess players() {
        return new PlayerAccess() {
            @Override public Optional<PlayerHandle> byId(UUID id) {
                return local != null && local.id().equals(id) ? Optional.of(local) : Optional.empty();
            }

            @Override public Optional<PlayerHandle> byName(String name) {
                return local != null && local.name().equals(name) ? Optional.of(local) : Optional.empty();
            }

            @Override public List<PlayerHandle> online() {
                return local == null ? List.of() : List.of(local);
            }

            @Override public Optional<PlayerHandle> local() {
                return Optional.ofNullable(local);
            }
        };
    }

    @Override
    public EntityAccess entities() {
        return new EntityAccess() {
            @Override public Optional<EntityHandle> byId(UUID id) {
                return Optional.empty();
            }

            @Override public void forEach(Consumer<EntityHandle> action) {
            }

            @Override public int count() {
                return 0;
            }
        };
    }

    @Override
    public LevelAccess levels() {
        return new LevelAccess() {
            @Override public Optional<LevelContext> primary() {
                return Optional.empty();
            }

            @Override public Optional<LevelContext> byDimension(String dimensionId) {
                return Optional.empty();
            }

            @Override public void forEach(Consumer<LevelContext> action) {
            }

            @Override public int count() {
                return 0;
            }
        };
    }

    @Override
    public EdgeEvents events() {
        return new EdgeEvents() {
            @Override public void onServerTickEnd(Runnable hook) {
            }

            @Override public void onEntityLoad(Consumer<EntityHandle> hook) {
            }
        };
    }
}
