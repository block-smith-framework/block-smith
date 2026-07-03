#!/bin/bash
#
# Run all generation strategies in Docker
# Before running this script, run `docker login`
# Usage: bash run_multiple_mutation_pipeline_in_docker.sh [-s <strategies>] [-p <percentage|-1>] <projects-url-list> <output-dir> <mutants-dir> [branch=false] [timeout=86400 (sec)]
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

STRATEGIES="EXTs,CONs,EXTr,CONrc,CONc,INd,INs,INr"
PERCENTAGE="-1"
while getopts :s:p: opts; do
  case "${opts}" in
    s ) STRATEGIES="${OPTARG}" ;;
    p ) PERCENTAGE="${OPTARG}" ;;
  esac
done
shift $((${OPTIND} - 1))


PROJECTS_LIST=$1
OUTPUT_DIR=$2
MUTANTS_DIR=$3
BRANCH=$4
TIMEOUT=$5

function check_input() {
  if [[ ! -f ${PROJECTS_LIST} || -z ${OUTPUT_DIR} ]]; then
    echo "Usage: run_multiple_mutation_pipeline_in_docker.sh [-s <strategies>] [-p <percentage|-1>] <projects-url-list> <output-dir> <mutants-dir> [branch=false] [timeout=86400 (sec)]"
    exit 1
  fi
  
  if [[ ! -d ${MUTANTS_DIR} ]]; then
    echo "Mutants dir is missing"
    exit 1
  fi

  if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
    OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
  fi

  mkdir -p ${OUTPUT_DIR}

  if [[ ! -s ${PROJECTS_LIST} ]]; then
    echo "${PROJECTS_LIST} is empty..."
    exit 0
  fi

  if [[ -z $(grep "###" ${PROJECTS_LIST}) ]]; then
    echo "You must end your projects-list file with ###"
    exit 1
  fi

  if [[ -z ${TIMEOUT} ]]; then
    TIMEOUT=86400
  fi
}

