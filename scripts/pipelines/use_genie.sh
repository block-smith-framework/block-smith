#!/bin/bash
#
# Given a project, run block test and measure time
# Usage: bash use_genie.sh <project-dir> <target-file> <taget-line-start> <traget-line-end> <output-dir> <extension-dir> <blocktest-dir>
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
USE_KEX=true
USE_JDOOP=true
USE_JDART=true
PERFORM_REDUCTION=true
while getopts :r:c:e:k:j:d:m: opts; do
	case "${opts}" in
		r ) USE_RANDOOP="${OPTARG}" ;;
		c ) USE_RANDOOP_CLASS="${OPTARG}" ;;
		e ) USE_EVOSUITE="${OPTARG}" ;;
		k ) USE_KEX="${OPTARG}" ;;
		j ) USE_JDOOP="${OPTARG}" ;;
		d ) USE_JDART="${OPTARG}" ;;
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
		echo "Usage: bash use_genie.sh <project-dir> <target-file> <taget-line-start> <traget-line-end> <output-dir> <extension-dir> <blocktest-dir>"
		exit 1
	fi
	
	if [[ ${USE_RANDOOP} == false && ${USE_RANDOOP_CLASS} == false && ${USE_EVOSUITE} == false && ${USE_KEX} == false && ${USE_JDOOP} == false && ${USE_JDART} == false ]]; then
		echo "Randoop, Randoop-class, EvoSuite, Kex, JDoop, and JDart are all disabled"
		exit 1
	fi
	
	if [[ -d ${OUTPUT_DIR}/data || -d ${OUTPUT_DIR}/logs ]]; then
		echo "Delete old data and old logs"
		find ${OUTPUT_DIR} -maxdepth 1 -mindepth 1 ! -name "mutation" -exec rm -rf {} +
		rm -rf ${PROJECT_DIR}/.blocktests
	fi
	
	pushd ${PROJECT_DIR} &> /dev/null
	find . -name "*BlockTest.java" | xargs rm -f
	find . -name "*_Extracted.java" | xargs rm -f
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
	
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original.java
	
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

extraction() {
	# Compile (needed for type resolution)
	local compile_start=$(epoch_ms)
	compile
	local compile_end=$(epoch_ms)
	local compile_duration=$((compile_end - compile_start))
	echo "TOTAL TIME: pre-compile - ${compile_duration}"
	
	pushd ${PROJECT_DIR} &> /dev/null
	local target_dir=target/classes
	if [[ ! -d ${target_dir} ]]; then
		target_dir=$(mvn help:evaluate -Dexpression=project.build.outputDirectory -q -DforceStdout)
	fi
	popd &> /dev/null
	echo "Target directory is ${target_dir}"
	
	local extraction_start=$(epoch_ms)
	echo ">>> Extract"
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	timeout 600s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar extraction ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/extraction ${block_gen_dir} ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/data/tmp.log ${target_dir}
	local status=$?
	# Note: extraction could modify TARGET_FILE (to add public to static methods)
	
	if [[ ${status} -ne 0 ]]; then
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		echo "Unable to extract fragment"
		exit 1
	fi
	echo "<<< Extract"

	local new_file="$(realpath $(echo ${TARGET_FILE} | rev | cut -d '/' -f 2- | rev)/*_Extracted.java)"
	local no_instr_file="/tmp/NonInstrumented.java"
	local no_instr_driver_file="/tmp/NonInstrumentedDriver.java"
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original_modified.java
	cp ${new_file} ${OUTPUT_DIR}/data/extracted_instrumented.java
	mv ${no_instr_file} ${OUTPUT_DIR}/data/extracted.java
	mv ${no_instr_driver_file} ${OUTPUT_DIR}/data/driver_extracted.java
	cp ${OUTPUT_DIR}/data/extracted.java ${new_file}
	local extraction_end=$(epoch_ms)
	local extraction_duration=$((extraction_end - extraction_start))
	echo "TOTAL TIME: extraction - ${extraction_duration}"
}

