/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore.client;

import org.aetherium.ui.AlignItems;
import org.aetherium.ui.Container;
import org.aetherium.ui.Insets;
import org.aetherium.ui.Ui;
import org.aetherium.ui.UiColor;

/**
 * One small design system shared by every RedSteelCore screen, so the two GUIs read as one product.
 *
 * <p>EN: Centralizes the palette, spacing and the two recurring row shapes (label + control). Keeping this
 * in one place is what makes "everything is consistent, the text fits, it is intuitive" a property of the
 * codebase rather than a per-screen accident. The aetherium-ui layer has no translation system, so labels
 * are raw strings (the framework's own screens are the same); RedSteelCore's are Russian, matching the mod.
 * RU: Единая дизайн-система для всех экранов, чтобы оба GUI выглядели как один продукт. Здесь собраны
 * палитра, отступы и две повторяющиеся формы строк (метка + контрол). Именно это делает «всё согласовано,
 * текст влезает, интуитивно» свойством кода, а не случайностью каждого экрана.
 */
final class UiTheme {

    static final UiColor PANEL = UiColor.rgb(0x14141A);
    static final UiColor ROW = UiColor.rgb(0x1E1E26);
    static final UiColor ROW_ALT = UiColor.rgb(0x24242E);
    static final UiColor TEXT = UiColor.WHITE;
    static final UiColor MUTED = UiColor.rgb(0xAAB2BD);
    static final UiColor ACCENT = UiColor.rgb(0xE74C3C);
    static final UiColor OK = UiColor.rgb(0x2ECC71);
    static final UiColor WARN = UiColor.rgb(0xE67E22);
    static final UiColor KEY = UiColor.rgb(0x2B2B36);
    /**
     * The keyboard cursor's fill.
     *
     * <p>Arrow-key navigation is worthless unless the row it moves is visible, so the selected row is filled
     * rather than outlined — an outline is a one-pixel difference that a player scanning the screen misses.
     */
    static final UiColor SELECTED = UiColor.rgb(0x3A3A4C);

    static final int GAP = 4;
    static final int PAD = 8;
    static final int ROW_PAD_V = 3;
    static final int ROW_PAD_H = 6;

    private UiTheme() {
    }

    /** The outer screen frame: padded column, stretch, dark panel. */
    static Container screen() {
        return Ui.column()
                .padding(Insets.all(PAD))
                .gap(GAP)
                .background(PANEL)
                .align(AlignItems.STRETCH);
    }

    /** A settings row: a padded, vertically-centred flex row on the row background. */
    static Container settingRow() {
        return Ui.row()
                .padding(Insets.symmetric(ROW_PAD_V, ROW_PAD_H))
                .gap(GAP)
                .background(ROW)
                .align(AlignItems.CENTER);
    }

    /** A section header. */
    static org.aetherium.ui.Text header(String text) {
        return Ui.label(text).color(MUTED).padding(Insets.symmetric(2, 0));
    }

    /**
     * Pick black or white text for a filled swatch of {@code rgb}, whichever is legible on it.
     *
     * <p>Faction colours are admin-configurable, so a fixed label colour would eventually land on an
     * unreadable pairing — a dark faction red as text on the dark panel, or white text on a pale yellow.
     * Using the faction colour as the <em>fill</em> and choosing the label from its relative luminance
     * (ITU-R BT.709 coefficients) keeps every combination readable whatever the admin picks.
     */
    static UiColor onColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return luminance > 0.55 ? UiColor.rgb(0x101014) : UiColor.WHITE;
    }
}
