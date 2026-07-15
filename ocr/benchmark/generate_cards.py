import argparse
import json
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont


WIDTH = 1_200
HEIGHT = 700


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("manifest.json"))
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("generated"))
    arguments = parser.parse_args()
    manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
    arguments.output.mkdir(parents=True, exist_ok=True)
    for index, case in enumerate(manifest["cases"]):
        image = render(case, index)
        image.save(arguments.output / f"{case['id']}.jpg", format="JPEG", quality=92, optimize=True)


def render(case: dict[str, object], seed: int) -> Image.Image:
    fields = case["fields"]
    assert isinstance(fields, dict)
    language = str(case["language"])
    regular = font(language, 38)
    medium = font(language, 48)
    display = font(language, 72)
    image = Image.new("RGB", (WIDTH, HEIGHT), "#f7f4ec")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 24, HEIGHT), fill="#1e5448")
    draw.rectangle((WIDTH - 24, 0, WIDTH, HEIGHT), fill="#d6a64f")
    layout = str(case["layout"])
    if layout == "centered":
        centered(draw, fields, display, medium, regular)
    elif layout == "split":
        split(draw, fields, display, medium, regular)
    elif layout == "minimal":
        minimal(draw, fields, display, medium, regular)
    else:
        classic(draw, fields, display, medium, regular)
    return condition(image, str(case["condition"]), seed)


def classic(draw: ImageDraw.ImageDraw, fields: dict[str, object], display, medium, regular) -> None:
    draw.text((92, 76), str(fields["company"]), font=medium, fill="#1e5448")
    draw.text((92, 194), str(fields["name"]), font=display, fill="#17241f")
    draw.text((96, 300), str(fields["title"]), font=medium, fill="#4c5d56")
    contact(draw, fields, 96, 468, regular)


def centered(draw: ImageDraw.ImageDraw, fields: dict[str, object], display, medium, regular) -> None:
    center(draw, str(fields["company"]), 74, medium, "#1e5448")
    center(draw, str(fields["name"]), 215, display, "#17241f")
    center(draw, str(fields["title"]), 340, medium, "#4c5d56")
    center(draw, str(fields["email"]), 500, regular, "#17241f")
    center(draw, "TEL " + str(fields["phone"]), 558, regular, "#17241f")


def split(draw: ImageDraw.ImageDraw, fields: dict[str, object], display, medium, regular) -> None:
    draw.rectangle((58, 54, 520, 646), fill="#1e5448")
    draw.multiline_text((98, 120), str(fields["company"]), font=medium, fill="#f7f4ec", spacing=12)
    draw.text((584, 124), str(fields["name"]), font=display, fill="#17241f")
    draw.text((590, 256), str(fields["title"]), font=medium, fill="#4c5d56")
    contact(draw, fields, 590, 442, regular)


def minimal(draw: ImageDraw.ImageDraw, fields: dict[str, object], display, medium, regular) -> None:
    draw.text((84, 82), str(fields["name"]), font=display, fill="#17241f")
    draw.text((90, 202), str(fields["title"]), font=medium, fill="#4c5d56")
    draw.line((90, 312, 1_100, 312), fill="#d6a64f", width=4)
    draw.text((90, 374), str(fields["company"]), font=medium, fill="#1e5448")
    contact(draw, fields, 90, 522, regular)


def contact(draw: ImageDraw.ImageDraw, fields: dict[str, object], x: int, y: int, regular) -> None:
    draw.text((x, y), "EMAIL " + str(fields["email"]), font=regular, fill="#17241f")
    draw.text((x, y + 62), "TEL " + str(fields["phone"]), font=regular, fill="#17241f")


def center(draw: ImageDraw.ImageDraw, text: str, y: int, selected_font, color: str) -> None:
    box = draw.textbbox((0, 0), text, font=selected_font)
    width = box[2] - box[0]
    draw.text(((WIDTH - width) / 2, y), text, font=selected_font, fill=color)


def font(language: str, size: int):
    if language in {"ja", "mixed"}:
        candidate = os.environ.get("CONNEX_BENCHMARK_JA_FONT", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
    else:
        candidate = os.environ.get("CONNEX_BENCHMARK_EN_FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    if not Path(candidate).is_file():
        raise FileNotFoundError(f"Set the benchmark font environment variable; missing: {candidate}")
    return ImageFont.truetype(candidate, size)


def condition(image: Image.Image, selected: str, seed: int) -> Image.Image:
    randomizer = random.Random(seed)
    if selected == "glare":
        overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)
        offset = randomizer.randint(-100, 100)
        draw.polygon(((340 + offset, 0), (580 + offset, 0), (920 + offset, HEIGHT), (650 + offset, HEIGHT)), fill=(255, 255, 255, 118))
        return Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
    if selected == "rotation":
        angle = -5.5 if seed % 2 else 5.5
        rotated = image.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="#26302c")
        left = (rotated.width - WIDTH) // 2
        top = (rotated.height - HEIGHT) // 2
        return rotated.crop((left, top, left + WIDTH, top + HEIGHT))
    if selected == "perspective":
        return image.transform(
            image.size,
            Image.Transform.PERSPECTIVE,
            (1.0, 0.08, -24, 0.04, 1.0, -18, 0.00006, 0.00008),
            resample=Image.Resampling.BICUBIC,
            fillcolor="#26302c",
        )
    if selected == "low_light":
        dark = ImageEnhance.Brightness(image).enhance(0.46)
        pixels = dark.load()
        assert pixels is not None
        for y in range(0, HEIGHT, 2):
            for x in range(0, WIDTH, 2):
                red, green, blue = pixels[x, y]
                noise = randomizer.randint(-12, 12)
                pixels[x, y] = tuple(max(0, min(255, value + noise)) for value in (red, green, blue))
        return dark
    return image


if __name__ == "__main__":
    main()
