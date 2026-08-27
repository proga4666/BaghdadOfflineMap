# Baghdad Offline Map & Routing Data Guide

This folder contains utilities and instructions for downloading and preparing offline vector map files (`.map`) and GraphHopper routing graphs (`-gh`) for **Baghdad & Iraq**.

---

## 1. Quick Data Setup

### Option A: Use the In-App Downloader
1. Launch the Android App on your device or emulator.
2. Tap the **"Offline Data"** button at the top right.
3. Tap **"Download Iraq Map (98 MB)"**. The app will stream and install the `.map` file directly to the device storage.

### Option B: Automated Host Script
Run the included bash script on your Mac/PC:
```bash
cd data-prep
./prepare_baghdad_data.sh
```
This script downloads `iraq-latest.osm.pbf` and `iraq.map`, then compiles the GraphHopper routing graph.

---

## 2. Directory Structure on Android Device

The app uses private external storage (Scoped Storage compliant, requires zero runtime permissions):

- **Vector Maps Directory**:
  `/sdcard/Android/data/com.offlinemap.baghdad/files/maps/`
  *(Place `iraq.map` or `baghdad.map` here)*

- **GraphHopper Routing Graphs**:
  `/sdcard/Android/data/com.offlinemap.baghdad/files/routes/baghdad-gh/`
  *(Place the GraphHopper graph directory containing `nodes`, `edges`, `geometry`, `names`, `properties` here)*

---

## 3. Pushing Files to Device via ADB

```bash
# Push vector map
adb push output/maps/iraq.map /sdcard/Android/data/com.offlinemap.baghdad/files/maps/

# Push GraphHopper routing folder
adb push output/routes/baghdad-gh /sdcard/Android/data/com.offlinemap.baghdad/files/routes/
```

---

## 4. Custom Bounding Box for Baghdad Metropolitan Area

If you only want a tiny extract specifically for Baghdad to keep file sizes under 10MB:
- **Bounding Box**: `[33.15, 44.20, 33.45, 44.55]` (Lat Min, Lon Min, Lat Max, Lon Max)
- You can crop `iraq-latest.osm.pbf` using **osmium** or **osmosis**:
  ```bash
  osmium extract --bbox 44.20,33.15,44.55,33.45 iraq-latest.osm.pbf -o baghdad.osm.pbf
  ```
