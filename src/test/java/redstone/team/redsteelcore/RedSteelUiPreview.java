/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore;

import org.aetherium.ui.AetheriumScreen;
import org.aetherium.ui.LaidOut;
import org.aetherium.ui.Rect;
import org.aetherium.ui.UiMetrics;
import org.aetherium.ui.UiRenderer;
import org.aetherium.ui.UiRuntime;
import redstone.team.redsteelcore.client.ConfigScreen;
import redstone.team.redsteelcore.client.FactionHud;
import redstone.team.redsteelcore.client.FactionScreen;
import redstone.team.redsteelcore.config.RedSteelConfigs;
import redstone.team.redsteelcore.faction.Faction;
import redstone.team.redsteelcore.faction.FactionManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Renders the mod's screens to PNG through a pure-JDK {@link Graphics2D} {@link UiRenderer} — a human-facing
 * proof that the layout is correct without a running game.
 *
 * <p>EN: The loader's real {@code UiRenderer} (over Minecraft's {@code GuiGraphics}) does not exist yet, so
 * there is no way to screenshot these screens in-game. This test-scope tool implements the same two-method
 * SPI over AWT — no Minecraft type involved — and paints exactly what {@code UiRuntime} dispatches, using
 * <em>font-accurate</em> metrics (unlike the offline {@code UiMetrics.DEFAULT}). If the text fits and reads
 * well here, it fits and reads well in-game, because the layout math is identical. Output is written to the
 * directory given as the first argument.
 * RU: Настоящего {@code UiRenderer} загрузчика (поверх {@code GuiGraphics} Minecraft) ещё нет, поэтому
 * заскриншотить эти экраны в игре нельзя. Этот тест-инструмент реализует тот же SPI из двух методов поверх
 * AWT (без единого типа Minecraft) и рисует ровно то, что раздаёт {@code UiRuntime}, со шрифтово-точными
 * метриками. Если текст влезает и читается здесь — влезает и в игре, т.к. математика раскладки одинакова.
 */
public final class RedSteelUiPreview {

