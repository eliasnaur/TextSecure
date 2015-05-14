package android

import (
	"crypto/tls"
	"crypto/x509"
	"log"
	"net/url"
	"strings"

	"golang.org/x/net/websocket"
)

type (
	Pipe struct {
		url      string
		certPool *x509.CertPool
		creds    CredentialsProvider
		ws       *websocket.Conn
	}
	CredentialsProvider interface {
		User() string
		Password() string
	}
)

func NewPipe(url string, creds CredentialsProvider) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{url: url, certPool: x509.NewCertPool(), creds: creds}
}

func (p *Pipe) AddAcceptedCert(encCert []byte) error {
	cert, err := decodeCert(encCert)
	if err != nil {
		return err
	}
	p.certPool.AddCert(cert)
	return nil
}

func (p *Pipe) Start() {
	if err := p.connect(); err != nil {
		log.Println("failed to connect: ", err)
	}
}

func (p *Pipe) connect() error {
	log.Println("connecting to websocket " + p.url)
	user, pass := p.creds.User(), p.creds.Password()
	authURL := p.url + "?login=" + url.QueryEscape(user) + "&password=" + url.QueryEscape(pass)
	conf, err := websocket.NewConfig(authURL, "http://localhost")
	if err != nil {
		return err
	}
	conf.TlsConfig = &tls.Config{RootCAs: p.certPool}
	ws, err := websocket.DialConfig(conf)
	if err != nil {
		return err
	}
	p.ws = ws
	log.Println("websocket connected")
	go p.loop()
	return nil
}

func (p *Pipe) loop() {
	codec := websocket.Codec{marshal, unmarshal}
	for {
		var data []byte
		if err := codec.Receive(p.ws, &data); err != nil {
			log.Println("error receiving", err)
			p.ws = nil
			break
		}
		log.Println("received data", data)
	}
}

func unmarshal(data []byte, payloadType byte, v interface{}) error {
	log.Println("received frame", payloadType, data)
	ret := v.(*[]byte)
	*ret = data
	return nil
}

func marshal(v interface{}) (data []byte, payloadType byte, err error) {
	return *v.(*[]byte), websocket.BinaryFrame, nil
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
