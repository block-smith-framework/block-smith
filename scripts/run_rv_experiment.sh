#!/bin/bash
#
# Run steps 2 and 3 of the RV experiment inside Docker
# Usage: bash run_rv-blockgen_experiment.sh [-C] [-S] <project-url> <output-dir> <libs-dir> <coverage-checker-jar> <strategies> <blocktest-dir>
#   -C  Skip step 2 (coverage checker)
#   -S  Use no-track-stats-agent.jar instead of track-agent.jar. This agent
#       does not collect traces, so trace processing/checking is skipped too.
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)
source ${SCRIPT_DIR}/constants.sh

SKIP_COVERAGE=0
USE_STATS_AGENT=0
while getopts :CS opts; do
  case "${opts}" in
    C ) SKIP_COVERAGE=1 ;;
    S ) USE_STATS_AGENT=1 ;;
  esac
done
shift $((${OPTIND} - 1))

PROJECT_URL=$1
OUTPUT_DIR=$2
LIBS_DIR=$3
COVERAGE_CHECKER_JAR=$4
STRATEGIES=$5
BLOCKTEST_DIR=$6

install() {
  if [[ -z ${MAVEN_REPO} ]]; then
    export MAVEN_REPO=${OUTPUT_DIR}/repo
  fi
  export MAVEN_SETTINGS="true"
  export MAVEN_SETTINGS_ONLY="1"
  local extension_jar=${SCRIPT_DIR}/../extension/target/blockgen-extension-1.0.jar
  if [[ ! -f ${extension_jar} ]]; then
    echo "Extension is missing... Building extension..."
    pushd ${SCRIPT_DIR}/../extension &> /dev/null
    mvn -Dmaven.repo.local=${MAVEN_REPO} package >> ${OUTPUT_DIR}/setup.log
    if [[ $? -ne 0 ]]; then
      echo "Step install extension failed"
      popd &> /dev/null
      exit 1
    fi
    popd &> /dev/null
  fi
  
  echo "At repository ${MAVEN_REPO}"
  
  echo "Setting up BlockTest"
  pushd ${BLOCKTEST_DIR} &> /dev/null
  mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} install >> ${OUTPUT_DIR}/setup.log
  popd &> /dev/null
  
  echo "Setting up BlockGen"
  pushd ${SCRIPT_DIR}/../blockgen &> /dev/null
  mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} install >> ${OUTPUT_DIR}/setup.log
  popd &> /dev/null
  
  unset MAVEN_SETTINGS_ONLY
}

# URL components: https://github.com/<owner>/<repo>/blob/<sha>/<path>#L<start>-L<end>
function get_repo_url()   { echo "${1%%|*}" | cut -d '/' -f 1-5; }
function get_sha()        { echo "${1%%|*}" | cut -d '/' -f 7; }
function get_file_path()  { echo "${1%%|*}" | cut -d '/' -f 8- | cut -d '#' -f 1; }
function get_start_line() { echo "${1%%|*}" | cut -d '#' -f 2 | cut -d '-' -f 1 | tr -d 'L'; }
function get_end_line()   { echo "${1%%|*}" | cut -d '#' -f 2 | cut -d '-' -f 2 | tr -d 'L'; }

# ${OUTPUT_DIR}/coverage/{evosuite-tests,randoop-tests} is where these land
# either way: run_coverage_checker() below produces them there itself
# (EvoSuiteComponent/RandoopComponent's saveGenerated(), via the coverage
# checker jar - outputDir param is ${OUTPUT_DIR}/coverage, one level), or the
# host-side wrapper (rv_exp_in_g2.sh / run_rv_experiment_in_docker.sh) stages
# precomputed suites fetched from other hosts into this same local path
# before this script ever runs (it has no access to those hosts' SSH keys).
# Either way, skip regenerating if already present.
function precomputed_coverage_tests_exist() {
  [[ -d ${OUTPUT_DIR}/coverage/evosuite-tests || -d ${OUTPUT_DIR}/coverage/randoop-tests ]]
}

EVOSUITE_JAR="${SCRIPT_DIR}/../libs/evosuite-master-1.2.1-SNAPSHOT.jar"
JUNIT_STANDALONE_JAR="${SCRIPT_DIR}/../libs/junit-platform-console-standalone-1.12.0.jar"

