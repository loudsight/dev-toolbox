# This will start a code-server container and expose it at http://127.0.0.1:8080.
# It will also mount your current directory into the container as `/home/coder/project`
# and forward your UID/GID so that all file system operations occur as your user outside
# the container.
#
# Your $HOME/.config is mounted at $HOME/.config within the container to ensure you can
# easily access/modify your code-server config in $HOME/.config/code-server/config.json
# outside the container.
mkdir -p c:/dev/.config
docker run -it --name code-server -p 127.0.0.1:8080:8080 \
  -v "c:/dev/.config:/home/coder/.config" \
  -v "c:/dev/code/synergisms/:/home/coder/project" \
  -u "$(id -u):$(id -g)" \
  -e "DOCKER_USER=$USER" \
  localhost:5001/loudsight/vscode-container:0.0.1
