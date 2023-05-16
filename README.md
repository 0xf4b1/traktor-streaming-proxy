# traktor-streaming-proxy
Allow Traktor DJ to stream music from YouTube by faking Beatport's API

Traktor DJ supports streaming of music tracks, but only from the Beatport and Beatsource services.
This project aims to integrate other streaming sources into Traktor DJ via Beatport Streaming.
It consists of an API server based on ktor which fakes some relevant parts of the Beatport API to serve custom content.

Currently, it supports YouTube Music (via NewPipe) and allows searching for music and listing trending content.
In theory other streaming services or self-hosted sources will be possible to integrate as long as they serve music files in mp4a audio format, since Traktor refuses to load other formats (even though these formats are supported for local files).
As a workaround, an on-the-fly format conversion of the music files should be possible at some cost in quality and time.

Please note that the server is currently not able to handle Beatport linking and authentication.
This means that you must use a valid account to enable Beatport streaming in Traktor and then redirect all API calls to this server.
While it's possible to link a free account without a subscription, unfortunately it turned out that you need an active subscription, as upon authentication an encrypted license is loaded and verified, which includes some required options, and without it Trakor won't analyze tracks from the server and will only allow you to load one track at a time.
As with Beatport streaming, Traktor does not allow to use the build-in recorder.

## Building

```
./gradlew build
```

## Setup

1. You need to create a SSL certificate for the domain `api.beatport.com` and have it in the trust store of the device running Traktor.

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

3. Redirect `api.beatport.com` to the server by adding the following to `/private/etc/hosts` on macOS

```
127.0.0.1   api.beatport.com
```

4. Verify that secure connections to the sever are working on the device running Traktor

```
curl https://api.beatport.com/v4/catalog/genres/
```

The command should succeed and show some output in JSON.
If you get SSL certificate errors, you need to fix the configuration.

## Running

1. Start the server with `./gradlew run`

2. Temporarily disable the redirect in `/private/etc/hosts` by commenting the line out

3. Start Traktor and make sure that Beatport streaming is showing up in the browser. If you are not linked, open settings and connect to Beatport streaming by logging in with your account.

4. Then redirect `api.beatport.com` to the server by uncommenting the line in `/private/etc/hosts` again.

5. You should be able to use Beatport search to find content from YouTube Music. If you navigate to Genres->YouTube, you should see trending content.