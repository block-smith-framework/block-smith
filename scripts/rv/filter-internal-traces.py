#!/usr/bin/env python3
"""
Remove traces from unique-traces.txt where at least one event has a location
that falls on a BLOCKGEN_FLOW_INTERNAL line in the extracted block test file.

Usage: filter-internal-traces.py <traces_dir> <extracted_tests_dir>
"""
import sys
import os
import re


def get_internal_locations(traces_dir, extracted_tests_dir):
    """Return the set of location IDs whose source line contains BLOCKGEN_FLOW_INTERNAL."""
    locations_file = os.path.join(traces_dir, "locations.txt")
    if not os.path.exists(locations_file):
        return set()

    # Cache file contents to avoid re-reading the same file repeatedly
    file_lines_cache = {}

    internal_locations = set()
    with open(locations_file) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("==="):
                continue
            parts = line.split(" ", 1)
            if len(parts) < 2:
                continue
            loc_id = int(parts[0])
            loc_info = parts[1]
            # Format: ...ClassName.method(FileName.java:<linenum>)<something>
            match = re.search(r'\((\w+\.java):(\d+)\)', loc_info)
            if not match:
                continue
            filename = match.group(1)
            linenum = int(match.group(2))
            test_file = os.path.join(extracted_tests_dir, filename)
            if not os.path.exists(test_file):
                continue
            if test_file not in file_lines_cache:
                with open(test_file) as tf:
                    file_lines_cache[test_file] = tf.readlines()
            lines = file_lines_cache[test_file]
            if linenum - 1 < len(lines) and "BLOCKGEN_FLOW_INTERNAL" in lines[linenum - 1]:
                internal_locations.add(loc_id)

    return internal_locations


def filter_traces(traces_dir, internal_locations):
    """Rewrite unique-traces.txt with traces containing internal locations removed."""
    traces_file = os.path.join(traces_dir, "unique-traces.txt")
    if not os.path.exists(traces_file):
        return

    kept = []
    removed = 0
    with open(traces_file) as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith("==="):
                kept.append(line)
                continue
            # Each event: e<spec>~<loc_id>[x<count>]
            loc_ids = {int(m) for m in re.findall(r'e\d+~(\d+)', stripped)}
            if loc_ids & internal_locations:
                removed += 1
            else:
                kept.append(line)

    with open(traces_file, "w") as f:
        f.writelines(kept)

    print(f"Removed {removed} traces with BLOCKGEN_FLOW_INTERNAL events "
          f"(internal locations: {sorted(internal_locations)})")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: filter-internal-traces.py <traces_dir> <extracted_tests_dir>")
        sys.exit(1)

    traces_dir = sys.argv[1]
    extracted_tests_dir = sys.argv[2]

    internal = get_internal_locations(traces_dir, extracted_tests_dir)
    filter_traces(traces_dir, internal)
