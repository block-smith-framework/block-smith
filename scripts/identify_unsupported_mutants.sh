#!/bin/bash
SCRIPT_DIR=$(cd $(dirname $0) && pwd)
MUTANT_DIR=$1
OUTPUT_DIR=$2
TARGET_FRAGMENT=$3

if [[ -n ${TARGET_FRAGMENT} && -n $(grep ${TARGET_FRAGMENT} ${SCRIPT_DIR}/unsupported_mutation.csv) ]]; then
  grep ${TARGET_FRAGMENT} ${SCRIPT_DIR}/unsupported_mutation.csv | awk -F',' 'NF > 2'
  exit 0
fi

cd ${MUTANT_DIR}

for fragment in $(ls); do
  if [[ -n ${TARGET_FRAGMENT} && ${fragment} != ${TARGET_FRAGMENT} ]]; then
    continue
  fi

  unsupported=()
  declare -A seen_replacements  # track (line_number -> set of normalized RHS)

  if [[ ! -d "${fragment}/output/mutation/mutants" ]]; then
    continue
  fi

  for mutant in $(ls ${fragment}/output/mutation/mutants); do
    diff_line=$(grep --text "${mutant}" ${fragment}/output/mutation/universalmutator-log.txt)
    if [[ -n $(echo "${diff_line}" | grep -F " ==> ") ]]; then
      lhs=$(echo "${diff_line}" | awk -F'==>' '{print $1}' | sed -n 's/.*PROCESSING MUTANT: [0-9]*: //p' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
      new_diff_line=$(echo "${diff_line}" | awk -F'==>' '{print $2}' | awk -F'...VALID' '{print $1}' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')

      # Extract source line number for keying deduplication
      src_line=$(echo "${diff_line}" | sed -n 's/.*PROCESSING MUTANT: \([0-9]*\):.*/\1/p')
      # Normalize the RHS by collapsing all leading whitespace
      normalized_rhs=$(echo "${new_diff_line}" | sed 's/^[[:space:]]*//')
      dedup_key="${src_line}:${normalized_rhs}"

      mutant_id=$(echo "${mutant}" | rev | cut -d '.' -f 2 | rev)

      # Check if LHS and RHS differ only in whitespace
      lhs_nows=$(echo "${lhs}" | tr -d '[:space:]')
      rhs_nows=$(echo "${new_diff_line}" | tr -d '[:space:]')

      if [[ -n "${seen_replacements[$dedup_key]}" ]]; then
        unsupported+=("${mutant_id}")
      elif [[ "${lhs_nows}" == "${rhs_nows}" ]]; then
        unsupported+=("${mutant_id}")
      else
        seen_replacements[$dedup_key]=1

        if [[ -n $(echo "${new_diff_line}" | grep -F "throw" | grep -F '""') ]]; then
          unsupported+=("${mutant_id}")
        fi
        if [[ -n $(echo "${new_diff_line}" | grep -F "/*" | grep -F "*/") ]]; then
          commented=$(echo "${new_diff_line}" | awk '{print substr($0, 3, length($0)-4)}')
          if [[ -n $(echo "${commented}" | grep -F "(") ]] && [[ -z $(echo "${commented}" | grep -F "new ") ]]; then
            caller=$(echo "${commented}" | cut -d '(' -f 1 | cut -d '=' -f 2-)
            if [[ -n $(find ${OUTPUT_DIR}/${fragment} -name extract-all.log | xargs grep "Mocking method" | grep -F "${caller}") ]]; then
              unsupported+=("${mutant_id}")
            fi
          fi
          if [[ -n $(echo "${commented}" | grep -F "printStackTrace") ]]; then
            unsupported+=("${mutant_id}")
          fi
        fi
      fi
    else
      mutant_id=$(echo "${mutant}" | rev | cut -d '.' -f 2 | rev)
      unsupported+=("${mutant_id}")
    fi
  done

  unset seen_replacements

  if [[ ${#unsupported[@]} -gt 0 ]]; then
    echo "${fragment},$(printf '%s,' "${unsupported[@]}")"
  fi
done
