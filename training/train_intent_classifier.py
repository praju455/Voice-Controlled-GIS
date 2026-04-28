#!/usr/bin/env python3
"""Train a lightweight TFLite intent classifier for the Android app.

This version avoids string-processing ops inside the exported model so the
resulting `.tflite` is easier to run directly on Android. Tokenization is done
outside the model both during training and at inference time in Kotlin.
"""

from __future__ import annotations

import json
import math
import random
import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras.preprocessing.sequence import pad_sequences
from tensorflow.keras.preprocessing.text import Tokenizer


ROOT = Path(__file__).resolve().parent
DATASET_PATH = ROOT / "intent_dataset.csv"
OUTPUT_DIR = ROOT / "output"
SAVED_MODEL_DIR = OUTPUT_DIR / "nlp_intent_saved_model"
TFLITE_PATH = OUTPUT_DIR / "nlp_intent.tflite"
LABELS_PATH = OUTPUT_DIR / "labels.txt"
VOCAB_PATH = OUTPUT_DIR / "vocab.json"
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


def normalize_text(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"[^a-z0-9\s]+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def load_dataset() -> DatasetBundle:
    df = pd.read_csv(DATASET_PATH)
    if set(df.columns) != {"text", "label"}:
        raise ValueError("Dataset must contain exactly 'text' and 'label' columns.")

    df = df.dropna().copy()
    df["text"] = df["text"].astype(str).map(normalize_text)
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


def build_tokenizer(train_texts: list[str]) -> Tokenizer:
    tokenizer = Tokenizer(num_words=VOCAB_SIZE, oov_token="<OOV>")
    tokenizer.fit_on_texts(train_texts)
    return tokenizer


def vectorize_texts(tokenizer: Tokenizer, texts: list[str]) -> np.ndarray:
    sequences = tokenizer.texts_to_sequences(texts)
    padded = pad_sequences(
        sequences,
        maxlen=SEQUENCE_LENGTH,
        padding="post",
        truncating="post",
        value=0,
    )
    return padded.astype(np.int32)


def build_model(num_labels: int) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(SEQUENCE_LENGTH,), dtype=tf.int32, name="token_ids")
    x = inputs
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


def build_datasets(
    train_inputs: np.ndarray,
    train_labels: np.ndarray,
    val_inputs: np.ndarray,
    val_labels: np.ndarray,
) -> tuple[tf.data.Dataset, tf.data.Dataset]:
    train_ds = tf.data.Dataset.from_tensor_slices((train_inputs, train_labels))
    val_ds = tf.data.Dataset.from_tensor_slices((val_inputs, val_labels))
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


def write_outputs(
    bundle: DatasetBundle,
    tokenizer: Tokenizer,
    history: tf.keras.callbacks.History,
    tflite_bytes: bytes,
) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    TFLITE_PATH.write_bytes(tflite_bytes)
    LABELS_PATH.write_text("\n".join(bundle.labels) + "\n", encoding="utf-8")
    vocab = {
        token: index
        for token, index in tokenizer.word_index.items()
        if index < VOCAB_SIZE
    }
    VOCAB_PATH.write_text(json.dumps(vocab, indent=2, sort_keys=True), encoding="utf-8")

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
        "vocab_file": str(VOCAB_PATH),
        "saved_model_dir": str(SAVED_MODEL_DIR),
        "sequence_length": SEQUENCE_LENGTH,
        "vocab_size": VOCAB_SIZE,
    }
    SUMMARY_PATH.write_text(json.dumps(summary, indent=2), encoding="utf-8")


def main() -> None:
    set_seed()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    bundle = load_dataset()
    tokenizer = build_tokenizer(bundle.train_texts)
    train_inputs = vectorize_texts(tokenizer, bundle.train_texts)
    val_inputs = vectorize_texts(tokenizer, bundle.val_texts)
    model = build_model(num_labels=len(bundle.labels))
    train_ds, val_ds = build_datasets(train_inputs, bundle.train_labels, val_inputs, bundle.val_labels)

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
    write_outputs(bundle, tokenizer, history, tflite_bytes)

    print("Training complete.")
    print(f"TFLite model: {TFLITE_PATH}")
    print(f"Labels file:   {LABELS_PATH}")
    print(f"Vocab file:    {VOCAB_PATH}")
    print(f"Summary file:  {SUMMARY_PATH}")
    print()
    print("Next step:")
    print("Copy training/output/nlp_intent.tflite, labels.txt, and vocab.json to app/src/main/assets/models/")


if __name__ == "__main__":
    main()
