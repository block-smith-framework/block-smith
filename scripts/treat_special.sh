#!/bin/bash
project_name=$1
sha=$2
coverage=$3
project_name=$(echo ${project_name} | tr _ - | tr / -)

if [[ ${project_name} == george-haddad-cardme ]]; then
  echo "Patching george-haddad-cardme"
  sed -i.bak "s/1.5/1.8/" pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == mvel-mvel && ${sha} == "bac95e0cf8884a0713693161c589514a8a43fe87" ]]; then
  echo "Patching mvel-mvel"
  if [[ -z $(grep "@org" ./src/test/java/org/mvel2/tests/core/ControlFlowTests.java) ]]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
      sed -i '' $'/public void testCalculateAge/i\\\n    @org.junit.Ignore' ./src/test/java/org/mvel2/tests/core/ControlFlowTests.java
    else
      sed -i $'/public void testCalculateAge/i\\\n    @org.junit.Ignore' ./src/test/java/org/mvel2/tests/core/ControlFlowTests.java
    fi
  fi
fi

if [[ ${project_name} == Harium-keel && ${sha} == "e4e6a302eb6b7dcf6dab5d1167cf6ceef836d4db" ]]; then
  echo "Patching Harium-keel"
  sed -i.bak "s/<version>[/<version>/g" pom.xml
  rm pom.xml.bak
  
  sed -i.bak "s/,)<\/version>/<\/version>/g" pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == jai-imageio-jai-imageio-core && ${sha} == "f81bc1ab19faa210ad289c6ae2588bc1157fd07a" ]]; then
  echo "Patching jai-imageio-jai-imageio-core"
  sed -i.bak 's/<source>1.6<\/source>/<source>7<\/source>/' pom.xml
  rm pom.xml.bak
  
  sed -i.bak 's/<target>1.6<\/target>/<target>7<\/target>/' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == pfmiles-min-velocity && ${sha} == "4e8ec4f64681c1d6941bf8d90a51849e87b86cf5" ]]; then
  echo "Patching pfmiles-min-velocity"
  sed -i.bak 's/3.8.1/4.13.2/g' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == tommyettinger-RegExodus ]]; then
  echo "Patching tommyettinger-RegExodus"
  sed -i.bak 's|<argLine>-Dfile.encoding=${project.build.sourceEncoding}</argLine>|<argLine>@{argLine} -Dfile.encoding=${project.build.sourceEncoding}</argLine>|' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == STEMLab-JIneditor ]]; then
  echo "Patching STEMLab-JIneditor"
  sed -i.bak 's/<argLine>-Xmx1024m<\/argLine>/<argLine>@{argLine} -Xmx1024m<\/argLine>/' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == jruby-jcodings ]]; then
  echo "Patching jruby-jcodings"
  sed -i.bak 's/<argLine>-Dfile.encoding=UTF-8<\/argLine>/<argLine>@{argLine} -Dfile.encoding=UTF-8<\/argLine>/' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == ATLANTBH-owl ]]; then
  echo "Patching ATLANTBH-owl"
  sed -i.bak 's/<argLine>-Dfile.encoding=UTF-8<\/argLine>/<argLine>@{argLine} -Dfile.encoding=UTF-8<\/argLine>/' pom.xml
  rm pom.xml.bak
  
  sed -i.bak '
/<plugin>/{
  :a
  N
  /<\/plugin>/!ba
  /<groupId>com.github.eirslett<\/groupId>/d
}
' pom.xml
  rm pom.xml.bak
fi


if [[ ${project_name} == dingjs-javaagent ]]; then
  echo "Patching dingjs-javaagent"
  sed -i.bak 's|<project\.build\.target>1\.6</project\.build\.target>|<project.build.target>1.8</project.build.target>|g' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == magdel-MapNav ]]; then
  echo "Patching magdel-MapNav"
  sed -i.bak '
