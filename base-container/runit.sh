#!/bin/bash

#docker run -it -v c:\\dev\\code:/opt/code -v c:/dev/programs:/opt/programs -p8080:8080  -p3333:3333 localhost:5001/loudsight/dev-container:0.0.1 zsh

docker run  --privileged -it -v c:\\dev\\code\\synergisms\\:/code/ -p8080:8080 -p3333:3333 -t localhost:5001/loudsight/dev-container:0.0.3 zsh
