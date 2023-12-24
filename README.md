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

Please note this project and the setup instructions are only tested on macOS. While it is possible to set it up on Windows in a similar way, Traktor on Windows uses different client certificates and does not work with the license file in this project (but there is a trick to get it working https://github.com/0xf4b1/traktor-streaming-proxy/issues/13#issuecomment-1742184706).

## Setup

1. You need to create a SSL certificate for the domain `api.beatport.com`.
You can use the script in `cert/gen-cert.sh` to generate a new CA and the server certificate. You have to import the CA certificate into the trust store of the device running Traktor.

2. Configure the server by adjusting the `config.properties` file in the project directory. Both Spotify and Tidal sources require an account.

3. You can now choose between a prebuilt Docker container (3.1) or a manual setup (3.2) and continue in both cases with 4.

3.1 Docker

Run the server in the Docker container with the following command:

```
docker run -p 443:443 -v <path-to-server.crt>:/app/cert/server.crt -v <path-to-server.key>:/app/cert/server.key -v <path-to-config.properties>:/app/config.properties ghcr.io/0xf4b1/traktor-streaming-proxy:latest
```

OR

3.2 Manual

3.2.1 Building

Build the project with the following command:

```
./gradlew build
```

3.2.2 Install and configure nginx with SSL and a proxy_pass to this server.

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

3.2.3 (Optional) Install ffmpeg on your system if you want to use spotify source.

3.2.4 Start the API server with `./gradlew run`

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

6. Run Traktor. If you are not yet linked with the server, open settings and connect to Beatport streaming. You should receive an immediate redirect which connects Traktor.

7. Done! If you navigate to Beatport Streaming, you should be able to browse through the predefined categories and use the search box to find content.

## Library Mapping

Beatport Streaming has the following predefined categories, which we try to match to our available sources in the best possible way.
The genres are identical in each category, which is why we use them to differentiate between the sources.

```
Curated Playlists
- <Genres>         --> source
 - <Playlists>     --> followed artists
  - <Tracks>       --> tracks from artist
Genres
- <Genres>         --> source
 - <Tracks>        --> saved/liked tracks in source
Playlists
- <Playlists>      --> playlists (all sources merged)
 - <Tracks>        --> tracks from playlist
Top 100
- <Genres>         --> source
 - <Tracks>        --> generated playlist of new released tracks
```