/<plugin>/,/<\/plugin>/{
  /<artifactId>maven-compiler-plugin<\/artifactId>/,/<\/plugin>/{
    s|<source>.*</source>|<source>1.8</source>|
    s|<target>.*</target>|<target>1.8</target>|
    /<compilerArgs>/,/<\/compilerArgs>/d
  }
}
' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == tud-fop-j-Algo ]]; then
  echo "Patching tud-fop-j-Algo"
  
  sed -i.bak '
/<plugin>/{
  :a
  N
  /<\/plugin>/!ba
  /<artifactId>maven-antrun-plugin<\/artifactId>/d
}
' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == demidenko05-beigesoft-accounting ]]; then
  echo "Patching demidenko05-beigesoft-accounting"
  sed -i.bak 's|<java\.version>1\.7</java\.version>|<java.version>1.8</java.version>|g' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == Harium-keel && ${sha} == "8b427e277df4b6c891617e6a0b9eea59c9d348d9" ]]; then
  echo "Patching Harium-keel"
  sed -i.bak -e 's/\[//g' -e 's/,)//g' pom.xml
  rm pom.xml.bak
fi


if [[ ${project_name} == Zettelkasten-Team-Zettelkasten ]]; then
  echo "Patching Zettelkasten-Team-Zettelkasten"
  sed -i.bak '/<dependency>/{
  :a
  N
  /<\/dependency>/!ba
  /surefire-testng/d
}' pom.xml
  sed -i.bak 's|<forkCount>3</forkCount>|<forkCount>1</forkCount>|' pom.xml
  sed -i.bak '/<reuseForks>/d' pom.xml
  sed -i.bak 's|<argLine>-Xmx1024m</argLine>||' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == lzgabel-camunda-cloud-bpmn-converter ]]; then
  echo "Patching lzgabel-camunda-cloud-bpmn-converter"
  awk '
  /<plugin>/ { block = $0; in_block = 1; next }
  in_block {
    block = block "\n" $0
    if (/<\/plugin>/) {
      if (block !~ /com\.diffplug\.spotless/) print block
      in_block = 0; block = ""
    }
    next
  }
  { print }
' pom.xml > pom.xml.tmp && mv pom.xml.tmp pom.xml
fi

if [[ ${project_name} == Azure-azure-functions-java-worker ]]; then
  echo "Patching Azure-azure-functions-java-worker"
  perl -i -0pe 's/( *)entry\.getValue\(\)\.computeFromValue\(\)\.ifPresent\(data ->\s+bindings\.add\(ParameterBinding\.newBuilder\(\)\.setName\(entry\.getKey\(\)\)\.setData\(data\)\.build\(\)\)\s*\);/$1entry.getValue().computeFromValue().ifPresent(data -> {\n$1    bindings.add(ParameterBinding.newBuilder().setName(entry.getKey()).setData(data).build());\n$1});/g' src/main/java/com/microsoft/azure/functions/worker/binding/BindingDataStore.java
  
  sed -i.bak '/<workingDirectory>${project.build.directory}<\/workingDirectory>/d' pom.xml
  rm pom.xml.bak

fi

if [[ ${project_name} == messai-engineering-Yuga ]]; then
  echo "Patching messai-engineering-Yuga"
  export BLOCKGEN_TESTING_FRAMEWORK="testng"
fi

if [[ ${project_name} == lettuce-io-lettuce-core ]]; then
  echo "Patching lettuce-io-lettuce-core"
  export BLOCKGEN_TESTING_FRAMEWORK="junit5"
fi

if [[ ${project_name} == Zettelkasten-Team-Zettelkasten ]]; then
  echo "Patching Zettelkasten-Team-Zettelkasten"
  export BLOCKGEN_TESTING_FRAMEWORK="junit5"
fi

if [[ ${project_name} == codeine-cd-codeine ]]; then
  echo "Patching codeine-cd-codeine"
  export SRC_DIRECTORY="src/common"
  export TEST_DIRECTORY="test"
