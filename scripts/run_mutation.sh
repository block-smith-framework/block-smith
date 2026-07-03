#!/bin/bash
#
# Run project
# Usage: bash run_mutation.sh <fragment-url> <r2-file> <dot-blocktests-dir> <mutants-dir> <blocktest-dir> <output-dir>
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

if [[ -f ${HOME}/.zshrc.pre-oh-my-zsh ]]; then
	source ${HOME}/.zshrc.pre-oh-my-zsh
fi
source ${SCRIPT_DIR}/constants.sh


FRAGMENT_URL=$1
R2_FILE=$2
DOT_BLOCKTESTS_DIR=$3
MUTANTS_DIR=$4
BLOCKTEST_DIR=$5
OUTPUT_DIR=$6
MANUAL_FILE=$7
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
PROJECT_DIR=${OUTPUT_DIR}/project
TARGET_FILE=${PROJECT_DIR}/${FILEPATH}

export FRAGMENT_ID="$(echo ${REPO} | tr / -)-${SHA}-${FILENAME}-${LINE_NUMBER}"

check_inputs() {
	if [[ ! -d ${BLOCKTEST_DIR} || ! -d ${MUTANTS_DIR} || -z ${OUTPUT_DIR} ]]; then
		echo "Usage: bash run_mutation.sh <fragment-url> <r2-file> <dot-blocktests-dir> <mutants-dir> <blocktest-dir> <output-dir>"
		exit 1
	elif [[ ! -f ${MANUAL_FILE} && ! -d ${DOT_BLOCKTESTS_DIR} ]]; then
		echo "Usage: bash run_mutation.sh <fragment-url> <r2-file> <dot-blocktests-dir> <mutants-dir> <blocktest-dir> <output-dir>"
		exit 1
	fi
	
	if [[ ! ${DOT_BLOCKTESTS_DIR} =~ ^/.* ]]; then
		DOT_BLOCKTESTS_DIR=${SCRIPT_DIR}/${DOT_BLOCKTESTS_DIR}
	fi
	
	if [[ ! ${MUTANTS_DIR} =~ ^/.* ]]; then
		MUTANTS_DIR=${SCRIPT_DIR}/${MUTANTS_DIR}
	fi
	
	if [[ ! ${BLOCKTEST_DIR} =~ ^/.* ]]; then
		BLOCKTEST_DIR=${SCRIPT_DIR}/${BLOCKTEST_DIR}
	fi
	
	if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
		OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
	fi
	
	# Reset
	rm -rf ${OUTPUT_DIR}
	
	mkdir -p ${OUTPUT_DIR}/logs
	mkdir -p ${OUTPUT_DIR}/data
	mkdir -p ${OUTPUT_DIR}/mutation

	cp -r ${MUTANTS_DIR} ${OUTPUT_DIR}/mutation/mutants
	
	echo "REPO: ${REPO}"
	echo "SHA: ${SHA}"
	echo "FILEPATH: ${FILEPATH}"
	echo "START_LINE: ${START_LINE}"
	echo "END_LINE: ${END_LINE}"
	echo "FILE_EXTRACTED_PATH: ${FILE_EXTRACTED_PATH}"
	echo "BLOCKTEST_PATH: ${BLOCKTEST_PATH}"
	
	export MAVEN_SETTINGS="true"
}

install() {
	if [[ ${IS_LOCAL} != true ]]; then
		export MAVEN_REPO=${OUTPUT_DIR}/repo
		export MAVEN_SETTINGS="true"
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
}

clone() {
	if [[ -d ${OUTPUT_DIR}/project ]]; then
		rm -rf ${OUTPUT_DIR}/project
	fi


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

	
	if [[ -d ${DOT_BLOCKTESTS_DIR} ]]; then
		cp -r ${DOT_BLOCKTESTS_DIR} ${OUTPUT_DIR}/project/.blocktests
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
}

insert() {
	if [[ -n ${MANUAL_FILE} && -f ${MANUAL_FILE} ]]; then
		cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original.java
		cp ${MANUAL_FILE} ${OUTPUT_DIR}/data/inserted.java
	else
		cp ${TARGET_FILE} ${OUTPUT_DIR}/data/original.java
		java -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar add-block-test ${R2_FILE}
		if [[ $? -ne 0 ]]; then
			echo "Failed to insert block test"
			exit 1
		fi
		cp ${TARGET_FILE} ${OUTPUT_DIR}/data/inserted.java
		cp ${OUTPUT_DIR}/data/original.java ${TARGET_FILE}
	fi
}

run() {
	# Remove argLine from pom (because we are not using JaCoCo)
	pushd ${PROJECT_DIR} &> /dev/null
	sed -i.bak "s/@{argLine} //g" pom.xml
	rm pom.xml.bak
	popd &> /dev/null
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/..)
	
	echo "Running: "
	if [[ -n ${MANUAL_FILE} && -f ${MANUAL_FILE} ]]; then
		echo "java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar mutation-score ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${OUTPUT_DIR}/mutation/mutants ${START_LINE} ${END_LINE} manual"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar mutation-score ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${OUTPUT_DIR}/mutation/mutants ${START_LINE} ${END_LINE} manual
	else
		echo "java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar mutation-score ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${OUTPUT_DIR}/mutation/mutants ${START_LINE} ${END_LINE} ${R2_FILE}"
		java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar mutation-score ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${OUTPUT_DIR}/mutation/mutants ${START_LINE} ${END_LINE} ${R2_FILE}
	fi
}

echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
install
clone
insert
run
