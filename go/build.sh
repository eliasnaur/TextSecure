GOPATH=$GOPATH:$HOME/git/hoenir/TextSecure/go GOROOT=$HOME/dev/go-src PATH="$HOME/dev/go-src/bin:$PATH" gomobile bind textsecure/android
mv android.aar ../aars
