#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

FROM maven:3.9-eclipse-temurin-25
WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY etc ./etc

# hardwood-parquet-java-compat is not on Maven Central yet — it comes from your locally
# built Hardwood artifacts, mounted into the container's Maven repository by
# docker-compose.yaml. Run ./build-compat.sh on the host first. That mount only exists at
# run time, so compile here rather than in a RUN layer.
ENTRYPOINT ["mvn", "-q", "-B", "compile", "exec:java"]
