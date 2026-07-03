SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

cd ${SCRIPT_DIR}/../

docker build --build-arg -f Docker/Dockerfile . --tag=blocksmith:latest
