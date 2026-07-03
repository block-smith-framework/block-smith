#!/bin/bash
#
# Given a project, run block test and measure time
# Usage: bash use_unittests.sh <project-dir> <target-file> <taget-line-start> <traget-line-end> <output-dir> <extension-dir> <blocktest-dir>
#

generate_test() {
	TEST_FILENAME="$(basename ${BLOCKTEST_FILE} | cut -d '.' -f 1)BlockTest"
	local absolute_path=$(realpath ${BLOCKTEST_FILE})

	if [[ ! -f ${OUTPUT_DIR}/deps.txt ]]; then
		# Find classpath
		mvn dependency:build-classpath -Dmdep.outputFile=${OUTPUT_DIR}/deps.txt &> ${OUTPUT_DIR}/logs/deps.log
		echo "$(cat ${OUTPUT_DIR}/deps.txt):$(pwd)/target/classes" > ${OUTPUT_DIR}/deps2.txt
		mv ${OUTPUT_DIR}/deps2.txt ${OUTPUT_DIR}/deps.txt
	fi
	popd &> /dev/null
	
	if [[ -z $(head -n 3 ${BLOCKTEST_FILE} | grep "TEST_DIR") ]]; then
		fullpath_dir=$(dirname $(echo "${absolute_path}" | sed 's/\/main\//\/test\//'))
	else
		fullpath_dir=${PROJECT_DIR}/$(head -n 3 ${BLOCKTEST_FILE} | grep "TEST_DIR" | cut -d ':' -f 2 | cut -d ' ' -f 2)/
	fi
	
	echo "Generating test"
	
	local framework=""
	if [[ -n $(head -n 3 ${BLOCKTEST_FILE} | grep "FRAMEWORK: junit5") ]]; then
		framework=" --junit_version=junit5"
		echo "JUnit 5"
	elif [[ -n $(head -n 3 ${BLOCKTEST_FILE} | grep "FRAMEWORK: testng") ]]; then
		framework=" --junit_version=testng"
		echo "TestNG"
	elif [[ -n $(head -n 3 ${BLOCKTEST_FILE} | grep "FRAMEWORK: junit4") ]]; then
		echo "Adding JUnit"
		export ADD_JUNIT=1
	fi
	
	local deps_file=${OUTPUT_DIR}/deps.txt
	local app_src=${PROJECT_DIR}/src/main/java
	
	pushd ${BLOCKTEST_DIR} &> /dev/null

	# Actually generate tests
	(time mvn exec:java -Dexec.mainClass="org.blocktest.BlockTestRunnerSourceCode" -Dexec.args="--input_file=${absolute_path} --output_dir=${fullpath_dir}${framework} --dep_file_path=${deps_file} --app_src_path=${app_src} --loadXml=true --rewriteStaticVar=false") &> ${OUTPUT_DIR}/logs/gen-timed.log
	popd &> /dev/null
	
	if [[ ! -f ${fullpath_dir}/${TEST_FILENAME}.java ]]; then
		echo "No test file is generated"
		exit 1
	fi
	
	if [[ -z $(grep "@Test" ${fullpath_dir}/${TEST_FILENAME}.java) ]]; then
		echo "No test case is generated"
		exit 1
	fi
	
	cp ${fullpath_dir}/${TEST_FILENAME}.java ${OUTPUT_DIR}/logs/${TEST_FILENAME}.java
}

run_blocktest() {
	echo "Running blocktest..."
	local extension_jar="${EXTENSION_DIR}/target/blockgen-extension-1.0.jar"
	
	if [[ -z ${TEST_FILENAME} ]]; then
		echo "Unable to find test file name"
		exit 1
	fi
	
	if [[ -n $(head -n 3 ${BLOCKTEST_FILE} | grep "WORKING_DIR") ]]; then
		pushd ${OUTPUT_DIR}/project/$(head -n 3 ${BLOCKTEST_FILE} | grep "WORKING_DIR" | cut -d ':' -f 2 | cut -d ' ' -f 2) &> /dev/null
	else
		pushd ${OUTPUT_DIR}/project &> /dev/null
	fi
	
	(time mvn ${SKIP} -Dmaven.ext.class.path=${extension_jar} test -Dtest="${TEST_FILENAME}") &> ${OUTPUT_DIR}/logs/run-timed.log
	local status=$?
	echo ">>> Run generated block tests status: ${status}"
	popd &> /dev/null
	
	unset ADD_AGENT
	unset MOP_AGENT_PATH
}

run_blocktests() {
	PROJECT_DIR=$1
	BLOCKTEST_FILE=$2
	EXTENSION_DIR=$3
	OUTPUT_DIR=$4
	BLOCKTEST_DIR=$5
	
	cd ${PROJECT_DIR}
	generate_test
	run_blocktest
}
