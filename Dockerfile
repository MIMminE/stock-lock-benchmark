FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY . .

# (Windows에서 자주 깨지는 지점) gradlew 실행권한 + CRLF 제거
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

RUN ./gradlew clean bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]