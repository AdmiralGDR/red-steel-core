#!/usr/bin/env python3
"""
generate_item_icons.py — HIGH-FIDELITY pixel-art engine (algorithmic shading, NO VANILLA ASSETS).

Рисует 16×16 иконки кастомных предметов RedSteelCore строго в
  assets/redsteelcore/textures/item/
Только Pillow (см. requirements.txt) — детерминированно, без сети.

Алгоритмический шейдинг (а не плоская заливка):
  • Outline — тёмная обводка по контуру (4-соседство с прозрачным фоном).
  • Spherical Shading — настоящее сферическое освещение (Lambert + Specular + Rim) для
    `syndicate_core` (неоново-синее ядро) и `order_samurai_soul` (красно-золотая душа):
    блик в левом-верхнем углу, градиентная тень к правому-нижнему.
  • Katana gradient — клинок `order_spirit_katana`/`syndicate_plasma_blade` идёт градиентом от
    тёмного металла (у гарды) к неоновому свечению (остриё) + чёткая цуба (Tsuba) + обмотка рукояти.

Запуск:  python3 scripts/generate_item_icons.py
"""
from __future__ import annotations

import math
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow не установлен. Выполните: pip install -r requirements.txt")

SIZE = 16
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "redsteelcore", "textures", "item")

TRANSPARENT = (0, 0, 0, 0)
OUTLINE = (12, 10, 18, 255)  # почти-чёрная обводка с лёгким фиолетовым тоном


# ───────────────────────────── низкоуровневые помощники ─────────────────────────────

def _new():
    return Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)


def _set(img, x, y, color):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        img.putpixel((x, y), color)


def _get(img, x, y):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        return img.getpixel((x, y))
    return TRANSPARENT


def _clamp(v):
    return max(0, min(255, int(round(v))))


def _mix(a, b, t):
    """Линейная интерполяция RGB (t=0→a, t=1→b), альфа берётся непрозрачной."""
    return (_clamp(a[0] + (b[0] - a[0]) * t),
            _clamp(a[1] + (b[1] - a[1]) * t),
            _clamp(a[2] + (b[2] - a[2]) * t), 255)


def _add(c, rgb, k):
    """Аддитивный блик: c + rgb*k (для specular/rim свечения)."""
    return (_clamp(c[0] + rgb[0] * k),
            _clamp(c[1] + rgb[1] * k),
            _clamp(c[2] + rgb[2] * k), 255)


def add_outline(img, color=OUTLINE):
    """Тёмная обводка: каждый прозрачный пиксель, граничащий (4-соседство) с непрозрачным, → color."""
    src = img.copy()
    for y in range(SIZE):
        for x in range(SIZE):
            if _get(src, x, y)[3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if _get(src, x + dx, y + dy)[3] != 0:
                    _set(img, x, y, color)
                    break


# ───────────────────────────── сферический шейдинг ─────────────────────────────

# Направление света: левее-верхнее и «на зрителя» (классический pixel-art свет).
_LX, _LY, _LZ = -0.50, -0.50, 0.71
_LL = math.sqrt(_LX * _LX + _LY * _LY + _LZ * _LZ)
_LX, _LY, _LZ = _LX / _LL, _LY / _LL, _LZ / _LL


def shade_orb(base, dark, glow, r=6.6, cx=7.5, cy=7.5):
    """
    Освещённая сфера. На каждом пикселе внутри диска радиуса r считаем нормаль сферы
    N=(dx,dy,nz), затем:
      Lambert  = max(0, N·L)            — основная светотень (тень к право-низу);
      Specular = (N·L)^k                — яркий блик в лево-верхнем углу;
      Rim      = (1-nz)^2               — контровой ободок свечения по краю.
    Цвет = mix(dark, base, ambient+lambert) + glow*(spec + rim).
    """
    img = _new()
    amb = 0.32
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x + 0.5 - cx) / r
            dy = (y + 0.5 - cy) / r
            d2 = dx * dx + dy * dy
            if d2 > 1.0:
                continue
            nz = math.sqrt(max(0.0, 1.0 - d2))
            ndotl = max(0.0, dx * _LX + dy * _LY + nz * _LZ)
            inten = min(1.0, amb + (1.0 - amb) * ndotl)
            col = _mix(dark, base, inten)
            spec = ndotl ** 14            # компактный зеркальный блик
            rim = (1.0 - nz) ** 2 * 0.45  # свечение по краю (энергия)
            col = _add(col, glow, spec * 0.9 + rim * 0.5)
            _set(img, x, y, col)
    return img


# ───────────────────────────── иконки-предметы ─────────────────────────────

