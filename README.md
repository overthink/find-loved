# find-loved

Fetch all your loved tracks from last.fm, find them on your local filesystem and output a playlist for your player of choice.

## Status

In progress.  Doesn't do anything useful yet.

## Usage

You'll need [Leiningen](https://github.com/technomancy/leiningen) (painless install) unless I ever release a .jar file.

    lein run --api-key <your key> <last.fm username>

Put your api key in `~/.lastfm_api_key` and omit `--api-key` to make it suck less.

Loved tracks for a last.fm user are cached indefinitely in `.<last.fm username>_loved_tracks` in whatever dir you invoke `find-loved` in.  Delete this file to make it hit last.fm live again.

## License

Copyright (C) 2012 Mark Feeney

Distributed under the Eclipse Public License, the same as Clojure.