    private RedSteelUiPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "build/ui-preview");
        Files.createDirectories(outDir);

        // Seed some believable state so the screens are not empty.
        RedSteelConfigs.resetForTesting();
        UUID viewer = UUID.randomUUID();
        FactionManager m = FactionManager.get();
        m.join(viewer, Faction.SAMURAI);
        m.setEssence(viewer, 1280L);
        m.setQuota(viewer, 3L);

        // Two widths for each screen: the responsive build(Rect) must read well narrow AND wide.
        render(new FactionScreen(viewer, "Кэндзо").withAdminAccess(true)
                        .withCoreStatus(new FactionScreen.CoreStatus(128, 64, -310, "Орден Самураев", 1536L)),
                360, 380, outDir.resolve("faction_narrow.png"));
        render(new FactionScreen(viewer, "Кэндзо").withAdminAccess(true)
                        .withCoreStatus(new FactionScreen.CoreStatus(128, 64, -310, "Орден Самураев", 1536L)),
                600, 380, outDir.resolve("faction_wide.png"));
        render(new ConfigScreen(), 360, 460, outDir.resolve("config_narrow.png"));
        render(new ConfigScreen(), 600, 460, outDir.resolve("config_wide.png"));

        // One shot per section, so the whole editor can be reviewed by eye rather than only its menu.
        for (ConfigScreen.Section s : ConfigScreen.Section.menu()) {
            ConfigScreen page = new ConfigScreen();
            page.goTo(s);
            render(page, 600, 460, outDir.resolve("config_" + s.name().toLowerCase() + ".png"));
        }

        // The same screen scrolled to the bottom: shows the restricted-item rows and the position read-out,
        // which only appear once the panel has measured its extents (i.e. after one layout pass).
        ConfigScreen scrolled = new ConfigScreen();
        scrolled.goTo(ConfigScreen.Section.PROGRESS);
        renderScrolledToEnd(scrolled, 600, 460, outDir.resolve("config_scrolled.png"));

        ConfigScreen editing = new ConfigScreen();
        // Drive it into text-edit mode by clicking the first "Переименовать".
        LaidOut probe = UiRuntime.render(editing.build(new Rect(0, 0, 480, 460)), new Rect(0, 0, 480, 460),
                AwtMetrics.INSTANCE, new AwtRenderer(null, 480, 460));
        Rect rename = findButton(probe, "Переименовать");
        if (rename != null) {
            UiRuntime.click(probe, rename.x() + rename.width() / 2, rename.y() + rename.height() / 2);
        }
        render(editing, 480, 320, outDir.resolve("config_edit.png"));

        // The HUD is drawn over a RUNNING game, so preview it on a green field rather than the dark screen
        // background — an overlay that only reads against its own backdrop is not actually legible.
        redstone.team.redsteelcore.gameplay.FactionProgress.get().reset(viewer);
        redstone.team.redsteelcore.gameplay.FactionProgress.get().onHonourableKill(viewer);
        redstone.team.redsteelcore.gameplay.FactionProgress.get().onHonourableKill(viewer);
        redstone.team.redsteelcore.gameplay.FactionProgress.get().onHonourableKill(viewer);
        renderHud(handleFor(viewer, org.aetherium.edge.InventoryAccess.AIR), 420, 200,
                outDir.resolve("hud.png"));

        // The same HUD in the state that matters most: standing on enemy ground with an item you are not
        // allowed to use. This is the view that answers "why did nothing happen when I swung it?".
        FactionManager.get().claim(0, 0, Faction.COMMUNISTS);
        RedSteelConfigs.save(RedSteelConfigs.current()
                .withRestrictedItem("minecraft:diamond_sword", Faction.COMMUNISTS));
        renderHud(handleFor(viewer, "minecraft:diamond_sword"), 420, 200,
                outDir.resolve("hud_blocked.png"));
        FactionManager.get().unclaim(0, 0);

        RedSteelConfigs.resetForTesting();
        System.out.println("UI preview written to " + outDir.toAbsolutePath());
    }

    /** Lay the screen out once so the panel measures its extents, scroll to the end, then paint. */
    private static void renderScrolledToEnd(AetheriumScreen screen, int w, int h, Path out) throws Exception {
        LaidOut first = UiRuntime.render(screen.build(new Rect(0, 0, w, h)), new Rect(0, 0, w, h),
                AwtMetrics.INSTANCE, new AwtRenderer(null, w, h));
        org.aetherium.ui.ScrollPanel panel = findScrollPanel(first);
        if (panel != null) {
            panel.setScrollOffset(panel.maxScroll());
        }
        render(screen, w, h, out);
    }

    private static org.aetherium.ui.ScrollPanel findScrollPanel(LaidOut node) {
        if (node.widget() instanceof org.aetherium.ui.ScrollPanel p) {
            return p;
        }
        for (LaidOut child : node.children()) {
            org.aetherium.ui.ScrollPanel found = findScrollPanel(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Paint a HUD over a stand-in for the world, so contrast against real scenery is visible. */
    private static void renderHud(org.aetherium.edge.PlayerHandle viewer, int w, int h, Path out) {
        TestBridge.withLocalPlayer(viewer, () -> {
            try {
                paintHud(new FactionHud(), w, h, out);
            } catch (Exception e) {
                throw new IllegalStateException("HUD preview failed", e);
            }
        });
    }

    private static void paintHud(FactionHud hud, int w, int h, Path out) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int y = 0; y < h; y++) {
            g.setColor(new Color(0x4A + y / 6, 0x7A + y / 8, 0x38 + y / 12));
            g.drawLine(0, y, w, y);
        }
        AwtRenderer renderer = new AwtRenderer(g, w, h);
        UiRuntime.render(hud.build(new Rect(0, 0, w, h)), new Rect(0, 0, w, h), AwtMetrics.INSTANCE, renderer);
        g.dispose();
        ImageIO.write(image, "png", out.toFile());
    }

    /** A minimal handle so the preview can name the player the HUD is about. */
    private static org.aetherium.edge.PlayerHandle handleFor(UUID id, String held) {
        return new org.aetherium.edge.PlayerHandle() {
            @Override public UUID id() {
                return id;
            }

            @Override public String name() {
                return "Кэндзо";
            }

            @Override public double x() {
                return 0;
            }

            @Override public double y() {
                return 0;
            }

            @Override public double z() {
                return 0;
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
                        return 1;
                    }

                    @Override public String itemId(int slot) {
                        return held;
                    }

                    @Override public int count(int slot) {
                        return AIR.equals(held) ? 0 : 1;
                    }

                    @Override public void setItem(int slot, String itemId, int count) {
                    }

                    @Override public int selectedSlot() {
                        return 0;
                    }
                };
            }

            @Override public void sendMessage(String message) {
            }
        };
    }

    private static void render(AetheriumScreen screen, int w, int h, Path out) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x0B, 0x0B, 0x0F));
        g.fillRect(0, 0, w, h);

        AwtRenderer renderer = new AwtRenderer(g, w, h);
        UiRuntime.render(screen.build(new Rect(0, 0, w, h)), new Rect(0, 0, w, h), AwtMetrics.INSTANCE, renderer);

        g.dispose();
        ImageIO.write(image, "png", out.toFile());
    }

    private static Rect findButton(LaidOut node, String text) {
        if (node.widget() instanceof org.aetherium.ui.Button b && b.text().equals(text)) {
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

    /** Font-accurate metrics so the preview matches what a real font renderer would measure. */
    private static final class AwtMetrics implements UiMetrics {
        static final AwtMetrics INSTANCE = new AwtMetrics();
        static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        private final java.awt.FontMetrics fm;

        AwtMetrics() {
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = probe.createGraphics();
            g.setFont(FONT);
            this.fm = g.getFontMetrics();
            g.dispose();
        }

        @Override public int textWidth(String text) {
            return text == null ? 0 : fm.stringWidth(text);
        }

        @Override public int lineHeight() {
            return fm.getAscent() + fm.getDescent();
        }
    }

    /** The two-method {@link UiRenderer} SPI over AWT — the same surface the loader implements over MC. */
    private static final class AwtRenderer implements UiRenderer {
        private final Graphics2D g;
        // A list, not an ArrayDeque: Graphics2D.getClip() returns null when unclipped, and ArrayDeque
        // rejects null elements.
        private final List<java.awt.Shape> clips = new ArrayList<>();

        AwtRenderer(Graphics2D g, int w, int h) {
            this.g = g;
        }

        @Override public void fillRect(int x, int y, int width, int height, int argb) {
            if (g == null) {
                return;
            }
            g.setColor(new Color(argb, true));
            g.fillRect(x, y, width, height);
            // A hairline border makes each element's bounds visible in the proof image.
            g.setColor(new Color(0xFF, 0xFF, 0xFF, 18));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x, y, width - 1, height - 1);
        }

        @Override public void drawText(int x, int y, String text, int argb) {
            if (g == null || text == null || text.isEmpty()) {
                return;
            }
            g.setFont(AwtMetrics.FONT);
            g.setColor(new Color(argb, true));
            g.drawString(text, x, y + AwtMetrics.INSTANCE.fm.getAscent());
        }

        @Override public void pushClip(int x, int y, int width, int height) {
            if (g == null) {
                return;
            }
            clips.add(g.getClip());
            g.clipRect(x, y, width, height);
        }

        @Override public void popClip() {
            if (g == null || clips.isEmpty()) {
                return;
            }
            g.setClip(clips.remove(clips.size() - 1));
        }
    }
}