# Resolve (or reuse the cached) classpath for project_dir's dependencies.
# Mirrors experiments/coverage-checker's Utils.buildDeps/getDepsContent (same
# deps.txt path/convention, so this reuses whatever run_rv_blockgen's blockgen
# jar already built); -Dmaven.ext.class.path is added on top so resolution
# goes through the same nexus-repo injection as everything else here.
function get_project_classpath() {
  local project_dir=$1
  local extension_jar="${SCRIPT_DIR}/../extension/target/blockgen-extension-1.0.jar"
  local deps_file="${project_dir}/deps.txt"
  if [[ ! -s ${deps_file} ]]; then
    pushd ${project_dir} &> /dev/null
    mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} \
      dependency:build-classpath -Dmdep.outputFile=deps.txt &>> ${OUTPUT_DIR}/setup.log
    if [[ -f ${deps_file} ]]; then
      # Not every project keeps the Maven default - some override
      # <build><outputDirectory> - so don't assume target/classes; ask Maven
      # for the real configured directory when the default doesn't exist.
      local classes_dir="${project_dir}/target/classes"
      if [[ ! -d ${classes_dir} ]]; then
        classes_dir=$(mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} \
          help:evaluate -Dexpression=project.build.outputDirectory -q -DforceStdout 2>> ${OUTPUT_DIR}/setup.log)
      fi
      echo -n ":${classes_dir}" >> ${deps_file}
    fi
    popd &> /dev/null
  fi
  head -n 1 ${deps_file} 2>/dev/null
}

function get_project_sourcepath() {
  local project_dir=$1
  local extension_jar="${SCRIPT_DIR}/../extension/target/blockgen-extension-1.0.jar"
  local source_dir="${project_dir}/src/main/java"
  if [[ ! -d ${source_dir} ]]; then
    pushd ${project_dir} &> /dev/null
    source_dir=$(mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} \
      help:evaluate -Dexpression=project.build.sourceDirectory -q -DforceStdout 2>> ${OUTPUT_DIR}/setup.log)
    popd &> /dev/null
  fi
  echo "${source_dir}"
}

# Step 2: Run coverage checker to measure coverage for dev tests, EvoSuite, and Randoop
function run_coverage_checker() {
  local coverage_output="${OUTPUT_DIR}/coverage"
  mkdir -p ${coverage_output}

  local repo_url=$(get_repo_url ${PROJECT_URL})
  local sha=$(get_sha ${PROJECT_URL})
  local file_path=$(get_file_path ${PROJECT_URL})
  local start_line=$(get_start_line ${PROJECT_URL})
  local end_line=$(get_end_line ${PROJECT_URL})

  echo "$(date) [Step 2] Running coverage checker"
  java -jar ${COVERAGE_CHECKER_JAR} \
    ${repo_url} ${sha} ${file_path} ${start_line} ${end_line} \
    ${coverage_output} ${LIBS_DIR} \
    &> ${OUTPUT_DIR}/coverage.log
}

install
cp ${SCRIPT_DIR}/../extension/target/blockgen-extension-1.0.jar ${SCRIPT_DIR}/../libs/

# Build coverage-checker if needed
if [[ ! -f ${SCRIPT_DIR}/../experiments/coverage-checker/target/coverage-checker-1.0-SNAPSHOT.jar ]]; then
  echo "$(date) Building coverage-checker"
  mvn -Dmaven.repo.local=${MAVEN_REPO} -f ${SCRIPT_DIR}/../experiments/coverage-checker/pom.xml package -q -DskipTests >> ${OUTPUT_DIR}/setup.log
fi

# Step 2: Run coverage checker
if precomputed_coverage_tests_exist; then
  echo "$(date) [Step 2] Using precomputed EvoSuite/Randoop tests, skipping coverage checker"
elif [[ ${SKIP_COVERAGE} -eq 0 ]]; then
  run_coverage_checker
else
  echo "$(date) [Step 2] Skipping coverage checker (-C flag set)"
fi

