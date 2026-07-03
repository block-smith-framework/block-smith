#!/bin/bash
#
# Exli pipeline
# Usage: bash use_exli.sh <project-dir> <target-file> <target-line-start> <target-line-end> <output-dir> <extension-dir> <blocktest-dir>
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

if [[ -f ${HOME}/.zshrc.pre-oh-my-zsh ]]; then
	source ${HOME}/.zshrc.pre-oh-my-zsh
fi
if [[ -f ${HOME}/.bashrc ]]; then
	source ${HOME}/.bashrc
fi

source ${SCRIPT_DIR}/../constants.sh
source ${SCRIPT_DIR}/run_blocktests.sh

USE_RANDOOP=true
USE_RANDOOP_CLASS=true
USE_EVOSUITE=true
USE_DEV=true
PERFORM_REDUCTION=true
while getopts :r:c:e:d:m: opts; do
	case "${opts}" in
		r ) USE_RANDOOP="${OPTARG}" ;;
		c ) USE_RANDOOP_CLASS="${OPTARG}" ;;
		e ) USE_EVOSUITE="${OPTARG}" ;;
		d ) USE_DEV="${OPTARG}" ;;
		m ) PERFORM_REDUCTION="${OPTARG}" ;;
	esac
done
shift $((${OPTIND} - 1))

PROJECT_DIR=$1
TARGET_FILE=$2
START_LINE=$3
END_LINE=$4
OUTPUT_DIR=$5
EXTENSION_DIR=$6
BLOCKTEST_DIR=$7

export NO_EVOSUITE_MOCKING=true

check_status_code() {
	local error_code=$?
	if [[ ${error_code} -ne 0 ]]; then
		echo "Step $1 failed"
		echo "$1,${error_code}" > "${OUTPUT_DIR}/logs/status.log"
		exit 1
	fi
}

check_inputs() {
	if [[ ! -d ${PROJECT_DIR} || ! -f ${TARGET_FILE} || ! -d ${EXTENSION_DIR} || ! -d ${BLOCKTEST_DIR} ]]; then
		echo "Usage: bash use_exli.sh <project-dir> <target-file> <target-line-start> <target-line-end> <output-dir> <extension-dir> <blocktest-dir>"
		exit 1
	fi
	
	if [[ ${USE_RANDOOP} == false && ${USE_RANDOOP_CLASS} == false && ${USE_EVOSUITE} == false && ${USE_DEV} == false ]]; then
		echo "Randoop, Randoop-class, EvoSuite, developer tests are all disabled"
		exit 1
	fi
	
	if [[ -d ${OUTPUT_DIR}/data || -d ${OUTPUT_DIR}/logs ]]; then
		echo "Delete old data and old logs"
		find ${OUTPUT_DIR} -maxdepth 1 -mindepth 1 ! -name "mutation" -exec rm -rf {} +
		rm -rf ${PROJECT_DIR}/.blocktests
	fi
	
	pushd ${PROJECT_DIR} &> /dev/null
	find . -name "*BlockTest.java" | xargs rm -f
	popd &> /dev/null
	
	if [[ ! ${PROJECT_DIR} =~ ^/.* ]]; then
		PROJECT_DIR=${SCRIPT_DIR}/${PROJECT_DIR}
	fi
	
	if [[ ! ${TARGET_FILE} =~ ^/.* ]]; then
		TARGET_FILE=${SCRIPT_DIR}/${TARGET_FILE}
	fi
	
	if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
		OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
	fi
	
	if [[ ! ${EXTENSION_DIR} =~ ^/.* ]]; then
		EXTENSION_DIR=${SCRIPT_DIR}/${EXTENSION_DIR}
	fi
	
	if [[ ! ${BLOCKTEST_DIR} =~ ^/.* ]]; then
		BLOCKTEST_DIR=${SCRIPT_DIR}/${BLOCKTEST_DIR}
	fi
	
	mkdir -p ${OUTPUT_DIR}/logs
	mkdir -p ${OUTPUT_DIR}/data
}

