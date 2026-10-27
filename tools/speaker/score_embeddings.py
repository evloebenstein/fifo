#!/usr/bin/env python3
"""Puntúa embeddings de locutor contra el corpus exportado (ADR-0048).

Herramienta de **desarrollo**: no forma parte de la app, no descarga modelos y no
extrae features. Recibe dos contratos y hace la parte que sí es independiente del
motor:

1. ``manifest.jsonl`` escrito por ``SpeakerCorpusExportTest``.
2. ``embeddings.jsonl`` con una línea por clip: ``{"id": "...", "embedding": [...]}``.

La extracción de embeddings la hace el toolkit del modelo (WeSpeaker,
sherpa-onnx, Picovoice Eagle…). Así este script no adivina el frontend de cada
modelo ni mete torch/onnx en la app.

Qué calcula:

- centroides de enrolamiento y similitud coseno;
- umbral calibrado a un FAR objetivo con los locutores de ``calibration``;
- EER, FRR y FAR en ``evaluation`` (locutores disjuntos);
- tablas por condición y por duración.

Los números del corpus sintético **no certifican campo**. Ver ADR-0048.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{line_number}: JSON inválido ({error})") from error
    return rows


def load_manifest(path: Path) -> dict[str, dict]:
    manifest = {}
    for row in read_jsonl(path):
        clip_id = row.get("id")
        if not clip_id:
            raise SystemExit(f"manifest sin id: {row}")
        manifest[clip_id] = row
    return manifest


def load_embeddings(path: Path) -> dict[str, list[float]]:
    embeddings = {}
    for row in read_jsonl(path):
        clip_id = row.get("id")
        vector = row.get("embedding")
        if not clip_id or not isinstance(vector, list) or not vector:
            raise SystemExit(f"embedding inválido: {row.get('id')}")
        embeddings[clip_id] = [float(value) for value in vector]
    return embeddings


def normalise(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 1e-9:
        return []
    return [value / norm for value in vector]


def cosine(first: list[float], second: list[float]) -> float:
    if not first or len(first) != len(second):
        return 0.0
    dot = sum(a * b for a, b in zip(first, second))
    return max(0.0, min(1.0, dot))


def centroid(vectors: list[list[float]]) -> list[float]:
    usable = [vector for vector in vectors if vector]
    if not usable:
        return []
    dimension = len(usable[0])
    if any(len(vector) != dimension for vector in usable):
        return []
    mean = [sum(vector[index] for vector in usable) / len(usable) for index in range(dimension)]
    return normalise(mean)


def build_profiles(
    manifest: dict[str, dict],
    embeddings: dict[str, list[float]],
    split: str,
) -> dict[str, list[float]]:
    """Perfiles enrolados que pertenecen al grupo de un split."""
    roles = {}
    for row in manifest.values():
        if row.get("split") == split and row.get("role") == "enrolled":
            roles[row["speaker"]] = True

    by_speaker: dict[str, list[list[float]]] = defaultdict(list)
    missing = []
    for row in manifest.values():
        if row.get("split") != "enroll" or row.get("speaker") not in roles:
            continue
        vector = embeddings.get(row["id"])
        if not vector:
            missing.append(row["id"])
            continue
        by_speaker[row["speaker"]].append(vector)

    if missing:
        print(f"aviso: {len(missing)} clips de enrolamiento sin embedding; se ignoran", file=sys.stderr)

    profiles = {}
    for speaker, vectors in by_speaker.items():
        reference = centroid(vectors)
        if reference:
            profiles[speaker] = reference
    return profiles


def score_trials(
    manifest: dict[str, dict],
    embeddings: dict[str, list[float]],
    split: str,
    profiles: dict[str, list[float]],
) -> list[dict]:
    trials = []
    for row in manifest.values():
        if row.get("split") != split:
            continue
        query = normalise(embeddings.get(row["id"], []))
        if not query:
            continue
        for profile_id, reference in profiles.items():
            if len(reference) != len(query):
                continue
            trials.append(
                {
                    "speaker": row["speaker"],
                    "condition": row.get("condition", "?"),
                    "syllables": row.get("syllables"),
                    "duration_ms": row.get("duration_ms"),
                    "genuine": profile_id == row["speaker"],
                    "score": cosine(query, reference),
                }
            )
    return trials


def eer_threshold(trials: list[dict]) -> tuple[float, float]:
    genuine = [trial["score"] for trial in trials if trial["genuine"]]
    impostor = [trial["score"] for trial in trials if not trial["genuine"]]
    if not genuine or not impostor:
        return 1.0, 1.0

    best_threshold, best_eer, best_gap = 0.0, 1.0, float("inf")
    for step in range(1001):
        threshold = step / 1000.0
        frr = sum(score < threshold for score in genuine) / len(genuine)
        far = sum(score >= threshold for score in impostor) / len(impostor)
        gap = abs(frr - far)
        if gap < best_gap:
            best_gap, best_threshold, best_eer = gap, threshold, (frr + far) / 2.0
    return best_threshold, best_eer


def threshold_at_far(impostor_scores: list[float], target_far: float) -> float:
    if not impostor_scores:
        return 1.0
    allowed = int(math.floor(target_far * len(impostor_scores)))
    ordered = sorted(impostor_scores, reverse=True)
    if allowed >= len(ordered):
        return 0.0
    return min(1.0, ordered[allowed] + 1e-6)


def metrics(trials: list[dict], threshold: float) -> dict:
    genuine = [trial["score"] for trial in trials if trial["genuine"]]
    impostor = [trial["score"] for trial in trials if not trial["genuine"]]
    frr = sum(score < threshold for score in genuine) / len(genuine) if genuine else 1.0
    far = sum(score >= threshold for score in impostor) / len(impostor) if impostor else 1.0
    return {
        "trials": len(trials),
        "genuine_trials": len(genuine),
        "impostor_trials": len(impostor),
        "frr": frr,
        "far": far,
    }


def group_metrics(trials: list[dict], threshold: float, key: str) -> dict[str, dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for trial in trials:
        groups[str(trial.get(key))].append(trial)
    return {name: metrics(subset, threshold) for name, subset in sorted(groups.items())}


def table(title: str, rows: dict[str, dict]) -> None:
    print(f"\n{title}")
    print(f"{'grupo':<24} {'trials':>7} {'FRR':>8} {'FAR':>8}")
    for name, values in rows.items():
        print(
            f"{name:<24} {values['trials']:>7} "
            f"{values['frr'] * 100:>7.1f}% {values['far'] * 100:>7.1f}%"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", required=True, type=Path, help="manifest.jsonl exportado")
    parser.add_argument("--embeddings", required=True, type=Path, help="embeddings.jsonl (id + vector)")
    parser.add_argument("--target-far", type=float, default=0.01, help="FAR objetivo (por defecto 0.01)")
    parser.add_argument("--model-id", default="unknown", help="nombre/versión del modelo evaluado")
    parser.add_argument("--out", type=Path, help="escribe el informe JSON en esta ruta")
    args = parser.parse_args()

    if not 0.0 <= args.target_far <= 1.0:
        raise SystemExit("--target-far debe estar en 0..1")

    manifest = load_manifest(args.manifest)
    embeddings = load_embeddings(args.embeddings)

    calibration_profiles = build_profiles(manifest, embeddings, "calibration")
    evaluation_profiles = build_profiles(manifest, embeddings, "evaluation")
    if not calibration_profiles:
        raise SystemExit("no hay perfiles de calibración: revisa el manifiesto y las embeddings")
    if not evaluation_profiles:
        raise SystemExit("no hay perfiles de evaluación: revisa el manifiesto y las embeddings")

    calibration_trials = score_trials(manifest, embeddings, "calibration", calibration_profiles)
    evaluation_trials = score_trials(manifest, embeddings, "evaluation", evaluation_profiles)
    if not calibration_trials or not evaluation_trials:
        raise SystemExit("faltan trials; ¿las embeddings cubren los clips exportados?")

    calibration_impostor = [trial["score"] for trial in calibration_trials if not trial["genuine"]]
    threshold = threshold_at_far(calibration_impostor, args.target_far)
    calibration = metrics(calibration_trials, threshold)
    evaluation = metrics(evaluation_trials, threshold)
    oracle_threshold, oracle_eer = eer_threshold(evaluation_trials)

    by_condition = group_metrics(evaluation_trials, threshold, "condition")
    by_syllables = group_metrics(evaluation_trials, threshold, "syllables")

    print(f"modelo: {args.model_id}")
    print(f"clips: {len(manifest)}  embeddings: {len(embeddings)}")
    print(f"perfiles: calibración={len(calibration_profiles)} evaluación={len(evaluation_profiles)}")
    print(
        f"umbral@FAR {args.target_far * 100:.1f}%: {threshold:.4f} "
        f"(FAR calibración {calibration['far'] * 100:.1f}%, "
        f"{calibration['impostor_trials']} impostores)"
    )
    print(
        f"evaluación: FRR {evaluation['frr'] * 100:.1f}%  FAR {evaluation['far'] * 100:.1f}%  "
        f"({evaluation['trials']} trials)"
    )
    print(f"referencia EER: {oracle_eer * 100:.1f}% (thr={oracle_threshold:.3f})")

    table("Por condición (umbral calibrado)", by_condition)
    table("Por sílabas / duración (umbral calibrado)", by_syllables)

    report = {
        "model_id": args.model_id,
        "manifest": str(args.manifest),
        "embeddings": str(args.embeddings),
        "target_far": args.target_far,
        "threshold": threshold,
        "calibration": calibration,
        "evaluation": evaluation,
        "eer_reference": {"threshold": oracle_threshold, "eer": oracle_eer},
        "profiles": {
            "calibration": sorted(calibration_profiles),
            "evaluation": sorted(evaluation_profiles),
        },
        "by_condition": by_condition,
        "by_syllables": by_syllables,
    }
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"\ninforme escrito en {args.out}")

    print(
        "\nRecuerda: el corpus es sintético. Un buen número aquí no autoriza a "
        "enviar un detector biométrico; hace falta validación con grabaciones reales."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
