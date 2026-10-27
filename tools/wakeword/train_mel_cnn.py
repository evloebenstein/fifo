#!/usr/bin/env python3
r"""Entrena y evalúa un detector de "Fifo" sobre el corpus sintético exportado.

Este es el paso que ADR-0046 dejó abierto: el vector CMVN de 257 dimensiones no
separa la palabra del habla continua. Aquí se cambia la representación por un
log-mel de 40 bandas y un CNN pequeño, se entrena con locutores que no aparecen
en la evaluación y se mide con la misma tabla que el arnés de Kotlin.

No descarga nada, no usa modelos preentrenados y no se envía con la app. Es una
herramienta de desarrollo: el modelo resultante solo sirve para decidir si la
representación mejora, no para certificar el rendimiento de campo.

Uso:
    python tools/wakeword/train_mel_cnn.py \
        --corpus C:\ruta\al\corpus \
        --out C:\ruta\al\modelo

El corpus se genera con `WakeWordCorpusExportTest` (FIFO_WAKEWORD_EXPORT_DIR).
"""

from __future__ import annotations

import argparse
import json
import math
import random
import time
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset

SAMPLE_RATE = 16_000
N_FFT = 512
WIN = 400  # 25 ms
HOP = 160  # 10 ms
N_MELS = 40
FMIN = 20.0
FMAX = 7_600.0
WINDOW_FRAMES = 80  # 0,8 s: cabe entero en cualquier clip de palabra
EVAL_STRIDE = 10  # 100 ms entre ventanas
MERGE_GAP_MS = 400
PRE_WINDOW_MS = 300
POST_WINDOW_MS = 500
DEFAULT_PERSISTENCE = 2