install() {
	local install_start=$(epoch_ms)
	if [[ ${IS_LOCAL} != true ]]; then
		if [[ -z ${MAVEN_REPO} ]]; then
			export MAVEN_REPO=${OUTPUT_DIR}/repo
		fi
		export MAVEN_SETTINGS="true"
		export MAVEN_SETTINGS_ONLY="1"
		local extension_jar=${EXTENSION_DIR}/target/blockgen-extension-1.0.jar
		if [[ ! -f ${extension_jar} ]]; then
			echo "Extension is missing... Building extension..."
			pushd ${EXTENSION_DIR} &> /dev/null
			mvn -Dmaven.repo.local=${MAVEN_REPO} package >> ${OUTPUT_DIR}/logs/setup.log
			check_status_code "install extension"
			popd &> /dev/null
		fi
		
		echo "Setting up BlockTest"
		pushd ${BLOCKTEST_DIR} &> /dev/null
		mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} install >> ${OUTPUT_DIR}/logs/setup.log
		popd &> /dev/null
		
		echo "Setting up BlockGen"
		pushd ${SCRIPT_DIR}/../../blockgen &> /dev/null
		mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} install >> ${OUTPUT_DIR}/logs/setup.log
		popd &> /dev/null
		
		unset MAVEN_SETTINGS_ONLY
	fi
	
	pushd ${PROJECT_DIR} &> /dev/null
	if [[ -z $(echo ${TARGET_FILE} | grep "src/main/java") ]]; then
		if [[ -z ${SRC_DIRECTORY} ]]; then
			export SRC_DIRECTORY=$(mvn help:evaluate -Dexpression=project.build.sourceDirectory -q -DforceStdout | sed "s|${PROJECT_DIR}/||")
		fi
		if [[ -z ${TEST_DIRECTORY} ]]; then
			export TEST_DIRECTORY=$(mvn help:evaluate -Dexpression=project.build.testSourceDirectory -q -DforceStdout | sed "s|${PROJECT_DIR}/||")
		fi
		echo "--- SRC_DIRECTORY is ${SRC_DIRECTORY}"
		echo "--- TEST_DIRECTORY is ${TEST_DIRECTORY}"
	elif [[ ! -d ${PROJECT_DIR}/src/test/java ]]; then
		if [[ -z ${TEST_DIRECTORY} ]]; then
			export TEST_DIRECTORY=$(mvn help:evaluate -Dexpression=project.build.testSourceDirectory -q -DforceStdout | sed "s|${PROJECT_DIR}/||")
		fi
		echo "--- TEST_DIRECTORY is ${TEST_DIRECTORY}"
	fi
	popd &> /dev/null
	local install_end=$(epoch_ms)
	local install_duration=$((install_end - install_start))
	echo "TOTAL TIME: install - ${install_duration}"
}

