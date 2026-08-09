/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.client;

import org.aetherium.edge.Platform;
import org.aetherium.edge.PlayerHandle;
import org.aetherium.ui.AetheriumScreen;
import org.aetherium.ui.AlignItems;
import org.aetherium.ui.Container;
import org.aetherium.ui.Insets;
import org.aetherium.ui.Justify;
import org.aetherium.ui.Keys;
import org.aetherium.ui.Rect;
import org.aetherium.ui.ScrollPanel;
import org.aetherium.ui.TextField;
import org.aetherium.ui.Ui;
import org.aetherium.ui.UiColor;
import org.aetherium.ui.UiRuntime;
import org.aetherium.ui.Widget;
import redstone.team.redsteelcore.config.RedSteelConfig;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.gameplay.FactionCommands;
import redstone.team.redsteelcore.network.FactionNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The server-settings editor: a short hub of sections, each of which opens as its own page.
 *
 * <p>EN: This replaces one long scrolling list of everything. That list could only be moved with the mouse
 * wheel, and the settings that mattered least sat exactly as far from the hand as the ones that mattered
 * most — the last row was several wheel-turns down and there was no other way to reach it. Now the first
 * page is a five-row menu that fits any window without scrolling, each row shows the value it leads to, and
 * a section is one click or one number key away.
 *
 * <p><strong>One model drives both input methods.</strong> Every page is described as a list of {@link Row}s
 * — a label, the current value, what activating it does, and (where it makes sense) how to nudge it. The
 * widget tree and the keyboard handler are both generated from that one list, so anything that can be
 * clicked can also be reached from the keyboard and the two can never drift apart. In practice: ↑/↓ move the
 * selection, ←/→ change the selected value <em>in place</em> (no travel to a distant pair of buttons), Enter
 * activates, Esc goes back a level, Home/End reach the ends of a long list, and 1–5 jump into a section.
 *
 * <p>Edits accumulate in a <em>draft</em> {@link RedSteelConfig}; nothing leaves the screen until
 * "Сохранить", which sends the draft to the server (the framework serverbound channel). The server is
 * the authority — it validates, checks permission, persists through the same atomic write the command tree
 * uses, and broadcasts what it accepted; the screen then shows that, not the admin's request.
 *
 * <p>RU: Вместо одного длинного списка — короткое меню разделов, каждый открывается своей страницей. Прежний
 * список двигался только колесом мыши, и до нижних пунктов иначе было не добраться. Теперь первая страница
 * умещается в любое окно, у каждой строки видно текущее значение, а раздел открывается кликом или цифрой.
 * ОДНА модель строк питает и отрисовку, и клавиатуру, поэтому мышь и клавиатура не могут разойтись: ↑/↓ —
 * выбор, ←/→ — изменить значение на месте, Enter — открыть, Esc — назад, Home/End — к краям списка,
 * 1–5 — прыжок в раздел.
 */
public final class ConfigScreen extends AetheriumScreen {

    /** Accent colours offered by the swatch cycler (admins pick, they do not type hex). */
    private static final int[] PALETTE = {
            0xE74C3C, 0xC0392B, 0xE67E22, 0xF1C40F, 0x2ECC71,
            0x1ABC9C, 0x3498DB, 0x9B59B6, 0xECF0F1, 0x95A5A6,
    };

    /** Below this width a row's value moves onto its own line. */
    private static final int WIDE = 420;

    /** GLFW's shift bit, as passed through {@link #onKey}'s {@code modifiers}. */
    private static final int MOD_SHIFT = 0x0001;

    /** The pages of the editor. {@link #HUB} is the menu; the rest are its sections, in menu order. */
    public enum Section {
        HUB("Настройки сервера"),
        FACTIONS("Фракции"),
        CORE("Аномальное ядро"),
        RULES("Правила"),
        PROGRESS("Прогресс фракций"),
        ITEMS("Предметы фракций");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        /** The sections reachable from the hub, in the order they are listed there. */
        public static Section[] menu() {
            return new Section[] {FACTIONS, CORE, RULES, PROGRESS, ITEMS};
        }
    }

