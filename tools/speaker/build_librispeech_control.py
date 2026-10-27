#!/usr/bin/env python3
"""Construye un control con voz real (LibriSpeech dev-clean) para ADR-0048.

Herramienta de **desarrollo**. No descarga ni commitea audio: espera el corpus
LibriSpeech ya extraído en disco y escribe WAV mono 16 kHz PCM16 + el mismo
``manifest.jsonl`` que consume ``extract_embeddings_sherpa.py``.

Sirve para separar dos preguntas:

1. ¿El pipeline de embeddings/manifiesto/scoring funciona? → este control.
2. ¿El corpus sintético representa voz humana? → no; ver ADR-0048.

Diseño: 10 locutores disjuntos, 4 de calibración (2 enrolados + 2 no vistos) y
6 de evaluación (4 enrolados + 2 no vistos). Cada enrolado aporta 3 frases de
enrolamiento y 6 de prueba; cada no visto, 6 de prueba. Todo en limpio.

```powershell
python tools/speaker/build_librispeech_control.py `
  --librispeech-root "$env:TEMP\\fifo-speaker-eval\\librispeech\\LibriSpeech\\dev-clean" `
  --out "$env:TEMP\\fifo-speaker-eval\\librispeech-corpus"
```

LibriSpeech es lectura limpia, no campo: el control demuestra que el pipeline no
está roto, no que un modelo sirva en un móvil.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

CAL_ENROLLED = ("1272", "1462")
CAL_UNSEEN = ("1673", "1919")
EVAL_ENROLLED = ("1988", "2035", "2078", "2086")
EVAL_UNSEEN = ("2277", "2412")


def clips(root: Path, speaker: str) -> list[Path]:
    files = sorted(root.joinpath(speaker).rglob("*.flac"))
    if len(files) < 9:
        raise SystemExit(f"{speaker}: se esperaban al menos 9 clips, hay {len(files)}")
    return files[:9]


def write_clip(
    root: Path,
    out: Path,
    speaker: str,
    index: int,
    source: Path,
    split: str,
    role: str,
) -> dict:
    import soundfile as sf

    audio, rate = sf.read(source, dtype="int16", always_2d=False)
    if audio.ndim != 1:
        audio = audio[:, 0]
    if rate != 16_000:
        raise SystemExit(f"{source}: se esperaban 16 kHz, hay {rate} Hz")
    relative = f"{split}/ls-{speaker}-{index}.wav"
    target = out / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    sf.write(target, audio, rate, subtype="PCM_16")
    return {
        "id": f"ls-{speaker}-{index}",
        "audio": relative,
        "split": split,
        "speaker": f"ls-{speaker}",
        "role": role,
        "enrolled": role == "enrolled",
        "condition": "clean",
        "snr_db": None,
        "noise": "white",
        "room": None,
        "lombard": False,
        "syllables": 0,
        "duration_ms": round(len(audio) * 1000 / rate),
        "sample_rate": rate,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--librispeech-root", required=True, type=Path, help=".../LibriSpeech/dev-clean")
    parser.add_argument("--out", required=True, type=Path, help="directorio del control")
    args = parser.parse_args()

    if not args.librispeech_root.is_dir():
        raise SystemExit(f"no existe LibriSpeech dev-clean: {args.librispeech_root}")
    args.out.mkdir(parents=True, exist_ok=True)

    rows: list[dict] = []
    for speaker in CAL_ENROLLED + EVAL_ENROLLED:
        split = "calibration" if speaker in CAL_ENROLLED else "evaluation"
        files = clips(args.librispeech_root, speaker)
        for index, source in enumerate(files[:3]):
            rows.append(write_clip(args.librispeech_root, args.out, speaker, index, source, "enroll", "enrolled"))
        for index, source in enumerate(files[3:9], start=3):
            rows.append(write_clip(args.librispeech_root, args.out, speaker, index, source, split, "enrolled"))
    for speaker in CAL_UNSEEN + EVAL_UNSEEN:
        split = "calibration" if speaker in CAL_UNSEEN else "evaluation"
        for index, source in enumerate(clips(args.librispeech_root, speaker)[:6]):
            rows.append(write_clip(args.librispeech_root, args.out, speaker, index, source, split, "unseen"))

    manifest = args.out / "manifest.jsonl"
    manifest.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )
    durations = [row["duration_ms"] for row in rows]
    by_split: dict[str, int] = {}
    for row in rows:
        by_split[row["split"]] = by_split.get(row["split"], 0) + 1
    print(
        f"{len(rows)} clips de {len({row['speaker'] for row in rows})} locutores "
        f"({', '.join(f'{key}={value}' for key, value in sorted(by_split.items()))}), "
        f"duración {min(durations)}–{max(durations)} ms"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
