FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    jq \
    lsof \
    maven \
    openjdk-21-jdk \
    python3 \
    python3-pip \
    unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/dynamicloader
COPY . /opt/dynamicloader
RUN chmod +x setup.sh gradlew docker-entrypoint.sh
RUN AUTO_CONFIRM=1 SKIP_SERVER_START=1 ./setup.sh

EXPOSE 25565 25566 25577

ENTRYPOINT ["/opt/dynamicloader/docker-entrypoint.sh"]
