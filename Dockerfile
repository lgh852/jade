FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S jade && adduser -S jade -G jade

COPY build/libs/*.jar /app/jade.jar

USER jade
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/jade.jar"]
