FROM eclipse-temurin:25-jdk AS maven-build
RUN useradd --no-log-init -ms /bin/bash dockerUser \
    && chown dockerUser:dockerUser /opt
USER dockerUser
COPY --chown=dockerUser:dockerUser pom.xml opt/pom.xml
COPY --chown=dockerUser:dockerUser mvnw opt/mvnw
COPY --chown=dockerUser:dockerUser .mvn opt/.mvn
WORKDIR /opt
RUN ./mvnw dependency:go-offline --no-transfer-progress --batch-mode
COPY --chown=dockerUser:dockerUser src ./src
RUN ./mvnw package -DskipTests --no-transfer-progress --batch-mode

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --no-log-init -ms /bin/bash appUser \
    && chown appUser:appUser /opt
COPY --chown=appUser:appUser --from=maven-build /opt/target/sales-api.jar /opt/sales-api.jar
USER appUser
WORKDIR /opt
ENTRYPOINT ["java","-jar","sales-api.jar"]

