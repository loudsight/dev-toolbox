FROM docker.io/python:3.12-slim AS dev-container

ARG NEO4J_UID

ARG NEO4J_GID
ARG NEO4J_UNAME
ARG USER_UID
ARG USER_GID
ARG HOST

ADD .bashrc /home/uknown/.bashrc

RUN ln -fs /usr/bin/python3 /usr/bin/python; \
    apt-get update && apt-get install -y dos2unix git wget curl sudo unzip libglib2.0-0 libnss3 neovim xz-utils \
    virtualenv gcc libssl-dev pkg-config net-tools iputils-ping python3-pip && \
    groupadd -g ${NEO4J_GID} ${NEO4J_UNAME}; \
    useradd ${NEO4J_UNAME} -u ${NEO4J_UID} -g ${NEO4J_GID}; \
    groupadd -g ${USER_GID} uknown; \
    useradd uknown -u ${USER_UID} -g ${USER_GID} --create-home --groups audio,video,sudo; \
    echo "uknown ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/uknown && \
    chmod 0440 /etc/sudoers.d/uknown && \
    chown -R uknown:uknown /home/uknown && \
    mkdir -p /opt/programs/python/venvs/ && \
    dos2unix /home/uknown/.bashrc && \
    cd /opt/programs/python/venvs/ && \
    virtualenv working && \
    chown -R uknown:uknown /opt && \
    cd -

ENV PATH="/home/programs/nvm/versions/node/current/bin/:${PATH}"

RUN mkdir -p /home/uknown && chown uknown:uknown /home/uknown && \
    chmod -R o+rw /home/uknown && \
    echo "$(date)" > /home/created_on.txt

USER uknown