fi

if [[ ${project_name} == jonatan-kazmierczak-class-visualizer ]]; then
  echo "Patching jonatan-kazmierczak-class-visualizer"
  export JAVA_AWT_HEADLESS=true
fi

if [[ ${FRAGMENT_ID} == jai-imageio-jai-imageio-core-f81bc1ab19faa210ad289c6ae2588bc1157fd07a-RawRenderedImage-L312_L319 ]]; then
  echo "Patching fragment jai-imageio-jai-imageio-core-f81bc1ab19faa210ad289c6ae2588bc1157fd07a-RawRenderedImage-L312_L319"
  export CAPTURE_ASSIGNED_VARIABLES=true
fi

if [[ ${FRAGMENT_ID} == maven-nar-nar-maven-plugin-e747b5ee080b243e0b817f722a43c9c203bb4df8-NarIntegrationTestMojo-L952_L955 ]]; then
  echo "Patching fragment maven-nar-nar-maven-plugin-e747b5ee080b243e0b817f722a43c9c203bb4df8-NarIntegrationTestMojo-L952_L955"
  export CAPTURE_ASSIGNED_VARIABLES=true
fi

if [[ ${FRAGMENT_ID} == alibaba-compileflow-e96a05224f71bf4aa3d9fb37bba85764b2fc153f-DataType-L1056_L1066 ]]; then
  echo "Patching fragment alibaba-compileflow-e96a05224f71bf4aa3d9fb37bba85764b2fc153f-DataType-L1056_L1066"
  export EVOSUITE_REPLACE_CALLS=false
fi

if [[ ${FRAGMENT_ID} == alibaba-compileflow-e96a05224f71bf4aa3d9fb37bba85764b2fc153f-DataType-L1041_L1047 ]]; then
  echo "Patching fragment alibaba-compileflow-e96a05224f71bf4aa3d9fb37bba85764b2fc153f-DataType-L1041_L1047"
  export EVOSUITE_REPLACE_CALLS=false
fi

if [[ ${project_name} == ThreeTen-threetenbp ]]; then
  echo "Patching ThreeTen-threetenbp"
  sed -i.bak 's/<argLine>-Xmx2G<\/argLine>/<argLine>@{argLine} -Xmx2G<\/argLine>/' pom.xml
  rm pom.xml.bak
fi

if [[ ${project_name} == maxmind-geoip-api-java ]]; then
  echo "Patching maxmind-geoip-api-java"
  export SUREFIRE_VERSION=3.5.5 # LAST WORKING VERSION, PIN IT
fi

if [[ ${project_name} == adobe-commerce-cif-magento-graphql ]]; then
  echo "Patching adobe-commerce-cif-magento-graphql"
  export SUREFIRE_VERSION=3.5.5 # LAST WORKING VERSION, PIN IT
fi

if [[ ${project_name} == hoijui-Jawk ]]; then
  echo "Patching hoijui-Jawk"
  export SUREFIRE_VERSION=3.5.5 # LAST WORKING VERSION, PIN IT
fi

if [[ ${project_name} == mangstadt-biweekly ]]; then
  echo "Patching mangstadt-biweekly"
  export SUREFIRE_VERSION=3.5.5 # LAST WORKING VERSION, PIN IT
fi

if [[ ${project_name} == fsantiag-sonar-clojure ]]; then
  echo "Patching fsantiag-sonar-clojure"
  export SUREFIRE_VERSION=3.5.5 # LAST WORKING VERSION, PIN IT
fi

if [[ ${FRAGMENT_ID} == magdel-MapNav-97c4f93f02a56aa1ce2b2ffc8d0cd41ed150e9b3-MapForms-L1022_L1049 ]]; then
  echo "Patching fragment magdel-MapNav-97c4f93f02a56aa1ce2b2ffc8d0cd41ed150e9b3-MapForms-L1022_L1049"
  export OVERRIDE_STATIC_FIELDS=true
fi
