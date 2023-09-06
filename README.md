# traktor-streaming-proxy
Allow Traktor DJ to stream music from YouTube, Spotify, and Tidal by faking Beatport's API

<img src="screenshot.png" align="right" width="250"></a>

Traktor DJ supports streaming of music tracks, but only from the Beatport and Beatsource services.
This project aims to integrate other streaming sources into Traktor DJ via Beatport Streaming.
It consists of an API server based on ktor which fakes some relevant parts of the Beatport API to serve custom content.

Currently, it supports YouTube Music (via NewPipe), Spotify, and Tidal with support for searching for music and browsing saved tracks and playlists.
In theory other streaming services or self-hosted sources will be possible to integrate as long as they serve music files in mp4a audio format, since Traktor refuses to load other formats (even though these formats are supported for local files).
As a workaround, an on-the-fly format conversion of the music files should be possible at some cost in quality and time.

As with Beatport streaming, Traktor does not allow to use the build-in recorder.

The project now contains a fully crafted Beatport license file that allows the server to handle linking and authentication, with enabling all features such as track analysis and simultaneous playback of multiple decks. You no longer need to take care of the license file or have a Beatport account with subscription! :)

## Docker

Run the server in the Docker container with the following command:

```
docker run -p 443:443 -v <path-to-server.crt>:/app/cert/server.crt -v <path-to-server.key>:/app/cert/server.key -v <path-to-config.properties>:/app/config.properties -v <path-to-license>:/app/license ghcr.io/0xf4b1/traktor-streaming-proxy:latest
```

## Building

```
./gradlew build
```

## Setup

1. You need to create a SSL certificate for the domain `api.beatport.com`.
You can use the script in `cert/gen-cert.sh` to generate a new CA and the server certificate. You have to import the CA certificate into the trust store of the device running Traktor.

2. Install and configure nginx with SSL and a proxy_pass to this server.

Edit nginx site config `/etc/nginx/sites-available/default` with the following parts:

```
server {
        listen 443 ssl default_server;
        listen [::]:443 ssl default_server;

        ssl_certificate     server.crt;
        ssl_certificate_key server.key;
        ssl_protocols       TLSv1 TLSv1.1 TLSv1.2;
        ssl_ciphers         HIGH:!aNULL:!MD5;

        location / {
                proxy_pass http://127.0.0.1:8000;
        }
}
```

Then reload the changed configuration in nginx.

3. Start the API server with `./gradlew run`

4. Redirect `api.beatport.com` to the server by adding the following to `/private/etc/hosts` on macOS

```
127.0.0.1   api.beatport.com
```

5. Verify that secure connections to the sever are working on the device running Traktor

```
curl https://api.beatport.com/v4/catalog/genres/
```

The command should succeed and show some output in JSON.
If you get SSL certificate errors, you need to fix the configuration.

## Running

1. Start the server with `./gradlew run`

2. Run Traktor. If you are not yet linked with the server, open settings and connect to Beatport streaming. You should receive an immediate redirect which connects Traktor.

3. If you navigate to Beatport Streaming, you should be able to browse through the playlists from the sources and use the search box to find content.