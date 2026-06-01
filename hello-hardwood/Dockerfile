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

RUN mvn -q -B compile

ENTRYPOINT ["mvn", "-q", "-B", "exec:java"]
