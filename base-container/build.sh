#!/bin/bash

user=ubuntu

USER_UID="$(id -u $user)"
USER_GID="$(id -g $user)"


FILE=/c/Users

mkdir -p build/opt
cp $WORK_ROOT/myEnv.sh build/opt/.

docker build \
 --build-arg USER_UID="$USER_UID" \
 --build-arg USER_GID="$USER_GID" \
 -t localhost:5001/loudsight/dev-container:0.0.1 .


rm -rf build