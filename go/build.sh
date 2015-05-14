#!/bin/bash

set -e

GOPATH=$GOPATH:$HOME/git/hoenir/TextSecure/go GOROOT=$HOME/dev/go-src PATH="$HOME/dev/go-src/bin:$PATH" gomobile bind textsecure/android
mkdir -p ../aars
mv android.aar ../aars/
