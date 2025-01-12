FROM ubuntu:jammy

RUN apt update && apt upgrade -y
RUN apt install -y openjdk-18-jre-headless

COPY . /app
WORKDIR /app

RUN ./gradlew distTar --no-daemon

FROM ubuntu:jammy

RUN apt update && apt upgrade -y
RUN apt install -y openjdk-18-jre-headless ffmpeg

WORKDIR /app

COPY --from=0 /app/build/distributions/traktor-streaming-proxy.tar /app
RUN tar xf traktor-streaming-proxy.tar --strip-components=1 && rm traktor-streaming-proxy.tar

CMD bin/traktor-streaming-proxy