    /**
     * One line of a page, as data rather than as widgets.
     *
     * @param label    what the setting is called
     * @param value    its current value, shown beside the label (empty for a caption or plain action)
     * @param activate what Enter, or a click on the row, does — {@code null} for a caption
     * @param adjust   how ←/→ change it: called with {@code ∓1}, or {@code ∓10} with Shift held.
     *                 {@code null} when the row has nothing to nudge
     */
    private record Row(String label, String value, UiColor valueColor, Runnable activate,
                       IntConsumer adjust) {

        static Row caption(String label) {
            return new Row(label, "", null, null, null);
        }

        static Row action(String label, String value, Runnable activate) {
            return new Row(label, value, null, activate, null);
        }

        static Row number(String label, String value, IntConsumer adjust) {
            return new Row(label, value, null, () -> adjust.accept(1), adjust);
        }

        static Row toggle(String label, boolean on, Runnable flip) {
            // Green for on, muted for off: a rule that is switched off has to be visibly different at a
            // glance, because an inert rule is the most confusing state a settings screen can be in.
            return new Row(label, on ? "ВКЛ" : "ВЫКЛ", on ? UiTheme.OK : UiTheme.MUTED,
                    flip, delta -> flip.run());
        }

        /** A row whose value names a faction, tinted with that faction's own colour. */
        static Row faction(String label, String value, int rgb, Runnable activate, IntConsumer adjust) {
            return new Row(label, value, UiColor.rgb(rgb), activate, adjust);
        }

        boolean interactive() {
            return activate != null || adjust != null;
        }
    }

    /** One panel for the screen's lifetime; content is swapped each rebuild so scroll position holds. */
    private final ScrollPanel body = Ui.scroll(Ui.column()).scrollbar(true);

    private RedSteelConfig draft;
    /** The settings version the draft was taken from; a newer one means the server has spoken. */
    private long draftVersion = RedSteelConfigs.version();

    private Section section = Section.HUB;
    /** Index into {@link #rows} of the highlighted line. */
    private int cursor;
    /** The current page as data — rebuilt every frame, and the one source for both mouse and keyboard. */
    private List<Row> rows = List.of();

    private TextField editor;
    private Consumer<String> onCommit;
    private String editLabel = "";
    private String status = "";
    private UiColor statusColor = UiTheme.MUTED;
    // Defaults to the framework's client-side navigation; a no-op off-client (see FactionScreen).
    private Runnable closeHandler = org.aetherium.ui.AetheriumUi.access()::close;

    public ConfigScreen() {
        this.draft = RedSteelConfigs.current();
    }

    /** What the "Закрыть" button should do — supplied by whoever opened the screen. */
    public ConfigScreen onCloseRequest(Runnable handler) {
        this.closeHandler = handler == null ? () -> { } : handler;
        return this;
    }

    /** The unsaved draft — exposed so a test can assert on edits without rendering. */
    public RedSteelConfig draft() {
        return draft;
    }

    /** The page currently shown. */
    public Section section() {
        return section;
    }

    /** The highlighted line on the current page. */
    public int cursor() {
        return cursor;
    }

    /** How many lines the current page has (meaningful after a {@code build}). */
    public int rowCount() {
        return rows.size();
    }

    public boolean isEditingText() {
        return editor != null;
    }

    public TextField textEditor() {
        return editor;
    }

    public String status() {
        return status;
    }

    @Override
    public String title() {
        return "Red Steel Core — " + section.title();
    }

    // --- input ------------------------------------------------------------------------------------

