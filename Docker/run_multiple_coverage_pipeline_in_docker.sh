#!/bin/bash
#
# Run all generation strategies in Docker
# Before running this script, run `docker login`
# Usage: bash run_multiple_coverage_pipeline_in_docker.sh [-s <strategies>] <projects-url-list> <output-dir> <mutants-dir> [branch=false] [timeout=86400 (sec)]
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

DELETE_REPO=true

STRATEGIES="EXTs,CONs,EXTr,CONrc,CONc,INd,INs,INr"
while getopts :s:m: opts; do
  case "${opts}" in
    s ) STRATEGIES="${OPTARG}" ;;
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
    echo "Usage: run_multiple_coverage_pipeline_in_docker.sh [-s <strategies>] <projects-url-list> <output-dir> <mutants-dir> [branch=false] [timeout=86400 (sec)]"
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

  local strategy_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e MAVEN_REPO=/home/blockgen/block-gen/output/repo -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_project.sh -t ${technique} -m false ${type} ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker-${strategy}.log
  local strategy_end=$(date +%s%3N)
  local strategy_duration=$((strategy_end - strategy_start))
  echo "BLOCKGEN TIME - strategy@${strategy} - ${strategy_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp ${id}:/home/blockgen/block-gen/output/${type}-output ${OUTPUT_DIR}/${project_name}/output-${strategy} &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker rm -f ${id} &>> ${OUTPUT_DIR}/${project_name}/docker.log
}

function perform_reduction() {
  local project_name=$1
  local url=$2
  
  local reduction_start=$(date +%s%3N)
  
  mkdir -p ${OUTPUT_DIR}/${project_name}/reduction
  mkdir -p ${OUTPUT_DIR}/${project_name}/reduction/dot-blocktests/serialized-data

  echo "Running reduction" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log

  # The idea here is to group results by strategy kind then find the minimum
  # Exli-based and Smack-based approaches have different line range
  local techniques=(
    "EXTs:coverage-genie.csv"
    "CONs:coverage-genie.csv"
    "EXTr:coverage-genie.csv"
    "CONrc:coverage-genie.csv"
    "CONc:coverage-genie.csv"
    "INd:coverage-exli.csv"
    "INs:coverage-exli.csv"
    "INr:coverage-exli.csv"
  )

  for entry in "${techniques[@]}"; do
    local technique=${entry%%:*}
    local csv=${entry##*:}
    local file=${OUTPUT_DIR}/${project_name}/output-${technique}/dot-blocktests/test-to-covered-lines.txt
    if [[ -f ${file} ]]; then
      local failing=""
      local log_file="${OUTPUT_DIR}/${project_name}/docker-${technique}.log"
      if [[ -f "${log_file}" ]]; then
        failing=$(grep "There are .* failing tests in r0:" "${log_file}" \
          | sed 's/.*\[//' | sed 's/\].*//' | tr -d ' ' | tr ',' '\n' | grep -v '^$' | sort -u | tr '\n' ',')
      fi
      if [[ -n "${failing}" ]]; then
        echo "Skipping failing tests for ${technique}: ${failing}" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
        awk -F',' -v val="${technique}" -v failing="${failing}" \
          'BEGIN{n=split(failing,f,","); for(i=1;i<=n;i++) skip[f[i]]=1}
           !skip[$1]{print val "," $0}' \
          "${file}" >> ${OUTPUT_DIR}/${project_name}/reduction/${csv}
      else
        awk -v val="${technique}" '{print val "," $0}' "${file}" >> ${OUTPUT_DIR}/${project_name}/reduction/${csv}
      fi
      if [[ -d ${OUTPUT_DIR}/${project_name}/output-${technique}/dot-blocktests/serialized-data ]]; then
        cp -r ${OUTPUT_DIR}/${project_name}/output-${technique}/dot-blocktests/serialized-data ${OUTPUT_DIR}/${project_name}/reduction/dot-blocktests/serialized-data/${technique}
      fi
    fi
  done

  local csvs=("coverage-genie.csv" "coverage-exli.csv")
  for csv in "${csvs[@]}"; do
    local csv_path=${OUTPUT_DIR}/${project_name}/reduction/${csv}
    local unique_csv_path=${OUTPUT_DIR}/${project_name}/reduction/${csv%.csv}-unique.csv
    if [[ -f ${csv_path} ]]; then
      awk -F',' '{
      new=0
      for(i=3; i<=NF; i++) {
        if (!seen[$i]++) new=1
      }
      if (new) print
    }' ${csv_path} > ${unique_csv_path}

      while IFS=',' read -r technique_name test_id _; do
        grep -F "\"${test_id}\"" "${OUTPUT_DIR}/${project_name}/output-${technique_name}/data/blocktest-r0.txt" \
        | sed -e "s/@\([0-9]*\.\(xml\|txt\|java\)\)/@${technique_name}\/\1/g" \
        -e "s/AUTO_GEN_\([0-9]*\)/AUTO_GEN_\1_${technique_name}/g" \
        >> "${OUTPUT_DIR}/${project_name}/reduction/blocktest-r2.txt"
      done < "${unique_csv_path}"
    fi
  done
  
  local reduction_end=$(date +%s%3N)
  local reduction_duration=$((reduction_end - reduction_start))
  echo "BLOCKGEN TIME - reduction - ${reduction_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
}

