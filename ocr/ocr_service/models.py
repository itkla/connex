import os
from pathlib import Path


MODEL_NAMES = (
    "PP-LCNet_x1_0_doc_ori",
    "PP-LCNet_x0_25_textline_ori",
    "PP-OCRv6_small_det",
    "PP-OCRv6_small_rec",
)

REQUIRED_MODEL_FILES = (
    "inference.json",
    "inference.pdiparams",
    "inference.yml",
)


def model_root() -> Path:
    return Path(os.environ.get(
        "CONNEX_OCR_MODEL_ROOT",
        str(Path.home() / ".paddlex" / "official_models"),
    ))


def model_paths() -> dict[str, Path]:
    root = model_root()
    return {
        "doc_orientation_classify_model_dir": root / MODEL_NAMES[0],
        "textline_orientation_model_dir": root / MODEL_NAMES[1],
        "text_detection_model_dir": root / MODEL_NAMES[2],
        "text_recognition_model_dir": root / MODEL_NAMES[3],
    }


def model_directory_ready(path: Path) -> bool:
    return path.is_dir() and all(
        (path / filename).is_file() and (path / filename).stat().st_size > 0
        for filename in REQUIRED_MODEL_FILES
    )


def models_ready(paths: dict[str, Path] | None = None) -> bool:
    selected_paths = model_paths() if paths is None else paths
    return all(model_directory_ready(path) for path in selected_paths.values())
