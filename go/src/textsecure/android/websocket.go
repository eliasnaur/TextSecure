package android

import (
	"crypto/tls"
	"crypto/x509"
	"log"
	"strings"

	"golang.org/x/net/websocket"
)

type (
	Pipe struct {
		url      string
		certPool *x509.CertPool
	}
)

func NewPipe(url string) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{url: url, certPool: x509.NewCertPool()}
}

func (p *Pipe) AddAcceptedCert(encCert []byte) error {
	cert, err := decodeCert(encCert)
	if err != nil {
		return err
	}
	p.certPool.AddCert(cert)
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
	conf, err := websocket.NewConfig(p.url, "http://localhost")
	if err != nil {
		return err
	}
	conf.TlsConfig = &tls.Config{RootCAs: p.certPool}
	ws, err := websocket.DialConfig(conf)
	if err != nil {
		return err
	}
	log.Println("connected websocket", ws)
	return nil
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
