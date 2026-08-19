#!/usr/bin/env python3
"""
Play 콘솔 스토어 등록정보에 올릴 그래픽을 만든다.

  python3 tools/make_store_graphics.py

만들어지는 것
  store/icon-512.png                  앱 아이콘 (512x512, 알파 없음)
  store/feature-graphic-1024x500.png  기능 그래픽 (1024x500, 알파 없음)

스크린샷은 여기서 만들지 않는다. Play 정책상 스크린샷은 실제 앱 화면이어야
하므로 폰에서 직접 찍어야 한다.
"""
import os
import sys
import urllib.request

from PIL import Image, ImageDraw, ImageFont

TEAL = (46, 125, 111)
TEAL_DEEP = (26, 84, 74)
AMBER = (255, 224, 130)
WHITE = (255, 255, 255)
CREAM = (247, 243, 238)

SS = 4  # 계단 현상을 없애려고 4배로 그린 뒤 줄인다
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "store")
FONT_URL = (
    "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanskr/"
    "NotoSansKR%5Bwght%5D.ttf"
)
FONT_PATH = os.path.join(HERE, ".cache", "NotoSansKR.ttf")


def korean_font(size, weight=700):
    """한글이 나오는 폰트를 찾거나 받아온다. 실패하면 None."""
    if not os.path.exists(FONT_PATH):
        os.makedirs(os.path.dirname(FONT_PATH), exist_ok=True)
        try:
            urllib.request.urlretrieve(FONT_URL, FONT_PATH)
        except Exception as exc:  # 네트워크가 막힌 환경
            print(f"  폰트를 받지 못했습니다({exc}). 글자 없이 만듭니다.", file=sys.stderr)
            return None
    font = ImageFont.truetype(FONT_PATH, size)
    try:
        font.set_variation_by_axes([weight])
    except Exception:
        pass
    return font


def stroked_path(draw, points, color, width):
    """PIL 은 둥근 끝을 지원하지 않아 끝점에 원을 찍어 채운다."""
    draw.line(points, fill=color, width=width, joint="curve")
    r = width / 2
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def draw_mark(draw, cx, cy, size):
    """체크박스 + 체크 표시. size 는 체크박스 한 변의 길이."""
    left, top = cx - size / 2, cy - size / 2
    box_stroke = size * 0.094
    draw.rounded_rectangle(
        (left, top, left + size, top + size),
        radius=size * 0.16,
        outline=WHITE,
        width=int(round(box_stroke)),
    )
    # 앱 런처 아이콘과 같은 비율로 체크를 얹는다.
    pts = [
        (left + size * 0.119, top + size * 0.500),
        (left + size * 0.333, top + size * 0.714),
        (left + size * 0.881, top + size * 0.190),
    ]
    stroked_path(draw, pts, AMBER, int(round(size * 0.167)))


def make_icon():
    n = 512 * SS
    img = Image.new("RGB", (n, n), TEAL)
    d = ImageDraw.Draw(img)
    # 위에서 아래로 아주 옅은 명암만. 런처 아이콘의 단색 톤을 그대로 유지한다.
    for y in range(n):
        t = y / n
        d.line(
            [(0, y), (n, y)],
            fill=(
                int(TEAL[0] + (TEAL_DEEP[0] - TEAL[0]) * t),
                int(TEAL[1] + (TEAL_DEEP[1] - TEAL[1]) * t),
                int(TEAL[2] + (TEAL_DEEP[2] - TEAL[2]) * t),
            ),
        )
    draw_mark(d, n / 2, n / 2, n * 0.50)
    img = img.resize((512, 512), Image.LANCZOS)
    path = os.path.join(OUT, "icon-512.png")
    img.save(path, "PNG")
    print(f"  {path}  512x512")


def make_feature():
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), TEAL)
    d = ImageDraw.Draw(img)
    for x in range(w):
        t = x / w
        d.line(
            [(x, 0), (x, h)],
            fill=(
                int(TEAL_DEEP[0] + (TEAL[0] - TEAL_DEEP[0]) * t),
                int(TEAL_DEEP[1] + (TEAL[1] - TEAL_DEEP[1]) * t),
                int(TEAL_DEEP[2] + (TEAL[2] - TEAL_DEEP[2]) * t),
            ),
        )
    draw_mark(d, w * 0.20, h * 0.50, h * 0.46)

    title = korean_font(int(96 * SS), weight=700)
    sub = korean_font(int(44 * SS), weight=400)
    if title and sub:
        d.text((w * 0.36, h * 0.30), "헨니 체크", font=title, fill=WHITE, anchor="lm")
        d.text(
            (w * 0.36, h * 0.60),
            "오늘 할 일, 스스로 체크",
            font=sub,
            fill=CREAM,
            anchor="lm",
        )
        d.text(
            (w * 0.36, h * 0.75),
            "떨어져 있어도 같이 챙겨요",
            font=sub,
            fill=(206, 233, 225),
            anchor="lm",
        )

    img = img.resize((1024, 500), Image.LANCZOS)
    path = os.path.join(OUT, "feature-graphic-1024x500.png")
    img.save(path, "PNG")
    print(f"  {path}  1024x500")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    print("스토어 그래픽 생성:")
    make_icon()
    make_feature()
