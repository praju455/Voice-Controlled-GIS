#!/usr/bin/env python3
"""
Train a lightweight TFLite intent classifier for the Android app.

This script intentionally uses a plain TensorFlow/Keras pipeline so the
project has a self-contained starting point. It trains a small text model,
converts it to TFLite, and writes labels/summary artifacts.

Note:
The Android app currently loads the output using TensorFlow Lite Task
NLClassifier. Depending on your final TensorFlow/TFLite setup, you may later
choose to swap this pipeline to Model Maker or add TFLite metadata tooling.
This script is the fastest practical scaffold for experimentation.
"""

from __future__ import annotations

import json
import math
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf


ROOT = Path(__file__).resolve().parent
DATASET_PATH = ROOT / "intent_dataset.csv"
OUTPUT_DIR = ROOT / "output"
SAVED_MODEL_DIR = OUTPUT_DIR / "nlp_intent_saved_model"
TFLITE_PATH = OUTPUT_DIR / "nlp_intent.tflite"
LABELS_PATH = OUTPUT_DIR / "labels.txt"
SUMMARY_PATH = OUTPUT_DIR / "training_summary.json"

SEED = 42
BATCH_SIZE = 16
EPOCHS = 20
VALIDATION_SPLIT = 0.2
VOCAB_SIZE = 5000
SEQUENCE_LENGTH = 40
EMBEDDING_DIM = 64


@dataclass
class DatasetBundle:
    train_texts: list[str]
    train_labels: np.ndarray
    val_texts: list[str]
    val_labels: np.ndarray
    labels: list[str]


def set_seed(seed: int = SEED) -> None:
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def load_dataset() -> DatasetBundle:
    df = pd.read_csv(DATASET_PATH)
    if set(df.columns) != {"text", "label"}:
        raise ValueError("Dataset must contain exactly 'text' and 'label' columns.")

    df = df.dropna().copy()
    df["text"] = df["text"].astype(str).str.strip()
    df["label"] = df["label"].astype(str).str.strip()
    df = df[(df["text"] != "") & (df["label"] != "")]

    labels = sorted(df["label"].unique().tolist())
    label_to_index = {label: idx for idx, label in enumerate(labels)}
    df["label_index"] = df["label"].map(label_to_index)

    records = list(df[["text", "label_index"]].itertuples(index=False, name=None))
    random.shuffle(records)

    split_index = max(1, math.floor(len(records) * (1 - VALIDATION_SPLIT)))
    train_records = records[:split_index]
    val_records = records[split_index:]
    if not val_records:
        val_records = train_records[-1:]
        train_records = train_records[:-1]

    train_texts = [text for text, _ in train_records]
    train_labels = np.array([label for _, label in train_records], dtype=np.int32)
    val_texts = [text for text, _ in val_records]
    val_labels = np.array([label for _, label in val_records], dtype=np.int32)

    return DatasetBundle(
        train_texts=train_texts,
        train_labels=train_labels,
        val_texts=val_texts,
        val_labels=val_labels,
        labels=labels,
    )


def build_vectorizer(train_texts: list[str]) -> tf.keras.layers.TextVectorization:
    vectorizer = tf.keras.layers.TextVectorization(
        max_tokens=VOCAB_SIZE,
        output_mode="int",
        output_sequence_length=SEQUENCE_LENGTH,
        standardize="lower_and_strip_punctuation",
    )
    vectorizer.adapt(tf.data.Dataset.from_tensor_slices(train_texts).batch(BATCH_SIZE))
    return vectorizer


def build_model(vectorizer: tf.keras.layers.TextVectorization, num_labels: int) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(1,), dtype=tf.string, name="text")
    x = vectorizer(inputs)
    x = tf.keras.layers.Embedding(VOCAB_SIZE, EMBEDDING_DIM, name="embedding")(x)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    x = tf.keras.layers.Dense(64, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(num_labels, activation="softmax", name="probabilities")(x)
    model = tf.keras.Model(inputs=inputs, outputs=outputs)
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def build_datasets(bundle: DatasetBundle) -> tuple[tf.data.Dataset, tf.data.Dataset]:
    train_ds = tf.data.Dataset.from_tensor_slices((bundle.train_texts, bundle.train_labels))
    val_ds = tf.data.Dataset.from_tensor_slices((bundle.val_texts, bundle.val_labels))
    train_ds = train_ds.shuffle(len(bundle.train_texts), seed=SEED).batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)
    val_ds = val_ds.batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)
    return train_ds, val_ds


def export_saved_model(model: tf.keras.Model) -> None:
    if SAVED_MODEL_DIR.exists():
        tf.io.gfile.rmtree(str(SAVED_MODEL_DIR))
    model.export(str(SAVED_MODEL_DIR))


def convert_to_tflite() -> bytes:
    converter = tf.lite.TFLiteConverter.from_saved_model(str(SAVED_MODEL_DIR))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    return converter.convert()


def write_outputs(bundle: DatasetBundle, history: tf.keras.callbacks.History, tflite_bytes: bytes) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    TFLITE_PATH.write_bytes(tflite_bytes)
    LABELS_PATH.write_text("\n".join(bundle.labels) + "\n", encoding="utf-8")

    summary = {
        "dataset_path": str(DATASET_PATH),
        "num_examples": len(bundle.train_texts) + len(bundle.val_texts),
        "num_train_examples": len(bundle.train_texts),
        "num_validation_examples": len(bundle.val_texts),
        "labels": bundle.labels,
        "epochs": EPOCHS,
        "batch_size": BATCH_SIZE,
        "history": history.history,
        "output_tflite": str(TFLITE_PATH),
        "labels_file": str(LABELS_PATH),
        "saved_model_dir": str(SAVED_MODEL_DIR),
    }
    SUMMARY_PATH.write_text(json.dumps(summary, indent=2), encoding="utf-8")


def main() -> None:
    set_seed()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    bundle = load_dataset()
    vectorizer = build_vectorizer(bundle.train_texts)
    model = build_model(vectorizer, num_labels=len(bundle.labels))
    train_ds, val_ds = build_datasets(bundle)

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy",
            patience=4,
            restore_best_weights=True,
        )
    ]

    history = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=EPOCHS,
        callbacks=callbacks,
        verbose=2,
    )

    export_saved_model(model)
    tflite_bytes = convert_to_tflite()
    write_outputs(bundle, history, tflite_bytes)

    print("Training complete.")
    print(f"TFLite model: {TFLITE_PATH}")
    print(f"Labels file:   {LABELS_PATH}")
    print(f"Summary file:  {SUMMARY_PATH}")
    print()
    print("Next step:")
    print("Copy training/output/nlp_intent.tflite to app/src/main/assets/models/")


if __name__ == "__main__":
    main()
