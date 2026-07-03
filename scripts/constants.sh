#!/bin/bash
TIMEOUT="10800s"
CHECK_PROJECT_TIMEOUT="3600s"
SKIP="-Dmaven.nbm.verify=skip -Dcheckstyle.skip -Drat.skip -Denforcer.skip -Danimal.sniffer.skip -Dmaven.javadoc.skip -Dfindbugs.skip -Dwarbucks.skip -Dmodernizer.skip -Dimpsort.skip -Dpmd.skip -Dxjc.skip -Djacoco.skip -Dinvoker.skip -DskipDocs -DskipITs -Dmaven.plugin.skip -Dlombok.delombok.skip -Dlicense.skipUpdateLicense -Dremoteresources.skip -Dlicense.skip -Dgpg.skip -Dspotbugs.skip -Dmaven.antrun.skip -Dfmt.skip -Dair.check.skip-all"
SKIP_WITH_JACOCO="-Dmaven.nbm.verify=skip -Dcheckstyle.skip -Drat.skip -Denforcer.skip -Danimal.sniffer.skip -Dmaven.javadoc.skip -Dfindbugs.skip -Dwarbucks.skip -Dmodernizer.skip -Dimpsort.skip -Dpmd.skip -Dxjc.skip -Dinvoker.skip -DskipDocs -DskipITs -Dmaven.plugin.skip -Dlombok.delombok.skip -Dlicense.skipUpdateLicense -Dremoteresources.skip -Dlicense.skip -Dgpg.skip -Dspotbugs.skip -Dmaven.antrun.skip -Dfmt.skip -Dair.check.skip-all"
TMP_DIR=/tmp
IS_LOCAL=${IS_LOCAL:-true}
export KEX_PATH=/home/blockgen/kex/kex.py
export JDOOP_PATH=/home/blockgen/jdart-project/jdoop
export LD_LIBRARY_PATH=/home/blockgen/jdart-project/z3/bin
export JPF_CORE_PATH=/home/blockgen/jdart-project/jpf-core
export JDART_PATH=/home/blockgen/jdart-project/jdart


epoch_ms() {
	if [[ "$(uname)" == "Darwin" ]]; then
		# macOS
		python3 -c 'import time; print(int(time.time() * 1000))'
	else
		# Linux (Ubuntu)
		date +%s%3N
	fi
}
