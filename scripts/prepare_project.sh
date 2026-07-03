#!/bin/bash
#
# Run project
# Usage: bash prepare_project.sh <fragment-url> <blocktest-dir> <output-dir>
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

if [[ -f ${HOME}/.zshrc.pre-oh-my-zsh ]]; then
	source ${HOME}/.zshrc.pre-oh-my-zsh
fi
source ${SCRIPT_DIR}/constants.sh

FRAGMENT_URL=$1
BLOCKTEST_DIR=$2
OUTPUT_DIR=$3
EXTENSION_DIR=${SCRIPT_DIR}/../extension

REPO=$(echo ${FRAGMENT_URL} | cut -d '/' -f 4-5)
SHA=$(echo ${FRAGMENT_URL} | cut -d '/' -f 7)
FILEPATH=$(echo ${FRAGMENT_URL} | cut -d '/' -f 8- | cut -d '#' -f 1)
FILENAME=$(echo ${FRAGMENT_URL} | rev | cut -d '/' -f 1 | rev | cut -d '#' -f 1 | cut -d '.' -f 1)
START_LINE=$(echo ${FRAGMENT_URL} | cut -d '#' -f 2 | cut -d '-' -f 1 | cut -d 'L' -f 2 | cut -d 'C' -f 1)
END_LINE=$(echo ${FRAGMENT_URL} | cut -d '#' -f 2 | cut -d '-' -f 2 | cut -d 'L' -f 2 | cut -d 'C' -f 1)
LINE_NUMBER=$(echo ${FRAGMENT_URL} | rev | cut -d '#' -f 1 | rev | tr - _)
FILE_EXTRACTED_PATH="$(echo ${FILEPATH} | cut -d '.' -f 1)_Extracted.java"
BLOCKTEST_PATH="$(echo ${FILEPATH} | sed "s/\/main\//\/test\//" | cut -d '.' -f 1)BlockTest.java"

export FRAGMENT_ID="$(echo ${REPO} | tr / -)-${SHA}-${FILENAME}-${LINE_NUMBER}"

check_inputs() {
	if [[ ! -d ${BLOCKTEST_DIR} || -z ${OUTPUT_DIR} ]]; then
		echo "Usage: prepare_project.sh <fragment-url> <blocktest-dir> <output-dir>"
		exit 1
	fi
	
	if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
		OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
	fi
	
	mkdir -p ${OUTPUT_DIR}/logs
	
	export MAVEN_REPO=${OUTPUT_DIR}/repo
	export MAVEN_SETTINGS="true"
	
	echo "REPO: ${REPO}"
	echo "SHA: ${SHA}"
	echo "FILEPATH: ${FILEPATH}"
	echo "START_LINE: ${START_LINE}"
	echo "END_LINE: ${END_LINE}"
	echo "FILE_EXTRACTED_PATH: ${FILE_EXTRACTED_PATH}"
	echo "BLOCKTEST_PATH: ${BLOCKTEST_PATH}"
}

clone() {
	if [[ -d ${OUTPUT_DIR}/project ]]; then
		pushd ${OUTPUT_DIR}/project &> /dev/null
		echo "Restore and re-checkout" >> ${OUTPUT_DIR}/logs/clone.log
		git restore $(echo ${FILEPATH} | cut -d '/' -f 1) >> ${OUTPUT_DIR}/logs/clone.log
		git clean -fd $(echo ${FILEPATH} | cut -d '/' -f 1) >> ${OUTPUT_DIR}/logs/clone.log
		if [[ -f pom.xml ]]; then
			git restore pom.xml >> ${OUTPUT_DIR}/logs/clone.log
		fi

		echo "Clean up"
		echo "find . -name \"*.class\" | xargs rm -rf && git restore ${FILEPATH} && rm -f ${BLOCKTEST_PATH} ${FILE_EXTRACTED_PATH}"
		find . -name "*.class" | xargs rm -rf && git restore ${FILEPATH} && rm -f ${BLOCKTEST_PATH} ${FILE_EXTRACTED_PATH}
		
		rm -rf evosuite-report evosuite-tests randoop-tests kex-generated
		
		git checkout ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
		
		source ${SCRIPT_DIR}/treat_special.sh ${REPO} ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
		popd &> /dev/null
	else
		pushd ${OUTPUT_DIR} &> /dev/null
		git clone https://github.com/${REPO} project &> ${OUTPUT_DIR}/logs/clone.log
		pushd project &> /dev/null
		git checkout ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
		if [[ $? -ne 0 ]]; then
			echo "Unable to checkout, try fetching again..."
			git fetch origin ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
			git checkout ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
			if [[ $? -ne 0 ]]; then
				echo "Still unable to checkout"
				exit 1
			fi
		fi
		
		source ${SCRIPT_DIR}/treat_special.sh ${REPO} ${SHA} >> ${OUTPUT_DIR}/logs/clone.log
		popd &> /dev/null
		popd &> /dev/null
	fi
}

