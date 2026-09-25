"""从 docs/icon/raw.png 生成全套启动图标并写入 app/src/main/res。

用法（需 Pillow，可临时注入：uv run --with pillow python scripts/gen_launcher_icons.py）：
- ic_launcher.png            传统方图标，mdpi 48 起每档 ×1.5
- ic_launcher_foreground.png 108dp 画布前景，原图铺满
- ic_launcher_background.png 108dp 画布背景，原图高斯模糊兜底
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageFilter

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "docs/icon/raw.png"
RES = REPO / "app/src/main/res"

# 密度 -> (传统图标 px, 108dp 画布 px)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def main() -> None:
    src = Image.open(SRC).convert("RGB")
    for density, (launcher, canvas) in DENSITIES.items():
        out = RES / f"mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        src.resize((launcher, launcher), Image.LANCZOS).save(out / "ic_launcher.png")
        src.resize((canvas, canvas), Image.LANCZOS).save(out / "ic_launcher_foreground.png")
        blur = src.resize((canvas, canvas), Image.LANCZOS).filter(
            ImageFilter.GaussianBlur(canvas / 12)
        )
        blur.save(out / "ic_launcher_background.png")
        print(f"mipmap-{density}: launcher={launcher}, canvas={canvas}")


if __name__ == "__main__":
    main()
