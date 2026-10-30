#!/usr/bin/env python3
"""Aplica un códec telefónico real a un corpus exportado (ADR-0048).

Herramienta de **desarrollo**. No forma parte de la app y no se ejecuta en la
suite normal. Toma un corpus con `manifest.jsonl` (por ejemplo el campo simulado
en español), pasa cada WAV por un códec de telefonía y escribe otro corpus con
los mismos nombres y el mismo manifiesto, para que `extract_embeddings_sherpa.py`
y `score_embeddings.py` lo lean sin cambios.

El códec es transporte, no acústica: por eso vive aquí y no en
`TelephonyChannel`, que se ocupa de banda, coloración y AGC dentro del arnés.

Códecs medidos:

- `amrwb`: VoLTE, 16 kHz, 23,85 kbit/s (`libvo_amrwbenc`).
- `amrnb`: GSM, 8 kHz, 12,2 kbit/s (`libopencore_amrnb`).
- `opus`: VoIP, 16 kHz, 24 kbit/s (`libopus`).

Siempre se decodifica de vuelta a WAV mono 16 kHz PCM16, porque el manifiesto
declara 16 kHz y las herramientas de extracción lo exigen.

Uso::

    python tools/speaker/apply_telephony_codec.py `
      --corpus "$env:TEMP\\fifo-speaker-eval\\real-field-corpus-es-tedx-channel" `
      --out "$env:TEMP\\fifo-speaker-eval\\real-field-corpus-es-tedx-channel-amrwb" `
      --codec amrwb

No se commitea ningún WAV ni el corpus resultante.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TARGET_RATE = 16_000

CODECS = {
    "amrwb": {
        "rate": 16_000,
        "encoder": "libvo_amrwbenc",
        "bitrate": "23.85k",
        "container": "amr",
        "note": "VoLTE, 16 kHz, AMR-WB 23,85 kbit/s",
    },
    "amrnb": {
        "rate": 8_000,
        "encoder": "libopencore_amrnb",
        "bitrate": "12.2k",
        "container": "amr",
        "note": "GSM, 8 kHz, AMR-NB 12,2 kbit/s",
    },
    "opus": {
        "rate": 16_000,
        "encoder": "libopus",
        "bitrate": "24k",
        "container": "ogg",
        "note": "VoIP, 16 kHz, Opus 24 kbit/s",
    },
}


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(
            "ffmpeg falló:\n  " + " ".join(command) + "\n" + result.stderr.strip()
        )


def transcode(
    source: Path,
    target: Path,
    codec: dict[str, object],
    work: Path,
    ffmpeg: str,
) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    container = work / f"{target.stem}.{codec['container']}"
    run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-ac",
            "1",
            "-ar",
            str(codec["rate"]),
            "-c:a",
            str(codec["encoder"]),
            "-b:a",
            str(codec["bitrate"]),
            str(container),
        ]
    )
    run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(container),
            "-ac",
            "1",
            "-ar",
            str(TARGET_RATE),
            "-c:a",
            "pcm_s16le",
            str(target),
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, type=Path, help="corpus con manifest.jsonl")
    parser.add_argument("--out", required=True, type=Path, help="corpus de salida")
    parser.add_argument("--codec", required=True, choices=sorted(CODECS))
    parser.add_argument("--bitrate", default=None, help="sobrescribe el bitrate del códec")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="no re-codifica un WAV que ya existe en la salida",
    )
    args = parser.parse_args()

    corpus = args.corpus.resolve()
    out = args.out.resolve()
    manifest = corpus / "manifest.jsonl"
    if not manifest.is_file():
        raise SystemExit(f"falta el manifiesto: {manifest}")
    if out == corpus:
        raise SystemExit("la salida no puede ser el mismo corpus")
    out.mkdir(parents=True, exist_ok=True)

    codec = dict(CODECS[args.codec])
    if args.bitrate:
        codec["bitrate"] = args.bitrate

    sources = sorted(path for path in corpus.rglob("*.wav") if path.is_file())
    if not sources:
        raise SystemExit(f"no hay WAV en {corpus}")

    work = Path(tempfile.mkdtemp(prefix="codec-", dir=str(out)))
    done = 0
    try:
        def job(source: Path) -> None:
            target = out / source.relative_to(corpus)
            if args.skip_existing and target.is_file():
                return
            transcode(source, target, codec, work / source.stem, args.ffmpeg)

        with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            futures = [pool.submit(job, source) for source in sources]
            for future in futures:
                future.result()
                done += 1
                if done % 100 == 0:
                    print(f"  {done}/{len(sources)}", file=sys.stderr)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    shutil.copyfile(manifest, out / "manifest.jsonl")
    report = {
        "source": str(corpus),
        "codec": args.codec,
        "note": codec["note"],
        "bitrate": codec["bitrate"],
        "input_rate": codec["rate"],
        "output_rate": TARGET_RATE,
        "files": len(sources),
    }
    (out / "codec.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(
        f"corpus {args.codec} en {out}: {len(sources)} WAV, "
        f"{codec['note']}, salida {TARGET_RATE} Hz PCM16"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
