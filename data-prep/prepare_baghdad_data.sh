#!/bin/bash
set -e

echo "=================================================================="
echo "    Baghdad Offline Map & Routing Data Preparation Tool"
echo "=================================================================="

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${WORK_DIR}/output"
mkdir -p "${OUTPUT_DIR}/maps" "${OUTPUT_DIR}/routes"

echo "[1/4] Downloading Iraq OpenStreetMap PBF from Geofabrik..."
PBF_FILE="${WORK_DIR}/iraq-latest.osm.pbf"
if [ ! -f "$PBF_FILE" ]; then
    curl -L -o "$PBF_FILE" "https://download.geofabrik.de/asia/iraq-latest.osm.pbf"
else
    echo "  -> Found existing $PBF_FILE"
fi

echo "[2/4] Downloading pre-built Iraq vector .map from Mapsforge Server..."
MAP_FILE="${OUTPUT_DIR}/maps/iraq.map"
if [ ! -f "$MAP_FILE" ]; then
    curl -L -o "$MAP_FILE" "https://download.mapsforge.org/maps/v5/asia/iraq.map"
else
    echo "  -> Found existing $MAP_FILE"
fi

echo "[3/4] Setting up GraphHopper 0.13.0 for offline routing graph generation..."
GH_JAR="${WORK_DIR}/graphhopper-web-0.13.0.jar"
if [ ! -f "$GH_JAR" ]; then
    echo "  -> Downloading GraphHopper 0.13.0 standalone jar..."
    curl -L -o "$GH_JAR" "https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/0.13.0/graphhopper-web-0.13.0.jar"
fi

echo "[4/4] Generating GraphHopper graph for Baghdad / Iraq..."
GH_CACHE="${OUTPUT_DIR}/routes/baghdad-gh"
if [ -f "$GH_JAR" ] && [ -f "$PBF_FILE" ]; then
    java -Xmx2g -Dgraphhopper.graph.flag_encoders=car,bike,foot \
         -Dgraphhopper.prepare.ch.weightings=no \
         -Dgraphhopper.graph.dataaccess=MMAP \
         -jar "$GH_JAR" import "$PBF_FILE"
    
    if [ -d "graph-cache" ]; then
        mv graph-cache "$GH_CACHE"
        echo "  -> Successfully generated GraphHopper cache at: $GH_CACHE"
    fi
fi

echo "=================================================================="
echo "Preparation Complete!"
echo "Files ready in: ${OUTPUT_DIR}"
echo ""
echo "To push to your Android device via ADB, run:"
echo "  adb push ${OUTPUT_DIR}/maps/* /sdcard/Android/data/com.offlinemap.baghdad/files/maps/"
echo "  adb push ${OUTPUT_DIR}/routes/* /sdcard/Android/data/com.offlinemap.baghdad/files/routes/"
echo "=================================================================="