def syndicate_core() -> Image.Image:
    """Неоново-синее энергетическое ядро (сфера) + тонкое кольцо-орбита + аддитивный блик."""
    base = (38, 120, 205, 255)
    dark = (10, 26, 55, 255)
    glow = (150, 235, 255, 255)
    img = shade_orb(base, dark, glow, r=6.6)
    # Кольцо-орбита (экваториальная техно-полоса) — чуть ярче base.
    ring = (70, 180, 240, 255)
    for x in range(SIZE):
        dx = (x + 0.5 - 7.5) / 6.6
        if abs(dx) <= 1.0:
            for yy in (7, 8):
                if _get(img, x, yy)[3] != 0:
                    _set(img, x, yy, _mix(_get(img, x, yy), ring, 0.45))
    # Яркое ядро-сердечник.
    _set(img, 6, 6, glow)
    _set(img, 7, 6, glow)
    add_outline(img)
    return img


def order_samurai_soul() -> Image.Image:
    """Красно-золотая духовная сфера: горячее ядро, золотой контровой ободок."""
    base = (200, 45, 38, 255)
    dark = (95, 12, 14, 255)
    glow = (255, 205, 90, 255)   # золотое свечение/блик
    img = shade_orb(base, dark, glow, r=6.6)
    # Золотой контровой ободок по самому краю (дух).
    gold = (240, 200, 80, 255)
    for y in range(SIZE):
        for x in range(SIZE):
            if _get(img, x, y)[3] == 0:
                continue
            dx = (x + 0.5 - 7.5) / 6.6
            dy = (y + 0.5 - 7.5) / 6.6
            if dx * dx + dy * dy >= 0.82:
                _set(img, x, y, _mix(_get(img, x, y), gold, 0.5))
    add_outline(img)
    return img


def _katana(metal_dark, glow) -> Image.Image:
    """
    Диагональная катана (рукоять снизу-слева → остриё сверху-справа).
    Лезвие — градиент вдоль клинка: тёмный металл у цубы → неоновое свечение к острию.
    Плюс чёткая цуба (Tsuba) и обмотанная рукоять.
    """
    img = _new()
    handle = (46, 32, 28, 255)
    wrap = (150, 120, 70, 255)    # обмотка цуки (рукояти)
    tsuba_a = (224, 188, 96, 255)  # золото цубы
    tsuba_b = (140, 108, 40, 255)

    # Лезвие: i от 6 (у гарды) до 15 (остриё). t — доля пути к острию.
    for i in range(6, SIZE):
        x = i
        y = SIZE - 1 - i
        t = (i - 6) / (SIZE - 1 - 6)
        edge = _mix(metal_dark, glow, t)              # светящаяся режущая кромка
        spine = _mix(metal_dark, _mix(metal_dark, glow, t), 0.35)  # обух темнее
        _set(img, x, y, edge)
        _set(img, x, y - 1, spine)
        if x + 1 < SIZE:
            _set(img, x + 1, y, _mix(edge, glow, 0.35))  # самый кончик ярче
    # яркая вспышка на самом острие
    _set(img, 15, 0, _mix(glow, (255, 255, 255, 255), 0.4))

    # Цуба (гарда) на изломе — крест из 5 пикселей.
    _set(img, 5, 10, tsuba_a)
    _set(img, 6, 9, tsuba_a)
    _set(img, 4, 11, tsuba_b)
    _set(img, 6, 11, tsuba_b)
    _set(img, 4, 9, tsuba_b)

    # Рукоять снизу-слева с диагональной обмоткой.
    for i in range(0, 5):
        x = i
        y = SIZE - 1 - i
        _set(img, x, y, wrap if i % 2 == 0 else handle)
        if x + 1 < SIZE:
            _set(img, x + 1, y, handle)
    _set(img, 0, 15, (28, 20, 18, 255))  # навершие

    add_outline(img)
    return img


def order_spirit_katana() -> Image.Image:
    # тёмная сталь → нефритово-бирюзовое духовное свечение
    return _katana(metal_dark=(40, 58, 60, 255), glow=(90, 235, 200, 255))


def syndicate_plasma_blade() -> Image.Image:
    # тёмный графит → циан-плазменное свечение
    return _katana(metal_dark=(40, 48, 62, 255), glow=(90, 225, 245, 255))


ICONS = {
    "syndicate_core": syndicate_core,
    "order_samurai_soul": order_samurai_soul,
    "order_spirit_katana": order_spirit_katana,
    "syndicate_plasma_blade": syndicate_plasma_blade,
}


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, fn in ICONS.items():
        path = os.path.join(OUT_DIR, name + ".png")
        fn().save(path)
        print(f"[item-icon] {name}.png -> {os.path.relpath(path, ROOT)}")
    print(f"Готово: {len(ICONS)} иконок (outline + spherical shading + katana gradient). НИКАКИХ ВАНИЛЬНЫХ ТЕКСТУР.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
