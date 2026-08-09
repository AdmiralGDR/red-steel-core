#!/usr/bin/env python3
"""
generate_humanoid_base.py — генерирует ИДЕАЛЬНЫЙ humanoid-rig (стандартный двуногий) для слота
`syndicate_trooper`, УБИВАЯ скачанного железного голема (у него чужой риг и нет наших анимаций).

Почему так: iron_golem.geo.json (авто-фетч) имел 20 чужих костей (armor*/item) и нестандартные пропорции —
для humanoid-анимаций (idle/walk/attack/shoot/draw_bow) нужен чистый двуногий риг с НАШИМИ костями
{root, body, head, right_arm, left_arm, right_leg, left_leg, right_item} (это и есть «bipedBody/bipedHead/
bipedRightArm/bipedLeftArm» в терминах ванильного игрока — имена выбраны под наш animations/humanoid.animation.json,
иначе GeckoLib бросит «missing bone»).

Скрипт детерминированно строит риг трупера из эталонного humanoid-рига проекта (order_samurai — выверенные
origin/size/UV под 128² текстуру), гарантируя точный маппинг на ВОССТАНАВЛИВАЕМУЮ оригинальную текстуру
трупера (UV-разметка обоих ригов идентична — один арт-пайплайн). Плюс достраивает кость `right_item` (кисть)
и математически генерирует анимацию `animation.archer.draw_bow` (обе руки вверх + доворот корпуса) в
humanoid.animation.json.

Run:  python3 scripts/generate_humanoid_base.py
"""
from __future__ import annotations

import copy
import json
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEO = os.path.join(ROOT, "src/main/resources/assets/redsteelcore/geo")
TEX = os.path.join(ROOT, "src/main/resources/assets/redsteelcore/textures/entity")
ANIM = os.path.join(ROOT, "src/main/resources/assets/redsteelcore/animations/humanoid.animation.json")

REF_GEO = os.path.join(GEO, "order_samurai.geo.json")          # эталонный humanoid-риг (выверенные UV/cubes)
TROOPER_GEO = os.path.join(GEO, "syndicate_trooper.geo.json")
TROOPER_TEX = os.path.join(TEX, "syndicate_trooper.png")

# Обязательные кости humanoid-анимаций (bipedBody/head/arms в наших именах).
REQUIRED = ["root", "body", "head", "right_arm", "left_arm", "right_leg", "left_leg"]


def build_humanoid_geo() -> dict:
    """Строит чистый двуногий риг трупера из эталонного humanoid-рига + кость-кисть right_item."""
    ref = json.load(open(REF_GEO, encoding="utf-8"))
    geo = ref["minecraft:geometry"][0]
    out_bones = []
    have = set()
    for b in geo["bones"]:
        nb = copy.deepcopy(b)
        out_bones.append(nb)
        have.add(nb["name"])
    # гарантируем наличие обязательных костей
    missing = [r for r in REQUIRED if r not in have]
    if missing:
        raise SystemExit(f"эталонный риг неполон, нет костей: {missing}")
    # кость-кисть для предмета (если эталон её не содержит)
    if "right_item" not in have:
        out_bones.append({"name": "right_item", "parent": "right_arm", "pivot": [-6, 11, -2]})

    desc = copy.deepcopy(geo["description"])
    desc["identifier"] = "geometry.syndicate_trooper"
    return {"format_version": ref.get("format_version", "1.12.0"),
            "minecraft:geometry": [{"description": desc, "bones": out_bones}]}


def draw_bow_animation() -> dict:
    """
    animation.archer.draw_bow — натяжение лука: ОБЕ руки поднимаются почти горизонтально вперёд,
    корпус доворачивается (твист) для позы стрельбы, кисть с луком слегка наклоняется. Держится
    на последнем кадре (hold_on_last_frame), пока моб целится.
    """
    return {
        "loop": "hold_on_last_frame",
        "animation_length": 1.0,
        "bones": {
            "right_arm": {"rotation": {"0.0": [0, 0, 0], "0.3": [-92, -8, -10], "1.0": [-92, -8, -10]}},
            "left_arm":  {"rotation": {"0.0": [0, 0, 0], "0.3": [-95, 10, 12], "1.0": [-95, 10, 12]}},
            "body":      {"rotation": {"0.0": [0, 0, 0], "0.3": [0, -18, 0], "1.0": [0, -18, 0]}},
            "right_item": {"rotation": {"0.0": [0, 0, 0], "0.3": [-18, 0, 0], "1.0": [-18, 0, 0]}}
        }
    }


def merge_draw_bow():
    d = json.load(open(ANIM, encoding="utf-8"))
    d["animations"]["animation.archer.draw_bow"] = draw_bow_animation()
    json.dump(d, open(ANIM, "w", encoding="utf-8"), indent=1)
    print(f"[anim] merged animation.archer.draw_bow → {os.path.relpath(ANIM, ROOT)} "
          f"({len(d['animations'])} анимаций)")


def kill_golem_restore_texture():
    """Восстанавливает оригинальную текстуру трупера из *.fetch_backup и удаляет бэкапы голема."""
    geo_bak = TROOPER_GEO + ".fetch_backup"
    tex_bak = TROOPER_TEX + ".fetch_backup"
    if os.path.exists(tex_bak):
        shutil.copy2(tex_bak, TROOPER_TEX)  # вернуть оригинальную humanoid-текстуру (UV совпадают с ригом)
        print(f"[texture] restored {os.path.relpath(TROOPER_TEX, ROOT)} from backup (golem tex removed)")
    for bak in (geo_bak, tex_bak):
        if os.path.exists(bak):
            os.remove(bak)
            print(f"[cleanup] removed {os.path.relpath(bak, ROOT)}")


def main() -> int:
    geo = build_humanoid_geo()
    json.dump(geo, open(TROOPER_GEO, "w", encoding="utf-8"), indent=1)
    cubes = sum(len(b.get("cubes", [])) for b in geo["minecraft:geometry"][0]["bones"])
    bones = [b["name"] for b in geo["minecraft:geometry"][0]["bones"]]
    print(f"[geo] wrote {os.path.relpath(TROOPER_GEO, ROOT)} — {cubes} cubes, bones={bones}")
    kill_golem_restore_texture()
    merge_draw_bow()
    print("Готово: голем удалён, humanoid-риг трупера сгенерирован, draw_bow добавлена.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