generate_blocktests() {
	local mvn_argument=""
	if [[ -n ${MAVEN_REPO} ]]; then
		mvn_argument="-Dmaven.repo.local=${MAVEN_REPO}"
	fi
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original.java
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	# Compile (needed for type resolution)
	local compile_start=$(epoch_ms)
	compile
	local compile_end=$(epoch_ms)
	local compile_duration=$((compile_end - compile_start))
	echo "TOTAL TIME: pre-compile - ${compile_duration}"
	
	# Instrument
	local instrument_start=$(epoch_ms)
	echo ">>> Instrument"
	timeout 600s java  -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar instrument ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/instrumentation ${block_gen_dir} ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/data/tmp.log target/classes
	local status=$?
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/instrumented.java
	
	if [[ ${status} -ne 0 ]]; then
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		echo "Unable to extract fragment"
		exit 1
	fi
	
	echo "<<< Instrument"
	local instrument_end=$(epoch_ms)
	local instrument_duration=$((instrument_end - instrument_start))
	echo "TOTAL TIME: instrument - ${instrument_duration}"

	# Run test to collect
	local compile_start=$(epoch_ms)
	compile
	local compile_end=$(epoch_ms)
	local compile_duration=$((compile_end - compile_start))
	echo "TOTAL TIME: compile - ${compile_duration}"
	
	if [[ ${USE_EVOSUITE} == true ]]; then
		local collect_evosuite_start=$(epoch_ms)
		echo ">>> Collect EvoSuite"
		export ADD_JACOCO=1
		pushd ${PROJECT_DIR} &> /dev/null
		cp ${OUTPUT_DIR}/data/instrumented.java ${TARGET_FILE}
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} evosuite
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		unset ADD_JACOCO
		
		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from EvoSuite"
			USE_EVOSUITE=false
		fi
		echo "<<< Collect EvoSuite"
		local collect_evosuite_end=$(epoch_ms)
		local collect_evosuite_duration=$((collect_evosuite_end - collect_evosuite_start))
		echo "TOTAL TIME: collect_evosuite - ${collect_evosuite_duration}"
	fi
	
	if [[ ${USE_RANDOOP} == true ]]; then
		local collect_randoop_start=$(epoch_ms)
		echo ">>> Collect Randoop"
		export ADD_JACOCO=1
		cp ${OUTPUT_DIR}/data/instrumented.java ${TARGET_FILE}
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 3660s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		unset ADD_JACOCO

		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from Randoop"
			USE_RANDOOP=false
		fi
		echo "<<< Collect Randoop"
		local collect_randoop_end=$(epoch_ms)
		local collect_randoop_duration=$((collect_randoop_end - collect_randoop_start))
		echo "TOTAL TIME: collect_randoop - ${collect_randoop_duration}"
	fi

	if [[ ${USE_RANDOOP_CLASS} == true ]]; then
		local collect_randoop_class_start=$(epoch_ms)
		echo ">>> Collect Randoop-class"
		export ADD_JACOCO=1
		cp ${OUTPUT_DIR}/data/instrumented.java ${TARGET_FILE}
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 3660s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop-class
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		unset ADD_JACOCO

		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from Randoop-class"
			USE_RANDOOP_CLASS=false
		fi
		echo "<<< Collect Randoop-class"
		local collect_randoop_class_end=$(epoch_ms)
		local collect_randoop_class_duration=$((collect_randoop_class_end - collect_randoop_class_start))
		echo "TOTAL TIME: collect_randoop_class - ${collect_randoop_class_duration}"
	fi


	if [[ ${USE_DEV} == true ]]; then
		local collect_unit_start=$(epoch_ms)
		echo ">>> Collect Unit"
		export ADD_JACOCO=1
		cp ${OUTPUT_DIR}/data/instrumented.java ${TARGET_FILE}
		pushd ${PROJECT_DIR} &> /dev/null
		(time timeout 1800s mvn ${mvn_argument} -Dmaven.ext.class.path=${extension_jar} ${SKIP_WITH_JACOCO} test) &> ${OUTPUT_DIR}/logs/collect.log
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		unset ADD_JACOCO
		echo "<<< Collect Unit"
		local collect_unit_end=$(epoch_ms)
		local collect_unit_duration=$((collect_unit_end - collect_unit_start))
		echo "TOTAL TIME: collect_unit - ${collect_unit_duration}"
	fi
	
	# Insert
	local insert_start=$(epoch_ms)
	echo ">>> Insert"
	if [[ ! -f ${OUTPUT_DIR}/data/blocktest-r0.txt ]]; then
		echo "Failed to generate test"
		exit 1
	fi
	timeout 600s java -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar add-block-test ${OUTPUT_DIR}/data/blocktest-r0.txt
	if [[ $? -ne 0 ]]; then
		echo "Failed to insert block test"
		exit 1
	fi
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/inserted.java
	echo "<<< Insert"
	local insert_end=$(epoch_ms)
	local insert_duration=$((insert_end - insert_start))
	echo "TOTAL TIME: insert - ${insert_duration}"
}

run_generated_blocktest() {
	local run_blocktests_start=$(epoch_ms)
	echo ">>> Run Block Tests"
	run_blocktests ${PROJECT_DIR} ${TARGET_FILE} ${EXTENSION_DIR} ${OUTPUT_DIR} ${BLOCKTEST_DIR}
	cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
	echo "<<< Run Block Tests"
	local run_blocktests_end=$(epoch_ms)
	local run_blocktests_duration=$((run_blocktests_end - run_blocktests_start))
	echo "TOTAL TIME: run_blocktests - ${run_blocktests_duration}"
}

