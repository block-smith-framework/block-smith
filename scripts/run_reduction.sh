#!/bin/bash
#
# Run project
# Usage: bash run_reduction.sh <fragment-url> <r0-files-dir> <r1-files-dir> <dot-blocktests-dirs-dir> <mutants-dir> <blocktest-dir> <output-dir>
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

if [[ -f ${HOME}/.zshrc.pre-oh-my-zsh ]]; then
	source ${HOME}/.zshrc.pre-oh-my-zsh
fi
source ${SCRIPT_DIR}/constants.sh


FRAGMENT_URL=$1
R0_FILES=$2
R1_FILES=$3
DOT_BLOCKTESTS_DIRS=$4
MUTANTS_DIR=$5
BLOCKTEST_DIR=$6
OUTPUT_DIR=$7
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
	if [[ ! -d ${DOT_BLOCKTESTS_DIRS} || ! -d ${BLOCKTEST_DIR} || ! -d ${MUTANTS_DIR} || -z ${OUTPUT_DIR} ]]; then
		echo "Usage: bash run_mutation.sh <fragment-url> <r0-files-dir> <r1-files-dir> <dot-blocktests-dirs-dir> <mutants-dir> <blocktest-dir> <output-dir>"
		exit 1
	fi
	
	if [[ ! ${R0_FILES} =~ ^/.* ]]; then
		R0_FILES=${SCRIPT_DIR}/${R0_FILES}
	fi
	
	if [[ ! ${R1_FILES} =~ ^/.* ]]; then
		R1_FILES=${SCRIPT_DIR}/${R1_FILES}
	fi
	
	if [[ ! ${DOT_BLOCKTESTS_DIRS} =~ ^/.* ]]; then
		DOT_BLOCKTESTS_DIRS=${SCRIPT_DIR}/${DOT_BLOCKTESTS_DIRS}
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

	mkdir -p ${OUTPUT_DIR}/logs
	mkdir -p ${OUTPUT_DIR}/data
	cp -r ${MUTANTS_DIR} ${OUTPUT_DIR}/mutation
	
	echo "REPO: ${REPO}"
	echo "SHA: ${SHA}"
	echo "FILEPATH: ${FILEPATH}"
	echo "START_LINE: ${START_LINE}"
	echo "END_LINE: ${END_LINE}"
	echo "FILE_EXTRACTED_PATH: ${FILE_EXTRACTED_PATH}"
	echo "BLOCKTEST_PATH: ${BLOCKTEST_PATH}"

	touch ${OUTPUT_DIR}/merged_r0.txt
	touch ${OUTPUT_DIR}/merged_r1.txt
	if [[ ${R0_FILES} == ${R1_FILES} && -f ${R0_FILES} ]]; then
		cp ${R0_FILES} ${OUTPUT_DIR}/merged_r0.txt
	else
		# Merge r0
		for file in $(ls ${R0_FILES}); do
			technique_name=$(echo ${file} | cut -d '.' -f 1)
			sed -i.bak "s/@\([0-9]*\.\(xml\|txt\|java\)\)/@${technique_name}\/\1/g" ${R0_FILES}/${file}
			sed -i.bak "s/AUTO_GEN_\([0-9]*\)/AUTO_GEN_\1_${technique_name}/g" ${R0_FILES}/${file}
			rm -rf ${file}.bak ${R0_FILES}/${file}.bak
			cat ${R0_FILES}/${file} >> ${OUTPUT_DIR}/merged_r0.txt
		done
		
		# Merge r1
		for file in $(ls ${R1_FILES}); do
			technique_name=$(echo ${file} | cut -d '.' -f 1)
			sed -i.bak "s/@\([0-9]*\.\(xml\|txt\|java\)\)/@${technique_name}\/\1/g" ${R1_FILES}/${file}
			sed -i.bak "s/AUTO_GEN_\([0-9]*\)/AUTO_GEN_\1_${technique_name}/g" ${R1_FILES}/${file}
			rm -rf ${file}.bak ${R1_FILES}/${file}.bak
			cat ${R1_FILES}/${file} >> ${OUTPUT_DIR}/merged_r1.txt
		done
	fi

	export MAVEN_SETTINGS="true"
}

install() {
	if [[ ${IS_LOCAL} != true ]]; then
		if [[ -z ${MAVEN_REPO} ]]; then
			export MAVEN_REPO=${OUTPUT_DIR}/repo
		fi

		# TODO: SKIP
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

	
	mkdir -p ${OUTPUT_DIR}/project/.blocktests
	cp -r ${DOT_BLOCKTESTS_DIRS} ${OUTPUT_DIR}/project/.blocktests/serialized-data
	
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
	java -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar add-block-test ${OUTPUT_DIR}/merged_r0.txt
	if [[ $? -ne 0 ]]; then
		echo "Failed to insert block test"
		exit 1
	fi
	cp ${TARGET_FILE} ${OUTPUT_DIR}/data/inserted.java
}

run() {
	# Remove argLine from pom (because we are not using JaCoCo)
	pushd ${PROJECT_DIR} &> /dev/null
	sed -i.bak "s/@{argLine} //g" pom.xml
	rm pom.xml.bak
	popd &> /dev/null
	
	local maven_home=$(mvn --version | grep "Maven home" | cut -d ' ' -f 3)
	local block_gen_dir=$(realpath ${SCRIPT_DIR}/..)
	
	echo "Running: reduction"
	java -Dmaven.home=${maven_home} -jar ${SCRIPT_DIR}/../blockgen/target/blockgen-1.0.jar mutation ${PROJECT_DIR} ${TARGET_FILE} ${OUTPUT_DIR}/mutation ${block_gen_dir} ${OUTPUT_DIR}/data/inserted.java ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/merged_r0.txt ${OUTPUT_DIR}/merged_r1.txt true
	
	if [[ -d ${OUTPUT_DIR}/project/.blocktests ]]; then
		mv ${OUTPUT_DIR}/project/.blocktests  ${OUTPUT_DIR}/dot-blocktests
	fi
}

echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
install
clone
insert
run
