#!/bin/bash

# Script to add/update hash comments to spec-refs files.
# Adds sha256 hashes (truncated to 8 chars) as comments to each source file.
# Handles #L... line ranges if present.

set -euo pipefail

YAML_FILE="${1}"
TEMP_FILE=$(mktemp)

if [[ ! -f "$YAML_FILE" ]]; then
    echo "Error: YAML file '$YAML_FILE' not found"
    exit 1
fi

# Function to calculate hash for a file or line range
calculate_hash() {
    local file_path="$1"
    local line_range="$2"

    if [[ ! -f "$file_path" ]]; then
        echo "Error: File not found: $file_path" >&2
        exit 1
    fi

    if [[ -n "$line_range" ]]; then
        # Extract line range (format: L123-L456 or L123)
        if [[ "$line_range" =~ ^L([0-9]+)-L([0-9]+)$ ]]; then
            local start_line="${BASH_REMATCH[1]}"
            local end_line="${BASH_REMATCH[2]}"

            # Validate that start_line <= end_line
            if (( start_line > end_line )); then
                echo "Error: Invalid line range $line_range in $file_path: start line ($start_line) is greater than end line ($end_line)" >&2
                exit 1
            fi

            # Check if the file has enough lines
            local total_lines=$(wc -l < "$file_path")
            if (( end_line > total_lines )); then
                echo "Error: Line range $line_range in $file_path exceeds file length (file has $total_lines lines)" >&2
                exit 1
            fi

            sed -n "${start_line},${end_line}p" "$file_path" | sha256sum | cut -c1-8
        elif [[ "$line_range" =~ ^L([0-9]+)$ ]]; then
            local line_num="${BASH_REMATCH[1]}"

            # Check if the file has enough lines
            local total_lines=$(wc -l < "$file_path")
            if (( line_num > total_lines )); then
                echo "Error: Line number $line_range in $file_path exceeds file length (file has $total_lines lines)" >&2
                exit 1
            fi

            sed -n "${line_num}p" "$file_path" | sha256sum | cut -c1-8
        else
            echo "Error: Invalid line range format '$line_range' in $file_path. Expected formats: L123 or L123-L456" >&2
            exit 1
        fi
    else
        # Hash entire file
        sha256sum "$file_path" | cut -c1-8
    fi
}

# Main processing
{
    in_source_section=false

    while IFS= read -r line; do
        if [[ "$line" =~ ^[[:space:]]*sources:[[:space:]]*$ ]]; then
            # Start of sources section
            echo "$line"
            in_source_section=true
        elif [[ "$in_source_section" == true ]]; then
            if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+(.+)$ ]]; then
                # This is a source file line - add hash comment if not already present
                source_file="${BASH_REMATCH[1]}"

                # Check if line already has a hash comment
                if [[ "$source_file" =~ ^(.+)[[:space:]]+#[[:space:]]+[a-f0-9]{8}$ ]]; then
                    # Hash comment already exists, just output the line
                    echo "$line"
                else
                    # No hash comment, calculate and add it
                    clean_source_file="$source_file"
                    line_range=""

                    # Check if source has line range
                    if [[ "$source_file" =~ ^(.+)#(L[0-9]+-L[0-9]+|L[0-9]+)$ ]]; then
                        clean_source_file="${BASH_REMATCH[1]}"
                        line_range="${BASH_REMATCH[2]}"
                    fi

                    hash=$(calculate_hash "$clean_source_file" "$line_range")
                    echo "    - $source_file # $hash"
                fi
            else
                # End of sources section
                echo "$line"
                in_source_section=false
            fi
        else
            # Regular line, just pass through
            echo "$line"
        fi
    done
} < "$YAML_FILE" > "$TEMP_FILE"

# Replace original file with updated version
mv "$TEMP_FILE" "$YAML_FILE"

echo "Successfully added hash comments to sources in $YAML_FILE"