mutation() {
	# Remove argLine from pom (because we are not using JaCoCo)
	pushd ${PROJECT_DIR} &> /dev/null
	sed -i.bak "s/@{argLine} //g" pom.xml
	rm pom.xml.bak
	popd &> /dev/null
	
	local mutation_start=$(epoch_ms)
	echo ">>> Mutation"
	cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar mutation ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/data/blocktest-r0.txt ${OUTPUT_DIR}/data/blocktest-r1.txt ${PERFORM_REDUCTION}
	if [[ $? -ne 0 ]]; then
		echo "Failed to get r2"
		exit 1
	fi
	echo "<<< Mutation"
	local mutation_end=$(epoch_ms)
	local mutation_duration=$((mutation_end - mutation_start))
	echo "TOTAL TIME: mutation - ${mutation_duration}"
}

generate_unittests() {
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original.java
	# Compile
	local initial_compile_start=$(epoch_ms)
	compile
	local initial_compile_end=$(epoch_ms)
	local initial_compile_duration=$((initial_compile_end - initial_compile_start))
	echo "TOTAL TIME: initial_compile - ${initial_compile_duration}"
	
	if [[ ${USE_EVOSUITE} == true ]]; then
		local gen_evosuite_start=$(epoch_ms)
		echo ">>> EvoSuite"
		local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
		local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} evosuite
		if [[ $? -ne 0 ]]; then
			echo "Failed to run EvoSuite"
			USE_EVOSUITE=false
		fi
		echo "<<< EvoSuite"
		local gen_evosuite_end=$(epoch_ms)
		local gen_evosuite_duration=$((gen_evosuite_end - gen_evosuite_start))
		echo "TOTAL TIME: gen_evosuite - ${gen_evosuite_duration}"
	fi
	
	if [[ ${USE_RANDOOP} == true ]]; then
		local gen_randoop_start=$(epoch_ms)
		echo ">>> Randoop"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop
		if [[ $? -ne 0 ]]; then
			echo "Failed to run Randoop"
			USE_RANDOOP=false
		fi
		echo "<<< Randoop"
		local gen_randoop_end=$(epoch_ms)
		local gen_randoop_duration=$((gen_randoop_end - gen_randoop_start))
		echo "TOTAL TIME: gen_randoop - ${gen_randoop_duration}"
	fi

	if [[ ${USE_RANDOOP_CLASS} == true ]]; then
		local gen_randoop_class_start=$(epoch_ms)
		echo ">>> Randoop-class"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop-class
		if [[ $? -ne 0 ]]; then
			echo "Failed to run Randoop-class"
			USE_RANDOOP_CLASS=false
		fi
		echo "<<< Randoop-class"
		local gen_randoop_class_end=$(epoch_ms)
		local gen_randoop_class_duration=$((gen_randoop_class_end - gen_randoop_class_start))
		echo "TOTAL TIME: gen_randoop_class - ${gen_randoop_class_duration}"
	fi
}

compile() {
	local mvn_argument=""
	if [[ -n ${MAVEN_REPO} ]]; then
		mvn_argument="-Dmaven.repo.local=${MAVEN_REPO}"
	fi
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	
	echo ">>> Compile"
	pushd ${PROJECT_DIR} &> /dev/null
	(time mvn ${mvn_argument} -Dmaven.ext.class.path=${extension_jar} ${SKIP} clean test-compile) &> ${OUTPUT_DIR}/logs/compile.log
	if [[ $? -ne 0 ]]; then
		echo "Failed to compile the project"
		exit 1
	fi
	popd &> /dev/null
	echo "<<< Compile"
}

total_start=$(epoch_ms)
echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
install
generate_unittests
generate_blocktests
mutation
#run_generated_blocktest
total_end=$(epoch_ms)
total_duration=$((total_end - total_start))
echo "TOTAL TIME: end_to_end - ${total_duration}"
