# syntax=docker/dockerfile:1
# SPDX-License-Identifier: GPL-3.0-or-later
#
# mihon-source image. Three stages: Node builds the admin UI, a JDK builds the service,
# and the runtime holds only a JRE and the built application.

# ---- Admin UI ----------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim AS admin
WORKDIR /src/admin
COPY admin/package.json admin/package-lock.json admin/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY admin/ ./
COPY service/src/main/resources/openapi/openapi.yaml /src/service/src/main/resources/openapi/openapi.yaml
RUN npm run build

# ---- Service -----------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
COPY --from=admin /src/admin/dist admin/dist
# The admin UI is already built above, so skip Gradle's Node build of it.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --no-configuration-cache :service:installDist -x :admin:buildSpa -x :admin:npmInstall

# ---- Runtime -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
# Fonts let extensions that draw text onto images (rare) do so.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 mihon \
    && useradd --uid 10001 --gid mihon --home-dir /data --no-create-home --shell /usr/sbin/nologin mihon \
    && mkdir -p /data && chown mihon:mihon /data

COPY --from=build /src/service/build/install/mihon-source /opt/mihon-source

ENV MIHON_DATA_DIR=/data \
    MIHON_PORT=8902 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Dpolyglot.engine.WarnInterpreterOnly=false"

USER mihon
VOLUME /data
EXPOSE 8902
ENTRYPOINT ["/opt/mihon-source/bin/mihon-source"]

LABEL org.opencontainers.image.title="mihon-source" \
      org.opencontainers.image.description="Runs Mihon extensions and serves their sources over gRPC and HTTP/JSON" \
      org.opencontainers.image.source="https://github.com/panelarr/mihon-source" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"