# Step 3: Run block tests with RV
function run_rv_blockgen() {
  local repo_url=$(get_repo_url ${PROJECT_URL})
  local sha=$(get_sha ${PROJECT_URL})
  local file_path=$(get_file_path ${PROJECT_URL})
  local rv_output="${OUTPUT_DIR}/rv-blockgen"
  mkdir -p ${rv_output}

  # Clone project at the specified commit to the same path used during block test generation
  local project_dir="${OUTPUT_DIR}/project"
  if [[ ! -d ${project_dir} ]]; then
    git clone ${repo_url} ${project_dir} &> ${rv_output}/clone.log
    pushd ${project_dir} &> /dev/null
    git checkout ${sha} &>> ${rv_output}/clone.log
    popd &> /dev/null
  fi

  cp -r ${OUTPUT_DIR}/reduction/dot-blocktests ${project_dir}/.blocktests

  local blockgen_jar="${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar"
  local blockgen_dir="${SCRIPT_DIR}/.."
  # blocktest-r2.txt is the file RV runs against. Note: the upstream pipelines
  # intentionally populate it with the full, unreduced r0 set (not the
  # mutation-reduced one) - RV trace collection wants every block test, not the
  # mutation-minimized subset.
  local r2_test_path="${OUTPUT_DIR}/reduction/blocktest-r2.txt"
  local abs_path_to_src="${project_dir}/${file_path}"

  export ADD_AGENT=1
  # Append, don't overwrite: callers (e.g. rv_exp_in_singularity.sh) may have
  # already set MAVEN_OPTS to force -Duser.home for settings.xml resolution.
  export MAVEN_OPTS="${MAVEN_OPTS} -Xmx500g -XX:-UseGCOverheadLimit"
  export RVMLOGGINGLEVEL=UNIQUE
  if [[ ${USE_STATS_AGENT} -eq 1 ]]; then
    export MOP_AGENT_PATH="-javaagent:${SCRIPT_DIR}/../libs/no-track-stats-agent.jar"
  else
    export MOP_AGENT_PATH="-javaagent:${SCRIPT_DIR}/../libs/track-agent.jar"
    export COLLECT_TRACES=1
    export COLLECT_MONITORS=1
    export TRACEDB_PATH=${OUTPUT_DIR}/traces-blockgen/all-traces
    export TRACEDB_RANDOM=1
    export TRACEDB_CONFIG_PATH="${SCRIPT_DIR}/.trace-db.config"
    echo -e "db=memory\ndumpDB=false" > ${TRACEDB_CONFIG_PATH}
    mkdir -p ${TRACEDB_PATH}
  fi

  local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)

  echo "$(date) [Step 3] Running RV with block tests"
  timeout -k 60 5400 java -Dmaven.home=${maven_home} -jar ${blockgen_jar} run-block-tests \
    ${project_dir} ${r2_test_path} ${rv_output} ${blockgen_dir} ${abs_path_to_src} \
    &> ${OUTPUT_DIR}/rv-blockgen.log

  unset ADD_AGENT
  unset MOP_AGENT_PATH
}