function mutation_testing() {
  local project_name=$1
  local url=$2
  
  echo "Running mutation_testing" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  if [[ ! -d ${MUTANTS_DIR}/${project_name}/output/mutation/mutants || $(ls ${MUTANTS_DIR}/${project_name}/output/mutation/mutants | wc -l) -eq 0 || ! -s ${MUTANTS_DIR}/${project_name}/output/mutation/compilable-mutants.txt ]]; then
    echo "No mutants available" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    return
  fi

  if [[ ! -s "${OUTPUT_DIR}/${project_name}/reduction/blocktest-r2.txt" ]]; then
    echo "No strategy generated block tests" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
    return
  fi
  
  local id=$(docker run -itd --name mutation-pipeline-${project_name} blocksmith:latest)
  setup_container ${id}
  
  docker cp ${OUTPUT_DIR}/${project_name}/setup ${id}:/home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/output &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker exec -w /home/blockgen/block-gen ${id} mkdir -p /home/blockgen/block-gen/mutation &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/mutants ${id}:/home/blockgen/block-gen/mutation/mutants &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/compilable-mutants.txt ${id}:/home/blockgen/block-gen/mutation/compilable-mutants.txt &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/mutation &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker cp "${OUTPUT_DIR}/${project_name}/reduction/blocktest-r2.txt" ${id}:/home/blockgen/block-gen/blocktest-r0.txt &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown blockgen:blockgen /home/blockgen/block-gen/blocktest-r0.txt &>> ${OUTPUT_DIR}/${project_name}/docker.log

  docker cp ${OUTPUT_DIR}/${project_name}/reduction/dot-blocktests/serialized-data ${id}:/home/blockgen/block-gen/serialized-data &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker exec -u root ${id} chown -R blockgen:blockgen /home/blockgen/block-gen/serialized-data &>> ${OUTPUT_DIR}/${project_name}/docker.log
  
  local reduction_start=$(date +%s%3N)
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e MUTATION_GREEDY=true -e MAVEN_REPO=/home/blockgen/block-gen/output/repo -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_reduction.sh ${url} /home/blockgen/block-gen/blocktest-r0.txt /home/blockgen/block-gen/blocktest-r0.txt /home/blockgen/block-gen/serialized-data /home/blockgen/block-gen/mutation /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/mutation.log
  local reduction_end=$(date +%s%3N)
  local reduction_duration=$((reduction_end - reduction_start))
  echo "BLOCKGEN TIME - mutation_testing - ${reduction_duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
  
  docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/repo /home/blockgen/block-gen/output/project &>> ${OUTPUT_DIR}/${project_name}/docker.log
  docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}/mutation &>> ${OUTPUT_DIR}/${project_name}/docker.log
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
  local config=""
  if [[ "${url}" == *"|"* ]]; then
    config=$(echo "${url}" | cut -d '|' -f 2)
    url=$(echo "${url}" | cut -d '|' -f 1)
  fi
  sha=$(echo "${url}" | cut -d '/' -f 7)
  repo=$(echo "${url}" | cut -d '/' -f 4-5 | tr / -)
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
  
  local run_start=$(date +%s%3N)
  satisfied=false
  IFS=',' read -ra all_strategies <<< ${STRATEGIES}
  for strategy in "${all_strategies[@]}"; do
    run_strategy ${strategy} ${project_name} ${url}
    if is_criterion_satisfied ${strategy} ${project_name} ${url}; then
      satisfied=true
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
  
  echo "BLOCKGEN TIME - run_all+reduction - ${both_duration} ms" |& tee -a  ${OUTPUT_DIR}/${project_name}/docker.log
  
  mutation_testing ${project_name} ${url}
  
  end=$(date +%s%3N)
  local duration=$((end - start))
  echo "BLOCKGEN TIME - e2e - ${duration} ms" |& tee -a ${OUTPUT_DIR}/${project_name}/docker.log
}

