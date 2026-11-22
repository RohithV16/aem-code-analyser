# syntax=docker/dockerfile:1
#
# AEM Code Analyser — runs the released shaded CLI jar on a bare JRE, so a CI agent
# needs a container runtime instead of a JDK. The jar is staged by the release job in
# target/release-assets/ (see .github/workflows/ci.yml) before this file is built.
FROM eclipse-temurin:21-jre

ARG VERSION=0.0.0-dev
ARG REVISION=unknown

LABEL org.opencontainers.image.title="AEM Code Analyser" \
      org.opencontainers.image.description="Whole-project static analysis for Adobe Experience Manager: dead code, orphaned OSGi configuration, duplication, Cloud Service parity and security findings in one pass." \
      org.opencontainers.image.source="https://github.com/RohithV16/aem-code-analyser" \
      org.opencontainers.image.url="https://rohithv16.github.io/aem-code-analyser/" \
      org.opencontainers.image.documentation="https://rohithv16.github.io/aem-code-analyser/getting-started/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

COPY --chown=1000:1000 target/release-assets/aem-analyser-cli.jar /opt/aem/aem-analyser.jar

# Numeric UID/GID: the analyser only reads the mounted tree and writes its reports, and
# matching 1000 keeps host-owned files usable on Linux agents. Override with
# `--user "$(id -u):$(id -g)"` where the CI user differs.
USER 1000:1000

# Mount the project here; reports land in ./target/aem-analyser of the mounted tree.
WORKDIR /src

ENTRYPOINT ["java", "-jar", "/opt/aem/aem-analyser.jar"]
