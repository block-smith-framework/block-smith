#!/bin/bash
#
# Run mutation testing in Docker
# Before running this script, run `docker login`
# Usage: bash run_mutation_in_docker.sh [-e <exli-pipeline-output-dir> -g <genie> -k <kex> -j <jdoop> -d <jdart>] <projects-url-list> <mutants-dir> <output-dir> [branch=false] [timeout=86400 (sec)]
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

DELETE_REPO=true

EXLIDEV_PIPELINE_OUTPUT_DIR=""
EXLIEVO_PIPELINE_OUTPUT_DIR=""
EXLIRC_PIPELINE_OUTPUT_DIR=""
GENIEEVO_PIPELINE_OUTPUT_DIR=""
GENIERC_PIPELINE_OUTPUT_DIR=""
KEX_PIPELINE_OUTPUT_DIR=""
JDOOP_PIPELINE_OUTPUT_DIR=""
JDART_PIPELINE_OUTPUT_DIR=""
while getopts :e:g:k:j:d:a:b:c: opts; do
  case "${opts}" in
    e ) EXLIDEV_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    a ) EXLIEVO_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    b ) EXLIRC_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    g ) GENIEEVO_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    c ) GENIERC_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    k ) KEX_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    j ) JDOOP_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
    d ) JDART_PIPELINE_OUTPUT_DIR="${OPTARG}" ;;
  esac
done
shift $((${OPTIND} - 1))

PROJECTS_LIST=$1
MUTANTS_DIR=$2
OUTPUT_DIR=$3
BRANCH=$4
TIMEOUT=$5

