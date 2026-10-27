#!/usr/bin/env python3
"""Prepara un pool de voces humanas reales (LibriSpeech) para ADR-0048.

Herramienta de **desarrollo**. Convierte los FLAC de LibriSpeech a WAV mono a
16 kHz PCM16, con un layout plano que lee `RealSpeechAugmentationExportTest`:

    <out>/<locutor>/<orden>.wav

No aumenta el audio ni escribe el manifiesto: eso lo hace el test Kotlin, que
reutiliza `RoomAcoustics`, `SimulatedNoise` y `LombardEffect` del arnés. Así el
campo simulado y el corpus sintético comparten exactamente la misma acústica.

Uso::

    python tools/speaker/prepare_librispeech_pool.py `
      --librispeech-root "$env:TEMP\\fifo-speaker-eval\\librispeech\\LibriSpeech\\dev-clean" `
      --out "$env:TEMP\\fifo-speaker-eval\\real-pool" `
      --speakers 40 --clips-per-speaker 7

LibriSpeech es lectura limpia; la simulación añade sala, ruido y esfuerzo, pero
no sustituye a grabar con un móvil en una calle real.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--librispeech-root", required=True, type=Path, help=".../LibriSpeech/dev-clean")
    parser.add_argument("--out", required=True, type=Path, help="carpeta del pool")
    parser.add_argument("--speakers", type=int, default=40, help="número máximo de locutores")
    parser.add_argument("--clips-per-speaker", type=int, default=7, help="clips por locutor (3 enroll + 4 test)")
    args = parser.parse_args()

    if args.clips_per_speaker < 7:
        raise SystemExit("--clips-per-speaker debe ser >= 7 (3 de enrolamiento + 4 de uso)")
    if not args.librispeech_root.is_dir():
        raise SystemExit(f"no existe LibriSpeech: {args.librispeech_root}")

    try:
        import soundfile as sf
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "falta soundfile: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    speakers = sorted(
        directory
        for directory in args.librispeech_root.iterdir()
        if directory.is_dir() and len(list(directory.rglob("*.flac"))) >= args.clips_per_speaker
    )[: args.speakers]
    if len(speakers) < 8:
        raise SystemExit(f"solo hay {len(speakers)} locutores utilizables; se necesitan al menos 8")

    args.out.mkdir(parents=True, exist_ok=True)
    written = 0
    for speaker in speakers:
        files = sorted(speaker.rglob("*.flac"))[: args.clips_per_speaker]
        target_dir = args.out / speaker.name
        target_dir.mkdir(parents=True, exist_ok=True)
        for index, source in enumerate(files):
            audio, rate = sf.read(source, dtype="int16", always_2d=False)
            if audio.ndim != 1:
                audio = audio[:, 0]
            if rate != 16_000:
                raise SystemExit(f"{source}: se esperaban 16 kHz, hay {rate} Hz")
            sf.write(target_dir / f"{index:02d}.wav", audio, rate, subtype="PCM_16")
            written += 1
        print(f"{speaker.name}: {len(files)} clips", file=sys.stderr)

    print(f"pool en {args.out}: {written} WAV de {len(speakers)} locutores")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
