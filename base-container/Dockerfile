#FROM mcr.microsoft.com/devcontainers/base:debian-12 AS dev-container
FROM docker.io/python:3.13-slim-bookworm AS dev-container

# Base image for essentially every deployment module's Dockerfile (backend-server,
# authentication-server, dev-web, aeron-media-driver, persistence-server, and several
# platform/* services) - it is never itself "in use" by a running container between builds,
# so scripts/jx host-clean-storage's aggressive `docker image prune -af` was sweeping it after 24h
# with nothing to detect/rebuild it, silently breaking every deploy that depends on it until
# someone noticed. This label lets jx host-clean-storage exclude it explicitly.
LABEL retain="true"

ARG USER_UID
ARG USER_GID
ARG HOST

ADD .bashrc /home/synergy/.bashrc
ADD build/opt/myEnv.sh /opt/myEnv.sh

RUN ln -fs /usr/bin/python3 /usr/bin/python && \
    apt-get update && apt-get install -y dos2unix git wget curl sudo unzip libglib2.0-0 libnss3 neovim xz-utils \
    virtualenv gcc libssl-dev pkg-config net-tools iputils-ping python3-pip && \
    groupadd -f -g ${USER_GID} synergy && \
    useradd -m synergy -u ${USER_UID} -g ${USER_GID} --groups audio,video,sudo 2>/dev/null || true && \
    echo "synergy ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/synergy && \
    chmod 0440 /etc/sudoers.d/synergy && \
    mkdir -p /home/synergy/python/venvs/ && \
    dos2unix /home/synergy/.bashrc && \
    cd /home/synergy/python/venvs/ && \
    virtualenv working && \
    chown -R synergy:synergy /home/synergy && \
    chmod -R o+rw /home/synergy && \
    chown -R synergy:synergy /opt && \
    cd / && \
    echo "$(date)" > /home/created_on.txt

ENV PATH="/home/programs/nvm/versions/node/current/bin/:${PATH}"
# activate_pyworking() in /opt/myEnv.sh resolves the venv from here (this image builds it at a
# path its WORK_ROOT/PROGRAMS_ROOT heuristics do not reach).
ENV LOUDSIGHT_PYVENV=/home/synergy/python/venvs/working

USER synergy