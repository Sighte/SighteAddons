# -*- coding: utf-8 -*-
"""Zeichnet nummerierte Callouts auf die Run-Screenshots.

Pink  = Sighte Addons.
Cyan  = gehoert einer anderen Mod, steht nur zum Vergleich mit im Bild.
"""
import os
from PIL import Image, ImageDraw, ImageFont

SRC = r"C:/Users/marvi/Documents/SighteAddonHarnees/sighteaddons-harness/ModScreenshots"
OUT = r"C:/Users/marvi/Documents/SighteAddonHarnees/sighteaddons-harness/SighteAddonMOD/docs/features/img"
os.makedirs(OUT, exist_ok=True)

PINK = (255, 45, 120)
CYAN = (34, 211, 238)
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)

BADGE_R = 23
BOX_W = 4
FONT_BADGE = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 30)
FONT_TAG = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 24)


def d(v):
    """Displayed-2000px-Koordinate -> Original 2560px."""
    return int(round(v * 1.28))


def box(draw, x0, y0, x1, y1, color, width=BOX_W, radius=10):
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, outline=color, width=width)


def badge(draw, n, cx, cy, color=PINK):
    draw.ellipse([cx - BADGE_R, cy - BADGE_R, cx + BADGE_R, cy + BADGE_R],
                 fill=color, outline=WHITE, width=3)
    t = str(n)
    bb = draw.textbbox((0, 0), t, font=FONT_BADGE)
    draw.text((cx - (bb[2] - bb[0]) / 2 - bb[0], cy - (bb[3] - bb[1]) / 2 - bb[1]),
              t, font=FONT_BADGE, fill=WHITE)


def tag(draw, text, x, y, color=CYAN):
    bb = draw.textbbox((0, 0), text, font=FONT_TAG)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    draw.rounded_rectangle([x - 8, y - 6, x + w + 10, y + h + 12], radius=8, fill=BLACK)
    draw.rounded_rectangle([x - 8, y - 6, x + w + 10, y + h + 12], radius=8, outline=color, width=2)
    draw.text((x, y - bb[1] + 3), text, font=FONT_TAG, fill=color)


