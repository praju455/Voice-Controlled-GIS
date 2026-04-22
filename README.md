# Voice-Controlled GIS (Offline Tactical Map)

An advanced, **100% offline**, voice-controlled Android tactical mapping application built for high-stakes, off-grid environments such as defense operations, emergency response routines, and remote wilderness navigation. 

Instead of relying on cloud services, this app natively processes speech, renders massive terrain maps, and calculates high-speed routing graphs entirely on the edge device—ensuring total operational security and reliability when internet is unavailable.

---

## ✨ Key Features

*   **🎙️ Offline Voice Commands:** Native speech recognition to control the map hands-free. (e.g., *"Route to objective"* or *"Show friendlies within 5 km"*).
*   **🗺️ Local Raster Mapping:** Sub-second rendering of massive, custom tactical maps packed locally using `MBTiles`. No network roundtrips required.
*   **🧭 Edge Routing Intelligence:** Fully standalone navigation module using Contraction Hierarchies capable of identifying ultra-fast offline routes in milliseconds.
*   **📡 Spatial Analysis (WIP):** Geometric buffer generation powered by SpatiaLite, enabling dynamic tracking of operating ranges and Points of Interest.

---

## 🛠️ Technology Stack

*   **Android / Kotlin:** Core platform application.
*   **[MapLibre Native (Android)](https://maplibre.org/):** Renders the map UI and graphical line overlays by pointing to local `.mbtiles` raster sources.
*   **[Vosk Speech Recognition](https://alphacephei.com/vosk/):** Powers the on-device acoustic modeling to rapidly parse continuous speech offline.
*   **[GraphHopper](https://github.com/graphhopper/graphhopper):** Compresses OpenStreetMap `.osm.pbf` data into highly aggressive routing caches (`.gh`) for lightning-fast embedded distance and navigation queries.
*   **[SpatiaLite]**(Simulated): C++ Spatial SQL Database engine for on-the-fly computational geometry (ST_Buffer, ST_Intersects).

---

## 🚀 Setup & Installation Guide

Because this application runs entirely offline, it requires pre-compiled geographic and acoustic assets to be injected into the Android application BEFORE compiling. 

### 1. Download Vosk Acoustic Model
1. Download an Android-compatible Vosk acoustic model (e.g., `vosk-model-small-en-us`).
2. Extract the model contents and place them strictly into `/app/src/main/assets/models/model/`.

### 2. Prepare Map Data (`.mbtiles`)
1. Use **QGIS** (or alternative geospatial software) to define your tactical operating area. 
2. Export your map layers (including LULC or satellite imagery) into an MBTiles raster database.
3. Name the file `sample_tactical.mbtiles` and drop it into `/app/src/main/assets/mbtiles/`.

### 3. Generate Routing Graph (`.gh` folder)
1. Download your region's raw OpenStreetMap file (e.g., `eastern-zone-latest.osm.pbf`) from **Geofabrik**.
2. Download the GraphHopper Desktop `.jar`.
3. Process the file via terminal: `java -jar graphhopper-web-X.jar import your-region.osm.pbf`.
4. Copy the freshly generated `*-gh` folder and push it into the Android file cache (or into the `assets` folder for extraction) so the `TacticalRouterEngine` can compute geographic paths.

### 4. Build and Run
*   Open the project in **Android Studio**.
*   Let Gradle sync the MapLibre and Vosk dependencies.
*   Deploy onto a physical Android device (An emulator can be used, but Voice Recognition works best with a dedicated hardware microphone).
*   Accept Microphone Permissions on boot.

---

## 💬 Supported Voice Commands

Currently mapped fallback intents include:

| Intent | Voice Command Trigger | Action Performed |
| :--- | :--- | :--- |
| **Navigation** | *"Route to base"*, *"Navigate to objective"* | Triggers GraphHopper routing between the operator and the destination, drawing a red LineString overlay on MapLibre. |
| **Spatial Radius** | *"Show friendlies within 5 km"* | Computes an ST_Buffer ring via SpatiaLite around the designated entity coordinates at the exact range. *(In Progress)* |

--- 

## 🗺 Roadmap

- [x] Initial map rendering pipeline (MBTiles/MapLibre)
- [x] Acoustic model ingestion & text parsing (Vosk)
- [x] Integrate MapLibre dynamic line overlays for Route projection
- [ ] Connect compiled Contraction Hierarchy GraphHopper `.gh` bundles
- [ ] Full NLP entity-extraction using a lightweight TFLite model instead of regex
- [ ] Cross-compile Native SpatiaLite SQLite drivers

*Maintainer Note: Built as a Hackathon proof-of-concept for offline tactical tracking.*
