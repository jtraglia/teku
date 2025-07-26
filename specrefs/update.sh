#!/bin/bash

# Script to add/update hash comments to all *.yml files in the same directory.
# Adds sha256 hashes (truncated to 8 chars) as comments to each source file.
# Handles #L... line ranges if present.

set -euo pipefail

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

            # Check if the file has enough lines (handle files without trailing newline)
            local total_lines=$(awk 'END {print NR}' "$file_path")
            if (( end_line > total_lines )); then
                echo "Error: Line range $line_range in $file_path exceeds file length (file has $total_lines lines)" >&2
                exit 1
            fi

            sed -n "${start_line},${end_line}p" "$file_path" | sha256sum | cut -c1-8
        elif [[ "$line_range" =~ ^L([0-9]+)$ ]]; then
            local line_num="${BASH_REMATCH[1]}"

            # Check if the file has enough lines (handle files without trailing newline)
            local total_lines=$(awk 'END {print NR}' "$file_path")
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

# Function to process a single YAML file
process_yaml_file() {
    local yaml_file="$1"
    local temp_file=$(mktemp)

    echo "Processing: $(basename "$yaml_file")"

    {
        in_source_section=false

        while IFS= read -r line; do
            if [[ "$line" =~ ^[[:space:]]*sources:[[:space:]]*$ ]]; then
                # Start of sources section
                echo "$line"
                in_source_section=true
            elif [[ "$in_source_section" == true ]]; then
                if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+(.+)$ ]]; then
                    # This is a source file line
                    source_file="${BASH_REMATCH[1]}"

                    # Check if line already has a hash comment and extract the base source file
                    if [[ "$source_file" =~ ^(.+)[[:space:]]+#[[:space:]]+.+$ ]]; then
                        # Hash comment exists, extract base file path for recalculation
                        base_source_file="${BASH_REMATCH[1]}"
                    else
                        # No hash comment
                        base_source_file="$source_file"
                    fi

                    # Calculate hash for the base source file
                    clean_source_file="$base_source_file"
                    line_range=""

                    # Check if source has line range
                    if [[ "$base_source_file" =~ ^(.+)#(L[0-9]+-L[0-9]+|L[0-9]+)$ ]]; then
                        clean_source_file="${BASH_REMATCH[1]}"
                        line_range="${BASH_REMATCH[2]}"
                    fi

                    # Resolve the file path relative to the parent directory of the script directory
                    # (source paths are relative to the teku project root)
                    if [[ "$clean_source_file" = /* ]]; then
                        # Absolute path, use as is
                        resolved_file="$clean_source_file"
                    else
                        # Relative path, resolve from parent of script directory
                        resolved_file="$(dirname "$SCRIPT_DIR")/$clean_source_file"
                    fi

                    hash=$(calculate_hash "$resolved_file" "$line_range")

                    echo "    - $base_source_file # $hash"
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
    } < "$yaml_file" > "$temp_file"

    # Replace original file with updated version
    mv "$temp_file" "$yaml_file"
}

# Main processing
# Find all yml files in the script directory
yml_files=()
while IFS= read -r -d '' file; do
    yml_files+=("$file")
done < <(find "$SCRIPT_DIR" -maxdepth 1 -name "*.yml" -type f -print0)

if [[ ${#yml_files[@]} -eq 0 ]]; then
    echo "No *.yml files found in $SCRIPT_DIR"
    exit 1
fi

# Process each yml file
for yml_file in "${yml_files[@]}"; do
    process_yaml_file "$yml_file"
done
