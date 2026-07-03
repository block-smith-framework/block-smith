#!/bin/bash
#
# Run block test's Exli and Genie generation in Docker
# Before running this script, run `docker login`
# Usage: bash run_pipeline_in_docker.sh <projects-url-list> <output-dir> [branch=false] [timeout=86400 (sec)]
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

DELETE_REPO=true

MUTATION=true
EXLI_GENERATION="none"
GENIE_GENERATION="evosuite,randoop"
while getopts :m:e:g: opts; do
  case "${opts}" in
    m ) MUTATION="${OPTARG}" ;;
    e ) EXLI_GENERATION="${OPTARG}" ;;
    g ) GENIE_GENERATION="${OPTARG}" ;;
  esac
done
shift $((${OPTIND} - 1))


PROJECTS_LIST=$1
OUTPUT_DIR=$2
BRANCH=$3
TIMEOUT=$4

function check_input() {
  if [[ ! -f ${PROJECTS_LIST} || -z ${OUTPUT_DIR} ]]; then
    echo "Usage: run_pipeline_in_docker.sh <projects-url-list> <output-dir> [branch=false] [timeout=86400 (sec)]"
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

  local id=$(docker run -itd --name bgen-pipeline-${project_name} blocksmith:latest)
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
  
  local exli_config=${EXLI_GENERATION}
  if [[ -n ${config} ]]; then
    exli_config=${config}
    echo "Using Exli config: ${exli_config}"
  fi

  if [[ -z $(echo ${exli_config} | grep "skip") ]]; then
    echo "Running Exli version"
    timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_project.sh -t ${exli_config} -m ${MUTATION} exli ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker-exli.log
  fi

  if [[ -z $(echo ${GENIE_GENERATION} | grep "skip") ]]; then
    echo "Running Genie version"
    timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash run_project.sh -t ${GENIE_GENERATION} -m ${MUTATION} genie ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker-genie.log
  fi

  if [[ ${DELETE_REPO} == true ]]; then
    # To save space, we don't always need it
    docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/exli-output/repo
    docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/genie-output/repo
  fi
  
  docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}
  docker rm -f ${id}
  
  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "$(date) Finished running ${project_name} in ${duration} ms" |& tee -a docker.log
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
