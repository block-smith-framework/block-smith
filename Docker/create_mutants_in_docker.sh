#!/bin/bash
#
# Run mutation testing in Docker
# Before running this script, run `docker login`
# Usage: bash create_mutants_in_docker.sh <projects-url-list> <output-dir> [branch=false] [timeout=86400 (sec)]
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

DELETE_REPO=true

PROJECTS_LIST=$1
OUTPUT_DIR=$2
BRANCH=$3
TIMEOUT=$4
MAX_PARALLEL=${5:-1}

function check_input() {
  if [[ ! -f ${PROJECTS_LIST} || -z ${OUTPUT_DIR} ]]; then
    echo "Usage: run_mutation_in_docker.sh <projects-url-list> <output-dir> [branch=false] [timeout=86400 (sec)]"
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
  
  
  timeout ${TIMEOUT} docker exec -w /home/blockgen/block-gen/scripts -e M2_HOME=/home/blockgen/apache-maven -e MAVEN_HOME=/home/blockgen/apache-maven -e CLASSPATH=/home/blockgen/aspectj-1.9.7/lib/aspectjtools.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjrt.jar:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar: -e PATH=/home/blockgen/.local/bin:/home/blockgen/apache-maven/bin:/usr/lib/jvm/java-8-openjdk/bin:/home/blockgen/aspectj-1.9.7/bin:/home/blockgen/aspectj-1.9.7/lib/aspectjweaver.jar:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ${id} timeout ${TIMEOUT} bash create_mutants.sh ${url} /home/blockgen/block-tests/blocktest /home/blockgen/block-gen/output &> ${OUTPUT_DIR}/${project_name}/docker.log
  
  if [[ ${DELETE_REPO} == true ]]; then
    # To save space, we don't always need it
    docker exec -w /home/blockgen/block-gen ${id} rm -rf /home/blockgen/block-gen/output/repo
  fi
  docker cp ${id}:/home/blockgen/block-gen/output ${OUTPUT_DIR}/${project_name}/output
  
  docker rm -f ${id}
  
  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "$(date) Finished getting mutation score for ${project_name} in ${duration} ms" |& tee -a docker.log
}

function run_all() {
  local start=$(date +%s%3N)
  while true; do
    if [[ ! -s ${PROJECTS_LIST} ]]; then
      echo "${PROJECTS_LIST} is empty..."
      break
    fi

    local project=$(head -n 1 ${PROJECTS_LIST})
    if [[ ${project} == "###" ]]; then
      break
    fi

    if [[ -z $(grep "###" ${PROJECTS_LIST}) ]]; then
      echo "You must end your projects-list file with ###"
      exit 1
    fi

    while [[ $(docker ps --format '{{.Names}}' | grep -c '^bmut-') -ge ${MAX_PARALLEL} ]]; do
      wait -n
    done

    sed -i 1d ${PROJECTS_LIST}
    echo $project >> ${PROJECTS_LIST}
    run_project ${project} $@ &
    sleep 5
  done

  wait
  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "$(date) Finished running all projects in ${duration} ms" |& tee -a docker.log
}

check_input
run_all $@
