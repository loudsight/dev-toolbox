#FROM mcr.microsoft.com/devcontainers/base:debian-12 AS dev-container
FROM docker.io/python:3.13-slim-bookworm AS dev-container

ARG USER_UID
ARG USER_GID
ARG HOST

ADD .bashrc /home/uknown/.bashrc
ADD build/opt/myEnv.sh /opt/myEnv.sh

RUN ln -fs /usr/bin/python3 /usr/bin/python && \
    apt-get update && apt-get install -y dos2unix git wget curl sudo unzip libglib2.0-0 libnss3 neovim xz-utils \
    virtualenv gcc libssl-dev pkg-config net-tools iputils-ping python3-pip && \
    groupadd -f -g ${USER_GID} uknown && \
    useradd -m uknown -u ${USER_UID} -g ${USER_GID} --groups audio,video,sudo 2>/dev/null || true && \
    echo "uknown ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/uknown && \
    chmod 0440 /etc/sudoers.d/uknown && \
    mkdir -p /home/uknown/python/venvs/ && \
    dos2unix /home/uknown/.bashrc && \
    cd /home/uknown/python/venvs/ && \
    virtualenv working && \
    chown -R uknown:uknown /home/uknown && \
    chmod -R o+rw /home/uknown && \
    chown -R uknown:uknown /opt && \
    cd / && \
    echo "$(date)" > /home/created_on.txt

ENV PATH="/home/programs/nvm/versions/node/current/bin/:${PATH}"

USER uknown