generate_blocktests() {
	local mvn_argument=""
	if [[ -n ${MAVEN_REPO} ]]; then
		mvn_argument="-Dmaven.repo.local=${MAVEN_REPO}"
	fi
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	
	# Instrument
	local new_file="$(realpath $(echo ${TARGET_FILE} | rev | cut -d '/' -f 2- | rev)/*_Extracted.java)"
	cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}

	# Run test to collect
	local compile_start=$(epoch_ms)
	compile
	local compile_end=$(epoch_ms)
	local compile_duration=$((compile_end - compile_start))
	echo "TOTAL TIME: compile - ${compile_duration}"
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	
	if [[ ${USE_EVOSUITE} == true ]]; then
		local collect_evosuite_start=$(epoch_ms)
		echo ">>> Collect EvoSuite"
		export ADD_JACOCO=1
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} evosuite
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
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
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
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
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop-class
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
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

	if [[ ${USE_KEX} == true ]]; then
		local collect_kex_start=$(epoch_ms)
		echo ">>> Collect Kex"
		export ADD_JACOCO=1
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} kex
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
		unset ADD_JACOCO
		
		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from Kex"
			USE_KEX=false
		fi
		echo "<<< Collect Kex"
		local collect_kex_end=$(epoch_ms)
		local collect_kex_duration=$((collect_kex_end - collect_kex_start))
		echo "TOTAL TIME: collect_kex - ${collect_kex_duration}"
	fi
	
	if [[ ${USE_JDOOP} == true ]]; then
		local collect_jdoop_start=$(epoch_ms)
		echo ">>> Collect JDoop"
		export ADD_JACOCO=1
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} jdoop
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
		unset ADD_JACOCO
		
		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from JDoop"
			USE_JDOOP=false
		fi
		echo "<<< Collect JDoop"
		local collect_jdoop_end=$(epoch_ms)
		local collect_jdoop_duration=$((collect_jdoop_end - collect_jdoop_start))
		echo "TOTAL TIME: collect_jdoop - ${collect_jdoop_duration}"
	fi
	
	if [[ ${USE_JDART} == true ]]; then
		local collect_jdart_start=$(epoch_ms)
		echo ">>> Collect JDart"
		export ADD_JACOCO=1
		pushd ${PROJECT_DIR} &> /dev/null
		timeout 1800s java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar run ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} jdart
		status=$?
		popd &> /dev/null
		cp ${OUTPUT_DIR}/data/extracted_instrumented.java ${new_file}
		unset ADD_JACOCO
		
		if [[ ${status} -ne 0 ]]; then
			echo "Unable to collect test from JDart"
			USE_JDART=false
		fi
		echo "<<< Collect JDart"
		local collect_jdart_end=$(epoch_ms)
		local collect_jdart_duration=$((collect_jdart_end - collect_jdart_start))
		echo "TOTAL TIME: collect_jdart - ${collect_jdart_duration}"
	fi
	
	cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
	
	if [[ ${USE_EVOSUITE} == false && ${USE_RANDOOP} == false && ${USE_RANDOOP_CLASS} == false && ${USE_KEX} == false && ${USE_JDOOP} == false && ${USE_JDART} == false ]]; then
		echo "Unable to collect test from EvoSuite, Randoop, Randoop-class, Kex, JDoop, and JDart"
		exit 1
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
	
	echo "Deleting file ${new_file}"
	rm -rf ${new_file}
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
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		echo "Failed to get r2"
		exit 1
	fi
	echo "<<< Mutation"
	cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
	local mutation_end=$(epoch_ms)
	local mutation_duration=$((mutation_end - mutation_start))
	echo "TOTAL TIME: mutation - ${mutation_duration}"
}

