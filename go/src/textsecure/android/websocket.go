package android

import (
	"crypto/tls"
	"crypto/x509"
	"log"
	"net/http"
	"net/url"
	"strings"

	"github.com/gorilla/websocket"
)

type (
	Pipe struct {
		url       string
		certPool  *x509.CertPool
		creds     CredentialsProvider
		ws        *websocket.Conn
		callbacks Callbacks
	}
	CredentialsProvider interface {
		User() string
		Password() string
	}
	Callbacks interface {
		OnMessage(msg []byte) []byte
	}
)

func NewPipe(url string, creds CredentialsProvider, callbacks Callbacks) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{url: url, certPool: x509.NewCertPool(), creds: creds, callbacks: callbacks}
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
	dialer := &websocket.Dialer{TLSClientConfig: &tls.Config{RootCAs: p.certPool}}
	ws, _, err := dialer.Dial(authURL, http.Header{})
	if err != nil {
		return err
	}
	p.ws = ws
	log.Println("websocket connected")
	go p.loop()
	return nil
}

func (p *Pipe) close() {
	p.ws.Close()
	p.ws = nil
}

func (p *Pipe) loop() {
	for {
		log.Println("reading message...")
		_, payload, err := p.ws.ReadMessage()
		if err != nil {
			log.Println("error receiving message", err)
			p.close()
			break
		}
		if resp := p.callbacks.OnMessage(payload); resp != nil {
			p.ws.WriteMessage(websocket.BinaryMessage, resp)
		}
	}
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
