#!/usr/bin/env python3
"""Extrae embeddings con sherpa-onnx para el corpus exportado (ADR-0048).

Herramienta de **desarrollo**: no forma parte de la app, no se commitea ningún
peso y no se ejecuta en la suite normal. Es la mitad que falta entre
``SpeakerCorpusExportTest`` y ``score_embeddings.py``:

1. Lee ``manifest.jsonl`` (WAV mono 16 kHz PCM16).
2. Extrae un vector por clip con un modelo de embeddings de sherpa-onnx.
3. Escribe ``embeddings.jsonl`` con ``{"id": ..., "embedding": [...]}``.

El modelo se pasa por ruta; no se descarga solo. Ejemplo con WeSpeaker CAM++:

```powershell
python tools/speaker/extract_embeddings_sherpa.py `
  --manifest "$env:TEMP\\fifo-speaker-corpus\\manifest.jsonl" `
  --model "$env:TEMP\\fifo-speaker-eval\\models\\wespeaker_en_voxceleb_CAM++.onnx" `
  --out "$env:TEMP\\fifo-speaker-corpus\\embeddings.jsonl"
```

Después, ``score_embeddings.py`` calibra con el split ``calibration`` y reporta
sobre ``evaluation``. El corpus es sintético: estos números no certifican campo.
"""

from __future__ import annotations

import argparse
import array
import json
import sys
import wave
from pathlib import Path


def read_wav(path: Path) -> tuple[int, list[float]]:
    with wave.open(str(path), "rb") as handle:
        if handle.getnchannels() != 1:
            raise SystemExit(f"{path}: se esperaba mono, hay {handle.getnchannels()} canales")
        if handle.getsampwidth() != 2:
            raise SystemExit(f"{path}: se esperaba PCM16, hay {handle.getsampwidth() * 8} bits")
        sample_rate = handle.getframerate()
        raw = handle.readframes(handle.getnframes())
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder == "big":
        samples.byteswap()
    return sample_rate, [sample / 32768.0 for sample in samples]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", required=True, type=Path, help="manifest.jsonl exportado")
    parser.add_argument("--model", required=True, type=Path, help="modelo ONNX de embeddings de sherpa-onnx")
    parser.add_argument("--out", required=True, type=Path, help="embeddings.jsonl de salida")
    parser.add_argument("--num-threads", type=int, default=1)
    parser.add_argument("--limit", type=int, default=0, help="procesa solo N clips (0 = todos)")
    args = parser.parse_args()

    if not args.manifest.is_file():
        raise SystemExit(f"no existe el manifiesto: {args.manifest}")
    if not args.model.is_file():
        raise SystemExit(f"no existe el modelo: {args.model}")

    try:
        import sherpa_onnx
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "falta sherpa-onnx: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(args.model),
        num_threads=args.num_threads,
        debug=False,
        provider="cpu",
    )
    if not config.validate():
        raise SystemExit(f"configuración inválida para el modelo {args.model}")
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)

    rows = [json.loads(line) for line in args.manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit > 0:
        rows = rows[: args.limit]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    skipped = 0
    with args.out.open("w", encoding="utf-8") as handle:
        for index, row in enumerate(rows, start=1):
            clip_id = row.get("id")
            audio = row.get("audio")
            if not clip_id or not audio:
                skipped += 1
                continue
            wav_path = args.manifest.parent / audio
            try:
                sample_rate, waveform = read_wav(wav_path)
                if sample_rate != 16_000:
                    raise SystemExit(f"{wav_path}: se esperaba 16 kHz, hay {sample_rate} Hz")
                stream = extractor.create_stream()
                stream.accept_waveform(sample_rate=sample_rate, waveform=waveform)
                stream.input_finished()
                embedding = list(extractor.compute(stream))
            except Exception as error:  # un clip roto no debe abortar el corpus entero
                print(f"aviso: {clip_id}: {error}", file=sys.stderr)
                skipped += 1
                continue
            if not embedding:
                print(f"aviso: {clip_id}: embedding vacío", file=sys.stderr)
                skipped += 1
                continue
            handle.write(json.dumps({"id": clip_id, "embedding": embedding}) + "\n")
            written += 1
            if index % 20 == 0:
                print(f"{index}/{len(rows)} clips", file=sys.stderr)

    print(f"embeddings: {written} escritos, {skipped} omitidos en {args.out}")
    return 0 if written else 1


if __name__ == "__main__":
    raise SystemExit(main())
