FROM ubuntu:jammy

RUN apt update && apt upgrade -y
RUN apt install -y openjdk-18-jre-headless nginx patch

COPY . /app
WORKDIR /app

RUN patch -d / -p0 < nginx-default.patch

RUN ./gradlew build --no-daemon
CMD nginx && ./gradlew run --no-daemon