    /**
     * Keyboard navigation over the same {@link #rows} the mouse sees.
     *
     * <p>Deliberately exhaustive, because the complaint this answers is that the wheel was the only way to
     * move: ←/→ adjust in place, so changing a number never means travelling to a distant pair of buttons,
     * and Home/End reach the ends of a long list without any wheel at all.
     */
    @Override
    public boolean onKey(int keyCode, int modifiers) {
        if (editor != null) {
            if (keyCode == UiRuntime.KEY_ENTER) {
                commitEdit();
                return true;
            }
            if (keyCode == UiRuntime.KEY_ESCAPE) {
                cancelEdit();
                return true;
            }
            return false;
        }
        // 1–5 jump straight into a section from anywhere; the digit is printed on the hub row itself.
        int digit = keyCode - Keys.NUM_1;
        if (digit >= 0 && digit < Section.menu().length) {
            goTo(Section.menu()[digit]);
            return true;
        }
        if (keyCode == Keys.ESCAPE || keyCode == Keys.BACKSPACE) {
            if (section == Section.HUB) {
                return false; // at the hub, Esc means "close the screen" — let the platform handle it
            }
            goTo(Section.HUB);
            return true;
        }
        if (rows.isEmpty()) {
            return false;
        }
        boolean coarse = (modifiers & MOD_SHIFT) != 0;
        switch (keyCode) {
            case Keys.UP -> moveCursor(-1);
            case Keys.DOWN -> moveCursor(1);
            case Keys.HOME -> cursor = firstInteractive(0, 1);
            case Keys.END -> cursor = firstInteractive(rows.size() - 1, -1);
            case Keys.LEFT -> {
                return adjust(coarse ? -10 : -1);
            }
            case Keys.RIGHT -> {
                return adjust(coarse ? 10 : 1);
            }
            case Keys.ENTER -> {
                Runnable activate = rows.get(clampedCursor()).activate();
                if (activate != null) {
                    activate.run();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Move the selection, skipping captions.
     *
     * <p>A cursor that can land on a line which does nothing makes the arrow keys feel broken — the player
     * presses ↓, something moves, and Enter does nothing. Only interactive rows are stops.
     */
    private void moveCursor(int delta) {
        int size = rows.size();
        int at = clampedCursor();
        for (int step = 0; step < size; step++) {
            at = ((at + delta) % size + size) % size; // wraps, so the far end is never far away
            if (rows.get(at).interactive()) {
                break;
            }
        }
        cursor = at;
    }

    /** The first interactive row scanning from {@code from} in direction {@code step}. */
    private int firstInteractive(int from, int step) {
        for (int i = from; i >= 0 && i < rows.size(); i += step) {
            if (rows.get(i).interactive()) {
                return i;
            }
        }
        return clampedCursor();
    }

    private boolean adjust(int delta) {
        IntConsumer adjust = rows.get(clampedCursor()).adjust();
        if (adjust == null) {
            return false;
        }
        adjust.accept(delta);
        return true;
    }

    private int clampedCursor() {
        return Math.max(0, Math.min(cursor, Math.max(0, rows.size() - 1)));
    }

    /** Open a page. Public so the faction screen can send an admin straight to the section they asked for. */
    public void goTo(Section target) {
        this.section = target;
        this.cursor = 0;
        this.rows = List.of(); // the new page's rows are built on the next frame
        body.setScrollOffset(0);
    }

    @Override
    public void onClose() {
        cancelEdit();
    }

    // --- layout -----------------------------------------------------------------------------------

    @Override
    public Widget<?> build() {
        return build(new Rect(0, 0, WIDE, 460));
    }

    @Override
    public Widget<?> build(Rect viewport) {
        followServer();
        if (editor != null) {
            return editPanel();
        }
        boolean wide = viewport.width() >= WIDE;
        rows = rowsFor(section);
        if (!rows.isEmpty() && !rows.get(clampedCursor()).interactive()) {
            cursor = firstInteractive(0, 1);
        }

        Container page = Ui.column().gap(UiTheme.GAP).align(AlignItems.STRETCH);
        for (int i = 0; i < rows.size(); i++) {
            page.add(rowWidget(rows.get(i), i, i == clampedCursor(), wide));
        }
        body.child(page);

        Container root = UiTheme.screen().children(
                header(),
                body.grow(1f).shrink(1f).background(UiTheme.ROW_ALT),
                hint());
        if (!status.isEmpty()) {
            root.add(Ui.label(status).color(statusColor).wrap(true));
        }
        root.add(actionsRow());
        return root;
    }

    /** Title, plus a visible way back — a level you can only leave by guessing at Esc is a trap. */
    private Widget<?> header() {
        if (section == Section.HUB) {
            return Ui.label(title()).color(UiTheme.TEXT);
        }
        return Ui.row().gap(UiTheme.GAP).align(AlignItems.CENTER).children(
                Ui.button("‹ Назад", () -> goTo(Section.HUB)).background(UiTheme.KEY),
                Ui.label(section.title()).color(UiTheme.TEXT).grow(1f));
    }

    private Widget<?> hint() {
        return Ui.label(section == Section.HUB
                        ? "↑↓ — выбор · Enter — открыть · 1–5 — сразу в раздел"
                        : "↑↓ — выбор · ←→ — изменить (Shift — крупный шаг) · Esc — назад")
                .color(UiTheme.MUTED).wrap(true);
    }

    // --- pages ------------------------------------------------------------------------------------

    private List<Row> rowsFor(Section which) {
        List<Row> out = new ArrayList<>();
        switch (which) {
            case HUB -> {
                Section[] menu = Section.menu();
                for (int i = 0; i < menu.length; i++) {
                    Section target = menu[i];
                    out.add(Row.action((i + 1) + ". " + target.title(), summaryOf(target),
                            () -> goTo(target)));
                }
            }
            case FACTIONS -> {
                for (Faction faction : Faction.playable()) {
                    out.add(Row.action("Название: " + faction.id(), draft.displayName(faction),
                            () -> promptRename(faction)));
                    out.add(Row.faction("Цвет: " + draft.displayName(faction),
                            String.format("#%06X", draft.color(faction)), draft.color(faction),
                            () -> cycleColor(faction, 1), delta -> cycleColor(faction, delta)));
                }
            }
            case CORE -> {
                out.add(Row.number("Энергии на 1 эссенцию", String.valueOf(draft.energyPerEssence()),
                        delta -> draft = draft.withEnergyPerEssence(draft.energyPerEssence() + delta)));
                out.add(Row.caption("Меньше значит быстрее: столько энергии ядро тратит "
                        + "на каждую единицу эссенции."));
            }
            case RULES -> {
                out.add(Row.toggle("Защита территорий", draft.territoryProtection(),
                        () -> draft = draft.withTerritoryProtection(!draft.territoryProtection())));
                out.add(Row.toggle("Ограничения предметов", draft.itemRestrictions(),
                        () -> draft = draft.withItemRestrictions(!draft.itemRestrictions())));
                out.add(Row.toggle("Запрет дружественного огня", draft.friendlyFireProtection(),
                        () -> draft = draft.withFriendlyFireProtection(!draft.friendlyFireProtection())));
                out.add(Row.caption("Ограниченный предмет может использовать только его фракция — "
                        + "и в бою, и в добыче, и при установке."));
            }
            case PROGRESS -> {
                RedSteelConfig.Mechanics m = draft.mechanics();
                out.add(Row.caption("— Концентрация (" + draft.displayName(Faction.SAMURAI) + ") —"));
                out.add(Row.number("За убийство", String.valueOf(m.focusPerKill()),
                        d -> draft = withFocus((long) d, null, null)));
                out.add(Row.number("Порог", String.valueOf(m.focusMax()),
                        d -> draft = withFocus(null, (long) d * 5, null)));
                out.add(Row.number("Награда", String.valueOf(m.focusReward()),
                        d -> draft = withFocus(null, null, (long) d)));
                out.add(Row.toggle("Только убийства врагов", m.honourableKillsOnly(),
                        () -> draft = draft.withMechanics(new RedSteelConfig.Mechanics(
                                m.focusPerKill(), m.focusMax(), m.focusReward(), m.comboNeeded(),
                                m.comboWindowTicks(), m.comboReward(), !m.honourableKillsOnly()))));
                out.add(Row.caption("— Резонанс (" + draft.displayName(Faction.COMMUNISTS) + ") —"));
                out.add(Row.number("Длина комбо", String.valueOf(m.comboNeeded()),
                        d -> draft = withCombo((long) d, null, null)));
                out.add(Row.number("Окно комбо, тиков", String.valueOf(m.comboWindowTicks()),
                        d -> draft = withCombo(null, (long) d * 20, null)));
                out.add(Row.number("Награда", String.valueOf(m.comboReward()),
                        d -> draft = withCombo(null, null, (long) d)));
            }
            case ITEMS -> {
                out.add(Row.action("+ Ограничить предмет в руке", "", this::addHeldItem));
                out.add(Row.action("+ Ввести id вручную", "", this::promptNewItem));
                if (!draft.itemRestrictions()) {
                    out.add(Row.toggle("Правило выключено — список не действует!",
                            false, () -> draft = draft.withItemRestrictions(true)));
                }
                if (draft.restrictedItems().isEmpty()) {
                    out.add(Row.caption("— список пуст —"));
                } else {
                    for (Map.Entry<String, Faction> entry : draft.restrictedItems().entrySet()) {
                        String itemId = entry.getKey();
                        Faction owner = entry.getValue();
                        out.add(Row.faction(itemId, draft.displayName(owner), draft.color(owner),
                                () -> removeItem(itemId), delta -> cycleItemOwner(itemId, delta)));
                    }
                    out.add(Row.caption("Enter на строке — удалить · ←→ — сменить владельца"));
                }
            }
            default -> { }
        }
        return out;
    }

    /** What a hub row says about the section it leads to, so the menu informs rather than merely lists. */
    private String summaryOf(Section which) {
        return switch (which) {
            case FACTIONS -> draft.displayName(Faction.SAMURAI) + " · "
                    + draft.displayName(Faction.COMMUNISTS);
            case CORE -> draft.energyPerEssence() + " энергии";
            case RULES -> enabledRules() + " из 3 вкл.";
            case PROGRESS -> "порог " + draft.mechanics().focusMax()
                    + " · комбо " + draft.mechanics().comboNeeded();
            // The count alone would be a lie while the rule is off: the admin sees "2" and a table that
            // does nothing. Saying so here is the difference between a list and an answer.
            case ITEMS -> draft.restrictedItems().isEmpty()
                    ? "нет"
                    : draft.restrictedItems().size() + (draft.itemRestrictions() ? " шт." : " шт., ВЫКЛ!");
            default -> "";
        };
    }

    private int enabledRules() {
        return (draft.territoryProtection() ? 1 : 0)
                + (draft.itemRestrictions() ? 1 : 0)
                + (draft.friendlyFireProtection() ? 1 : 0);
    }

    // --- row rendering ----------------------------------------------------------------------------

    /**
     * One row. The selected row is <em>filled</em> with the cursor colour rather than outlined: arrow-key
     * navigation is worthless if the row it moves cannot be picked out at a glance.
     */
    private Widget<?> rowWidget(Row row, int index, boolean selected, boolean wide) {
        UiColor background = selected ? UiTheme.SELECTED : UiTheme.ROW;
        if (!row.interactive()) {
            return Ui.row().padding(Insets.symmetric(UiTheme.ROW_PAD_V, UiTheme.ROW_PAD_H))
                    .align(AlignItems.CENTER)
                    .children(Ui.label(row.label()).color(UiTheme.MUTED).wrap(true).grow(1f));
        }

        Widget<?> label = Ui.button(row.label(), () -> {
            cursor = index;
            if (row.activate() != null) {
                row.activate().run();
            }
        }).background(background).grow(3f);

        if (row.value().isEmpty()) {
            return wrap(label, background, wide, null);
        }
        Container value = Ui.row().gap(UiTheme.GAP).align(AlignItems.CENTER);
        if (row.adjust() != null) {
            value.add(Ui.button("‹", () -> {
                cursor = index;
                row.adjust().accept(-1);
            }).background(UiTheme.KEY));
        }
        UiColor tint = row.valueColor() != null ? row.valueColor()
                : selected ? UiTheme.TEXT : UiTheme.MUTED;
        value.add(Ui.label(row.value()).color(tint)
                .align(Justify.CENTER).minContentSize(true).grow(1f));
        if (row.adjust() != null) {
            value.add(Ui.button("›", () -> {
                cursor = index;
                row.adjust().accept(1);
            }).background(UiTheme.KEY));
        }
        return wrap(label, background, wide, value);
    }

    /**
     * Lay a row out on one line where there is room and two where there is not.
     *
     * <p>Item ids are long by nature and faction names are admin-configurable, so on a narrow window the
     * label takes its own line rather than being squeezed until it is unreadable.
     */
    private Widget<?> wrap(Widget<?> label, UiColor background, boolean wide, Container value) {
        if (value == null) {
            return Ui.row().padding(Insets.symmetric(UiTheme.ROW_PAD_V, UiTheme.ROW_PAD_H))
                    .background(background).align(AlignItems.CENTER).children(label);
        }
        if (wide) {
            return Ui.row().gap(UiTheme.GAP)
                    .padding(Insets.symmetric(UiTheme.ROW_PAD_V, UiTheme.ROW_PAD_H))
                    .background(background).align(AlignItems.CENTER).children(label, value.grow(2f));
        }
        return Ui.column().gap(2).padding(Insets.symmetric(UiTheme.ROW_PAD_V, UiTheme.ROW_PAD_H))
                .background(background).align(AlignItems.STRETCH).children(label, value);
    }

    /**
     * The pinned action bar, laid out 2×2 rather than 1×4.
     *
     * <p>{@code FlexLayout} distributes surplus space but does not shrink children when the row is too
     * narrow, so four buttons on one row overflow a 320px screen. Two rows of two keep every label inside
     * its button at every supported width.
     */
    private Widget<?> actionsRow() {
        return Ui.column().gap(UiTheme.GAP).align(AlignItems.STRETCH).children(
                Ui.row().gap(UiTheme.GAP).children(
                        Ui.button("Сохранить", this::save).color(UiTheme.OK).grow(1f),
                        Ui.button("Перечитать файл", this::reload).grow(1f)),
                Ui.row().gap(UiTheme.GAP).children(
                        Ui.button("Сбросить", this::resetDefaults).grow(1f),
                        Ui.button("Закрыть", () -> closeHandler.run()).grow(1f)));
    }

    // --- text editing -----------------------------------------------------------------------------

    private Widget<?> editPanel() {
        return UiTheme.screen().children(
                Ui.label(editLabel).color(UiTheme.MUTED).wrap(true),
                editor.background(UiColor.rgb(0x101014)).padding(Insets.all(4)),
                Ui.label("Enter — подтвердить · Esc — отмена").color(UiTheme.MUTED),
                Ui.row().gap(UiTheme.GAP).children(
                        Ui.button("Готово", this::commitEdit).color(UiTheme.OK).grow(1f),
                        Ui.button("Отмена", this::cancelEdit).grow(1f)),
                Ui.spacer().grow(1f));
    }

    private void beginEdit(String label, String initial, int maxLength, Consumer<String> commit) {
        this.editLabel = label;
        this.onCommit = commit;
        // focused(true) at construction — a field that silently ignores typing reads as broken input.
        this.editor = Ui.textField(initial).maxLength(maxLength).textColor(UiTheme.TEXT).focused(true);
    }

    private void commitEdit() {
        if (editor == null) {
            return;
        }
        String value = editor.text();
        Consumer<String> commit = onCommit;
        cancelEdit();
        if (commit != null) {
            commit.accept(value);
        }
    }

    private void cancelEdit() {
        editor = null;
        onCommit = null;
        editLabel = "";
    }

    private void promptRename(Faction faction) {
        beginEdit("Название фракции: " + faction.id(), draft.displayName(faction),
                RedSteelConfig.MAX_DISPLAY_NAME, name -> {
                    draft = draft.withDisplayName(faction, name);
                    note("Название изменено (не сохранено)", UiTheme.WARN);
                });
    }

    private void promptNewItem() {
        beginEdit("ID предмета (напр. minecraft:diamond_sword)", "minecraft:", 128, itemId -> {
            String trimmed = itemId.trim();
            // The same validity rule the command uses, so a typo is rejected identically on both surfaces
            // instead of being stored as a restriction that can never match anything.
            if (!FactionCommands.isItemId(trimmed)) {
                note("Нужен id вида namespace:path, например minecraft:diamond_sword", UiTheme.WARN);
            } else {
                draft = draft.withRestrictedItem(trimmed, Faction.playable()[0]);
                note("Добавлен " + trimmed + " (не сохранено)", UiTheme.WARN);
            }
        });
    }

    /**
     * Restrict the item in the admin's hand — one click, no typing.
     *
     * <p>An item id is the one setting here that cannot be guessed: exact, case-sensitive, namespaced, and
     * wrong by one character means a restriction that silently never fires. Holding the item is how a player
     * already names it, so that is the primary path; typing stays as the fallback for items not to hand.
     */
    private void addHeldItem() {
        PlayerHandle viewer = Platform.bridge().players().local().orElse(null);
        if (viewer == null) {
            note("Инвентарь недоступен — введите id вручную", UiTheme.WARN);
            return;
        }
        String held = FactionCommands.heldItem(viewer);
        if (held == null) {
            note("В руке ничего нет — возьмите предмет", UiTheme.WARN);
            return;
        }
        draft = draft.withRestrictedItem(held, Faction.playable()[0]);
        note("Добавлен " + held + " (не сохранено) — ←→ меняет владельца", UiTheme.WARN);
    }

    // --- actions ----------------------------------------------------------------------------------

    private void removeItem(String itemId) {
        draft = draft.withRestrictedItem(itemId, Faction.NEUTRAL);
        note("Предмет " + itemId + " убран (не сохранено)", UiTheme.WARN);
    }

    private void cycleColor(Faction faction, int delta) {
        int current = draft.color(faction);
        int index = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == current) {
                index = i;
                break;
            }
        }
        int next = ((index + delta) % PALETTE.length + PALETTE.length) % PALETTE.length;
        draft = draft.withColor(faction, PALETTE[next]);
    }

    private void cycleItemOwner(String itemId, int delta) {
        Faction[] playable = Faction.playable();
        int index = 0;
        for (int i = 0; i < playable.length; i++) {
            if (playable[i] == draft.restrictionOwner(itemId)) {
                index = i;
                break;
            }
        }
        int next = ((index + delta) % playable.length + playable.length) % playable.length;
        draft = draft.withRestrictedItem(itemId, playable[next]);
    }

    /** Nudge one Focus field, keeping the rest — {@code null} means "leave alone". */
    private RedSteelConfig withFocus(Long perKill, Long max, Long reward) {
        RedSteelConfig.Mechanics m = draft.mechanics();
        return draft.withMechanics(new RedSteelConfig.Mechanics(
                perKill == null ? m.focusPerKill() : m.focusPerKill() + perKill,
                max == null ? m.focusMax() : m.focusMax() + max,
                reward == null ? m.focusReward() : m.focusReward() + reward,
                m.comboNeeded(), m.comboWindowTicks(), m.comboReward(), m.honourableKillsOnly()));
    }

    /** Nudge one Kinetic Resonance field, keeping the rest. */
    private RedSteelConfig withCombo(Long needed, Long window, Long reward) {
        RedSteelConfig.Mechanics m = draft.mechanics();
        return draft.withMechanics(new RedSteelConfig.Mechanics(
                m.focusPerKill(), m.focusMax(), m.focusReward(),
                needed == null ? m.comboNeeded() : m.comboNeeded() + needed,
                window == null ? m.comboWindowTicks() : m.comboWindowTicks() + window,
                reward == null ? m.comboReward() : m.comboReward() + reward,
                m.honourableKillsOnly()));
    }

    /**
     * Ask the server to adopt the draft.
     *
     * <p>The screen writes nothing itself: the edit goes to the server, which validates it, checks the
     * sender's permission, persists it and broadcasts the result back. The draft is refreshed from that
     * broadcast, so what the admin ends up looking at is what the server actually accepted — including
     * values it clamped.
     */
    private void save() {
        FactionNetwork.requestConfigChange(draft);
        note("Отправлено на сервер…", UiTheme.MUTED);
    }

    /**
     * Take the server's word for it whenever a newer snapshot has been adopted.
     *
     * <p>The server is the authority: after "Сохранить" it validates, clamps and persists, then broadcasts
     * what it actually accepted. Silently discarding the local draft at that moment is correct — showing the
     * admin their own rejected numbers would be a lie about the server's state.
     */
    private void followServer() {
        long live = RedSteelConfigs.version();
        if (live != draftVersion) {
            draftVersion = live;
            draft = RedSteelConfigs.current();
            if (!status.isEmpty()) {
                note("Настройки применены сервером", UiTheme.OK);
            }
        }
    }

    private void reload() {
        // reload() reports rather than throws, so a malformed hand-edit surfaces as a message on the screen
        // instead of an exception, with the last-good config still live.
        if (RedSteelConfigs.store() != null) {
            var result = RedSteelConfigs.store().reload();
            draft = RedSteelConfigs.current();
            draftVersion = RedSteelConfigs.version();
            note(result.ok() ? "Перечитано из файла"
                            : "Файл повреждён, оставлены прежние значения: "
                                    + result.diagnostic().map(Object::toString).orElse("?"),
                    result.ok() ? UiTheme.OK : UiTheme.WARN);
            return;
        }
        draft = RedSteelConfigs.current();
        note("Конфиг не подключён", UiTheme.WARN);
    }

    private void resetDefaults() {
        draft = RedSteelConfig.defaults();
        note("Восстановлены значения по умолчанию (не сохранено)", UiTheme.WARN);
    }

    private void note(String message, UiColor color) {
        this.status = message;
        this.statusColor = color;
    }
}
