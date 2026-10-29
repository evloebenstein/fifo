#!/usr/bin/env python3
"""Prepara un pool de voces humanas reales en español (VoxForge) para ADR-0048.

Herramienta de **desarrollo**. Lee los parquets de
`ciempiess/voxforge_spanish` (VoxForge Spanish Corpus, ~49 h, 2180 locutores,
16 kHz) y escribe el mismo layout plano que consume
`RealSpeechAugmentationExportTest`:

    <out>/<locutor>/<orden>.wav

No aumenta el audio ni escribe el manifiesto: eso lo hace el test Kotlin, que
reutiliza `RoomAcoustics`, `SimulatedNoise` y `LombardEffect` del arnés. Así el
campo simulado en español y el corpus sintético comparten la misma acústica.

El corpus se reparte por género para no entrenar el umbral con una sola voz
masculina: VoxForge es ~79 % masculino, así que la mitad de los locutores se
toma de cada género cuando hay suficientes. Además, cada locutor aporta un
subconjunto aleatorio de **prompts distintos**: VoxForge es lectura y todos
recitan el mismo juego de 43 frases, así que dos locutores con la misma frase se
parecen por contenido aunque el timbre sea distinto. Sin ese reparto, los trials
de evaluación miden la frase, no la persona.

Uso::

    python tools/speaker/prepare_voxforge_spanish_pool.py `
      --parquet "$env:TEMP\\fifo-speaker-eval\\voxforge-es" `
      --out "$env:TEMP\\fifo-speaker-eval\\real-pool-es" `
      --speakers 40 --clips-per-speaker 7

VoxForge es lectura espontánea de voluntarios con micrófonos domésticos: es más
realista que LibriSpeech, pero sigue siendo audio limpio de una sola toma. La
simulación añade sala, ruido y esfuerzo; no sustituye a grabar con un móvil en
una calle real.
"""

from __future__ import annotations

import argparse
import io
import random
import sys
from pathlib import Path


def parquet_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_dir():
            files.extend(sorted(path.glob("*.parquet")))
        elif path.is_file():
            files.append(path)
        else:
            raise SystemExit(f"no existe el parquet ni la carpeta: {path}")
    if not files:
        raise SystemExit("no se encontró ningún .parquet")
    return files


def load_index(files: list[Path]) -> dict[str, dict]:
    """Lee solo las columnas de metadatos: el audio no se toca todavía."""
    try:
        import pyarrow.parquet as pq
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "falta pyarrow: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    speakers: dict[str, dict] = {}
    for path in files:
        table = pq.read_table(
            path,
            columns=["audio_id", "speaker_id", "gender", "country", "duration", "normalized_text"],
        )
        columns = table.to_pydict()
        for audio_id, speaker_id, gender, country, duration, text in zip(
            columns["audio_id"],
            columns["speaker_id"],
            columns["gender"],
            columns["country"],
            columns["duration"],
            columns["normalized_text"],
        ):
            entry = speakers.setdefault(
                speaker_id,
                {"gender": gender, "country": country, "clips": []},
            )
            entry["clips"].append((audio_id, float(duration or 0.0), (text or "").strip()))
        print(f"{path.name}: {table.num_rows} clips", file=sys.stderr)
    return speakers


def pick_clips(clips: list[tuple[str, float, str]], count: int, rng: random.Random) -> list[str]:
    """Elige un clip por prompt, con un subconjunto aleatorio distinto por locutor.

    VoxForge es un corpus **leído**: todos los locutores recitan el mismo juego de
    43 frases. Si dos locutores leen la misma frase, la similitud de contenido
    domina al timbre y los trials de un modelo de hablante se vuelven basura.
    Un reparto por turnos (los 7 prompts menos usados) crea ciclos: el locutor 1
    y el 7 acaban con las mismas 7 frases. Un subconjunto aleatorio por locutor
    deja el solapamiento esperado en ~1 frase de 7 y no sesga ningún par.
    """
    by_prompt: dict[str, tuple[str, float]] = {}
    for audio_id, duration, text in clips:
        current = by_prompt.get(text)
        if current is None or duration > current[1]:
            by_prompt[text] = (audio_id, duration)
    prompts = sorted(by_prompt)
    if len(prompts) <= count:
        return [by_prompt[prompt][0] for prompt in prompts]
    return [by_prompt[prompt][0] for prompt in rng.sample(prompts, count)]



