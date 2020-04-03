#!/bin/bash -x
export MAVEN_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8001,suspend=n"
#export JENKINS_HOME=/home/mamh/github/jenkins_home

mvn  -DskipTests=true   package hpi:run