# $1: suffix (blockgen or dev)
function process() {
  local suffix=$1
  local traces_dir="${OUTPUT_DIR}/traces-${suffix}"
  local extracted_tests_dir="${OUTPUT_DIR}/rv-${suffix}/extracted-tests/all"
  local log_file="${OUTPUT_DIR}/rv-${suffix}.log"

  print_trace_stats() {
    local dir=$1 label=$2
    local unique=$(( $(wc -l < ${dir}/unique-traces.txt 2>/dev/null || echo 1) - 1 ))
    local total=$(awk 'NR>1 {sum+=$2} END {print sum+0}' ${dir}/unique-traces.txt 2>/dev/null || echo 0)
    echo "$(date) [process-${suffix}] ${label}: ${unique} unique traces, ${total} total trace occurrences" |& tee -a ${log_file}
  }

  if [[ -f ${TRACEDB_PATH}/unique-traces.txt ]]; then
    mv ${TRACEDB_PATH}/unique-traces.txt ${TRACEDB_PATH}/traces-id.txt
    (time python3 ${SCRIPT_DIR}/rv/count-traces-frequency.py ${TRACEDB_PATH}) &>> ${log_file}
    print_trace_stats ${TRACEDB_PATH} "all-traces"
    rm ${TRACEDB_PATH}/traces-id.txt ${TRACEDB_PATH}/traces.txt
  fi

  local num_db=0
  local last_db=""
  for db in $(ls ${TRACEDB_PATH}/../ 2>/dev/null | grep "all-traces-"); do
    if [[ ! -f ${traces_dir}/${db}/unique-traces.txt || ! -f ${traces_dir}/${db}/specs-frequency.csv || ! -f ${traces_dir}/${db}/locations.txt || ! -f ${traces_dir}/${db}/traces.txt ]]; then
      continue
    fi

    mv ${traces_dir}/${db}/unique-traces.txt ${traces_dir}/${db}/traces-id.txt
    (time python3 ${SCRIPT_DIR}/rv/count-traces-frequency.py ${traces_dir}/${db}/) &>> ${log_file}
    if [[ ${suffix} == "blockgen" && -d ${extracted_tests_dir} ]]; then
      python3 ${SCRIPT_DIR}/rv/filter-internal-traces.py ${traces_dir}/${db} ${extracted_tests_dir} &>> ${log_file}
    fi
    print_trace_stats ${traces_dir}/${db} "${db}"
    rm ${traces_dir}/${db}/traces-id.txt ${traces_dir}/${db}/traces.txt
    num_db=$((num_db + 1))
    last_db=${db}
  done

  if [[ ! -d ${TRACEDB_PATH} || -z $(ls -A ${TRACEDB_PATH}) ]]; then
    rm -rf ${TRACEDB_PATH}
  fi

  if [[ ${num_db} -eq 1 ]]; then
    mv ${traces_dir}/${db} ${TRACEDB_PATH}
  elif [[ ${num_db} -eq 0 ]]; then
    local empty_db=$(ls -d ${traces_dir}/all-traces-* 2>/dev/null)
    if [[ -n "${empty_db}" && -d "${empty_db}" && -z "$(ls -A "${empty_db}")" ]]; then
      rm -rf "${empty_db}"
    fi
  fi
}

function run_rv_dev() {
  local repo_url=$(get_repo_url ${PROJECT_URL})
  local sha=$(get_sha ${PROJECT_URL})
  local rv_output="${OUTPUT_DIR}/rv-dev"
  mkdir -p ${rv_output}

  local project_dir="${OUTPUT_DIR}/project"
  if [[ ! -d ${project_dir} ]]; then
    git clone ${repo_url} ${project_dir} &> ${rv_output}/clone.log
    pushd ${project_dir} &> /dev/null
    git checkout ${sha} &>> ${rv_output}/clone.log
    popd &> /dev/null
  fi

  local extension_jar="${SCRIPT_DIR}/../extension/target/blockgen-extension-1.0.jar"

  export ADD_AGENT=1
  # Append, don't overwrite: callers (e.g. rv_exp_in_singularity.sh) may have
  # already set MAVEN_OPTS to force -Duser.home for settings.xml resolution.
  export MAVEN_OPTS="${MAVEN_OPTS} -Xmx500g -XX:-UseGCOverheadLimit"
  export RVMLOGGINGLEVEL=UNIQUE
  if [[ ${USE_STATS_AGENT} -eq 1 ]]; then
    export MOP_AGENT_PATH="-javaagent:${SCRIPT_DIR}/../libs/no-track-stats-agent.jar"
  else
    export MOP_AGENT_PATH="-javaagent:${SCRIPT_DIR}/../libs/track-agent.jar"
    export COLLECT_TRACES=1
    export COLLECT_MONITORS=1
    export TRACEDB_PATH=${OUTPUT_DIR}/traces-dev/all-traces
    export TRACEDB_RANDOM=1
    export TRACEDB_CONFIG_PATH="${SCRIPT_DIR}/.trace-db.config"
    echo -e "db=memory\ndumpDB=false" > ${TRACEDB_CONFIG_PATH}
    mkdir -p ${TRACEDB_PATH}
  fi

  echo "$(date) [Step 3b] Running RV with developer tests"
  pushd ${project_dir} &> /dev/null
  git checkout -- . &>> ${OUTPUT_DIR}/rv-dev.log
  git clean -fd &>> ${OUTPUT_DIR}/rv-dev.log
  timeout -k 60 5400 mvn test -Dmaven.repo.local=${MAVEN_REPO} \
    -Dmaven.ext.class.path=${extension_jar} \
    -Dsurefire.exitTimeout=300 \
    ${SKIP} \
    --no-transfer-progress \
    &> ${OUTPUT_DIR}/rv-dev.log
  popd &> /dev/null

  unset ADD_AGENT
  unset MOP_AGENT_PATH
}

