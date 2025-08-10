#!/bin/bash

user=uknown
NEO4J_UNAME=neo4j

USER_UID="$(id -u $user)"
USER_GID="$(id -g $user)"


FILE=/c/Users
if [ -d "$FILE" ]; then
  NEO4J_GID="4444"
  NEO4J_UID="4444"
else
  NEO4J_UID="$(id -u $NEO4J_UNAME)"
  NEO4J_GID="$(id -g $NEO4J_UNAME)"
fi

docker build \
 --build-arg USER_UID="$USER_UID" \
 --build-arg USER_GID="$USER_GID" \
 --build-arg NEO4J_UID="$NEO4J_UID" \
 --build-arg NEO4J_GID="$NEO4J_GID" \
 --build-arg NEO4J_UNAME="$NEO4J_UNAME" \
 -t localhost:5000/loudsight/dev-container:0.0.1 .
