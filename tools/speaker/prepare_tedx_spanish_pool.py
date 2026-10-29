#!/usr/bin/env python3
"""Prepara un pool de voz espontánea en español (TEDx) para ADR-0048.

Herramienta de **desarrollo**. Lee `tedx_spanish_corpus.tgz` (OpenSLR SLR67,
24 h de charlas TEDx en español, WAV mono 16 kHz) y escribe el layout plano que
consume `RealSpeechAugmentationExportTest`:

    <out>/<locutor>/<orden>.wav

Por qué TEDx y no un corpus leído: en VoxForge (y en casi todo corpus de lectura)
todos los locutores recitan las mismas frases, así que dos personas distintas se
parecen por el texto y los trials de un modelo de hablante miden la frase, no la
persona. TEDx es habla espontánea con contenido distinto por persona, y reparte
los clips a lo largo de la charla (principio, medio y final) para no comparar dos
tomas del mismo párrafo.

Ventaja adicional: son micrófonos y salas distintas por charla, así que el
dominio es más variado que un estudio. Sigue siendo audio de conferencia, no una
llamada de móvil en la calle.

Uso::

    python tools/speaker/prepare_tedx_spanish_pool.py `
      --archive "$env:TEMP\\fifo-speaker-eval\\tedx-es\\tedx_spanish_corpus.tgz" `
      --out "$env:TEMP\\fifo-speaker-eval\\real-pool-es-tedx" `
      --speakers 40 --clips-per-speaker 7

El archivo se lee en streaming dos veces (índice y extracción), sin descomprimir
todo en disco. No se commitea ningún WAV.
"""

from __future__ import annotations

import argparse
import io
import random
import sys
import tarfile
import wave
from pathlib import Path

TARGET_RATE = 16_000


def speaker_of(name: str) -> str | None:
    base = name.split("/")[-1]
    if not base.endswith(".wav"):
        return None
    parts = base.split("_")
    if len(parts) < 4:
        return None
    return "_".join(parts[:3])


def gender_of(speaker: str) -> str:
    parts = speaker.split("_")
    return {"M": "male", "F": "female"}.get(parts[1] if len(parts) > 1 else "", "unknown")


def index(archive: Path) -> dict[str, list[tuple[str, float]]]:
    speakers: dict[str, list[tuple[str, float]]] = {}
    with tarfile.open(archive) as tar:
        for member in tar:
            speaker = speaker_of(member.name)
            if speaker is None:
                continue
            handle = tar.extractfile(member)
            if handle is None:
                continue
            header = handle.read(64)
            try:
                with wave.open(io.BytesIO(header)) as wav:
                    if wav.getframerate() != TARGET_RATE or wav.getnchannels() != 1:
                        continue
                    seconds = wav.getnframes() / TARGET_RATE
            except Exception:  # un WAV roto no debe abortar el índice
                continue
            speakers.setdefault(speaker, []).append((member.name, seconds))
    return speakers


def pick_clips(clips: list[tuple[str, float]], count: int) -> list[str]:
    """Reparte los clips a lo largo de la charla: principio, medio y final."""
    ordered = [name for name, _ in sorted(clips)]
    if len(ordered) <= count:
        return ordered
    chosen: list[str] = []
    for position in range(count):
        index = round(position * (len(ordered) - 1) / (count - 1))
        chosen.append(ordered[index])
    return chosen


def select_speakers(speakers: dict[str, list[tuple[str, float]]], wanted: int, clips: int,
                    min_seconds: float, seed: int) -> list[str]:
    usable = [
        speaker
        for speaker, entries in speakers.items()
        if len([seconds for _, seconds in entries if seconds >= min_seconds]) >= clips
    ]
    if len(usable) < 8:
        raise SystemExit(f"solo hay {len(usable)} locutores utilizables; se necesitan al menos 8")

    rng = random.Random(seed)
    by_gender: dict[str, list[str]] = {}
    for speaker in sorted(usable):
        by_gender.setdefault(gender_of(speaker), []).append(speaker)
    for members in by_gender.values():
        rng.shuffle(members)

    half = wanted // 2
    chosen: list[str] = []
    for gender in ("female", "male"):
        chosen.extend(by_gender.get(gender, [])[:half])
    leftovers = [speaker for speaker in sorted(usable) if speaker not in chosen]
    rng.shuffle(leftovers)
    chosen.extend(leftovers[: max(0, wanted - len(chosen))])
    if len(chosen) < 8:
        raise SystemExit(f"solo se pudieron seleccionar {len(chosen)} locutores")
    return sorted(chosen[:wanted])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--archive", required=True, type=Path, help="tedx_spanish_corpus.tgz")
    parser.add_argument("--out", required=True, type=Path, help="carpeta del pool")
    parser.add_argument("--speakers", type=int, default=40, help="número de locutores a exportar")
    parser.add_argument("--clips-per-speaker", type=int, default=7, help="clips por locutor (3 enroll + 4 test)")
    parser.add_argument("--min-clip-seconds", type=float, default=3.0, help="duración mínima de un clip")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    if args.clips_per_speaker < 7:
        raise SystemExit("--clips-per-speaker debe ser >= 7 (3 de enrolamiento + 4 de uso)")
    if not args.archive.is_file():
        raise SystemExit(f"no existe el archivo: {args.archive}")

    print("indexando el archivo (primera pasada)…", file=sys.stderr)
    speakers = index(args.archive)
    selected = select_speakers(speakers, args.speakers, args.clips_per_speaker, args.min_clip_seconds, args.seed)
    wanted = {speaker: pick_clips(speakers[speaker], args.clips_per_speaker) for speaker in selected}

    wanted_names = {name: (speaker, position) for speaker, names in wanted.items() for position, name in enumerate(names)}
    args.out.mkdir(parents=True, exist_ok=True)
    written = 0
    seconds = 0.0
    print("extrayendo clips (segunda pasada)…", file=sys.stderr)
    with tarfile.open(args.archive) as tar:
        for member in tar:
            target = wanted_names.get(member.name)
            if target is None:
                continue
            handle = tar.extractfile(member)
            if handle is None:
                continue
            with wave.open(io.BytesIO(handle.read())) as source:
                frames = source.readframes(source.getnframes())
                rate = source.getframerate()
            speaker, position = target
            out = args.out / speaker / f"{position:02d}.wav"
            out.parent.mkdir(parents=True, exist_ok=True)
            with wave.open(str(out), "wb") as sink:
                sink.setnchannels(1)
                sink.setsampwidth(2)
                sink.setframerate(rate)
                sink.writeframes(frames)
            written += 1
            seconds += len(frames) / 2 / rate

    genders: dict[str, int] = {}
    for speaker in selected:
        genders[gender_of(speaker)] = genders.get(gender_of(speaker), 0) + 1
    print(f"pool en {args.out}: {written} WAV de {len(selected)} locutores, "
          f"{seconds / 60:.1f} min, género {genders}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