generate_unittests() {
	# Compile
	local initial_compile_start=$(epoch_ms)
	compile
	local initial_compile_end=$(epoch_ms)
	local initial_compile_duration=$((initial_compile_end - initial_compile_start))
	echo "TOTAL TIME: initial_compile - ${initial_compile_duration}"
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/../..)
	local new_file="$(realpath $(echo ${TARGET_FILE} | rev | cut -d '/' -f 2- | rev)/*_Extracted.java)"
	
	if [[ ${USE_EVOSUITE} == true ]]; then
		local gen_evosuite_start=$(epoch_ms)
		echo ">>> EvoSuite"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} evosuite
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
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop
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
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} randoop-class
		if [[ $? -ne 0 ]]; then
			echo "Failed to run Randoop-class"
			USE_RANDOOP_CLASS=false
		fi
		echo "<<< Randoop-class"
		local gen_randoop_class_end=$(epoch_ms)
		local gen_randoop_class_duration=$((gen_randoop_class_end - gen_randoop_class_start))
		echo "TOTAL TIME: gen_randoop_class - ${gen_randoop_class_duration}"
	fi

	if [[ ${USE_KEX} == true ]]; then
		local gen_kex_start=$(epoch_ms)
		echo ">>> Kex"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} kex
		if [[ $? -ne 0 ]]; then
			echo "Failed to run Kex"
			USE_KEX=false
		fi
		echo "<<< Kex"
		local gen_kex_end=$(epoch_ms)
		local gen_kex_duration=$((gen_kex_end - gen_kex_start))
		echo "TOTAL TIME: gen_kex - ${gen_kex_duration}"
	fi
	
	if [[ ${USE_JDOOP} == true ]]; then
		local gen_jdoop_start=$(epoch_ms)
		echo ">>> JDoop"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} jdoop
		if [[ $? -ne 0 ]]; then
			echo "Failed to run JDoop"
			USE_JDOOP=false
		fi
		echo "<<< JDoop"
		local gen_jdoop_end=$(epoch_ms)
		local gen_jdoop_duration=$((gen_jdoop_end - gen_jdoop_start))
		echo "TOTAL TIME: gen_jdoop - ${gen_jdoop_duration}"
	fi
	
	if [[ ${USE_JDART} == true ]]; then
		cp ${OUTPUT_DIR}/data/driver_extracted.java ${new_file}
		compile driver
		
		local gen_jdart_start=$(epoch_ms)
		echo ">>> JDart"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../../blockgen/target/blockgen-1.0.jar generation ${PROJECT_DIR} ${new_file} ${OUTPUT_DIR}/generation ${block_gen_dir} jdart
		if [[ $? -ne 0 ]]; then
			echo "Failed to run JDart"
			USE_JDART=false
		fi
		echo "<<< JDart"
		local gen_jdart_end=$(epoch_ms)
		local gen_jdart_duration=$((gen_jdart_end - gen_jdart_start))
		echo "TOTAL TIME: gen_jdart - ${gen_jdart_duration}"
		
		cp ${OUTPUT_DIR}/data/extracted.java ${new_file}
	fi
	
	if [[ ${USE_EVOSUITE} == false && ${USE_RANDOOP} == false && ${USE_RANDOOP_CLASS} == false && ${USE_KEX} == false && ${USE_JDOOP} == false && ${USE_JDART} == false ]]; then
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
		echo "Failed to run EvoSuite, Randoop, Randoop-class, Kex, JDoop, and JDart"
		exit 1
	fi
}

compile() {
	local stage=$1
	local mvn_argument=""
	if [[ -n ${MAVEN_REPO} ]]; then
		mvn_argument="-Dmaven.repo.local=${MAVEN_REPO}"
	fi
	if [[ -n ${stage} ]]; then
		stage="-${stage}"
	fi
	
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	
	echo ">>> Compile${stage}"
	pushd ${PROJECT_DIR} &> /dev/null
	(time mvn ${mvn_argument} -Dmaven.ext.class.path=${extension_jar} ${SKIP} clean test-compile) &> ${OUTPUT_DIR}/logs/compile${stage}.log
	if [[ $? -ne 0 ]]; then
		echo "Failed to compile${stage} the project"
		exit 1
	fi
	popd &> /dev/null
	echo "<<< Compile${stage}"
}

total_start=$(epoch_ms)
echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
install
extraction
generate_unittests
generate_blocktests
mutation
#run_generated_blocktest
total_end=$(epoch_ms)
total_duration=$((total_end - total_start))
echo "TOTAL TIME: end_to_end - ${total_duration}"
