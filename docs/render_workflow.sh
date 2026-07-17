#!/bin/bash
# =============================================================================
# Render the Magisk Workflow Diagram to PDF
# =============================================================================
# Requires: graphviz (dot command)
# Install:  sudo apt install graphviz        # Debian/Ubuntu
#           brew install graphviz            # macOS
#           choco install graphviz           # Windows
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOT_FILE="$SCRIPT_DIR/work_flow.gv"
OUTPUT_PDF="$SCRIPT_DIR/work_flow.pdf"

if ! command -v dot &>/dev/null; then
    echo "ERROR: 'dot' (graphviz) is not installed."
    echo ""
    echo "Install it with one of:"
    echo "  sudo apt install graphviz        # Debian/Ubuntu"
    echo "  brew install graphviz            # macOS"
    echo "  choco install graphviz           # Windows"
    echo ""
    echo "Then run: dot -Tpdf \"$DOT_FILE\" -o \"$OUTPUT_PDF\""
    exit 1
fi

echo "Rendering workflow diagram..."
dot -Tpdf "$DOT_FILE" -o "$OUTPUT_PDF"

if [ $? -eq 0 ]; then
    echo "SUCCESS: PDF generated at: $OUTPUT_PDF"
    echo "File size: $(wc -c < "$OUTPUT_PDF") bytes"
else
    echo "ERROR: Failed to generate PDF."
    exit 1
fi