def place(draw, n, rect, side, color=PINK):
    x0, y0, x1, y1 = rect
    box(draw, x0, y0, x1, y1, color)
    if side == "l":
        badge(draw, n, x0 - BADGE_R - 12, (y0 + y1) // 2, color)
    elif side == "r":
        badge(draw, n, x1 + BADGE_R + 12, (y0 + y1) // 2, color)
    elif side == "t":
        badge(draw, n, (x0 + x1) // 2, y0 - BADGE_R - 10, color)
    elif side == "b":
        badge(draw, n, (x0 + x1) // 2, y1 + BADGE_R + 10, color)
    elif side == "tl":
        badge(draw, n, x0, y0, color)


def render(name, calls, tags=(), free=(), scale=1600):
    im = Image.open(os.path.join(SRC, name)).convert("RGB")
    dr = ImageDraw.Draw(im)
    for c in calls:
        if c[0] is None:
            box(dr, *c[1], PINK)
            continue
        place(dr, c[0], c[1], c[2], c[3] if len(c) > 3 else PINK)
    for f in free:
        global BADGE_R
        old, BADGE_R = BADGE_R, f[3]
        badge(dr, f[0], f[1], f[2])
        BADGE_R = old
    for t in tags:
        tag(dr, t[0], t[1], t[2], t[3] if len(t) > 3 else CYAN)
    if scale:
        im = im.resize((scale, int(im.height * scale / im.width)), Image.LANCZOS)
    out = os.path.join(OUT, os.path.splitext(name)[0].replace("2026-08-18_", "") + ".png")
    im.save(out, optimize=True)
    print(out, os.path.getsize(out) // 1024, "KB")


# ---------------------------------------------------------------- 1  HUD-Karte
render("2026-08-18_17.48.34.png", [
    (1, (1576, 144, 1770, 187), "l"),        # Raumname
    (2, (1918, 142, 2118, 202), "r"),        # Raumuhr
    (3, (1576, 188, 1750, 222), "l"),        # Split gegen den Rekord
    (4, (1576, 226, 2118, 284), "l"),        # Secret-Balken + Zaehler + Secret-Run
    (5, (1576, 294, 2118, 338), "l"),        # zuletzt geraeumte Raeume
    (6, (1576, 352, 2118, 392), "l"),        # Run-Summe
    (7, (1576, 392, 2118, 424), "l"),        # Standings
    (8, (1576, 424, 2118, 462), "l"),        # idle / nav
])

# ---------------------------------------------------------- 2  Clear-Popup
render("2026-08-18_17.48.44.png", [
    (1, (872, 540, 1682, 618), "l"),         # Popup
    (2, (5, 1232, 560, 1268), "r"),          # SA-Chatzeile
    (3, (1576, 290, 2118, 360), "l"),        # History: zwei Raeume dahinter
], tags=[
    ("andere Mod", 1150, 706),               # vanilla title "Cleared"
])

# ------------------------------------------------------- 3  Popup mit Rekord
render("2026-08-18_17.48.56.png", [
    (1, (772, 532, 1786, 620), "l"),         # PB-Popup
    (2, (795, 545, 845, 605), "t"),          # Chevron
    (3, (5, 1230, 1040, 1268), "r"),         # SA-Chatzeile mit PB-Delta
], tags=[
    ("andere Mod", 1030, 710),               # "Secrets Done!"
])

# ------------------------------------------------- 4  Einstellungen: HUD-Tab
render("2026-08-18_17.50.54.png", [
    (1, (d(330), d(150), d(455), d(425)), "r"),      # Tabs
    (2, (d(580), d(208), d(1690), d(252)), "l"),     # show HUD
    (3, (d(580), d(258), d(1690), d(300)), "l"),     # position / move
    (4, (d(580), d(345), d(1690), d(425)), "l"),     # backdrop + scrim
    (5, (d(580), d(500), d(1690), d(630)), "l"),     # Zeilen auf der Karte
    (6, (d(580), d(675), d(1690), d(760)), "l"),     # Run-Totals-Panel
    (7, (d(580), d(790), d(1690), d(830)), "l"),     # expand key
    (8, (d(580), d(860), d(1690), d(975)), "l"),     # idle & nav, standings
    (9, (d(1480), d(60), d(1690), d(102)), "l"),     # Version
])

# ----------------------------------------------- 5  Einstellungen: rooms-Tab
render("2026-08-18_17.51.37.png", [
    (1, (d(1340), d(60), d(1690), d(102)), "l"),     # Kopfzahlen
    (2, (d(585), d(148), d(1465), d(194)), "b"),     # Typ-Chips
    (3, (d(585), d(200), d(1690), d(232)), "r"),     # Spaltenkopf
    (4, (d(585), d(245), d(1690), d(286)), "r"),     # eine Zeile
    (5, (d(572), d(668), d(1690), d(710)), "r"),     # markierte Zeile
    (6, (d(585), d(1052), d(1100), d(1092)), "r"),   # Fusszeile
])

# ------------------------------------------------------- 6  UI-Gallery
render("2026-08-18_17.52.24.png", [
    (1, (d(50), d(38), d(215), d(72)), "r"),         # Titel
    (2, (d(50), d(86), d(975), d(122)), "b"),        # Seiten
    (3, (d(50), d(162), d(950), d(600)), "r"),       # scripted timeline
    (4, (d(995), d(162), d(1930), d(715)), "r"),     # Storm, vier Stufen
    (5, (d(50), d(742), d(950), d(1042)), "l"),      # Popup normal
    (6, (d(970), d(742), d(1935), d(1042)), "r"),    # Popup PB
    (7, (d(50), d(1068), d(980), d(1104)), "t"),     # Tasten
])

# ------------------------------------------------ 7  Party-Run: Standings
render("2026-08-18_18.00.53.png", [
    (1, (d(1232), d(118), d(1660), d(178)), "l"),    # Raum + Uhr
    (2, (d(1232), 214, d(1660), d(228)), "l"),    # Secret-Balken 1/6
    (3, (d(1232), d(232), d(1660), 385), "l"),    # drei Raeume dahinter
    (4, (d(1232), d(332), d(1660), d(362)), "l"),    # Run-Summe M4
    (5, (d(1232), d(362), d(1660), d(500)), "l"),    # Standings mit ~
    (6, (d(1232), d(500), d(1660), d(532)), "l"),    # idle / nav
], tags=[
    ("andere Mod", d(360), d(300)),                  # Odin-Map
    ("andere Mod", d(8), d(398)),                    # Odin-Splits
])

# -------------------------------------------- 8  Run-Ende: Zusammenfassung
render("2026-08-18_18.04.46.png", [
    (None, (5, 962, 1010, 1220)),                    # der ganze SA-Block
], tags=[
    ("andere Mod", 600, 788),                         # Odin-Zeilen
], free=[
    (1, 1062, 1030, 20),                             # Standings-Zeilen
    (2, 1062, 1117, 17),                             # neue Rekorde
    (3, 1062, 1160, 17),                             # secrets per Hypixel
    (4, 1062, 1203, 17),                             # tracker-Audit
])