# ── Audio y features ────────────────────────────────────────────────────────


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as handle:
        if handle.getnchannels() != 1 or handle.getsampwidth() != 2:
            raise ValueError(f"{path}: se esperaba PCM mono de 16 bits")
        if handle.getframerate() != SAMPLE_RATE:
            raise ValueError(f"{path}: se esperaba {SAMPLE_RATE} Hz")
        raw = handle.readframes(handle.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def hz_to_mel(hz: np.ndarray | float) -> np.ndarray | float:
    return 2595.0 * np.log10(1.0 + np.asarray(hz) / 700.0)


def mel_to_hz(mel: np.ndarray | float) -> np.ndarray | float:
    return 700.0 * (10.0 ** (np.asarray(mel) / 2595.0) - 1.0)


def mel_filterbank() -> np.ndarray:
    points = np.linspace(hz_to_mel(FMIN), hz_to_mel(FMAX), N_MELS + 2)
    hz = mel_to_hz(points)
    bins = np.floor((N_FFT + 1) * hz / SAMPLE_RATE).astype(int)
    bank = np.zeros((N_MELS, N_FFT // 2 + 1), dtype=np.float32)
    for m in range(1, N_MELS + 1):
        left, center, right = bins[m - 1], bins[m], bins[m + 1]
        if center > left:
            for k in range(left, center):
                bank[m - 1, k] = (k - left) / (center - left)
        if right > center:
            for k in range(center, right):
                bank[m - 1, k] = (right - k) / (right - center)
    return bank


MEL_BANK = mel_filterbank()
HANN = np.hanning(WIN).astype(np.float32)


def raw_log_mel(wave: np.ndarray) -> np.ndarray:
    """Log-mel sin normalizar: (tramas, N_MELS)."""
    if wave.size < WIN:
        wave = np.pad(wave, (0, WIN - wave.size))
    frames = np.lib.stride_tricks.sliding_window_view(wave, WIN)[::HOP]
    spectrum = np.abs(np.fft.rfft(frames * HANN, n=N_FFT)) ** 2
    mel = spectrum @ MEL_BANK.T
    return np.log(mel + 1e-6).astype(np.float32)


def normalise(window: np.ndarray) -> np.ndarray:
    """CMVN por ventana: imita al detector, que no ve el clip completo."""
    mean = window.mean(axis=0, keepdims=True)
    std = window.std(axis=0, keepdims=True) + 1e-5
    return (window - mean) / std


def pad_window(features: np.ndarray, start: int) -> np.ndarray:
    """Corta una ventana completa. Si el clip es más corto, replica el borde.

    Nunca rellena con ceros: un bloque de ceros al final de un clip es una
    señal artificial que el modelo aprende como "palabra" y arruina la
    evaluación (fue el primer fallo de este experimento).
    """
    indices = np.clip(np.arange(start, start + WINDOW_FRAMES), 0, features.shape[0] - 1)
    return features[indices]


# ── Corpus y ventanas ───────────────────────────────────────────────────────


@dataclass(frozen=True)
class Clip:
    id: str
    audio: Path
    split: str
    family: str
    label: int
    speaker: str
    condition: str
    duration_ms: int
    word_start_ms: int | None
    word_end_ms: int | None


def load_manifest(corpus: Path) -> list[Clip]:
    clips: list[Clip] = []
    with (corpus / "manifest.jsonl").open("r", encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            clips.append(
                Clip(
                    id=row["id"],
                    audio=corpus / row["audio"],
                    split=row["split"],
                    family=row["family"],
                    label=int(row["label"]),
                    speaker=row["speaker"],
                    condition=row["condition"],
                    duration_ms=int(row["duration_ms"]),
                    word_start_ms=row.get("word_start_ms"),
                    word_end_ms=row.get("word_end_ms"),
                )
            )
    return clips


class FeatureCache:
    def __init__(self) -> None:
        self._cache: dict[str, np.ndarray] = {}

    def get(self, clip: Clip) -> np.ndarray:
        if clip.id not in self._cache:
            self._cache[clip.id] = raw_log_mel(read_wav(clip.audio))
        return self._cache[clip.id]


def centre_frame(clip: Clip) -> int:
    if clip.word_start_ms is not None and clip.word_end_ms is not None:
        centre_ms = (clip.word_start_ms + clip.word_end_ms) // 2
        return int(centre_ms / 1000.0 * SAMPLE_RATE / HOP)
    return int(clip.duration_ms / 2000.0 * SAMPLE_RATE / HOP)


def spec_augment(window: np.ndarray, rng: random.Random) -> np.ndarray:
    out = window.copy()
    for _ in range(2):
        width = rng.randint(4, 12)
        start = rng.randint(0, max(0, out.shape[0] - width))
        out[start : start + width, :] = 0.0
    for _ in range(2):
        width = rng.randint(2, 6)
        start = rng.randint(0, max(0, out.shape[1] - width))
        out[:, start : start + width] = 0.0
    return out


def training_windows(
    clips: Iterable[Clip], cache: FeatureCache, rng: random.Random
) -> tuple[np.ndarray, np.ndarray]:
    windows: list[np.ndarray] = []
    labels: list[int] = []
    for clip in clips:
        features = cache.get(clip)
        centre = centre_frame(clip)
        if clip.family == "positive":
            copies = 8
        elif clip.family == "hard_negative":
            copies = 2
        else:
            copies = 8
        last_start = max(0, features.shape[0] - WINDOW_FRAMES)
        for _ in range(copies):
            jitter = rng.randint(-8, 8)
            if clip.family == "conversation":
                start = rng.randint(0, last_start)
            else:
                start = min(last_start, max(0, centre - WINDOW_FRAMES // 2 + jitter))
            window = normalise(pad_window(features, start))
            windows.append(spec_augment(window, rng))
            labels.append(clip.label)
    return (
        np.stack(windows).astype(np.float32)[:, None, :, :],
        np.asarray(labels, dtype=np.float32),
    )


class ArrayDataset(Dataset):
    def __init__(self, features: np.ndarray, labels: np.ndarray) -> None:
        self.features = torch.from_numpy(features)
        self.labels = torch.from_numpy(labels)

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor]:
        return self.features[index], self.labels[index]


# ── Modelo ──────────────────────────────────────────────────────────────────


class MelCnn(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.body = nn.Sequential(
            nn.Conv2d(1, 16, 3, padding=1),
            nn.BatchNorm2d(16),
            nn.ReLU(),
            nn.MaxPool2d(2),
            nn.Conv2d(16, 32, 3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(),
            nn.MaxPool2d(2),
            nn.Conv2d(32, 64, 3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(),
            nn.MaxPool2d(2),
        )
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Linear(64 * (N_MELS // 8) * (WINDOW_FRAMES // 8), 64),
            nn.ReLU(),
            nn.Dropout(0.3),
            nn.Linear(64, 1),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.head(self.body(x)).squeeze(1)


def train_model(
    model: MelCnn,
    features: np.ndarray,
    labels: np.ndarray,
    epochs: int,
    batch_size: int,
    seed: int,
) -> MelCnn:
    torch.manual_seed(seed)
    loader = DataLoader(
        ArrayDataset(features, labels),
        batch_size=batch_size,
        shuffle=True,
        drop_last=False,
    )
    positives = float(labels.sum())
    negatives = float(len(labels) - labels.sum())
    pos_weight = torch.tensor([min(4.0, negatives / max(1.0, positives))])
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    for epoch in range(1, epochs + 1):
        model.train()
        total = 0.0
        for batch_features, batch_labels in loader:
            optimizer.zero_grad()
            logits = model(batch_features)
            loss = criterion(logits, batch_labels)
            loss.backward()
            optimizer.step()
            total += float(loss.item()) * len(batch_labels)
        scheduler.step()
        print(
            f"epoch {epoch:02d}/{epochs} loss={total / max(1, len(labels)):.4f} "
            f"lr={scheduler.get_last_lr()[0]:.2e}",
            flush=True,
        )
    return model


# ── Evaluación ──────────────────────────────────────────────────────────────


@torch.no_grad()
def clip_window_scores(
    model: MelCnn, clip: Clip, cache: FeatureCache, stride: int = EVAL_STRIDE
) -> list[tuple[float, float]]:
    """Devuelve (fin de ventana en ms, logit) para todo el clip.

    Se comparan logits, no probabilidades: `sigmoid` en float32 satura a 1.0 y
    dos negativos distintos se vuelven indistinguibles justo en la cola, que es
    donde se decide el FA/h.
    """
    model.eval()
    features = cache.get(clip)
    # Solo ventanas completas: una ventana que se sale del clip no existe en
    # producción y, si se rellena, introduce una pista falsa.
    last_start = max(0, features.shape[0] - WINDOW_FRAMES)
    starts = list(range(0, last_start + 1, stride)) if features.shape[0] >= WINDOW_FRAMES else [0]
    batch = np.stack(
        [normalise(pad_window(features, start)) for start in starts]
    ).astype(np.float32)[:, None, :, :]
    logits = model(torch.from_numpy(batch)).numpy().tolist()
    return [
        ((start + WINDOW_FRAMES) * HOP / SAMPLE_RATE * 1000.0, float(logit))
        for start, logit in zip(starts, logits)
    ]


def runs(
    scored: list[tuple[float, float]], threshold: float, persistence: int
) -> list[float]:
    """Instantes en los que se completa una racha de `persistence` ventanas.

    Una palabra real mantiene el score alto durante cientos de milisegundos;
    un pico aislado de conversación no. Exigir persistencia es lo que separa
    "detector de habla" de "detector de la palabra".
    """
    confirmed: list[float] = []
    streak = 0
    for end_ms, score in scored:
        if score >= threshold:
            streak += 1
            if streak >= persistence:
                confirmed.append(end_ms)
        else:
            streak = 0
    return confirmed


def count_events(
    scored: list[tuple[float, float]], threshold: float, persistence: int = DEFAULT_PERSISTENCE
) -> int:
    events = 0
    last_above: float | None = None
    for end_ms in runs(scored, threshold, persistence):
        if last_above is None or end_ms - last_above > MERGE_GAP_MS:
            events += 1
        last_above = end_ms
    return events


def detected(
    clip: Clip,
    scored: list[tuple[float, float]],
    threshold: float,
    persistence: int = DEFAULT_PERSISTENCE,
) -> float | None:
    if clip.word_start_ms is None or clip.word_end_ms is None:
        return None
    low = clip.word_start_ms - PRE_WINDOW_MS
    high = clip.word_end_ms + POST_WINDOW_MS
    for end_ms in runs(scored, threshold, persistence):
        if low <= end_ms <= high:
            return end_ms - clip.word_end_ms
    return None


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * fraction))))
    return ordered[index]


@dataclass
class Metrics:
    clips: int
    positives: int
    hard_negatives: int
    hard_negative_accept: float
    conversations: int
    events: int
    hours: float
    false_accepts_per_hour: float
    frr: float
    median_best_score: float
    p95_latency_ms: float


def evaluate(
    model: MelCnn,
    clips: list[Clip],
    cache: FeatureCache,
    threshold: float,
    label: str,
    persistence: int = DEFAULT_PERSISTENCE,
) -> Metrics:
    pos = [clip for clip in clips if clip.label == 1]
    hard = [clip for clip in clips if clip.label == 0 and clip.family != "conversation"]
    conv = [clip for clip in clips if clip.family == "conversation"]

    pos_outcomes: list[float | None] = []
    best_scores: list[float] = []
    for clip in pos:
        scored = clip_window_scores(model, clip, cache)
        pos_outcomes.append(detected(clip, scored, threshold, persistence))
        best_scores.append(max(score for _, score in scored))

    accepted = 0
    for clip in hard:
        scored = clip_window_scores(model, clip, cache)
        if runs(scored, threshold, persistence):
            accepted += 1

    events = sum(
        count_events(clip_window_scores(model, clip, cache), threshold, persistence)
        for clip in conv
    )
    hours = sum(clip.duration_ms for clip in conv) / 3_600_000.0
    metrics = Metrics(
        clips=len(clips),
        positives=len(pos),
        hard_negatives=len(hard),
        hard_negative_accept=accepted / max(1, len(hard)),
        conversations=len(conv),
        events=events,
        hours=hours,
        false_accepts_per_hour=events / max(1e-6, hours),
        frr=sum(1 for value in pos_outcomes if value is None) / max(1, len(pos)),
        median_best_score=stable_sigmoid(percentile(best_scores, 0.5)),
        p95_latency_ms=percentile([v for v in pos_outcomes if v is not None], 0.95),
    )
    print(
        f"{label:<28} clips={metrics.clips:4d} FRR={metrics.frr:6.1%} "
        f"hard_neg={metrics.hard_negative_accept:6.1%} "
        f"FA/h={metrics.false_accepts_per_hour:8.1f} "
        f"score={metrics.median_best_score:.4f} p95={metrics.p95_latency_ms:6.0f} ms",
        flush=True,
    )
    return metrics


def stable_sigmoid(logit: float) -> float:
    if logit >= 0.0:
        return 1.0 / (1.0 + math.exp(-logit))
    exponential = math.exp(logit)
    return exponential / (1.0 + exponential)


def calibrate(
    model: MelCnn,
    clips: list[Clip],
    cache: FeatureCache,
    persistence: int = DEFAULT_PERSISTENCE,
) -> tuple[float, float]:
    """Umbral más bajo que respeta ≤1 FA/h en validación.

    La barra es FA/h en habla continua, no en palabras sueltas: un negativo
    difícil aceptado se reporta aparte, pero no se usa para inflar el umbral.
    Se barre el umbral de menor a mayor y se queda el primero que cumple, que
    es el que maximiza la sensibilidad en ese punto de trabajo.
    """
    conversations = [clip for clip in clips if clip.family == "conversation"]
    traces = [clip_window_scores(model, clip, cache) for clip in conversations]
    hours = sum(clip.duration_ms for clip in conversations) / 3_600_000.0
    max_logit = max(max(logit for _, logit in trace) for trace in traces)
    candidates = sorted({logit for trace in traces for _, logit in trace})
    for threshold in candidates:
        events = sum(count_events(trace, threshold, persistence) for trace in traces)
        if events / max(1e-6, hours) <= 1.0:
            return threshold, max_logit
    return max_logit + 0.5, max_logit


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=18)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--seed", type=int, default=20261026)
    parser.add_argument("--persistence", type=int, default=DEFAULT_PERSISTENCE)
    parser.add_argument(
        "--checkpoint",
        type=Path,
        help="evalúa un modelo ya entrenado en vez de entrenar otro",
    )
    args = parser.parse_args()

    started = time.time()
    clips = load_manifest(args.corpus)
    cache = FeatureCache()

    train = [clip for clip in clips if clip.split == "train"]
    validation = [clip for clip in clips if clip.split == "validation"]
    field = [clip for clip in clips if clip.split == "field"]
    base = [clip for clip in clips if clip.split == "base"]

    train_windows = 0
    train_positives = 0
    epochs = args.epochs
    model = MelCnn()
    if args.checkpoint is not None:
        checkpoint = torch.load(args.checkpoint, weights_only=False)
        model.load_state_dict(checkpoint["state_dict"])
        model.eval()
        # El resumen de evaluación no debe perder cómo se entrenó el modelo.
        epochs = int(checkpoint.get("epochs", args.epochs))
        train_windows = int(checkpoint.get("train_windows", train_windows))
        train_positives = int(checkpoint.get("train_positives", train_positives))
        print(f"Modelo cargado de {args.checkpoint}", flush=True)
    else:
        rng = random.Random(args.seed)
        train_features, train_labels = training_windows(train, cache, rng)
        train_windows = int(len(train_labels))
        train_positives = int(train_labels.sum())
        print(
            f"Entrenamiento: {len(train_features)} ventanas "
            f"({int(train_labels.sum())} positivas, "
            f"{int(len(train_labels) - train_labels.sum())} negativas) "
            f"de {len(train)} clips; {len(validation)} de validación y {len(field)} de campo",
            flush=True,
        )
        model = train_model(
            model, train_features, train_labels, args.epochs, args.batch_size, args.seed
        )
        model.eval()

    threshold, max_negative = calibrate(model, validation, cache, args.persistence)
    print(
        f"Umbral calibrado: logit={threshold:.3f} "
        f"(p={stable_sigmoid(threshold):.6f}); "
        f"máximo logit en conversación de validación={max_negative:.3f}; "
        f"persistencia={args.persistence} ventanas",
        flush=True,
    )

    print("", flush=True)
    results = {
        "validation": evaluate(
            model, validation, cache, threshold, "validation", args.persistence
        ),
        "base": evaluate(model, base, cache, threshold, "base", args.persistence),
        "field": evaluate(model, field, cache, threshold, "field", args.persistence),
    }

    by_condition = {}
    for condition in sorted({clip.condition for clip in field if clip.label == 1}):
        subset = [clip for clip in field if clip.condition == condition]
        condition_metrics = evaluate(
            model, subset, cache, threshold, condition, args.persistence
        )
        by_condition[condition] = condition_metrics.__dict__

    args.out.parent.mkdir(parents=True, exist_ok=True)
    torch.save(
        {
            "state_dict": model.state_dict(),
            "threshold": threshold,
            "n_mels": N_MELS,
            "window_frames": WINDOW_FRAMES,
            "hop": HOP,
            "win": WIN,
            "n_fft": N_FFT,
            "sample_rate": SAMPLE_RATE,
            "seed": args.seed,
            "persistence": args.persistence,
            "epochs": epochs,
            "train_windows": train_windows,
            "train_positives": train_positives,
        },
        args.out.with_suffix(".pt"),
    )
    summary = {
        "threshold_logit": threshold,
        "threshold_probability": stable_sigmoid(threshold),
        "max_validation_conversation_logit": max_negative,
        "persistence_windows": args.persistence,
        "epochs": epochs,
        "train_windows": train_windows,
        "train_positives": train_positives,
        "validation": results["validation"].__dict__,
        "base": results["base"].__dict__,
        "field": results["field"].__dict__,
        "field_by_condition": by_condition,
        "elapsed_seconds": round(time.time() - started, 1),
    }
    args.out.with_suffix(".json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\nModelo y métricas en {args.out.with_suffix('.pt')} / {args.out.with_suffix('.json')}", flush=True)

    bar = results["field"].false_accepts_per_hour <= 1.0
    print(
        f"Barra <=1 FA/h: {'cumple' if bar else 'NO cumple'} "
        f"({results['field'].false_accepts_per_hour:.1f} FA/h). "
        "Un buen resultado en el simulador no sustituye la validación de campo.",
        flush=True,
    )


if __name__ == "__main__":
    main()
