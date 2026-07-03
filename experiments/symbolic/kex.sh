#!/bin/bash
# Install Kex

sudo apt install -y python3.9

cd ~/
git clone https://github.com/vorpal-research/kex
cd kex
export MAVEN_SETTINGS="true"
mvn clean package -DskipTests