# Run the precomputed EvoSuite tests standalone (not assumed to live under
# src/test/java, so not run through mvn test) with the RV agent attached
# directly on the java command line. Mirrors experiments/coverage-checker's
# EvoSuiteComponent compile()/execute(), swapping the jacoco agent for ours.
function run_rv_evosuite() {
  local project_dir="${OUTPUT_DIR}/project"
  local tests_dir="${OUTPUT_DIR}/coverage/evosuite-tests"
  local rv_output="${OUTPUT_DIR}/rv-evosuite"
  mkdir -p ${rv_output}

  # Set before any early return below - check_traces/process are called
  # unconditionally for this suffix after this function returns, and without
  # this they'd read whatever TRACEDB_PATH the previous suffix (dev) left
  # exported, misattributing/reprocessing its traces under "evosuite".
  export TRACEDB_PATH=${OUTPUT_DIR}/traces-evosuite/all-traces

  if [[ ! -d ${tests_dir} ]]; then
    echo "$(date) [Step 3c] No precomputed EvoSuite tests, skipping"
    return
  fi

  local deps=$(get_project_classpath ${project_dir})

  echo "$(date) [Step 3c] Compiling EvoSuite tests"
  pushd ${project_dir} &> /dev/null
  javac -cp "${EVOSUITE_JAR}:${JUNIT_STANDALONE_JAR}:${deps}" \
    $(find ${tests_dir} -name "*.java") &> ${rv_output}/compile.log
  popd &> /dev/null

  local test_classes=$(find ${tests_dir} -name "*Test.java" \
    | sed "s|^${tests_dir}/||; s/\.java$//" | tr '/' '.')
  if [[ -z ${test_classes} ]]; then
    echo "$(date) [Step 3c] No EvoSuite test classes found after compilation, skipping"
    return
  fi

  export RVMLOGGINGLEVEL=UNIQUE
  local agent_jar
  if [[ ${USE_STATS_AGENT} -eq 1 ]]; then
    agent_jar="${SCRIPT_DIR}/../libs/no-track-stats-agent.jar"
  else
    agent_jar="${SCRIPT_DIR}/../libs/track-agent.jar"
    export COLLECT_TRACES=1
    export COLLECT_MONITORS=1
    export TRACEDB_RANDOM=1
    export TRACEDB_CONFIG_PATH="${SCRIPT_DIR}/.trace-db.config"
    echo -e "db=memory\ndumpDB=false" > ${TRACEDB_CONFIG_PATH}
    mkdir -p ${TRACEDB_PATH}
  fi

  echo "$(date) [Step 3c] Running RV with EvoSuite tests"
  pushd ${project_dir} &> /dev/null
  timeout -k 60 5400 java -javaagent:${agent_jar} \
    -cp "${agent_jar}:${tests_dir}:${EVOSUITE_JAR}:${JUNIT_STANDALONE_JAR}:${deps}" \
    org.junit.runner.JUnitCore ${test_classes} &> ${OUTPUT_DIR}/rv-evosuite.log
  popd &> /dev/null
}

