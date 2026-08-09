/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the Anomaly Core's 16×16 block texture and writes it into the mod's resources.
 *
 * <p>EN: the framework changed the generated block model to reference {@code <modid>:block/<name>}
 * instead of substituting a vanilla texture, so the mod must ship this PNG or the block renders as the
 * missing-texture checkerboard. The image is <em>drawn</em>, not sampled from noise: at 16×16 random
 * variation reads as mush, so every pixel comes from an explicit pattern over a fixed six-colour palette —
 * a bevelled steel frame (lit from the top-left, in the same direction vanilla lights its blocks), corner
 * rivets, and a crimson rift whose glow falls off toward the frame with a dark focus at the centre.
 *
 * <p>This is a build tool, not shipped code: it lives in {@code src/test/java} and its output is committed
 * under {@code src/main/resources}. Re-running it is deterministic — the same bytes every time — so it
 * never disturbs reproducible builds.
 *
 * <p>RU: теперь генератор моделей ссылается на {@code <modid>:block/<name>} вместо ванильной текстуры,
 * поэтому без этого PNG блок отрисуется чёрно-фиолетовой «шашечкой». Изображение именно РИСУЕТСЯ, а не
 * набирается шумом: на 16×16 случайность читается как мыло, поэтому каждый пиксель задан явным узором на
 * фиксированной палитре из шести цветов — стальная рама со скосом (свет сверху-слева, как ванильное
 * освещение блоков), заклёпки по углам и багровый разлом со спадающим к раме свечением и тёмным ядром.
 * Инструмент сборки, а не поставляемый код; вывод детерминирован, поэтому воспроизводимость сборки цела.
 */
public final class AnomalyCoreTexture {

    private static final int SIZE = 16;

    // --- palette (ARGB) -------------------------------------------------------------------------
    /** Frame highlight — the top/left bevel. */
    private static final int STEEL_LIT = 0xFF6E7178;
    /** Frame body. */
    private static final int STEEL = 0xFF4A4D54;
    /** Frame shadow — the bottom/right bevel, and the recess around the rift. */
    private static final int STEEL_DARK = 0xFF2B2D33;
    /** Rift, hottest. */
    private static final int RIFT_HOT = 0xFFFF6B4A;
    /** Rift, mid. */
    private static final int RIFT = 0xFFC0392B;
    /** Rift core / deepest shadow. */
    private static final int RIFT_DEEP = 0xFF3A0F0C;

    private AnomalyCoreTexture() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/assets/redsteelcore/textures/block/anomaly_core.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(draw(), "png", out.toFile());
        System.out.println("Anomaly Core texture written to " + out.toAbsolutePath());
    }

    /** Build the 16×16 image. Pure function — same bytes on every run. */
    public static BufferedImage draw() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                img.setRGB(x, y, pixel(x, y));
            }
        }
        return img;
    }

    private static int pixel(int x, int y) {
        int edge = Math.min(Math.min(x, y), Math.min(SIZE - 1 - x, SIZE - 1 - y));

        // --- outer frame: a two-pixel bevelled border -------------------------------------------
        if (edge == 0) {
            // Outermost ring: lit on the top/left run, shadowed on the bottom/right run.
            return (x == 0 || y == 0) ? STEEL_LIT : STEEL_DARK;
        }
        if (edge == 1) {
            // Rivets sit on this ring's four corners — at 16×16 a single lit pixel reads as a fastener.
            boolean rivet = (x == 1 || x == SIZE - 2) && (y == 1 || y == SIZE - 2);
            return rivet ? STEEL_LIT : STEEL;
        }
        if (edge == 2) {
            // A dark recess separating the frame from the rift, so the glow looks inset.
            return STEEL_DARK;
        }

        // --- the rift: a diamond centred on the block, glow falling off outward -----------------
        // Chebyshev-like diamond distance from the centre of a 16×16 grid (centre between 7 and 8).
        int dx = Math.abs(2 * x - (SIZE - 1));   // doubled to keep integer arithmetic exact
        int dy = Math.abs(2 * y - (SIZE - 1));
        int diamond = dx + dy;                   // 0 at centre, grows outward

        if (diamond <= 3) {
            return RIFT_DEEP;                    // the eye of the rift: darkest, so the glow reads as a ring
        }
        if (diamond <= 9) {
            return RIFT_HOT;
        }
        if (diamond <= 15) {
            return RIFT;
        }
        // Between the rift and the recess: the rift's edge cooling into the metal.
        return (diamond <= 19) ? RIFT_DEEP : STEEL_DARK;
    }
}