function run_strategy() {
  local strategy=$1
  local project_name=$2
  local url=$3
  
  echo "Running strategy: ${strategy}" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  local technique=""
  local type=""
  if [[ ${strategy} == "EXTs" ]]; then
    technique="evosuite"
    type="genie"
  elif [[ ${strategy} == "CONs" ]]; then
    technique="kex"
    type="genie"
  elif [[ ${strategy} == "EXTr" ]]; then
    technique="randoop-class"
    type="genie"
  elif [[ ${strategy} == "CONrc" ]]; then
    technique="jdoop"
    type="genie"
  elif [[ ${strategy} == "CONc" ]]; then
    technique="jdart"
    type="genie"
  elif [[ ${strategy} == "INd" ]]; then
    technique="developer"
    type="exli"
  elif [[ ${strategy} == "INs" ]]; then
    technique="evosuite"
    type="exli"
  elif [[ ${strategy} == "INr" ]]; then
    technique="randoop-class"
    type="exli"
  fi
  
  local id=$(docker run -itd --name ${strategy}-pipeline-${project_name} blocksmith:latest)
  setup_container ${id}
  
  docker cp ${OUTPUT_DIR}/${project_name}/setup ${id}:/home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log

  # Java looks for mutants at output/<type>-output/mutation/{mutants,compilable-mutants.txt}
  local mutation_dir="/home/blockgen/block-gen/output/${type}-output/mutation"
  docker exec -w /home/blockgen/block-gen ${id} mkdir -p ${mutation_dir} &>> ${OUTPUT_DIR}/${project_name}/docker.log

  local do_mutation="false"
  if [[ -d "${MUTANTS_DIR}/${project_name}/output/mutation/mutants" && \
        $(ls "${MUTANTS_DIR}/${project_name}/output/mutation/mutants" | wc -l) -gt 0 && \
        -s "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt" ]]; then
    do_mutation="true"
    docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/mutants ${id}:${mutation_dir} &>> ${OUTPUT_DIR}/${project_name}/docker.log
    docker cp ${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt ${id}:${mutation_dir}/compilable-mutants.txt &>> ${OUTPUT_DIR}/${project_name}/docker.log
    docker exec -u root ${id} chown -R blockgen:blockgen ${mutation_dir} &>> ${OUTPUT_DIR}/${project_name}/docker.log
  else
    echo "Mutants directory or compilable-mutants.txt missing or empty, skipping mutation for ${strategy}" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  fi

  local strategy_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e MAVEN_REPO=/home/blockgen/block-gen/output/repo -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_project.sh -t ${technique} -m ${do_mutation} ${type} ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker-${strategy}.log
  local strategy_end=$(date +%s%3N)
  local strategy_duration=$((strategy_end - strategy_start))
  echo "BLOCKGEN TIME - strategy@${strategy} - ${strategy_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${id}:/home/blockgen/block-gen/output/${type}-output ${OUTPUT_DIR}/${project_name}/output-${strategy} &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker rm -f ${id} &>> ${OUTPUT_DIR}/${project_name}/docker.log
}

function perform_reduction() {
  local project_name=$1
  local url=$2
  
  echo "Running reduction" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  if [[ ! -d ${MUTANTS_DIR}/${project_name}/output/mutation/mutants || $(ls ${MUTANTS_DIR}/${project_name}/output/mutation/mutants | wc -l) -eq 0 || ! -s ${MUTANTS_DIR}/${project_name}/output/mutation/compilable-mutants.txt ]]; then
    echo "No mutants available, merging strategy outputs directly into reduction directory" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    mkdir -p ${OUTPUT_DIR}/${project_name}/reduction/data
    mkdir -p ${OUTPUT_DIR}/${project_name}/reduction/dot-blocktests/serialized-data
    IFS=',' read -ra all_strategies <<< ${STRATEGIES}
    for strategy in "${all_strategies[@]}"; do
      local r1_file="${OUTPUT_DIR}/${project_name}/output-${strategy}/data/blocktest-r1.txt"
      if [[ -f "${r1_file}" ]]; then
        sed -e "s/@\([0-9]*\.\(xml\|txt\|java\)\)/@${strategy}\/\1/g" \
            -e "s/AUTO_GEN_\([0-9]*\)/AUTO_GEN_\1_${strategy}/g" \
            "${r1_file}" >> ${OUTPUT_DIR}/${project_name}/reduction/data/blocktest-r2.txt
      fi
      if [[ -d ${OUTPUT_DIR}/${project_name}/output-${strategy}/dot-blocktests/serialized-data ]]; then
        cp -r ${OUTPUT_DIR}/${project_name}/output-${strategy}/dot-blocktests/serialized-data ${OUTPUT_DIR}/${project_name}/reduction/dot-blocktests/serialized-data/${strategy}
      fi
    done
    return
  fi
  
  mkdir -p ${OUTPUT_DIR}/${project_name}/.r0_files
  mkdir -p ${OUTPUT_DIR}/${project_name}/.r1_files
  mkdir -p ${OUTPUT_DIR}/${project_name}/.serialized-data
  
  IFS=',' read -ra all_strategies <<< ${STRATEGIES}
  for strategy in "${all_strategies[@]}"; do
    if [[ -f ${OUTPUT_DIR}/${project_name}/output-${strategy}/data/blocktest-r0.txt ]]; then
      cp ${OUTPUT_DIR}/${project_name}/output-${strategy}/data/blocktest-r0.txt ${OUTPUT_DIR}/${project_name}/.r0_files/${strategy}.txt
    fi
    if [[ -f ${OUTPUT_DIR}/${project_name}/output-${strategy}/data/blocktest-r1.txt ]]; then
      cp ${OUTPUT_DIR}/${project_name}/output-${strategy}/data/blocktest-r1.txt ${OUTPUT_DIR}/${project_name}/.r1_files/${strategy}.txt
    fi
    if [[ -d ${OUTPUT_DIR}/${project_name}/output-${strategy}/dot-blocktests/serialized-data ]]; then
      cp -r ${OUTPUT_DIR}/${project_name}/output-${strategy}/dot-blocktests/serialized-data ${OUTPUT_DIR}/${project_name}/.serialized-data/${strategy}
    fi
  done
  
  if [[ $(ls ${OUTPUT_DIR}/${project_name}/.r0_files | wc -l) -eq 0 ]]; then
    echo "No strategy generated block tests" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    return
  fi
  
  local id=$(docker run -itd --name reduce-pipeline-${project_name} blocksmith:latest)
  setup_container ${id}
  
  docker cp ${OUTPUT_DIR}/${project_name}/setup ${id}:/home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker exec -w /home/blockgen/block-gen ${id} mkdir -p /home/blockgen/block-gen/mutation &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/mutants ${id}:/home/blockgen/block-gen/mutation/mutants &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/compilable-mutants.txt ${id}:/home/blockgen/block-gen/mutation/compilable-mutants.txt &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/mutation &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${OUTPUT_DIR}/${project_name}/.r0_files ${id}:/home/blockgen/block-gen/r0_files &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/r0_files &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${OUTPUT_DIR}/${project_name}/.r1_files ${id}:/home/blockgen/block-gen/r1_files &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/r1_files &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${OUTPUT_DIR}/${project_name}/.serialized-data ${id}:/home/blockgen/block-gen/serialized-data &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/serialized-data &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  local reduction_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e MUTATION_GREEDY=true -e MAVEN_REPO=/home/blockgen/block-gen/output/repo -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_reduction.sh ${url} /home/blockgen/block-gen/r0_files /home/blockgen/block-gen/r1_files /home/blockgen/block-gen/serialized-data /home/blockgen/block-gen/mutation /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/reduce.log
  local reduction_end=$(date +%s%3N)
  local reduction_duration=$((reduction_end - reduction_start))
  echo "BLOCKGEN TIME - reduction - ${reduction_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/repo /home/blockgen/block-gen/output/project &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}/reduction &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker rm -f ${id} &>> ${OUTPUT_DIR}/${project_name}/docker.log
}

function create_mutants() {
  local project_name=$1
  local url=$2
  
  local id=$(docker run -itd --name main-pipeline-${project_name} blocksmith:latest)
  setup_container ${id}
  
  docker cp ${OUTPUT_DIR}/${project_name}/setup ${id}:/home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  local mutant_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash create_mutants.sh ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/mutants.log
  local mutant_end=$(date +%s%3N)
  local mutant_duration=$((mutant_end - mutant_start))
  echo "BLOCKGEN TIME - mutants - ${mutant_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  mkdir -p ${MUTANTS_DIR}/${project_name}
  
  docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/repo /home/blockgen/block-gen/output/project &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${id}:/home/blockgen/block-gen/output ${MUTANTS_DIR}/${project_name}/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  if [[ $? -ne 0 ]]; then
    echo "Unable to generate mutants" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  elif [[ ! -d ${MUTANTS_DIR}/${project_name}/output/mutation/mutants || $(ls ${MUTANTS_DIR}/${project_name}/output/mutation/mutants | wc -l) -eq 0 ]]; then
    echo "Unable to generate mutants" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  fi
  docker rm -f ${id} &>> ${OUTPUT_DIR}/${project_name}/docker.log
}

function setup_container() {
  local id=$1
  docker exec -w /home/blockgen/block-gen ${id} git pull &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  if [[ -n ${BRANCH} && ${BRANCH} != "false" ]]; then
    docker exec -w /home/blockgen/block-gen ${id} git checkout ${BRANCH} &>> ${OUTPUT_DIR}/${project_name}/docker.log
    docker exec -w /home/blockgen/block-gen ${id} git pull &>> ${OUTPUT_DIR}/${project_name}/docker.log
  fi
  
  if [[ -n ${TIMEOUT} ]]; then
    docker exec -w /home/blockgen/block-gen ${id} sed -i "s/TIMEOUT=.*/TIMEOUT=${TIMEOUT}/" scripts/constants.sh &>> ${OUTPUT_DIR}/${project_name}/docker.log
  fi
  docker exec -w /home/blockgen/block-gen ${id} sed -i "s/IS_LOCAL=.*/IS_LOCAL=false/" scripts/constants.sh &>> ${OUTPUT_DIR}/${project_name}/docker.log
}

function run_project() {
  local url=$1
  if [[ "${url}" == *"|"* ]]; then
    url=$(echo "${url}" | cut -d '|' -f 1)
  fi
  local sha=$(echo "${url}" | cut -d '/' -f 7)
  local repo=$(echo "${url}" | cut -d '/' -f 4-5 | tr / -)
  local filename=$(echo ${url} | rev | cut -d '/' -f 1 | rev | cut -d '#' -f 1 | cut -d '.' -f 1)
  local line_number=$(echo ${url} | rev | cut -d '#' -f 1 | rev | tr - _)

  local project_name="${repo}-${sha}-${filename}-${line_number}"
  
  if [[ -d ${OUTPUT_DIR}/${project_name} ]]; then
    echo "$(date) Skip ${project_name} because output already exists" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    return
  fi
  
  mkdir -p ${OUTPUT_DIR}/${project_name}

  local start=$(date +%s%3N)
  echo "Running ${project_name} with SHA ${sha}" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log

  local id=$(docker run -itd --name main-pipeline-${project_name} blocksmith:latest)
  setup_container ${id}
  
  local setup_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} bash prepare_project.sh ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/setup.log
  local setup_status=$?
  local setup_end=$(date +%s%3N)
  local setup_duration=$((setup_end - setup_start))
  echo "BLOCKGEN TIME - setup - ${setup_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}/setup &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker rm -f ${id} &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  if [[ ${setup_status} -ne 0 ]]; then
    echo "Setup failed" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    return
  fi
  
  if [[ ! -d ${MUTANTS_DIR}/${project_name}/output/mutation/mutants ]]; then
    echo "Mutants directory is missing" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    create_mutants ${project_name} ${url}
  fi

  local compilable_src="${MUTANTS_DIR}/${project_name}/output/mutation/compilable-mutants.txt"

  # Remove unsupported mutants every time (not inside create_mutants, so it also runs
  # when mutants already existed). MUTANTS_DIR is left untouched; the filtered list
  # is written only into OUTPUT_DIR.
  if [[ -f "${compilable_src}" ]]; then
    local unsupported_line
    unsupported_line=$(bash "${SCRIPT_DIR}/../scripts/identify_unsupported_mutants.sh" "${MUTANTS_DIR}" "${OUTPUT_DIR}" "${project_name}")
    if [[ -n "${unsupported_line}" ]]; then
      local temp_compilable unsupported_ids_file
      temp_compilable=$(mktemp)
      unsupported_ids_file=$(mktemp)
      echo "${unsupported_line}" | cut -d',' -f2- | tr ',' '\n' | grep -v '^$' > "${unsupported_ids_file}"
      local unsupported_count
      unsupported_count=$(wc -l < "${unsupported_ids_file}")
      grep -vxFf "${unsupported_ids_file}" "${compilable_src}" > "${temp_compilable}"
      echo "Removed ${unsupported_count} unsupported mutants from compilable-mutants.txt: $(paste -sd',' "${unsupported_ids_file}")" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
      rm -f "${unsupported_ids_file}"
      mv "${temp_compilable}" "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt"
    fi
  fi

  # Seed .compilable-mutants.txt if the unsupported-mutants step didn't create it
  # (i.e., no unsupported mutants were found).
  if [[ ! -f "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt" ]]; then
    if [[ -s "${compilable_src}" ]]; then
      cp "${compilable_src}" "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt"
    else
      echo "compilable-mutants.txt missing or empty, cannot seed remaining mutants list" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    fi
  fi

  # Record total mutant count once for percentage-based stopping criterion.
  if [[ -f "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt" && \
        ! -f "${OUTPUT_DIR}/${project_name}/.total-mutants" ]]; then
    grep -cE '^[0-9]+$' "${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt" \
      > "${OUTPUT_DIR}/${project_name}/.total-mutants" 2>/dev/null || echo 0 \
      > "${OUTPUT_DIR}/${project_name}/.total-mutants"
  fi

  local run_start=$(date +%s%3N)
  local strategy_index=0
  IFS=',' read -ra all_strategies <<< ${STRATEGIES}
  for strategy in "${all_strategies[@]}"; do
    run_strategy ${strategy} ${project_name} ${url}
    strategy_index=$((strategy_index + 1))
    is_criterion_satisfied ${strategy} ${project_name} ${url}
    local result=$?
    if [[ ${result} -eq 0 ]]; then
      # All mutants killed — stop immediately
      echo "satisfied requirement!" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
      break
    elif [[ ${result} -eq 2 && ${strategy_index} -ge 2 ]]; then
      # Percentage criterion met — only stop after at least 2 strategies
      echo "satisfied requirement!" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
      break
    fi
  done
  
  local run_end=$(date +%s%3N)
  local run_duration=$((run_end - run_start))
  echo "BLOCKGEN TIME - run_all - ${run_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  perform_reduction ${project_name} ${url}

  local end=$(date +%s%3N)
  local both_duration=$((end - run_start))
  local duration=$((end - start))
  echo "BLOCKGEN TIME - run_all+reduction - ${both_duration} ms" |& tee -a  ${OUTPUT_DIR}/${project_name}/docker.log
  echo "BLOCKGEN TIME - e2e - ${duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
}

