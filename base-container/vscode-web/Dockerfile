FROM codercom/code-server:latest

RUN sudo mkdir -p /var/local/programs/ && \
    sudo chown -R $(whoami):$(whoami)  /var/local/programs/ && \
    cd /var/local/programs/ && \
    wget https://github.com/neovim/neovim/releases/download/v0.10.1/nvim-linux64.tar.gz && \
    tar xzvf nvim-linux64.tar.gz

ENV PATH=$PATH:/var/local/programs/nvim-linux64/bin