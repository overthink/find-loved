# find-loved

Find your last.fm "loved tracks" on your local machine and generate a playlist of them for use in a media player of your choice.

## Status

In progress.  Can generate m3u-compatible playlist, but the matching of loved tracks to real tracks on disk is pretty lame right now.  It'll work ok if your tags line up well with last.fm.  When there are multiple matches it takes the file with the *oldest* "YEAR" tag on disk.

## Usage

You'll need [Leiningen](https://github.com/technomancy/leiningen) (painless install) unless I ever release a .jar file.

    lein run overthink ~/music /data/some_other_music /nas/yet/another/dir > loved.m3u

More detailed:

    lein run --api-key <your key> <last.fm username> folder0 folder1 ... folderN

Put your api key in `~/.lastfm_api_key` and omit `--api-key` to make it suck less.

Loved tracks for a last.fm user are cached indefinitely in `.<last.fm username>_loved_tracks` in whatever dir you invoke `find-loved` in.  Delete this file to make it hit last.fm live again.

## License

Copyright (C) 2012 Mark Feeney

Distributed under the Eclipse Public License, the same as Clojure.

