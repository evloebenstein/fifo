#!/usr/bin/env python3
"""Construye el manifiesto de un corpus de campo para ADR-0048.

Herramienta de **desarrollo**. No graba, no descarga y no commitea audio: recibe
una carpeta con las grabaciones y un plan de locutores, valida el formato y
escribe el mismo ``manifest.jsonl`` que consumen ``extract_embeddings_sherpa.py``
y ``score_embeddings.py``.

Layout esperado (una carpeta por locutor, sin ambigüedad)::

    <root>/<locutor>/enroll/<clip>.wav
    <root>/<locutor>/test/<condición>/<clip>.wav

``enroll`` contiene las frases de enrolamiento en limpio. ``test`` contiene las
frases de uso, una carpeta por condición (``clean``, ``tv``, ``street``,
``far``…). Todo debe ser WAV mono a 16 kHz PCM16; si tu grabadora produce otro
formato, conviértelo antes::

    ffmpeg -i entrada.m4a -ac 1 -ar 16000 -c:a pcm_s16le salida.wav

El plan reparte locutores **disjuntos** entre calibración y evaluación. Nunca
calibres y reportes con las mismas voces::

    {
      "speakers": [
        {"id": "ana", "split": "calibration", "role": "enrolled"},
        {"id": "bea", "split": "calibration", "role": "unseen"},
        {"id": "carla", "split": "evaluation", "role": "enrolled"},
        {"id": "diego", "split": "evaluation", "role": "unseen"}
      ]
    }

Uso::

    python tools/speaker/build_field_manifest.py `
      --root "$env:TEMP\\fifo-speaker-field" `
      --plan "$env:TEMP\\fifo-speaker-field\\plan.json" `
      --out "$env:TEMP\\fifo-speaker-field\\manifest.jsonl"

El script avisa si el corpus se aleja del protocolo (10–15 locutores, al menos
3 frases de enrolamiento por locutor, condiciones de uso variadas), pero no
inventa datos ni rellena huecos.
"""

from __future__ import annotations

import argparse
import json
import sys
import wave
from pathlib import Path

SPLITS = ("calibration", "evaluation")
ROLES = ("enrolled", "unseen")
PROTOCOL_SPEAKERS = (10, 15)
MIN_ENROLL_CLIPS = 3
MIN_CLIP_MS = 300


def read_plan(path: Path) -> list[dict]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        raise SystemExit(f"no existe el plan: {path}") from None
    except json.JSONDecodeError as error:
        raise SystemExit(f"{path}: JSON inválido ({error})") from error

    speakers = payload.get("speakers") if isinstance(payload, dict) else None
    if not isinstance(speakers, list) or not speakers:
        raise SystemExit(f"{path}: se esperaba {{'speakers': [...]}}")

    seen: set[str] = set()
    plan = []
    for index, entry in enumerate(speakers, start=1):
        if not isinstance(entry, dict):
            raise SystemExit(f"{path}: locutor #{index} no es un objeto")
        speaker = entry.get("id")
        split = entry.get("split")
        role = entry.get("role")
        if not isinstance(speaker, str) or not speaker.strip():
            raise SystemExit(f"{path}: locutor #{index} sin 'id'")
        if speaker in seen:
            raise SystemExit(f"{path}: locutor duplicado '{speaker}'")
        if split not in SPLITS:
            raise SystemExit(f"{path}: '{speaker}' tiene split inválido '{split}'")
        if role not in ROLES:
            raise SystemExit(f"{path}: '{speaker}' tiene role inválido '{role}'")
        seen.add(speaker)
        plan.append({"id": speaker, "split": split, "role": role})
    return plan


def wav_info(path: Path) -> tuple[int, int, int, int]:
    try:
        with wave.open(str(path), "rb") as handle:
            return (
                handle.getnchannels(),
                handle.getframerate(),
                handle.getsampwidth(),
                handle.getnframes(),
            )
    except wave.Error as error:
        raise SystemExit(f"{path}: no es un WAV legible ({error}); conviértelo a PCM16") from error


def collect_clip(root: Path, path: Path, speaker: str, condition: str) -> dict:
    channels, rate, width, frames = wav_info(path)
    label = f"{speaker}/{condition}/{path.name}"
    if channels != 1:
        raise SystemExit(f"{label}: se esperaba mono, hay {channels} canales")
    if rate != 16_000:
        raise SystemExit(f"{label}: se esperaba 16 kHz, hay {rate} Hz")
    if width != 2:
        raise SystemExit(f"{label}: se esperaba PCM16, hay {width * 8} bits")
    duration_ms = round(frames * 1000 / rate)
    if duration_ms < MIN_CLIP_MS:
        print(f"aviso: {label}: solo {duration_ms} ms; ¿frase truncada?", file=sys.stderr)
    relative = path.relative_to(root).as_posix()
    return {
        "audio": relative,
        "condition": condition,
        "syllables": None,
        "duration_ms": duration_ms,
        "sample_rate": rate,
    }


