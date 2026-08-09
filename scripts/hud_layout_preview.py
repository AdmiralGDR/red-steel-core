#!/usr/bin/env python3
"""
hud_layout_preview.py — детерминированный оффлайн-рендер раскладки HUD для «зрячего» QA БЕЗ запуска GL.

Воспроизводит точные координаты из FactionHUDOverlay.render(...) и накладывает их на макет ванильного
HUD (хотбар + сердца + еда + опыт) при типичном GUI-размере 640×360 (1080p, GUI scale 3). Так можно
визуально проверить: перекрывает ли фракционный HUD ванильные бары и где он расположен.

Вывод: build/hud_layout_preview.png
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "build", "hud_layout_preview.png")

GW, GH = 640, 360            # эффективный размер GUI (guiWidth/guiHeight)
SCALE = 2                    # увеличение картинки для читаемости
SLIDE = 15                   # slideOffset при активном HUD (translate X)


def R(x): return int(round(x * SCALE))


def main():
    img = Image.new("RGB", (GW * SCALE, GH * SCALE), (28, 30, 36))
    d = ImageDraw.Draw(img)

    # ── Ванильный HUD (макет) ──
    cx = GW // 2
    # хотбар: ширина 182, низ экрана
    hb_x0, hb_y0, hb_x1, hb_y1 = cx - 91, GH - 22, cx + 91, GH - 1
    d.rectangle([R(hb_x0), R(hb_y0), R(hb_x1), R(hb_y1)], fill=(60, 60, 66), outline=(120, 120, 130))
    # сердца: слева от центра над хотбаром (10 сердец по 8px), y≈GH-39
    for i in range(10):
        hx = cx - 91 + i * 8
        d.rectangle([R(hx), R(GH - 39), R(hx + 6), R(GH - 33)], fill=(200, 40, 40))
    # еда: справа от центра, y≈GH-39
    for i in range(10):
        fx = cx + 91 - 8 - i * 8
        d.rectangle([R(fx), R(GH - 39), R(fx + 6), R(GH - 33)], fill=(150, 110, 40))
    # бар опыта: над хотбаром
    d.rectangle([R(cx - 91), R(GH - 29), R(cx + 91), R(GH - 27)], fill=(90, 200, 70))
    d.text((R(cx - 30), R(GH - 50)), "VANILLA BARS", fill=(180, 180, 190))

    # ── Фракционный HUD (точные координаты из FactionHUDOverlay) ──
    yPos = GH // 2 + 10
    # панель: drawThematicPanel(g, 0, yPos, 110, 36) + translate(slideOffset)
    px0, py0, pw, ph = 0 + SLIDE, yPos, 110, 36
    d.rectangle([R(px0), R(py0), R(px0 + pw), R(py0 + ph)],
                fill=(40, 26, 30), outline=(220, 60, 60), width=2)
    # заголовок фракции (8, yPos+4)
    d.text((R(SLIDE + 8), R(yPos + 4)), "ORDER OF SAMURAI", fill=(235, 220, 180))
    # валюта-иконка (8, yPos+18) + число (28, yPos+22)
    d.rectangle([R(SLIDE + 8), R(yPos + 18), R(SLIDE + 16), R(yPos + 26)], fill=(235, 200, 70))
    d.text((R(SLIDE + 28), R(yPos + 20)), "128", fill=(255, 255, 255))
    # шкала фокуса: barX=45, barY=yPos+24, barW=55, barH=4
    bx, by, bw, bh = SLIDE + 45, yPos + 24, 55, 4
    d.rectangle([R(bx), R(by), R(bx + bw), R(by + bh)], fill=(70, 130, 200), outline=(120, 180, 240))
    d.text((R(bx), R(by - 7)), "FOCUS", fill=(170, 170, 170))
    d.text((R(SLIDE), R(yPos - 9)), "FACTION HUD (left, mid)", fill=(220, 120, 120))

    # рамки экрана
    d.rectangle([0, 0, GW * SCALE - 1, GH * SCALE - 1], outline=(80, 80, 90))
    img.save(OUT)
    print("saved", os.path.relpath(OUT, ROOT))
    # численная проверка перекрытия
    hud_box = (px0, py0, px0 + pw, py0 + ph)
    vanilla_top = GH - 39
    print(f"HUD bottom y={hud_box[3]}, vanilla bars top y={vanilla_top} → overlap={hud_box[3] > vanilla_top}")
    print(f"HUD x-range [{hud_box[0]},{hud_box[2]}], vanilla bars x-range [{cx-91},{cx+91}]")


if __name__ == "__main__":
    main()
