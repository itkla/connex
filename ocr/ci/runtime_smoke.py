import io
import json
import os
import urllib.request

from PIL import Image, ImageDraw, ImageFont


def main() -> None:
    token = os.environ["CONNEX_OCR_SERVICE_TOKEN"]
    image = Image.new("RGB", (1_600, 900), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=64)
    draw.text((100, 100), "Ada Lovelace", fill="black", font=font)
    draw.text((100, 240), "Analytical Engines", fill="black", font=font)
    draw.text((100, 380), "ada@example.test", fill="black", font=font)
    content = io.BytesIO()
    image.save(content, format="JPEG", quality=92)
    request = urllib.request.Request(
        "http://127.0.0.1:8090/v1/ocr",
        data=content.getvalue(),
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "image/jpeg",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.load(response)
        if response.status != 200:
            raise RuntimeError(f"OCR smoke returned status {response.status}")
    lines = payload.get("lines")
    if not isinstance(lines, list) or not lines:
        raise RuntimeError("OCR smoke returned no recognized lines")
    if not all(isinstance(line, dict) and isinstance(line.get("text"), str) for line in lines):
        raise RuntimeError("OCR smoke returned an invalid line contract")


if __name__ == "__main__":
    main()
