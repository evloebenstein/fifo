#!/usr/bin/env python3
"""Sintetiza un pool de voz sintética en español para medir spoofing (ADR-0048).

Herramienta de **desarrollo**. Usa `piper-tts` (TTS neuronal local, voces Piper)
para generar WAV mono a 16 kHz PCM16 con el mismo layout plano que el resto de
herramientas:

    <out>/<voz>[-<hablante>]/<orden>.wav

El ataque simulado es **TTS de acceso lógico**: texto a voz de calidad comercial,
el mismo tipo de fraude que debe frenar `SpoofingDetector`. No cubre conversión
de voz (VC), reproducción de audio grabado ni ataques adversarios.

Uso::

    python tools/speaker/make_spoofed_spanish_pool.py `
      --voices "$env:TEMP\\fifo-speaker-eval\\spoof\\tts" `
      --out "$env:TEMP\\fifo-speaker-eval\\spoofed-pool-es" `
      --utterances 10

`--voices` apunta a una carpeta con un subdirectorio por voz; dentro debe haber
un `.onnx` y su `.onnx.json` (los bundles `vits-piper-*` de sherpa-onnx sirven).
El script no descarga modelos: se pasan por ruta. No se commitea ningún WAV ni
peso. El texto es fijo para que el pool sea reproducible; no se usa para
entrenar nada.
"""

from __future__ import annotations

import argparse
import array
import json
import sys
import wave
from pathlib import Path

TARGET_RATE = 16_000


def utterances() -> list[str]:
    return [
        "Fifo, contesta la llamada de mi madre y avísame cuando termine.",
        "No puedo hablar ahora, estoy conduciendo y necesito llegar a la oficina.",
        "Recuérdame comprar pan y leche cuando salga del trabajo esta tarde.",
        "El paquete llegó ayer, pero nadie me avisó de que estaba en el portal.",
        "Voy a llegar tarde a la cena, avisa a todos y empieza sin mí.",
        "¿Dónde estoy? Quiero saber si voy bien hacia la calle Mayor.",
        "El informe está en la carpeta compartida, échale un vistazo cuando puedas.",
        "Mi teléfono nuevo llegó con la pantalla rota y quiero devolverlo.",
        "La reunión se cambia al jueves a las diez de la mañana.",
        "Sube un poco el volumen del navegador, no escucho las indicaciones.",
        "Necesito que me digas cuánto falta para llegar a casa de mi hermana.",
        "Mañana tengo cita con el médico a primera hora de la mañana.",
        "Gracias por la ayuda, ahora mismo no necesito nada más.",
        "¿Me puedes leer el último mensaje que me ha enviado Juan?",
        "Apaga la linterna, ya hemos salido del aparcamiento.",
        "El tren de las ocho sale del andén cuatro, según la aplicación.",
        "Quiero cambiar la ruta porque hay mucho tráfico en la avenida.",
        "Llama a mi hermano y dile que ya he aparcado cerca del restaurante.",
        "Descansa un momento, luego seguimos con la mudanza del salón.",
        "¿Qué tiempo va a hacer mañana en la sierra?",
    ]


def resample(samples: list[float], source_rate: int) -> list[float]:
    if source_rate == TARGET_RATE:
        return samples
    count = round(len(samples) * TARGET_RATE / source_rate)
    step = source_rate / TARGET_RATE
    output: list[float] = []
    for index in range(count):
        position = index * step
        left = int(position)
        right = min(left + 1, len(samples) - 1)
        fraction = position - left
        output.append(samples[left] * (1.0 - fraction) + samples[right] * fraction)
    return output


def normalise(samples: list[float], peak: float = 0.7) -> list[float]:
    top = max((abs(value) for value in samples), default=0.0)
    if top <= 0.0:
        return samples
    return [value * (peak / top) for value in samples]


def write_wav(path: Path, samples: list[float]) -> None:
    pcm = array.array("h", [int(max(-1.0, min(1.0, value)) * 32767) for value in samples])
    if sys.byteorder == "big":
        pcm.byteswap()
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(TARGET_RATE)
        handle.writeframes(pcm.tobytes())


def voice_model(directory: Path) -> tuple[Path, int]:
    onnx_files = sorted(directory.glob("*.onnx"))
    if not onnx_files:
        raise SystemExit(f"{directory}: falta el .onnx")
    onnx = onnx_files[0]
    speakers = 1
    config = Path(f"{onnx}.json") if Path(f"{onnx}.json").is_file() else directory / "config.json"
    if config.is_file():
        payload = json.loads(config.read_text(encoding="utf-8"))
        speakers = int(payload.get("num_speakers") or 1)
    return onnx, speakers


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--voices", required=True, type=Path, help="carpeta con un modelo Piper por voz")
    parser.add_argument("--out", required=True, type=Path, help="carpeta del pool sintético")
    parser.add_argument("--utterances", type=int, default=10, help="frases por voz")
    parser.add_argument("--speed", type=float, default=1.0, help="length_scale de Piper (menor = más rápido)")
    args = parser.parse_args()

    if not args.voices.is_dir():
        raise SystemExit(f"no existe la carpeta de voces: {args.voices}")

    try:
        from piper import PiperVoice, SynthesisConfig
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "falta piper-tts: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    texts = utterances()[: args.utterances]
    directories = sorted(path for path in args.voices.iterdir() if path.is_dir())
    if not directories:
        raise SystemExit(f"no hay modelos en {args.voices}")

    written = 0
    seconds = 0.0
    for directory in directories:
        onnx, speakers = voice_model(directory)
        voice = PiperVoice.load(str(onnx))
        for speaker_id in range(speakers):
            name = directory.name if speakers == 1 else f"{directory.name}-s{speaker_id}"
            target = args.out / name
            config = SynthesisConfig(speaker_id=speaker_id, length_scale=args.speed)
            for index, text in enumerate(texts):
                samples: list[float] = []
                for chunk in voice.synthesize(text, syn_config=config):
                    samples.extend(float(value) for value in chunk.audio_float_array)
                    rate = chunk.sample_rate
                rendered = normalise(resample(samples, rate))
                write_wav(target / f"{index:02d}.wav", rendered)
                written += 1
                seconds += len(rendered) / TARGET_RATE
            print(f"{name}: {len(texts)} clips", file=sys.stderr)

    print(f"pool sintético en {args.out}: {written} WAV, {seconds / 60:.1f} min")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
