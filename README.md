# find-loved

Find your last.fm "loved tracks" on your local machine and generate a playlist of them for use in a media player of your choice.

## Status

Usable.  find-loved can generate m3u-compatible playlist, but the matching of loved tracks to real tracks on disk is pretty lame right now.  It'll work ok if your tags line up well with last.fm.  When there are multiple matches it takes the file with the *oldest* "YEAR" tag on disk.

## Usage

You'll need [Leiningen](https://github.com/technomancy/leiningen) (painless install) unless I ever release a .jar file.

Here are a couple of  example invoactions:

    lein run overthink ~/music /data/some_other_music /nas/yet/another/dir > loved.m3u
    lein run overthink /huge_collection 1>loved.m3u 2>report.txt
    lein run --quiet -- overthink /muzak > loved.m3u

More details:

    lein run --api-key <your key> <last.fm username> searchdir0 searchdir1 ... searchdirN

Put your api key in `~/.lastfm_api_key` and omit `--api-key` to make it suck less.

Loved tracks for a last.fm user are cached indefinitely in `.<last.fm username>_loved_tracks` in whatever dir you invoke `find-loved` in.  Delete this file to make it hit last.fm live again.

## Bugs/Features

"It works on my computer."

Loved tracks are cached forever for a user.  Delete the `.$username_loved_tracks` file to clear the cache.  It's also easy to get a half-baked cache on disk if you ctrl-c part way through downloading from last.fm.  Again, delete the cached file to "fix" this.

## License

Copyright (C) 2012 Mark Feeney

Distributed under the Eclipse Public License, the same as Clojure.

