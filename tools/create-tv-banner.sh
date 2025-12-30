# create-tv-banner.sh
# Usage: bash create-tv-banner.sh
set -e

OUT="app/src/main/res/drawable/tv_banner.png"
mkdir -p "$(dirname "$OUT")"

# prefer ImageMagick v7 'magick' binary, fall back to 'convert'
if command -v magick >/dev/null 2>&1; then
  IM_CMD="magick"
elif command -v convert >/dev/null 2>&1; then
  IM_CMD="convert"
else
  echo "ImageMagick not found. Install with: brew install imagemagick"
  exit 1
fi

# text parameters
BG="#111111"
FG="#FFFFFF"
WIDTH=320
HEIGHT=180
LINE1="Tivo"
LINE2="Channels"
SIZE1=56
SIZE2=28

# Try Pango rendering (preferred — handles newlines and font names)
# this often works if ImageMagick includes pango support
PANGO_OK=0
if "$IM_CMD" -version 2>&1 | grep -i pango >/dev/null 2>&1; then
  # Use pango markup; font default will be system fallback if named font isn't present
  echo "Using Pango rendering with $IM_CMD..."
  "$IM_CMD" -size ${WIDTH}x${HEIGHT} -background "$BG" \
    pango:"<span font='${SIZE1}'>${LINE1}</span>\n<span font='${SIZE2}'>${LINE2}</span>" \
    -gravity center -fill "$FG" "$OUT" && PANGO_OK=1 || PANGO_OK=0
fi

if [ "$PANGO_OK" -eq 1 ]; then
  echo "Created $OUT via Pango render"
  exit 0
fi

# If Pango unavailable or failed, try a list of common font paths (macOS)
CANDIDATE_FONTS=(
  "/Library/Fonts/Arial.ttf"
  "/Library/Fonts/Helvetica.ttf"
  "/System/Library/Fonts/SFNS.ttf"
  "/System/Library/Fonts/Supplemental/Arial.ttf"
  "/System/Library/Fonts/Supplemental/Helvetica.ttf"
)

FONT=""
for f in "${CANDIDATE_FONTS[@]}"; do
  if [ -f "$f" ]; then
    FONT="$f"
    break
  fi
done

if [ -n "$FONT" ]; then
  echo "Using font file: $FONT"
  # Use magick/convert with explicit font path
  if [ "$IM_CMD" = "magick" ]; then
    # magick will accept the same arguments
    magick -size ${WIDTH}x${HEIGHT} xc:"$BG" \
      -font "$FONT" -fill "$FG" -gravity center -pointsize $SIZE1 -annotate +0,-18 "${LINE1}" \
      -font "$FONT" -fill "$FG" -gravity center -pointsize $SIZE2 -annotate +0,32 "${LINE2}" \
      "$OUT"
  else
    convert -size ${WIDTH}x${HEIGHT} xc:"$BG" \
      -font "$FONT" -fill "$FG" -gravity center -pointsize $SIZE1 -annotate +0,-18 "${LINE1}" \
      -font "$FONT" -fill "$FG" -gravity center -pointsize $SIZE2 -annotate +0,32 "${LINE2}" \
      "$OUT"
  fi
  echo "Created $OUT using font file"
  exit 0
fi

# Last-resort fallback: draw text with default font (no explicit font)
echo "Pango not available and no common font found at candidate paths; using default font fallback."
if [ "$IM_CMD" = "magick" ]; then
  magick -size ${WIDTH}x${HEIGHT} xc:"$BG" \
    -gravity center -fill "$FG" -pointsize $SIZE1 -annotate +0,-18 "${LINE1}" \
    -gravity center -fill "$FG" -pointsize $SIZE2 -annotate +0,32 "${LINE2}" \
    "$OUT"
else
  convert -size ${WIDTH}x${HEIGHT} xc:"$BG" \
    -gravity center -fill "$FG" -pointsize $SIZE1 -annotate +0,-18 "${LINE1}" \
    -gravity center -fill "$FG" -pointsize $SIZE2 -annotate +0,32 "${LINE2}" \
    "$OUT"
fi

echo "Created $OUT using default font (no explicit font file)"
exit 0
