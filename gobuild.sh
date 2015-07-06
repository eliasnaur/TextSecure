#!/bin/bash

set -e

GOPATH=$GOPATH:$HOME/git/hoenir/TextSecure/go gomobile bind textsecure/android
mkdir -p aars
mv android.aar aars/
