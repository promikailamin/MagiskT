#!/usr/bin/env bash

TARGET_DIR="${1:-.}"
OLD_STR="pro.magisk"
NEW_STR="pro.magisk"

# Find all regular files containing the string and perform in-place replacement
find "$TARGET_DIR" -type f -exec grep -l "$OLD_STR" {} + | while IFS= read -r file; do
    echo "Updating contents of: $file"
    # Linux (GNU sed)
    sed -i "s/$OLD_STR/$NEW_STR/g" "$file"
    
    # If running on macOS (BSD sed), use this line instead:
    # sed -i '' "s/$OLD_STR/$NEW_STR/g" "$file"
done
