#!/bin/bash

#user=uknown
#NEO4J_UNAME=neo4j
#
#USER_UID="$(id -u $user)"
#USER_GID="$(id -g $user)"
#
#
#FILE=/c/Users
#if [ -d "$FILE" ]; then
#  NEO4J_GID="4444"
#  NEO4J_UID="4444"
#else
#  NEO4J_UID="$(id -u $NEO4J_UNAME)"
#  NEO4J_GID="$(id -g $NEO4J_UNAME)"
#fi

docker build \
 -t localhost:5000/loudsight/vscode-container:0.0.1 .