function is_criterion_satisfied() {
  local strategy=$1
  local project_name=$2
  local url=$3

  local log_file="${OUTPUT_DIR}/${project_name}/docker-${strategy}.log"
  local compilable_file="${OUTPUT_DIR}/${project_name}/.compilable-mutants.txt"

  # No mutants means mutation testing is impossible, so the criterion is
  # vacuously satisfied: run one strategy (with -m false) and stop.
  if [[ ! -d "${MUTANTS_DIR}/${project_name}/output/mutation/mutants" || \
        $(ls "${MUTANTS_DIR}/${project_name}/output/mutation/mutants" | wc -l) -eq 0 ]]; then
    return 0
  fi

  if [[ ! -f ${compilable_file} ]]; then
    return 0
  fi

  # Collect all killed mutant IDs from lines like "R2 killed mutants: [0, 17, 18, ...]"
  local killed_mutants
  killed_mutants=$(grep 'killed mutants:' "${log_file}" 2>/dev/null \
    | sed 's/.*\[//; s/\].*//' \
    | tr ',' '\n' \
    | tr -d ' ' \
    | grep -E '^[0-9]+$' \
    | sort -u)

  # Read remaining mutant IDs from before this strategy ran (one per line)
  local all_mutants
  all_mutants=$(grep -E '^[0-9]+$' "${compilable_file}" | sort -u)

  # Compute remaining (unkilled) mutants and persist them
  local remaining
  remaining=$(comm -23 <(echo "${all_mutants}") <(echo "${killed_mutants}"))

  if [[ -z ${remaining} ]]; then
    > "${compilable_file}"
    echo "Strategy ${strategy}: killed all remaining mutants" |& tee -a "${OUTPUT_DIR}/${project_name}/docker.log"
    return 0
  fi
  echo "${remaining}" > "${compilable_file}"

  # Kill-all mode: not satisfied until remaining is empty
  if [[ "${PERCENTAGE}" == "-1" ]]; then
    return 1
  fi

  # Percentage-based mode: stop if new_kills / total_mutants * 100 <= PERCENTAGE
  local y
  y=$(cat "${OUTPUT_DIR}/${project_name}/.total-mutants" 2>/dev/null || echo 0)
  if [[ "${y}" -eq 0 ]]; then
    return 0
  fi

  local x pct
  x=$(echo "${killed_mutants}" | grep -E '^[0-9]+$' | wc -l)
  pct=$(awk "BEGIN { printf \"%.2f\", ${x} * 100 / ${y} }")
  echo "Strategy ${strategy}: killed ${x}/${y} new mutants (${pct}%, threshold ${PERCENTAGE}%)" \
    |& tee -a "${OUTPUT_DIR}/${project_name}/docker.log"

  if awk "BEGIN { exit (${x} * 100 <= ${PERCENTAGE} * ${y}) ? 0 : 1 }"; then
    return 2
  fi
  return 1
}

function run_all() {
  local start=$(date +%s%3N)
  while true; do
    if [[ ! -s ${PROJECTS_LIST} ]]; then
      echo "${PROJECTS_LIST} is empty..."
      exit 0
    fi

    local project=$(head -n 1 ${PROJECTS_LIST})
    if [[ ${project} == "###" ]]; then
      local end=$(date +%s%3N)
      local duration=$((end - start))
      echo "$(date) Finished running all projects in ${duration} ms" |& tee -a docker.log

      exit 0
    fi

    if [[ -z $(grep "###" ${PROJECTS_LIST}) ]]; then
      echo "You must end your projects-list file with ###"
      exit 1
    fi

    sed -i 1d ${PROJECTS_LIST}
    echo $project >> ${PROJECTS_LIST}
    run_project ${project} $@
  done

}

check_input
run_all $@
