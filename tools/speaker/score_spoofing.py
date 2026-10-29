#!/usr/bin/env python3
"""Puntúa audio real y sintético con una contramedida de spoofing (ADR-0048).

Herramienta de **desarrollo**. No forma parte de la app, no se commitea ningún
peso y no se ejecuta en la suite normal. Carga un modelo ONNX de detección de
voz sintética (por ejemplo AASIST, `SpeechAntiSpoofingBenchmarks/AASIST`), puntúa
dos carpetas de WAV mono a 16 kHz PCM16 y escribe un informe con la distribución
de riesgo, el EER y el FRR al umbral que usa la app (`maxSpoofRisk`, 0,50).

El riesgo se define como ``1 - P(bona fide)``, así que encaja tal cual en
`SpeakerEvidence.spoofRisk`: 0 = voz humana, 1 = voz sintética. El umbral de
decisión no se elige aquí; el script solo mide y reporta.

Uso::

    python tools/speaker/score_spoofing.py `
      --model "$env:TEMP\\fifo-speaker-eval\\spoof\\aasist.onnx" `
      --bonafide "$env:TEMP\\fifo-speaker-eval\\real-field-corpus-es" `
      --spoof "$env:TEMP\\fifo-speaker-eval\\spoofed-pool-es" `
      --out "$env:TEMP\\fifo-speaker-eval\\spoof\\report-es.json"

AASIST consume los primeros 64 600 samples (4,04 s a 16 kHz) y devuelve dos
logits; se replica el `pad` del repositorio original (repite la señal si es más
corta) para que el número sea comparable con la tabla publicada.
"""

from __future__ import annotations

import argparse
import array
import json
import math
import sys
import wave
from pathlib import Path

WINDOW_SAMPLES = 64_600
POLICY_THRESHOLD = 0.50


def read_wav(path: Path) -> list[float]:
    with wave.open(str(path), "rb") as handle:
        if handle.getnchannels() != 1:
            raise SystemExit(f"{path}: se esperaba mono, hay {handle.getnchannels()} canales")
        if handle.getsampwidth() != 2:
            raise SystemExit(f"{path}: se esperaba PCM16, hay {handle.getsampwidth() * 8} bits")
        sample_rate = handle.getframerate()
        if sample_rate != 16_000:
            raise SystemExit(f"{path}: se esperaba 16 kHz, hay {sample_rate} Hz")
        raw = handle.readframes(handle.getnframes())
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder == "big":
        samples.byteswap()
    return [sample / 32768.0 for sample in samples]


def pad_like_upstream(samples: list[float]) -> list[float]:
    if len(samples) >= WINDOW_SAMPLES:
        return samples[:WINDOW_SAMPLES]
    if not samples:
        return [0.0] * WINDOW_SAMPLES
    repeats = WINDOW_SAMPLES // len(samples) + 1
    tiled = samples * repeats
    return tiled[:WINDOW_SAMPLES]


def normalize(samples: list[float], mode: str) -> list[float]:
    """Ajusta el nivel de entrada.

    AASIST no normaliza por energía: el mismo audio escalado ×10 cambia el signo
    del logit. `raw` mide lo que llega del micrófono, `peak` replica un WAV
    normalizado a fondo de escala y `rms` fija la energía (0,05 ≈ −26 dBFS), que
    es lo que hace un AGC. Comparar los tres es lo que revela si el detector
    depende del nivel.
    """
    if mode == "raw" or not samples:
        return samples
    if mode == "peak":
        top = max(abs(value) for value in samples)
        return samples if top <= 0.0 else [value / top for value in samples]
    if mode == "rms":
        power = math.sqrt(sum(value * value for value in samples) / len(samples))
        return samples if power <= 0.0 else [value / power * 0.05 for value in samples]
    raise SystemExit(f"normalización desconocida: {mode}")


def discover(root: Path, limit: int, needle: str = "") -> list[Path]:
    if root.is_file():
        return [root]
    files = sorted(root.rglob("*.wav"))
    if needle:
        files = [path for path in files if needle in path.name]
    if limit > 0:
        files = files[:limit]
    return files


