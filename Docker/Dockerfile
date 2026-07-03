FROM ubuntu:20.04

ARG GITHUB_TOKEN

RUN \
  apt-get update && \
  apt-get install -y software-properties-common && \
# Install Git
  apt-get install -y git && \
# Install python
  apt-get update && \
  apt-get install -y python3 python3-pip python-is-python3 && \
  rm -rf /var/lib/apt/lists/* && \
# Install misc
  apt-get update && \
  apt-get install -y sudo && \
  apt-get install -y vim && \
  apt-get install -y emacs && \  
  apt-get install -y wget && \
  apt-get install -y bc && \
  apt-get install -y cloc && \
  apt-get install -y zip unzip && \
  apt-get install -y locales locales-all && \
  apt-get install -y parallel && \
  apt-get install -y pigz && \
# Install OpenJDK 8
  apt-get install -y openjdk-8-jdk && \
  apt-get install -y openjdk-8-dbg && \
  apt-get install -y gdb && \
  mv /usr/lib/jvm/java-8-openjdk* /usr/lib/jvm/java-8-openjdk

# Set up user (blockgen)
RUN useradd -ms /bin/bash -c "blockgen" blockgen && echo "blockgen:docker" | chpasswd && adduser blockgen sudo

USER blockgen

WORKDIR /home/blockgen/

# Use OpenJDK 8 when building the docker image
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk

# Install Maven 3.5.4 locally for user
RUN \
  wget http://mirrors.ibiblio.org/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz && \
  tar -xzf apache-maven-3.9.16-bin.tar.gz && mv apache-maven-3.9.16/ apache-maven/ && \
  rm apache-maven-3.9.16-bin.tar.gz && \
  cd /home/blockgen/ && \
  tail -n +10 ~/.bashrc > ~/tmp-bashrc && \
  mv ~/tmp-bashrc ~/.bashrc && \
  wget https://github.com/eclipse-aspectj/aspectj/releases/download/V1_9_7/aspectj-1.9.7.jar && \
  mkdir aspectj-1.9.7 && /usr/lib/jvm/java-8-openjdk/bin/java -jar aspectj-1.9.7.jar -to aspectj-1.9.7 && rm aspectj-1.9.7.jar && \
  sed -i 's/64M/10240M/g' aspectj-1.9.7/bin/ajc && \
  echo 'JAVAHOME=/usr/lib/jvm/java-8-openjdk' >> ~/.bashrc && \
  echo 'export JAVA_HOME=${JAVAHOME}' >> ~/.bashrc && \
  echo 'export M2_HOME=${HOME}/apache-maven' >> ~/.bashrc && \
  echo 'export MAVEN_HOME=${HOME}/apache-maven' >> ~/.bashrc && \
  echo 'ASPECTJ_DIR=${HOME}/aspectj-1.9.7' >> ~/.bashrc && \
  echo 'export PATH=${M2_HOME}/bin:${JAVAHOME}/bin:${ASPECTJ_DIR}/bin:${ASPECTJ_DIR}/lib/aspectjweaver.jar:/home/blockgen/.local/bin:${PATH}' >> ~/.bashrc && \
  echo 'export CLASSPATH=${ASPECTJ_DIR}/lib/aspectjtools.jar:${ASPECTJ_DIR}/lib/aspectjrt.jar:${ASPECTJ_DIR}/lib/aspectjweaver.jar:${CLASSPATH}' >> ~/.bashrc

# Install BlockSmith and the extension we made to BDK
RUN \
  cd /home/blockgen/ && \
  git clone https://github.com/block-smith-framework/block-smith block-gen && \
  mkdir /home/blockgen/block-tests/ && \
  mv /home/blockgen/block-gen/experiments/blocktest /home/blockgen/block-tests/ && \
  cd /home/blockgen/block-tests/blocktest && \
  /home/blockgen/apache-maven/bin/mvn install && \
  cd /home/blockgen/block-gen/blockgen && \
  /home/blockgen/apache-maven/bin/mvn install && \
  cd /home/blockgen/block-gen/extension && \
  /home/blockgen/apache-maven/bin/mvn install

# Install universalmutator (For mutation testing)
# Source: https://github.com/agroce/universalmutator
RUN \
  git clone https://github.com/agroce/universalmutator && \
  cd universalmutator && git checkout 5ac925503ab6618a4ffc3ca4146661a74e8781cc && cd .. && \
  cp /home/blockgen/block-gen/experiments/patches/genmutants.py universalmutator/universalmutator/genmutants.py && \
  cd universalmutator && pip install .

ENV LC_ALL en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US.UTF-8
ENV PATH=$PATH:/home/blockgen/.local/bin