function check_input() {
  if [[ ! -f ${PROJECTS_LIST} || ! -d ${MUTANTS_DIR} || -z ${OUTPUT_DIR} ]]; then
    echo "Usage: run_mutation_in_docker.sh [-e <exli-pipeline-output-dir> -g <genie> -k <kex> -j <jdoop> -d <jdart>] <projects-url-list> <mutants-dir> <output-dir> [branch=false] [timeout=86400 (sec)]"
    exit 1
  fi

  if [[ ! ${MUTANTS_DIR} =~ ^/.* ]]; then
    MUTANTS_DIR=${SCRIPT_DIR}/${MUTANTS_DIR}
  fi

  local at_least_one=false
  if [[ -n ${EXLIDEV_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${EXLIDEV_PIPELINE_OUTPUT_DIR} ]]; then
      echo "ExliDev pipeline is missing: ${EXLIDEV_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${EXLIDEV_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        EXLIDEV_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${EXLIDEV_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi
  
  if [[ -n ${EXLIEVO_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${EXLIEVO_PIPELINE_OUTPUT_DIR} ]]; then
      echo "ExliEvo pipeline is missing: ${EXLIEVO_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${EXLIEVO_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        EXLIEVO_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${EXLIEVO_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi
  
  if [[ -n ${EXLIRC_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${EXLIRC_PIPELINE_OUTPUT_DIR} ]]; then
      echo "ExliRandoopClass pipeline is missing: ${EXLIRC_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${EXLIRC_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        EXLIRC_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${EXLIRC_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi

  if [[ -n ${GENIEEVO_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${GENIEEVO_PIPELINE_OUTPUT_DIR} ]]; then
      echo "GenieEvo pipeline is missing: ${GENIEEVO_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${GENIEEVO_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        GENIEEVO_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${GENIEEVO_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi
  
  if [[ -n ${GENIERC_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${GENIERC_PIPELINE_OUTPUT_DIR} ]]; then
      echo "GenieRandoopClass pipeline is missing: ${GENIERC_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${GENIERC_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        GENIERC_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${GENIERC_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi

  if [[ -n ${KEX_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${KEX_PIPELINE_OUTPUT_DIR} ]]; then
      echo "Kex pipeline is missing: ${KEX_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${KEX_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        KEX_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${KEX_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi

  if [[ -n ${JDOOP_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${JDOOP_PIPELINE_OUTPUT_DIR} ]]; then
      echo "JDoop pipeline is missing: ${JDOOP_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${JDOOP_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        JDOOP_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${JDOOP_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi

  if [[ -n ${JDART_PIPELINE_OUTPUT_DIR} ]]; then
    if [[ ! -d ${JDART_PIPELINE_OUTPUT_DIR} ]]; then
      echo "JDart pipeline is missing: ${JDART_PIPELINE_OUTPUT_DIR}"
      exit 1
    else
      if [[ ! ${JDART_PIPELINE_OUTPUT_DIR} =~ ^/.* ]]; then
        JDART_PIPELINE_OUTPUT_DIR=${SCRIPT_DIR}/${JDART_PIPELINE_OUTPUT_DIR}
      fi
      at_least_one=true
    fi
  fi

  if [[ ${at_least_one} == false ]]; then
    echo "Need to check at least one pipeline!"
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


function run_project() {
  local url=$1
  sha=$(echo "${url}" | cut -d '/' -f 7)
  repo=$(echo "${url}" | cut -d '/' -f 4-5 | tr / -)
  local filename=$(echo ${url} | rev | cut -d '/' -f 1 | rev | cut -d '#' -f 1 | cut -d '.' -f 1)
  local line_number=$(echo ${url} | rev | cut -d '#' -f 1 | rev | tr - _)

  local project_name="${repo}-${sha}-${filename}-${line_number}"
  if [[ -n ${DUPLICATE} ]]; then
    project_name="${repo}-${sha}-${filename}-${line_number}-dup${DUPLICATE}"
  fi

  if [[ -d ${OUTPUT_DIR}/${project_name} ]]; then
    echo "$(date) Skip ${project_name} because output already exists" |& tee -a docker.log
    return
  fi

  local start=$(date +%s%3N)
  echo "Running ${project_name} with SHA ${sha}"
  mkdir -p ${OUTPUT_DIR}/${project_name}

  local id=$(docker run -itd --name bmut-${project_name} blocksmith:latest)
  docker exec -w /home/blockgen/block-gen ${id} git pull

  if [[ -n ${BRANCH} && ${BRANCH} != "false" ]]; then
    docker exec -w /home/blockgen/block-gen ${id} git checkout ${BRANCH}
    docker exec -w /home/blockgen/block-gen ${id} git pull
  fi

  if [[ -n ${TIMEOUT} ]]; then
    echo "Setting test timeout to ${TIMEOUT}"
    docker exec -w /home/blockgen/block-gen ${id} sed -i "s/TIMEOUT=.*/TIMEOUT=${TIMEOUT}/" scripts/constants.sh
  fi
  docker exec -w /home/blockgen/block-gen ${id} sed -i "s/IS_LOCAL=.*/IS_LOCAL=false/" scripts/constants.sh

  # In place
  process_technique "INd" ${url} ${project_name} ${id} ${EXLIDEV_PIPELINE_OUTPUT_DIR}
  
  process_technique "INs" ${url} ${project_name} ${id} ${EXLIEVO_PIPELINE_OUTPUT_DIR}
  
  process_technique "INr" ${url} ${project_name} ${id} ${EXLIRC_PIPELINE_OUTPUT_DIR}

  # Extracted
  process_technique "EXTs" ${url} ${project_name} ${id} ${GENIEEVO_PIPELINE_OUTPUT_DIR}
  
  process_technique "EXTr" ${url} ${project_name} ${id} ${GENIERC_PIPELINE_OUTPUT_DIR}

  # Kex
  process_technique "CONs" ${url} ${project_name} ${id} ${KEX_PIPELINE_OUTPUT_DIR}

  # JDoop
  process_technique "CONrc" ${url} ${project_name} ${id} ${JDOOP_PIPELINE_OUTPUT_DIR}

  # JDart
  process_technique "CONc" ${url} ${project_name} ${id} ${JDART_PIPELINE_OUTPUT_DIR}

  docker rm -f ${id}

  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "$(date) Finished getting mutation score for ${project_name} in ${duration} ms" |& tee -a docker.log
}

function process_technique() {
  local technique=$1
  local url=$2
  local project_name=$3
  local id=$4
  local pipeline_output_dir=$5

  local tests_file=""
  local dot_blocktests=""
  local dir_output="genie-output"
  if [[ ${technique} == "exli" || ${technique} == "INd" || ${technique} == "INs" || ${technique} == "INr" ]]; then
    dir_output="exli-output"
  fi

  if [[ -f ${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r2.txt ]]; then
    tests_file=${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r2.txt
  elif [[ -f ${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r1.txt ]]; then
    tests_file=${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r1.txt
  elif [[ -f ${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r0.txt ]]; then
    tests_file=${pipeline_output_dir}/${project_name}/output/${dir_output}/data/blocktest-r0.txt
  fi

  if [[ -d ${pipeline_output_dir}/${project_name}/output/${dir_output}/dot-blocktests ]]; then
    dot_blocktests=${pipeline_output_dir}/${project_name}/output/${dir_output}/dot-blocktests
  fi

  if [[ -n ${tests_file} && -n ${dot_blocktests} && -n ${pipeline_output_dir} ]]; then
    echo "Running ${technique} version"
    docker cp ${dot_blocktests} ${id}:/home/blockgen/dot-blocktests-${technique}
    docker cp ${tests_file} ${id}:/home/blockgen/tests-${technique}.txt
    docker cp ${MUTANTS_DIR}/${project_name}/output/mutation/mutants ${id}:/home/blockgen/mutants-${technique}

    timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_mutation.sh ${url} /home/blockgen/tests-${technique}.txt /home/blockgen/dot-blocktests-${technique} /home/blockgen/mutants-${technique} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker-${technique}.log

    if [[ ${DELETE_REPO} == true ]]; then
      # To save space, we don't always need it
      docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/repo
    fi
    docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}/output-${technique}
  else
    echo "Skipping ${technique}. Missing tests (${tests_file}) or dot-blocktests (${dot_blocktests}) or pipeline path (${pipeline_output_dir})"
  fi
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