def select_speakers(speakers: dict[str, dict], wanted: int, clips: int, min_seconds: float, seed: int) -> list[str]:
    usable = [
        speaker_id
        for speaker_id, entry in speakers.items()
        if len({clip[2] for clip in entry["clips"] if clip[1] >= min_seconds}) >= clips
    ]
    if len(usable) < 8:
        raise SystemExit(f"solo hay {len(usable)} locutores utilizables; se necesitan al menos 8")

    rng = random.Random(seed)
    by_gender: dict[str, list[str]] = {}
    for speaker_id in sorted(usable):
        by_gender.setdefault(speakers[speaker_id]["gender"] or "unknown", []).append(speaker_id)
    for members in by_gender.values():
        rng.shuffle(members)

    half = wanted // 2
    chosen: list[str] = []
    for gender in ("female", "male"):
        chosen.extend(by_gender.get(gender, [])[:half])

    # Relleno determinista si un género no llega a la mitad.
    leftovers = [speaker_id for speaker_id in sorted(usable) if speaker_id not in chosen]
    rng.shuffle(leftovers)
    chosen.extend(leftovers[: max(0, wanted - len(chosen))])
    if len(chosen) < 8:
        raise SystemExit(f"solo se pudieron seleccionar {len(chosen)} locutores")
    return sorted(chosen[:wanted])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--parquet", required=True, type=Path, nargs="+", help="carpeta o parquets de voxforge_spanish")
    parser.add_argument("--out", required=True, type=Path, help="carpeta del pool")
    parser.add_argument("--speakers", type=int, default=40, help="número de locutores a exportar")
    parser.add_argument("--clips-per-speaker", type=int, default=7, help="clips por locutor (3 enroll + 4 test)")
    parser.add_argument("--min-clip-seconds", type=float, default=3.0, help="duración mínima de un clip utilizable")
    parser.add_argument("--seed", type=int, default=7, help="semilla de la selección")
    args = parser.parse_args()

    if args.clips_per_speaker < 7:
        raise SystemExit("--clips-per-speaker debe ser >= 7 (3 de enrolamiento + 4 de uso)")
    if args.speakers < 8:
        raise SystemExit("--speakers debe ser >= 8")

    try:
        import soundfile as sf
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "falta soundfile: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    files = parquet_files(args.parquet)
    speakers = load_index(files)
    selected = select_speakers(
        speakers, args.speakers, args.clips_per_speaker, args.min_clip_seconds, args.seed
    )
    rng = random.Random(args.seed)
    wanted_clips = {
        speaker_id: pick_clips(speakers[speaker_id]["clips"], args.clips_per_speaker, rng)
        for speaker_id in selected
    }
    prompts = {prompt for speaker_id in selected for prompt in
               {clip[2] for clip in speakers[speaker_id]["clips"]}}
    if prompts:
        print(f"prompts disponibles: {len(prompts)}", file=sys.stderr)

    try:
        import pyarrow.parquet as pq
    except ImportError as error:  # pragma: no cover
        raise SystemExit("falta pyarrow: instala tools/speaker/requirements.txt") from error

    audio: dict[str, bytes] = {}
    for path in files:
        table = pq.read_table(
            path,
            columns=["audio_id", "audio"],
            filters=[("speaker_id", "in", selected)],
        )
        columns = table.to_pydict()
        for audio_id, payload in zip(columns["audio_id"], columns["audio"]):
            if payload and payload.get("bytes") is not None:
                audio[audio_id] = payload["bytes"]

    args.out.mkdir(parents=True, exist_ok=True)
    written = 0
    seconds = 0.0
    for speaker_id in selected:
        target_dir = args.out / speaker_id
        target_dir.mkdir(parents=True, exist_ok=True)
        entry = speakers[speaker_id]
        for index, audio_id in enumerate(wanted_clips[speaker_id]):
            raw = audio.get(audio_id)
            if raw is None:
                raise SystemExit(f"el parquet no trajo el audio de {audio_id}")
            samples, rate = sf.read(io.BytesIO(raw), dtype="int16", always_2d=False)
            if samples.ndim != 1:
                samples = samples[:, 0]
            if rate != 16_000:
                raise SystemExit(f"{audio_id}: se esperaban 16 kHz, hay {rate} Hz")
            sf.write(target_dir / f"{index:02d}.wav", samples, rate, subtype="PCM_16")
            written += 1
            seconds += samples.size / rate
        print(
            f"{speaker_id} ({entry['gender']}, {entry['country']}): "
            f"{len(wanted_clips[speaker_id])} clips",
            file=sys.stderr,
        )

    genders: dict[str, int] = {}
    countries: dict[str, int] = {}
    for speaker_id in selected:
        entry = speakers[speaker_id]
        genders[entry["gender"]] = genders.get(entry["gender"], 0) + 1
        countries[entry["country"]] = countries.get(entry["country"], 0) + 1
    print(
        f"pool en {args.out}: {written} WAV de {len(selected)} locutores, "
        f"{seconds / 60:.1f} min, género {genders}, país {countries}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
