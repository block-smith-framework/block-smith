#!/bin/bash
#
# Run project
# Usage: bash run_project.sh [-m <get_r2:true/false>] <exli/genie> <fragment-url> <blocktest-dir> <output-dir>
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

if [[ -f ${HOME}/.zshrc.pre-oh-my-zsh ]]; then
	source ${HOME}/.zshrc.pre-oh-my-zsh
fi
source ${SCRIPT_DIR}/constants.sh

PERFORM_REDUCTION=true
GENERATION_TOOL=""
while getopts :m:t: opts; do
	case "${opts}" in
		m ) PERFORM_REDUCTION="${OPTARG}" ;;
		t ) GENERATION_TOOL="${OPTARG}" ;;
	esac
done
shift $((${OPTIND} - 1))

MODE=$1
FRAGMENT_URL=$2
BLOCKTEST_DIR=$3
OUTPUT_DIR=$4

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
		echo "Usage: bash run_project.sh [-m <get_r2:true/false>] <exli/genie> <fragment-url> <blocktest-dir> <output-dir>"
		exit 1
	fi
	
	if [[ ${MODE} != "exli" && ${MODE} != "genie" && ${MODE} != "smack" ]]; then
		echo "Mode ${MODE} is not exli/genie/smack"
		exit 1
	fi
	
	if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
		OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
	fi
	
	mkdir -p ${OUTPUT_DIR}/logs
	
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

run() {
	if [[ ${MODE} == "exli" ]]; then
		local use_developer=false
		local use_randoop=false
		local use_randoop_class=false
		local use_evosuite=false
		if [[ -n ${GENERATION_TOOL} && ${GENERATION_TOOL} != "none" ]]; then
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "randoop-class") ]]; then
				use_randoop_class=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | sed 's/randoop-class//Ig' | grep -i "randoop") ]]; then
				use_randoop=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "evosuite") ]]; then
				use_evosuite=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "developer") ]]; then
				use_developer=true
			fi
		fi
		${SCRIPT_DIR}/pipelines/use_exli.sh -d ${use_developer} -r ${use_randoop} -c ${use_randoop_class} -e ${use_evosuite} -m ${PERFORM_REDUCTION} ${OUTPUT_DIR}/project ${OUTPUT_DIR}/project/${FILEPATH} ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/exli-output ${SCRIPT_DIR}/../extension ${BLOCKTEST_DIR}
		
		if [[ -d ${OUTPUT_DIR}/project/.blocktests ]]; then
			mv ${OUTPUT_DIR}/project/.blocktests  ${OUTPUT_DIR}/exli-output/dot-blocktests
		fi
	else
		local use_randoop=true
		local use_randoop_class=true
		local use_evosuite=true
		local use_kex=true
		local use_jdoop=true
		local use_jdart=true
		if [[ -n ${GENERATION_TOOL} ]]; then
			use_randoop=false
			use_randoop_class=false
			use_evosuite=false
			use_kex=false
			use_jdoop=false
			use_jdart=false

			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "randoop-class") ]]; then
				use_randoop_class=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | sed 's/randoop-class//Ig' | grep -i "randoop") ]]; then
				use_randoop=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "evosuite") ]]; then
				use_evosuite=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "kex") ]]; then
				use_kex=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "jdoop") ]]; then
				use_jdoop=true
			fi
			if [[ -n $(echo ${GENERATION_TOOL} | grep -i "jdart") ]]; then
				use_jdart=true
			fi
		fi
		${SCRIPT_DIR}/pipelines/use_genie.sh -r ${use_randoop} -c ${use_randoop_class} -e ${use_evosuite} -k ${use_kex} -j ${use_jdoop} -d ${use_jdart} -m ${PERFORM_REDUCTION} ${OUTPUT_DIR}/project ${OUTPUT_DIR}/project/${FILEPATH} ${START_LINE} ${END_LINE} ${OUTPUT_DIR}/genie-output ${SCRIPT_DIR}/../extension ${BLOCKTEST_DIR}
		
		if [[ -d ${OUTPUT_DIR}/project/.blocktests ]]; then
			mv ${OUTPUT_DIR}/project/.blocktests  ${OUTPUT_DIR}/genie-output/dot-blocktests
		fi
	fi
}

echo "BLOCKGEN version: ($(git rev-parse HEAD) - $(date +%s))"
check_inputs
clone
run
