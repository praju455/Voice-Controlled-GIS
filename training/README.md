# Intent Classifier Training

This folder contains the starter dataset and training scaffold for the
offline command-intent model used by:

- `/app/src/main/java/com/defense/tacticalmap/SpatialIntelligenceEngine.kt`

## Goal

Train a TensorFlow Lite intent classifier that outputs one of these labels:

- `route`
- `clear_route`
- `clear_destination`
- `recenter`
- `show_entities`

The exported model assets should be copied to:

- `/app/src/main/assets/models/nlp_intent.tflite`
- `/app/src/main/assets/models/nlp_intent_labels.txt`
- `/app/src/main/assets/models/nlp_intent_vocab.json`

Once those files exist, the app will prefer TFLite intent classification and
fall back to regex when the model bundle is missing or fails.

## Files

- `intent_dataset.csv`
  - Starter dataset with `text,label` columns
- `train_intent_classifier.py`
  - Local training/export scaffold

## Recommended Path

The easiest path is:

1. Create a Python environment with TensorFlow installed
2. Run `train_intent_classifier.py`
3. Copy the exported model bundle into the Android assets folder
4. Rebuild and test on device

## Local Training Example

```bash
cd /Users/venkat/Desktop/Voice-Controlled-GIS
python3 -m venv .venv
source .venv/bin/activate
pip install tensorflow pandas numpy
python training/train_intent_classifier.py
```

## Expected Outputs

The training script writes to:

- `training/output/nlp_intent_saved_model/`
- `training/output/nlp_intent.tflite`
- `training/output/labels.txt`
- `training/output/vocab.json`
- `training/output/training_summary.json`

## Install Into The App

After training:

```bash
cp training/output/nlp_intent.tflite app/src/main/assets/models/nlp_intent.tflite
cp training/output/labels.txt app/src/main/assets/models/nlp_intent_labels.txt
cp training/output/vocab.json app/src/main/assets/models/nlp_intent_vocab.json
./gradlew assembleDebug
```

## Important Limitation

This model improves **intent classification**, not raw speech-to-text quality.

It helps the app decide whether a phrase means:

- route
- clear route
- clear destination
- recenter
- show entities

It does **not** directly fix Vosk mishearing local place names like
`Hebbal` or `Yelahanka`. Those are separate speech-recognition and
post-processing problems.

## Next Improvements After First Model

Once the first model is working, the next improvements should be:

1. Add more command variations to `intent_dataset.csv`
2. Add hard negatives such as unrelated speech/noise phrases
3. Measure confusion between `route` and `show_entities`
4. Tune the confidence threshold in `SpatialIntelligenceEngine.kt`
5. Add a separate entity extraction stage later if needed
