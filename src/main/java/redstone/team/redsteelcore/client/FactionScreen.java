/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.client;

import org.aetherium.ui.AetheriumScreen;
import org.aetherium.ui.AetheriumUi;
import org.aetherium.ui.AlignItems;
import org.aetherium.ui.Container;
import org.aetherium.ui.Justify;
import org.aetherium.ui.Rect;
import org.aetherium.ui.ScrollPanel;
import org.aetherium.ui.Ui;
import org.aetherium.ui.UiColor;
import org.aetherium.ui.Widget;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;
import redstone.team.redsteelcore.gameplay.FactionProgress;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The faction screen — what a player sees when they right-click an Anomaly Core or type {@code /redsteel gui}.
 *
 * <p>EN: A declarative {@link AetheriumScreen} (no Blaze3D, no vanilla {@code Screen}): the viewer's faction
 * and balances, the live Focus/Resonance meter, the status of the core they clicked, the join/leave actions
 * and — for an operator — the way into {@link ConfigScreen}. The layout is <em>responsive</em>
 * ({@link #build(Rect)}): on a wide window the stats sit two-to-a-row, on a narrow one they stack, so the
 * same screen reads well at any GUI scale. Every explanation the player needs is on the screen itself; the
 * mod has no manual.
 * RU: Декларативный {@link AetheriumScreen} (без Blaze3D и ванильного {@code Screen}): фракция и балансы
 * зрителя, живой счётчик Концентрации/Резонанса, состояние ядра, по которому кликнули, вступление/выход и —
 * для оператора — вход в {@link ConfigScreen}. Раскладка <em>адаптивная</em> ({@link #build(Rect)}): на
 * широком окне показатели идут по два в ряд, на узком — столбцом. Все пояснения игроку — на самом экране.
 */
public final class FactionScreen extends AetheriumScreen {

    /** Below this width the two-column stat grid becomes a single column. */
    private static final int WIDE = 420;

    private final UUID viewer;
    private final String viewerName;
    private final ScrollPanel body = Ui.scroll(Ui.column()).scrollbar(true);

    // Defaults to the framework's own navigation (the framework+): AetheriumUi resolves a client-side UiAccess
    // via ServiceLoader and is a safe no-op on a dedicated server, so these work without injection —
    // they stay overridable purely so the self-test can observe navigation with no game present.
    private Runnable closeHandler = AetheriumUi::close;
    private Consumer<AetheriumScreen> screenOpener = AetheriumUi::open;
    private boolean adminView;
    private CoreStatus coreStatus;

    /**
     * Readings of the Anomaly Core the player just used: where it is, which faction it works for, and how
     * much essence is waiting in it.
     */
    public record CoreStatus(int x, int y, int z, String owner, long stored) {
    }

    public FactionScreen(UUID viewer, String viewerName) {
        this.viewer = viewer;
        this.viewerName = viewerName == null ? "Оперативник" : viewerName;
    }

    /** The loader injects how to actually dismiss the platform window; the Close button calls it. */
    public FactionScreen onCloseRequest(Runnable handler) {
        this.closeHandler = handler == null ? () -> { } : handler;
        return this;
    }

    /**
     * How to swap the visible screen. The UI module has no navigation stack, so pushing a screen is the
     * caller's job; this stays a plain {@link Consumer} so the screen remains testable offline.
     */
    public FactionScreen onOpenScreen(Consumer<AetheriumScreen> opener) {
        this.screenOpener = opener == null ? screen -> { } : opener;
        return this;
    }

    /** Show the "Настройки сервера" entry — the caller decides who counts as an administrator. */
    public FactionScreen withAdminAccess(boolean admin) {
        this.adminView = admin;
        return this;
    }

    /** Attach the status of the core the player just clicked, shown as its own section. */
    public FactionScreen withCoreStatus(CoreStatus status) {
        this.coreStatus = status;
        return this;
    }

    @Override
    public String title() {
        return "Red Steel Core — Фракции";
    }

    @Override
    public Widget<?> build() {
        return build(new Rect(0, 0, WIDE, 400));
    }

    @Override
    public Widget<?> build(Rect viewport) {
        if (viewer == null) {
            // Opened by the keybind while the platform could not name a player (the PAL exposes no local
            // player). Say so plainly and point at the route that always carries an identity.
            return UiTheme.screen().children(
                    Ui.label(title()).color(UiTheme.TEXT),
                    Ui.label("Не удалось определить игрока на клиенте.").color(UiTheme.WARN).wrap(true),
                    Ui.label("Откройте экран командой /redsteel gui или кликните по Аномальному Ядру.")
                            .color(UiTheme.MUTED).wrap(true),
                    Ui.spacer().grow(1f),
                    Ui.button("Закрыть", () -> closeHandler.run()).grow(1f));
        }
        FactionManager m = FactionManager.get();
        FactionProgress p = FactionProgress.get();
        RedSteelConfig config = RedSteelConfigs.current();
        Faction current = m.faction(viewer);
        boolean wide = viewport.width() >= WIDE;

        body.child(buildBody(m, p, config, current, wide));

        return UiTheme.screen().children(
                Ui.label(viewerName + " — " + config.displayName(current))
                        .color(UiColor.rgb(config.color(current))),
                body.grow(1f).shrink(1f).background(UiTheme.ROW_ALT),
                Ui.row().gap(UiTheme.GAP).children(
                        Ui.button("Покинуть фракцию", () -> m.leave(viewer)).grow(1f),
                        Ui.button("Закрыть", () -> closeHandler.run()).grow(1f)));
    }

    private Widget<?> buildBody(FactionManager m, FactionProgress p, RedSteelConfig config,
                                Faction current, boolean wide) {
        long meter = current == Faction.SAMURAI ? p.focus(viewer) : p.combo(viewer);
        Container out = Ui.column().gap(UiTheme.GAP).align(AlignItems.STRETCH);

        out.add(UiTheme.header("Ваше состояние"));
        // On a wide window two stats share a row; on a narrow one each takes its own, so nothing is
        // squeezed to the point of clipping at small GUI scales.
        if (wide) {
            out.add(Ui.row().gap(UiTheme.GAP).children(
                    statCell("Эссенция", m.essence(viewer)).grow(1f),
                    statCell("Квота добычи", m.quota(viewer)).grow(1f)));
            out.add(statCell(meterLabel(current), meter));
        } else {
            out.add(statCell("Эссенция", m.essence(viewer)));
            out.add(statCell("Квота добычи", m.quota(viewer)));
            out.add(statCell(meterLabel(current), meter));
        }
        out.add(hint(meterHint(current)));

        if (coreStatus != null) {
            out.add(UiTheme.header("Аномальное Ядро"));
            out.add(statCell("Координаты",
                    coreStatus.x() + ", " + coreStatus.y() + ", " + coreStatus.z()));
            if (wide) {
                out.add(Ui.row().gap(UiTheme.GAP).children(
                        statCell("Работает на", coreStatus.owner()).grow(1f),
                        statCell("Накоплено", coreStatus.stored()).grow(1f)));
            } else {
                out.add(statCell("Работает на", coreStatus.owner()));
                out.add(statCell("Накоплено", coreStatus.stored()));
            }
            out.add(hint(coreStatus.stored() > 0
                    ? "Кликните по ядру ещё раз, чтобы забрать накопленное."
                    : "Ядро закрепляет чанк за фракцией и копит эссенцию. Возвращайтесь за добычей."));
        }

        out.add(UiTheme.header("Выбор фракции"));
        // One full-width button per faction: faction names are admin-configurable and can be long, and a
        // full-width button always has room for its label.
        for (Faction faction : Faction.playable()) {
            int rgb = config.color(faction);
            out.add(Ui.button("Вступить: " + config.displayName(faction), () -> m.join(viewer, faction))
                    .background(UiColor.rgb(rgb))
                    .color(UiTheme.onColor(rgb))
                    .grow(1f));
        }
        out.add(hint(current == Faction.NEUTRAL
                ? "Вы ни в одной фракции — выберите сторону, чтобы начать зарабатывать эссенцию."
                : "Сменить фракцию можно в любой момент; накопленная эссенция сохраняется."));

        if (adminView) {
            out.add(UiTheme.header("Администрирование"));
            out.add(Ui.button("Настройки сервера", () -> screenOpener.accept(
                    new ConfigScreen().onCloseRequest(() -> screenOpener.accept(this)))).grow(1f));
            out.add(hint("Те же значения правятся в config/redsteelcore.json и командой /redsteel config."));
        }
        return out;
    }

    private static String meterLabel(Faction faction) {
        return faction == Faction.SAMURAI ? "Концентрация" : "Резонанс (комбо)";
    }

    /** The screen explains its own mechanic — the mod ships no manual. */
    private static String meterHint(Faction faction) {
        return switch (faction) {
            case SAMURAI -> "Побеждайте врагов враждебной фракции — любой полученный урон обнуляет счётчик.";
            case COMMUNISTS -> "Добывайте руду цепочкой — блоки, установленные вами, комбо не растят.";
            case NEUTRAL -> "Вступите во фракцию, чтобы открыть её механику прогресса.";
        };
    }

    /**
     * An explanatory line. Wrapping is the layout engine's job since the framework ({@code Text.wrap}),
     * which knows both the box width and the font metrics — so the mod no longer estimates line breaks.
     */
    private static org.aetherium.ui.Text hint(String text) {
        return Ui.label(text).color(UiTheme.MUTED).wrap(true);
    }

    private Container statCell(String label, long value) {
        return statCell(label, String.valueOf(value));
    }

    private Container statCell(String label, String value) {
        return UiTheme.settingRow().children(
                // minContentSize keeps the label readable: when the row is over-full the controls give up
                // space first, instead of the text being shrunk until it is cut.
                Ui.label(label).color(UiTheme.MUTED).minContentSize(true).grow(1f),
                Ui.label(value).color(UiTheme.TEXT).align(Justify.END).grow(1f));
    }
}