install() {
	local install_start=$(epoch_ms)
	if [[ ${IS_LOCAL} != true ]]; then
		export MAVEN_SETTINGS_ONLY="1"
		local extension_jar=${EXTENSION_DIR}/target/blockgen-extension-1.0.jar
		if [[ ! -f ${extension_jar} ]]; then
			echo "Extension is missing... Building extension..."
			pushd ${EXTENSION_DIR} &> /dev/null
			mvn -Dmaven.repo.local=${MAVEN_REPO} package >> ${OUTPUT_DIR}/logs/setup.log
			if [[ $? -ne 0 ]]; then
				echo "Step install extension failed"
				popd &> /dev/null
				exit 1
			fi
			popd &> /dev/null
		fi
		
		echo "Setting up BlockTest"
		pushd ${BLOCKTEST_DIR} &> /dev/null
		mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} install >> ${OUTPUT_DIR}/logs/setup.log
		popd &> /dev/null
		
		echo "Setting up BlockGen"
		pushd ${SCRIPT_DIR}/../blockgen &> /dev/null
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
	
	pushd ${OUTPUT_DIR}/project &> /dev/null
	mvn -Dmaven.repo.local=${MAVEN_REPO} -Dmaven.ext.class.path=${extension_jar} dependency:build-classpath -Dmdep.outputFile=${OUTPUT_DIR}/deps.txt >> ${OUTPUT_DIR}/logs/deps.log
	popd &> /dev/null
	
	local install_end=$(epoch_ms)
	local install_duration=$((install_end - install_start))
	echo "TOTAL TIME: install - ${install_duration}"
}

run() {
	local failed=false
	local total_start=$(epoch_ms)
	local compile_start=$(epoch_ms)
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	if [[ -n ${MAVEN_REPO} ]]; then
		mvn_argument="-Dmaven.repo.local=${MAVEN_REPO}"
	fi

	echo ">>> Compile"
	pushd ${OUTPUT_DIR}/project &> /dev/null
	(time mvn ${mvn_argument} -Dmaven.ext.class.path=${extension_jar} ${SKIP} clean test-compile) &> ${OUTPUT_DIR}/logs/compile.log
	
	if [[ $? -ne 0 ]]; then
		echo "Failed to compile the project"
		failed=true
	fi
	popd &> /dev/null
	echo "<<< Compile"
	local compile_end=$(epoch_ms)
	local compile_duration=$((compile_end - compile_start))
	echo "TOTAL TIME: compile - ${compile_duration}"
	
	if [[ ${failed} == false ]]; then
		local test_start=$(epoch_ms)
		echo ">>> Test"
		pushd ${OUTPUT_DIR}/project &> /dev/null
		(timeout 120s /usr/bin/time mvn ${mvn_argument} -Dmaven.ext.class.path=${extension_jar} ${SKIP} test) &> ${OUTPUT_DIR}/logs/test.log
		popd &> /dev/null
		echo "<<< Test"
		local test_end=$(epoch_ms)
		local test_duration=$((test_end - test_start))
		echo "TOTAL TIME: test - ${test_duration}"
	fi
	
	local total_end=$(epoch_ms)
	local total_duration=$((total_end - total_start))
	echo "TOTAL TIME: e2e - ${total_duration}"
	
	if [[ ${failed} == true ]]; then
		return 1
	else
		return 0
	fi
}

echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
clone
install
run