def eer_and_frr(bonafide_risks: list[float], spoof_risks: list[float]) -> dict:
    """EER y FRR a FAR fijo, con el mismo barrido que usa el arnés."""
    thresholds = sorted(set(bonafide_risks + spoof_risks))
    total_pos = len(bonafide_risks)
    total_neg = len(spoof_risks)

    def rates(threshold: float) -> tuple[float, float]:
        frr = sum(1 for risk in bonafide_risks if risk >= threshold) / total_pos
        far = sum(1 for risk in spoof_risks if risk < threshold) / total_neg
        return frr, far

    best_threshold = min(thresholds, key=lambda t: abs(rates(t)[0] - rates(t)[1]))
    eer_frr, eer_far = rates(best_threshold)

    fixed = {}
    for target in (0.01, 0.05):
        candidates = [t for t in thresholds if rates(t)[1] <= target]
        threshold = max(candidates) if candidates else max(thresholds)
        fixed[f"frr_at_far_{target:g}"] = {"threshold": threshold, "frr": rates(threshold)[0]}

    return {
        "eer": (eer_frr + eer_far) / 2,
        "eer_threshold": best_threshold,
        "frr_at_eer": eer_frr,
        "far_at_eer": eer_far,
        **fixed,
    }


def softmax_bona_fide(logits: list[float]) -> float:
    highest = max(logits)
    exp = [math.exp(value - highest) for value in logits]
    total = sum(exp)
    return exp[1] / total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, type=Path, help="ONNX de spoofing (entrada [batch, 64600])")
    parser.add_argument("--bonafide", required=True, type=Path, help="carpeta o WAV de voz humana")
    parser.add_argument("--spoof", required=True, type=Path, help="carpeta o WAV de voz sintética")
    parser.add_argument("--limit", type=int, default=0, help="máximo de WAV por clase (0 = todos)")
    parser.add_argument("--bonafide-filter", default="", help="subcadena obligatoria en el nombre de los WAV reales")
    parser.add_argument("--spoof-filter", default="", help="subcadena obligatoria en el nombre de los WAV sintéticos")
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--normalize", choices=("raw", "peak", "rms"), default="raw",
                        help="nivel de entrada: raw, peak (fondo de escala) o rms (AGC)")
    parser.add_argument("--out", type=Path, help="escribe el informe JSON en esta ruta")
    args = parser.parse_args()

    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError as error:  # pragma: no cover - depende del entorno de desarrollo
        raise SystemExit(
            "faltan onnxruntime/numpy: instala tools/speaker/requirements.txt en un venv de desarrollo"
        ) from error

    if not args.model.is_file():
        raise SystemExit(f"no existe el modelo: {args.model}")
    bonafide = discover(args.bonafide, args.limit, args.bonafide_filter)
    spoof = discover(args.spoof, args.limit, args.spoof_filter)
    if not bonafide or not spoof:
        raise SystemExit(f"faltan WAV: {len(bonafide)} reales, {len(spoof)} sintéticos")

    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    def score(files: list[Path]) -> list[float]:
        risks: list[float] = []
        for start in range(0, len(files), args.batch):
            chunk = files[start:start + args.batch]
            batch = np.array([pad_like_upstream(normalize(read_wav(path), args.normalize)) for path in chunk], dtype=np.float32)
            logits = session.run(None, {input_name: batch})[0]
            risks.extend(1.0 - softmax_bona_fide([float(a), float(b)]) for a, b in logits)
        return risks

    bonafide_risks = score(bonafide)
    spoof_risks = score(spoof)
    metrics = eer_and_frr(bonafide_risks, spoof_risks)
    policy_reject = sum(1 for risk in bonafide_risks if risk >= POLICY_THRESHOLD) / len(bonafide_risks)
    policy_accept = sum(1 for risk in spoof_risks if risk < POLICY_THRESHOLD) / len(spoof_risks)

    report = {
        "model": args.model.name,
        "normalization": args.normalize,
        "bonafide": {"files": len(bonafide), "mean_risk": sum(bonafide_risks) / len(bonafide_risks)},
        "spoof": {"files": len(spoof), "mean_risk": sum(spoof_risks) / len(spoof_risks)},
        "policy_threshold": POLICY_THRESHOLD,
        "policy_frr": policy_reject,
        "policy_far": policy_accept,
        **metrics,
    }
    print(f"modelo: {report['model']}")
    print(f"reales: {len(bonafide)}  sintéticos: {len(spoof)}")
    print(f"riesgo medio  reales={report['bonafide']['mean_risk']:.3f}  sintéticos={report['spoof']['mean_risk']:.3f}")
    print(f"umbral de política {POLICY_THRESHOLD:.2f}: FRR={policy_reject * 100:.1f}%  FAR={policy_accept * 100:.1f}%")
    print(f"EER={metrics['eer'] * 100:.1f}% (thr={metrics['eer_threshold']:.3f})")
    for key, value in metrics.items():
        if key.startswith("frr_at_far"):
            print(f"{key}: {value['frr'] * 100:.1f}% a thr={value['threshold']:.3f}")
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"informe escrito en {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