# Run the precomputed Randoop tests standalone, same rationale as
# run_rv_evosuite. Mirrors experiments/coverage-checker's RandoopComponent
# compile()/execute() (RegressionTest/ErrorTest), swapping the jacoco agent
# for ours.
function run_rv_randoop() {
  local project_dir="${OUTPUT_DIR}/project"
  local tests_dir="${OUTPUT_DIR}/coverage/randoop-tests"
  local rv_output="${OUTPUT_DIR}/rv-randoop"
  mkdir -p ${rv_output}

  # See run_rv_evosuite for why this is set before any early return.
  export TRACEDB_PATH=${OUTPUT_DIR}/traces-randoop/all-traces

  if [[ ! -d ${tests_dir} ]]; then
    echo "$(date) [Step 3d] No precomputed Randoop tests, skipping"
    return
  fi

  local deps=$(get_project_classpath ${project_dir})
  local source_path=$(get_project_sourcepath ${project_dir})

  echo "$(date) [Step 3d] Compiling Randoop tests"
  local randoop_sources="${rv_output}/randoop-sources.txt"
  find ${tests_dir} -name "*.java" > ${randoop_sources}
  pushd ${project_dir} &> /dev/null
  javac -classpath "${deps}:${JUNIT_STANDALONE_JAR}" "@${randoop_sources}" -sourcepath "${source_path}" &> ${rv_output}/compile.log
  popd &> /dev/null

  export RVMLOGGINGLEVEL=UNIQUE
  local agent_jar
  if [[ ${USE_STATS_AGENT} -eq 1 ]]; then
    agent_jar="${SCRIPT_DIR}/../libs/no-track-stats-agent.jar"
  else
    agent_jar="${SCRIPT_DIR}/../libs/track-agent.jar"
    export COLLECT_TRACES=1
    export COLLECT_MONITORS=1
    export TRACEDB_RANDOM=1
    export TRACEDB_CONFIG_PATH="${SCRIPT_DIR}/.trace-db.config"
    echo -e "db=memory\ndumpDB=false" > ${TRACEDB_CONFIG_PATH}
    mkdir -p ${TRACEDB_PATH}
  fi

  local randoop_classes=""
  [[ -f ${tests_dir}/RegressionTest.class ]] && randoop_classes="${randoop_classes} RegressionTest"
  [[ -f ${tests_dir}/ErrorTest.class ]] && randoop_classes="${randoop_classes} ErrorTest"

  pushd ${project_dir} &> /dev/null
  if [[ -n ${randoop_classes} ]]; then
    echo "$(date) [Step 3d] Running RV with Randoop tests (${randoop_classes})"
    timeout -k 60 5400 java -javaagent:${agent_jar} \
      -classpath "${agent_jar}:${source_path}:${deps}:${tests_dir}:${JUNIT_STANDALONE_JAR}" \
      org.junit.runner.JUnitCore ${randoop_classes} &> ${OUTPUT_DIR}/rv-randoop.log
  else
    echo "$(date) [Step 3d] No Randoop RegressionTest/ErrorTest class found, skipping"
  fi
  popd &> /dev/null
}

# Check whether any unique-traces.txt was collected after an RV run
# $1: suffix (blockgen or dev)
function check_traces() {
  local suffix=$1
  local traces_dir="${OUTPUT_DIR}/traces-${suffix}"
  local log_file="${OUTPUT_DIR}/rv-${suffix}.log"
  local found=0

  if [[ -f ${TRACEDB_PATH}/unique-traces.txt && -s ${TRACEDB_PATH}/unique-traces.txt ]]; then
    found=1
  fi

  for db_dir in ${traces_dir}/all-traces-*/; do
    if [[ -f ${db_dir}/unique-traces.txt && -s ${db_dir}/unique-traces.txt ]]; then
      found=1
      break
    fi
  done

  if [[ ${found} -eq 1 ]]; then
    echo "$(date) [rv-${suffix}] Traces collected successfully" |& tee -a ${log_file}
  else
    echo "$(date) [rv-${suffix}] WARNING: No traces collected" |& tee -a ${log_file}
  fi
}

# RV may write violation-counts into the project dir; move it out under a
# run-specific name so the next run doesn't append to (and pollute) it.
function copy_violation_counts() {
  local suffix=$1
  if [[ -f ${OUTPUT_DIR}/project/violation-counts ]]; then
    mv ${OUTPUT_DIR}/project/violation-counts ${OUTPUT_DIR}/violations-${suffix}.txt
  fi
}

run_rv_blockgen
copy_violation_counts blockgen
if [[ ${USE_STATS_AGENT} -eq 0 ]]; then
  check_traces blockgen
  process blockgen
fi

run_rv_dev
copy_violation_counts dev
if [[ ${USE_STATS_AGENT} -eq 0 ]]; then
  check_traces dev
  process dev
fi

run_rv_evosuite
copy_violation_counts evosuite
if [[ ${USE_STATS_AGENT} -eq 0 ]]; then
  check_traces evosuite
  process evosuite
fi

run_rv_randoop
copy_violation_counts randoop
if [[ ${USE_STATS_AGENT} -eq 0 ]]; then
  check_traces randoop
  process randoop
fi
