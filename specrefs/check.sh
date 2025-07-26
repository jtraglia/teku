#!/bin/bash

# Script to check that all spec tags from ethspecify are present in our YAML files.
# Ensures each spec item has a spec tag for every fork it appears in.

set -euo pipefail

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Format: "ItemName" (all forks) or "ItemName#fork" (specific fork)
SSZ_EXCEPTIONS=(
    "Eth1Block"
    "LightClientFinalityUpdate"
    "LightClientHeader"
    "LightClientOptimisticUpdate"
)

CONFIG_EXCEPTIONS=(
    "BLOB_SCHEDULE"
)

PRESET_EXCEPTIONS=(
)

DATACLASS_EXCEPTIONS=(
    "LatestMessage"
    "LightClientStore"
    "OptimisticStore"
    "Store"
)

# Function to check if an item#fork is in the exception list
is_excepted() {
    local item_fork="$1"
    shift
    local exception_array=("$@")

    # Handle empty arrays
    if [[ ${#exception_array[@]} -eq 0 ]]; then
        return 1  # Not in exception list (empty array)
    fi

    # Extract item name from item#fork
    local item_name="${item_fork%#*}"

    for exception in "${exception_array[@]}"; do
        # Check exact match for item#fork
        if [[ "$item_fork" == "$exception" ]]; then
            return 0  # Found exact match
        fi
        # Check if exception is just item name (covers all forks)
        if [[ "$item_name" == "$exception" ]]; then
            return 0  # Found item name match (all forks)
        fi
    done
    return 1  # Not in exception list
}

# Function to parse ethspecify output and extract item/fork pairs
parse_ethspecify_tags() {
    local tag_type="$1"  # ssz_object, config_var, preset_var, or dataclass

    ethspecify list-tags | grep "${tag_type}=" | while IFS= read -r line; do
        # Extract item name: ... (fork1, fork2)
        local item=$(echo "$line" | sed -E "s/.*${tag_type}=\"([^\"]+)\".*/\1/")

        # Extract forks from parentheses
        local forks_part=$(echo "$line" | sed -E 's/.*\(([^)]+)\).*/\1/')

        # Split forks by comma and output item#fork pairs
        echo "$forks_part" | tr ',' '\n' | while IFS= read -r fork; do
            fork=$(echo "$fork" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')  # trim whitespace
            echo "${item}#${fork}"
        done
    done | sort
}

# Function to extract spec tags from YAML files
extract_spec_tags() {
    local yaml_file="$1"
    local tag_type="$2"  # ssz_object, config_var, preset_var, or dataclass

    if [[ ! -f "$yaml_file" ]]; then
        return
    fi

    # Find all spec tags and extract item#fork pairs
    grep -o "<spec ${tag_type}=\"[^\"]*\"[^>]*fork=\"[^\"]*\"" "$yaml_file" 2>/dev/null | while IFS= read -r tag; do
        local item=$(echo "$tag" | sed -E "s/.*${tag_type}=\"([^\"]+)\".*/\1/")
        local fork=$(echo "$tag" | sed -E 's/.*fork="([^"]+)".*/\1/')
        echo "${item}#${fork}"
    done | sort
}

# Function to validate that a source file exists
validate_source_file() {
    local file_path="$1"
    local repo_root="$(dirname "$SCRIPT_DIR")"
    
    # Resolve full file path
    local full_path="$repo_root/$file_path"
    
    # Check if file exists
    if [[ ! -f "$full_path" ]]; then
        echo "MISSING FILE: $file_path"
        return 1
    fi
    
    return 0
}

# Function to validate that a search string can be found in a file
validate_search_string() {
    local file_path="$1"
    local search_string="$2"
    local repo_root="$(dirname "$SCRIPT_DIR")"
    
    # Resolve full file path
    local full_path="$repo_root/$file_path"
    
    # Check if file exists
    if [[ ! -f "$full_path" ]]; then
        echo "MISSING FILE: $file_path"
        return 1
    fi
    
    # Check if search string can be found
    if ! grep -F "$search_string" "$full_path" >/dev/null 2>&1; then
        echo "SEARCH NOT FOUND: '$search_string' in $file_path"
        return 1
    fi
    
    return 0
}

# Function to check source files for a specific YAML file
check_source_files() {
    local section_name="$1"
    local yaml_file="$2"
    
    echo "=== $section_name Source Files ==="
    
    if [[ ! -f "$yaml_file" ]]; then
        echo "YAML file not found: $yaml_file"
        return 1
    fi
    
    local error_count=0
    local total_count=0
    local in_sources_section=false
    local current_file=""
    local current_search=""
    
    # Extract all source file paths from the YAML file
    while IFS= read -r line; do
        # Check if we're starting a sources section
        if [[ "$line" =~ ^[[:space:]]*sources:[[:space:]]*$ ]]; then
            in_sources_section=true
            current_file=""
            current_search=""
            
        # Check if we're leaving the sources section (new top-level key or end of sources)
        elif [[ "$in_sources_section" == true ]] && [[ "$line" =~ ^[a-zA-Z_-].*:[[:space:]]*.*$ ]]; then
            # Validate any pending entry before leaving sources section
            if [[ -n "$current_file" ]]; then
                total_count=$((total_count + 1))
                if [[ -n "$current_search" ]]; then
                    # Had search string, validate it
                    if ! validate_search_string "$current_file" "$current_search"; then
                        error_count=$((error_count + 1))
                    fi
                else
                    # No search string, just validate file exists
                    if ! validate_source_file "$current_file"; then
                        error_count=$((error_count + 1))
                    fi
                fi
                current_file=""
                current_search=""
            fi
            in_sources_section=false
            
        # Match source file lines only when in sources section
        elif [[ "$in_sources_section" == true ]]; then
            if [[ "$line" =~ ^[[:space:]]*-[[:space:]]*file:[[:space:]]*(.+)$ ]]; then
                # file field - validate any pending entry first
                if [[ -n "$current_file" ]]; then
                    total_count=$((total_count + 1))
                    if [[ -n "$current_search" ]]; then
                        # Had search string, validate it
                        if ! validate_search_string "$current_file" "$current_search"; then
                            error_count=$((error_count + 1))
                        fi
                    else
                        # No search string, just validate file exists
                        if ! validate_source_file "$current_file"; then
                            error_count=$((error_count + 1))
                        fi
                    fi
                fi
                
                # Start new entry
                current_file="${BASH_REMATCH[1]}"
                current_search=""
                
            elif [[ "$line" =~ ^[[:space:]]*search:[[:space:]]*(.+)$ ]]; then
                # search field
                current_search="${BASH_REMATCH[1]}"
            fi
        fi
    done < "$yaml_file"
    
    # Handle the last entry if it exists
    if [[ -n "$current_file" ]]; then
        total_count=$((total_count + 1))
        if [[ -n "$current_search" ]]; then
            # Had search string, validate it
            if ! validate_search_string "$current_file" "$current_search"; then
                error_count=$((error_count + 1))
            fi
        else
            # No search string, just validate file exists
            if ! validate_source_file "$current_file"; then
                error_count=$((error_count + 1))
            fi
        fi
    fi
    
    local valid_count=$((total_count - error_count))
    echo "Source Files: $valid_count/$total_count valid"
    echo
    
    if [[ $error_count -eq 0 ]]; then
        return 0
    else
        return 1
    fi
}


# Function to check coverage for a specific section
check_section_coverage() {
    local section_name="$1"
    local yaml_file="$2"
    local tag_type="$3"
    shift 3
    local exception_array=("$@")

    echo "=== $section_name Coverage ==="

    # Get expected item#fork pairs from ethspecify
    local expected_pairs=$(parse_ethspecify_tags "$tag_type")

    # Get actual item#fork pairs from YAML file
    local actual_pairs=$(extract_spec_tags "$yaml_file" "$tag_type")

    local missing_count=0
    local total_count=0

    while IFS= read -r item_fork; do
        [[ -z "$item_fork" ]] && continue
        total_count=$((total_count + 1))

        if is_excepted "$item_fork" "${exception_array[@]+"${exception_array[@]}"}"; then
            continue  # Skip excepted items silently
        fi

        if ! echo "$actual_pairs" | grep -Fxq "$item_fork"; then
            echo "MISSING: $item_fork"
            missing_count=$((missing_count + 1))
        fi
    done <<< "$expected_pairs"

    local found_count=$((total_count - missing_count))
    echo "Coverage: $found_count/$total_count"
    echo

    # Return 0 if no missing, 1 if any missing (bash can only return 0-255)
    if [[ $missing_count -eq 0 ]]; then
        return 0
    else
        return 1
    fi
}

# Check source files and spec tag coverage for each section
has_errors=false

# Check source files
check_source_files "SSZ Objects" "$SCRIPT_DIR/ssz-objects.yml" || has_errors=true
check_source_files "Config Variables (Mainnet)" "$SCRIPT_DIR/config-variables-mainnet.yml" || has_errors=true
check_source_files "Config Variables (Minimal)" "$SCRIPT_DIR/config-variables-minimal.yml" || has_errors=true
check_source_files "Preset Variables (Mainnet)" "$SCRIPT_DIR/preset-variables-mainnet.yml" || has_errors=true
check_source_files "Preset Variables (Minimal)" "$SCRIPT_DIR/preset-variables-minimal.yml" || has_errors=true
check_source_files "Dataclasses" "$SCRIPT_DIR/dataclasses.yml" || has_errors=true

# Check spec tag coverage
check_section_coverage "SSZ Objects" "$SCRIPT_DIR/ssz-objects.yml" "ssz_object" "${SSZ_EXCEPTIONS[@]}" || has_errors=true
check_section_coverage "Config Variables (Mainnet)" "$SCRIPT_DIR/config-variables-mainnet.yml" "config_var" "${CONFIG_EXCEPTIONS[@]}" || has_errors=true
check_section_coverage "Config Variables (Minimal)" "$SCRIPT_DIR/config-variables-minimal.yml" "config_var" "${CONFIG_EXCEPTIONS[@]}" || has_errors=true
check_section_coverage "Preset Variables (Mainnet)" "$SCRIPT_DIR/preset-variables-mainnet.yml" "preset_var" "${PRESET_EXCEPTIONS[@]+"${PRESET_EXCEPTIONS[@]}"}" || has_errors=true
check_section_coverage "Preset Variables (Minimal)" "$SCRIPT_DIR/preset-variables-minimal.yml" "preset_var" "${PRESET_EXCEPTIONS[@]+"${PRESET_EXCEPTIONS[@]}"}" || has_errors=true
check_section_coverage "Dataclasses" "$SCRIPT_DIR/dataclasses.yml" "dataclass" "${DATACLASS_EXCEPTIONS[@]+"${DATACLASS_EXCEPTIONS[@]}"}" || has_errors=true

echo "Spec references check complete!"

# Exit with appropriate code
if [[ $has_errors == false ]]; then
    echo "✓ All checks passed!"
    exit 0
else
    echo "✗ Some checks failed"
    exit 1
fi
