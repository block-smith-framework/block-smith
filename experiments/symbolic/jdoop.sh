#!/bin/bash
# Install JDoop

sudo apt-get -y install python2

JDART_DIR=/home/blockgen/jdart-project
mkdir -p ${JDART_DIR}

cd ${JDART_DIR}
git clone https://github.com/psycopaths/jdoop
cd jdoop
rm jdoop.ini
