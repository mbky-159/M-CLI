FROM eclipse-temurin:17-jdk-jammy

# Lab image only. Pin reviewed image digests before a public release.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 maven ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 sandbox \
    && useradd --uid 10001 --gid 10001 --create-home sandbox

USER 10001:10001
ENV HOME=/home/sandbox
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:ActiveProcessorCount=1"
WORKDIR /workspace
CMD ["bash"]