def build(root: Path, plan: list[dict]) -> list[dict]:
    rows = []
    ids: set[str] = set()
    for entry in plan:
        speaker, split, role = entry["id"], entry["split"], entry["role"]
        speaker_dir = root / speaker
        enroll_dir = speaker_dir / "enroll"
        test_dir = speaker_dir / "test"
        if not speaker_dir.is_dir():
            raise SystemExit(f"falta la carpeta del locutor: {speaker_dir}")

        enroll_clips = sorted(enroll_dir.glob("*.wav")) if enroll_dir.is_dir() else []
        if role == "unseen" and enroll_clips:
            print(f"aviso: {speaker} es 'unseen'; se ignoran sus clips de enrolamiento", file=sys.stderr)
            enroll_clips = []
        if role == "enrolled" and len(enroll_clips) < MIN_ENROLL_CLIPS:
            raise SystemExit(
                f"{speaker}: {len(enroll_clips)} clips de enrolamiento; "
                f"se necesitan al menos {MIN_ENROLL_CLIPS}"
            )
        for path in enroll_clips:
            clip_id = f"field-{speaker}-enroll-{path.stem}"
            if clip_id in ids:
                raise SystemExit(f"id duplicado: {clip_id}")
            ids.add(clip_id)
            row = {
                "id": clip_id,
                "split": "enroll",
                "speaker": speaker,
                "role": role,
                "enrolled": role == "enrolled",
                **collect_clip(root, path, speaker, "clean"),
            }
            rows.append(row)

        if not test_dir.is_dir():
            raise SystemExit(f"{speaker}: falta la carpeta de uso {test_dir}")
        loose = sorted(test_dir.glob("*.wav"))
        if loose:
            raise SystemExit(
                f"{speaker}: hay {len(loose)} WAV sueltos en test/; "
                "muévelos a una subcarpeta de condición (clean, tv, street, far…)"
            )
        conditions = sorted(path for path in test_dir.iterdir() if path.is_dir())
        if not conditions:
            raise SystemExit(f"{speaker}: no hay condiciones dentro de {test_dir}")
        for condition_dir in conditions:
            clips = sorted(condition_dir.glob("*.wav"))
            if not clips:
                print(f"aviso: {speaker}/{condition_dir.name}: sin clips", file=sys.stderr)
            for path in clips:
                clip_id = f"field-{speaker}-{condition_dir.name}-{path.stem}"
                if clip_id in ids:
                    raise SystemExit(f"id duplicado: {clip_id}")
                ids.add(clip_id)
                rows.append(
                    {
                        "id": clip_id,
                        "split": split,
                        "speaker": speaker,
                        "role": role,
                        "enrolled": role == "enrolled",
                        **collect_clip(root, path, speaker, condition_dir.name),
                    }
                )
    return rows


def validate(plan: list[dict], rows: list[dict]) -> None:
    for split in SPLITS:
        members = [entry for entry in plan if entry["split"] == split]
        enrolled = sum(1 for entry in members if entry["role"] == "enrolled")
        unseen = sum(1 for entry in members if entry["role"] == "unseen")
        if not enrolled:
            raise SystemExit(f"{split}: hace falta al menos un locutor 'enrolled'")
        if not unseen:
            raise SystemExit(f"{split}: hace falta al menos un locutor 'unseen'")
        if not any(row["split"] == split for row in rows):
            raise SystemExit(f"{split}: no hay clips de uso")

    total = len(plan)
    low, high = PROTOCOL_SPEAKERS
    if total < low:
        print(
            f"aviso: {total} locutores; el protocolo pide {low}–{high} para que el "
            "número tenga valor fuera del simulador",
            file=sys.stderr,
        )
    if total > high:
        print(f"aviso: {total} locutores; más de {high} alarga la grabación sin cambiar el diseño", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", required=True, type=Path, help="carpeta raíz del corpus de campo")
    parser.add_argument("--plan", required=True, type=Path, help="plan JSON con locutores, split y rol")
    parser.add_argument("--out", type=Path, help="manifest.jsonl de salida (por defecto <root>/manifest.jsonl)")
    args = parser.parse_args()

    if not args.root.is_dir():
        raise SystemExit(f"no existe la carpeta del corpus: {args.root}")
    plan = read_plan(args.plan)
    rows = build(args.root, plan)
    validate(plan, rows)

    out = args.out or (args.root / "manifest.jsonl")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )

    by_split: dict[str, int] = {}
    for row in rows:
        by_split[row["split"]] = by_split.get(row["split"], 0) + 1
    conditions = sorted({row["condition"] for row in rows if row["split"] != "enroll"})
    durations = [row["duration_ms"] for row in rows]
    print(
        f"{len(rows)} clips de {len(plan)} locutores "
        f"({', '.join(f'{key}={value}' for key, value in sorted(by_split.items()))}), "
        f"condiciones: {', '.join(conditions)}, "
        f"duración {min(durations)}–{max(durations)} ms"
    )
    print(f"manifiesto escrito en {out}")
    print(
        "\nSiguiente: extract_embeddings_sherpa.py con el manifiesto, luego "
        "score_embeddings.py. Un corpus de campo mal repartido no se arregla "
        "con un umbral: mantén calibración y evaluación disjuntas."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