function is_criterion_satisfied() {
  local strategy=$1
  local project_name=$2

  # Genie and exli have different line ranges, so check within each group separately
  local group_strategies=()
  if [[ ${strategy} == exli* ]]; then
    group_strategies=("INd" "INs" "INr")
  else
    group_strategies=("EXTs" "CONs" "EXTr" "CONrc" "CONc")
  fi

  local tmp_dir
  tmp_dir=$(mktemp -d)

  local uncovered_files=()
  local any_covered=false

  for s in "${group_strategies[@]}"; do
    local dir="${OUTPUT_DIR}/${project_name}/output-${s}/dot-blocktests"
    if [[ ! -f "${dir}/uncovered-lines.txt" || ! -f "${dir}/test-to-covered-lines.txt" ]]; then
      continue
    fi

    # Parse failing tests for this strategy (same logic as perform_reduction)
    local log_file="${OUTPUT_DIR}/${project_name}/docker-${s}.log"
    local failing=""
    if [[ -f "${log_file}" ]]; then
      failing=$(grep "There are .* failing tests in r0:" "${log_file}" \
        | sed 's/.*\[//' | sed 's/\].*//' | tr -d ' ' | tr ',' '\n' | grep -v '^$' | sort -u | tr '\n' ',')
    fi

    # Compute lines covered by passing tests only
    local pass_cov="${tmp_dir}/${s}_pass.txt"
    local all_cov="${tmp_dir}/${s}_all.txt"
    local eff_uncov="${tmp_dir}/${s}_eff.txt"

    if [[ -n "${failing}" ]]; then
      awk -F',' -v failing="${failing}" \
        'BEGIN{n=split(failing,f,","); for(i=1;i<=n;i++) skip[f[i]]=1}
         !skip[$1]{for(i=2;i<=NF;i++) print $i}' \
        "${dir}/test-to-covered-lines.txt" | sort -u > "${pass_cov}"

      awk -F',' '{for(i=2;i<=NF;i++) print $i}' \
        "${dir}/test-to-covered-lines.txt" | sort -u > "${all_cov}"

      # Effective uncovered = original uncovered + lines only failing tests cover
      {
        cat "${dir}/uncovered-lines.txt"
        comm -23 "${all_cov}" "${pass_cov}"
      } | sort -u > "${eff_uncov}"
    else
      cp "${dir}/uncovered-lines.txt" "${eff_uncov}"
      awk -F',' '{for(i=2;i<=NF;i++) print $i}' \
        "${dir}/test-to-covered-lines.txt" | sort -u > "${pass_cov}"
    fi

    if [[ -s "${pass_cov}" ]]; then
      any_covered=true
    fi

    uncovered_files+=("${eff_uncov}")
  done

  if [[ ${#uncovered_files[@]} -eq 0 || ${any_covered} == false ]]; then
    rm -rf "${tmp_dir}"
    return 1
  fi

  # A line is still uncovered collectively only if it appears in ALL strategies'
  # effective uncovered files (intersection). Satisfied when that intersection is empty.
  local n=${#uncovered_files[@]}
  local remaining
  remaining=$(sort "${uncovered_files[@]}" | uniq -c | awk -v n="$n" '$1 >= n {print $2}')
  rm -rf "${tmp_dir}"

  if [[ -z "${remaining}" ]]; then
    return 0
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
