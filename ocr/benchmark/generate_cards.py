import argparse
import hashlib
import json
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont


WIDTH = 1_200
HEIGHT = 700
CANONICAL_MANIFEST_SHA256 = "a641d9af0c946a03753606b88924fc9ac5ed0c58a2678ee9e960adc07fd84d87"
CANONICAL_FONT_SHA256 = "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5"
CANONICAL_FONT_REVISION = "f8d157532fbfaeda587e826d4cd5b21a49186f7c"
CANONICAL_FIXTURES_SHA256 = "bfff98a022ded013b42d2313f75c2ec6e5fc7632c1926adea6274ca0172899e5"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("generated"))
    arguments = parser.parse_args()
    manifest_path = Path(__file__).with_name("manifest.json")
    if file_sha256(manifest_path) != CANONICAL_MANIFEST_SHA256:
        raise ValueError("Canonical benchmark manifest hash does not match the reviewed suite")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) != 40:
        raise ValueError("Canonical benchmark manifest must contain exactly 40 cases")
    font_path = benchmark_font()
    if arguments.output.exists() and any(arguments.output.iterdir()):
        raise ValueError("Benchmark output directory must be empty")
    arguments.output.mkdir(parents=True, exist_ok=True)
    fixtures: list[dict[str, object]] = []
    for index, case in enumerate(cases):
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            raise ValueError("Every canonical benchmark case must have a string id")
        image = render(case, index, font_path)
        image_path = arguments.output / f"{case['id']}.jpg"
        image.save(image_path, format="JPEG", quality=92, optimize=True)
        fixtures.append({
            "id": case["id"],
            "sha256": file_sha256(image_path),
            "size": image_path.stat().st_size,
        })
    fixture_digest = hashlib.sha256()
    for fixture in sorted(fixtures, key=lambda item: str(item["id"])):
        case_id = str(fixture["id"])
        fixture_digest.update(case_id.encode("utf-8"))
        fixture_digest.update(b"\0")
        fixture_digest.update((arguments.output / f"{case_id}.jpg").read_bytes())
    if fixture_digest.hexdigest() != CANONICAL_FIXTURES_SHA256:
        raise ValueError("Generated benchmark fixtures do not match the reviewed canonical set")
    metadata = {
        "schemaVersion": 1,
        "manifestSha256": CANONICAL_MANIFEST_SHA256,
        "generatorSha256": file_sha256(Path(__file__)),
        "fontSha256": CANONICAL_FONT_SHA256,
        "fontSourceRevision": CANONICAL_FONT_REVISION,
        "fixturesSha256": CANONICAL_FIXTURES_SHA256,
        "cases": fixtures,
    }
    (arguments.output / "fixtures.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def render(case: dict[str, object], seed: int, font_path: Path) -> Image.Image:
    fields = case["fields"]
    assert isinstance(fields, dict)
    regular = font(font_path, 38)
    medium = font(font_path, 48)
    display = font(font_path, 72)
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


def benchmark_font() -> Path:
    candidate = Path(os.environ.get("CONNEX_BENCHMARK_FONT", ""))
    if not candidate.is_file():
        raise FileNotFoundError("CONNEX_BENCHMARK_FONT must identify the pinned benchmark font")
    if file_sha256(candidate) != CANONICAL_FONT_SHA256:
        raise ValueError("Benchmark font hash does not match the pinned font")
    return candidate


def font(path: Path, size: int):
    return ImageFont.truetype(path, size)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


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
