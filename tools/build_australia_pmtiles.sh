#!/usr/bin/env bash
# Build Roadguard's whole-of-Australia offline map archive, reproducibly.
#
# Output: a Shortbread 1.0 vector tile archive in PMTiles v3, zoom 0-14, built from OpenStreetMap
# data by planetiler, plus a SHA-256 manifest. MapLibre reads the archive directly on the device
# through its pmtiles:// file source, so the phone needs no tile server and no network once the file
# is installed.
#
# Why Roadguard hosts this itself
# -------------------------------
# The OpenStreetMap data is ODbL, so redistributing tiles built from it is entirely permitted. What
# is *not* reasonable is pointing an app's first-run download at somebody else's extract server:
# Protomaps explicitly discourages hotlinking its builds, BBBike is donation funded at roughly
# EUR600/month of server costs, Mapsforge's server is labelled "not suitable for mass downloads",
# and VersaTiles asks that its service be used only to fetch the files themselves. Build once, host
# once, attribute properly. See docs/offline-maps.md.
#
# Why PMTiles rather than MBTiles
# -------------------------------
# The identical Australia tileset is roughly 2.9x smaller as PMTiles than as MBTiles, because
# PMTiles deduplicates identical tiles -- and Australia is mostly ocean and desert, which produces a
# great many identical tiles -- and carries no SQLite B-tree index. Under a gigabyte is a reasonable
# ask of a phone; three gigabytes is not.
#
# Why zoom 14
# -----------
# Shortbread's maximum zoom is 14 and MapLibre overzooms it for higher display zooms. A driver reads
# roughly z15-z17, all of which render from z14 geometry, so zoom 15 and 16 would double the archive
# size to add detail nobody can use at 100 km/h.
#
# Requirements: JDK 21+, ~15 GB free disk, ~6 GB RAM, and an internet connection for the source
# extract. Expect tens of minutes on four cores.
#
# Usage:  tools/build_australia_pmtiles.sh [output-directory]
set -euo pipefail

OUT_DIR="${1:-$(pwd)/build/map}"
WORK_DIR="${ROADGUARD_MAP_WORK:-$OUT_DIR/work}"
AREA="${ROADGUARD_MAP_AREA:-australia}"
MAXZOOM="${ROADGUARD_MAP_MAXZOOM:-14}"
HEAP="${ROADGUARD_MAP_HEAP:-6g}"
THREADS="${ROADGUARD_MAP_THREADS:-$(nproc 2>/dev/null || echo 4)}"
ARCHIVE_NAME="${ROADGUARD_MAP_NAME:-australia-shortbread.pmtiles}"

mkdir -p "$OUT_DIR" "$WORK_DIR"
cd "$WORK_DIR"

if [ ! -f planetiler.jar ]; then
  echo "==> downloading planetiler"
  curl -sSL --retry 4 --retry-delay 5 -o planetiler.jar \
    https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar
fi

# The Shortbread schema definition ships inside planetiler's own jar, so the build uses the
# upstream schema rather than a copy that could drift from it.
if [ ! -f samples/shortbread.yml ]; then
  echo "==> extracting the Shortbread schema from planetiler"
  unzip -oq planetiler.jar 'samples/shortbread.yml' -d .
fi

echo "==> building $AREA, Shortbread 1.0, z0-$MAXZOOM (threads=$THREADS heap=$HEAP)"
java "-Xmx$HEAP" -jar planetiler.jar samples/shortbread.yml \
  --download --area="$AREA" \
  --output="$OUT_DIR/$ARCHIVE_NAME" \
  --maxzoom="$MAXZOOM" \
  --threads="$THREADS" \
  --nodemap-type=sparsearray \
  --storage=mmap \
  --force

cd "$OUT_DIR"
BYTES=$(stat -c%s "$ARCHIVE_NAME")
SHA=$(sha256sum "$ARCHIVE_NAME" | cut -d' ' -f1)

cat > "$ARCHIVE_NAME.sha256" <<EOF
$SHA  $ARCHIVE_NAME
EOF

# Emit the exact JSON fragment the app's catalogue needs, so the two can never disagree.
cat > "$ARCHIVE_NAME.catalogue.json" <<EOF
{
  "sizeBytes": $BYTES,
  "sha256": "$SHA"
}
EOF

echo
echo "==> built $OUT_DIR/$ARCHIVE_NAME"
echo "    size   : $BYTES bytes ($((BYTES / 1024 / 1024)) MiB)"
echo "    sha256 : $SHA"
echo
echo "Next: publish it as a release asset and copy sizeBytes/sha256 into"
echo "      app/src/main/assets/map_packages.json"
