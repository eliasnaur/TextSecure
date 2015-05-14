package android

import (
	"crypto/tls"
	"log"
	"strings"

	"golang.org/x/net/websocket"
)

type (
	Pipe struct {
		url   string
		certs []tls.Certificate
	}
)

func NewPipe(url string) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{url: url}
}

func (p *Pipe) AddAcceptedCert(encCert, encKey []byte) error {
	log.Printf("adding cert %#v", encCert)
	log.Printf("adding key %#v", encKey)
	cert, err := tls.X509KeyPair(encCert, encKey)
	if err != nil {
		return err
	}
	p.certs = append(p.certs, cert)
	log.Printf("added cert %+v", cert)
	return nil
}

func (p *Pipe) Connect() {
	if err := p.connect(); err != nil {
		log.Println("failed to connect: ", err)
	}
}

func (p *Pipe) connect() error {
	//	?login=%s&password=%s
	log.Println("connecting to websocket " + p.url)
	ws, err := websocket.Dial(p.url, "", "http://localhost")
	if err != nil {
		return err
	}
	log.Println("connected websocket", ws)
	return nil
}
