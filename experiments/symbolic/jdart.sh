#!/bin/bash
# Install JDart

sudo apt-get -y install ant

JDART_DIR=/home/blockgen/jdart-project
mkdir ${JDART_DIR}

cd ${JDART_DIR}
git clone https://github.com/javapathfinder/jpf-core.git
cd ${JDART_DIR}/jpf-core
git checkout JPF-8.0
ant

cd ${JDART_DIR}
git clone https://github.com/psycopaths/jconstraints.git
cd ${JDART_DIR}/jconstraints
git checkout jconstraints-0.9.1
mvn install

cd ${JDART_DIR}
wget https://github.com/Z3Prover/z3/releases/download/z3-4.4.1/z3-4.4.1-x64-ubuntu-14.04.zip 
unzip z3-4.4.1-x64-ubuntu-14.04.zip && rm z3-4.4.1-x64-ubuntu-14.04.zip
ln -s z3-4.4.1-x64-ubuntu-14.04 z3
cd ${JDART_DIR}/z3/bin
mvn install:install-file -Dfile=com.microsoft.z3.jar -DgroupId=com.microsoft -DartifactId=z3 -Dversion=4.4.1 -Dpackaging=jar
export LD_LIBRARY_PATH=${JDART_DIR}/z3/bin

cd ${JDART_DIR}
git clone https://github.com/psycopaths/jconstraints-z3.git 
cd ${JDART_DIR}/jconstraints-z3
git checkout jconstraints-z3-0.9.0
mvn install

cd ${JDART_DIR}
git clone https://github.com/javapathfinder/jpf-nhandler.git
cd ${JDART_DIR}/jpf-nhandler
git checkout 8181cfe
ant

mkdir -p ~/.jpf/
echo "jpf-core = ${JDART_DIR}/jpf-core" >> ~/.jpf/site.properties
echo "jpf-jdart = ${JDART_DIR}/jdart" >> ~/.jpf/site.properties
echo "jpf-nhandler = ${JDART_DIR}/jpf-nhandler" >> ~/.jpf/site.properties
echo "extensions=\${jpf-core},\${jpf-jdart}" >> ~/.jpf/site.properties

mkdir -p ~/.jconstraints/extensions
cp ${JDART_DIR}/jconstraints-z3/target/jconstraints-z3-0.9.0.jar ~/.jconstraints/extensions
cp ~/.m2/repository/com/microsoft/z3/4.4.1/z3-4.4.1.jar ~/.jconstraints/extensions/com.microsoft.z3.jar

cd ${JDART_DIR}
git clone https://github.com/psycopaths/jdart.git 
cd ${JDART_DIR}/jdart
ant

mkdir -p /home/blockgen/tests