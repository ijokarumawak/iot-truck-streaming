#!/bin/bash

wget http://apache.cs.utah.edu/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz
mv apache-maven-3.3.9-bin.tar.gz /tmp
tar xvzf /tmp/apache-maven-*-bin.tar.gz -C /usr
mv /usr/apache-maven* /usr/maven

echo 'M2_HOME=/usr/maven' >> ~/.bash_profile
echo 'M2=$M2_HOME/bin' >> ~/.bash_profile
echo 'PATH=$M2:$PATH' >> ~/.bash_profile
