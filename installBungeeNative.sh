#!/bin/bash
cd $(dirname "$0")

mkdir .tmp
cd .tmp

wget -nv http://ci.md-5.net/job/BungeeCord/ws/proxy/pom.xml
wget -nv http://ci.md-5.net/job/BungeeCord/ws/proxy/target/bungeecord-proxy-1.8-SNAPSHOT.jar
wget -nv http://ci.md-5.net/job/BungeeCord/ws/proxy/target/bungeecord-proxy-1.8-SNAPSHOT-javadoc.jar
wget -nv http://ci.md-5.net/job/BungeeCord/ws/proxy/target/bungeecord-proxy-1.8-SNAPSHOT-sources.jar

mvn -Dmaven.compiler.showWarnings=false install:install-file \
	-Dfile=bungeecord-proxy-1.8-SNAPSHOT.jar \
	-Djavadoc=bungeecord-proxy-1.8-SNAPSHOT-javadoc.jar \
	-Dsources=bungeecord-proxy-1.8-SNAPSHOT-sources.jar \
	-DpomFile=pom.xml
	
cd ../
rm -r .tmp