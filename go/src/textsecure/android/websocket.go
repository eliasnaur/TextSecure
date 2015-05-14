package android

import (
	"crypto/tls"
	"crypto/x509"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

type (
	Pipe struct {
		url            string
		certPool       *x509.CertPool
		creds          CredentialsProvider
		ws             *websocket.Conn
		callbacks      Callbacks
		closer         chan struct{}
		waker          chan struct{}
		writer         chan []byte
		commErr        chan error
		connected      chan *websocket.Conn
		retryDelay     time.Duration
		keepaliveDelay time.Duration
		wl             WakeLock
		rWL            WakeLock
	}
	CredentialsProvider interface {
		User() string
		Password() string
	}
	Callbacks interface {
		OnMessage(msg []byte) []byte
		NewKeepAliveMessage() []byte
		WakeupIn(nanos int64)
		ConnectionRequired() int
	}
	WakeLock interface {
		Acquire()
		Release()
	}
)

const (
	minDelay = 1 * time.Second
	maxDelay = 15 * time.Minute
)

func NewPipe(url string, wl, rWL WakeLock, creds CredentialsProvider, callbacks Callbacks) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{
		url:       url,
		certPool:  x509.NewCertPool(),
		creds:     creds,
		callbacks: callbacks,
		waker:     make(chan struct{}),
		closer:    make(chan struct{}),
		wl:        wl,
		rWL:       rWL,
	}
}

func (p *Pipe) AddAcceptedCert(encCert []byte) error {
	cert, err := decodeCert(encCert)
	if err != nil {
		return err
	}
	p.certPool.AddCert(cert)
	return nil
}

func (p *Pipe) Wakeup() {
	p.waker <- struct{}{}
}

func (p *Pipe) Shutdown() {
	close(p.closer)
}

func (p *Pipe) Start() {
	go p.loop()
}

func (p *Pipe) connect() (*websocket.Conn, error) {
	log.Println("connecting to websocket " + p.url)
	user, pass := p.creds.User(), p.creds.Password()
	authURL := p.url + "?login=" + url.QueryEscape(user) + "&password=" + url.QueryEscape(pass)
	dialer := &websocket.Dialer{TLSClientConfig: &tls.Config{RootCAs: p.certPool}}
	ws, _, err := dialer.Dial(authURL, http.Header{})
	return ws, err
}

func (p *Pipe) close() {
	p.ws.Close()
	p.ws = nil
}

func (p *Pipe) connecter() {
	ws, err := p.connect()
	p.rWL.Acquire()
	if err != nil {
		p.commErr <- err
		return
	}
	p.connected <- ws
}

func (p *Pipe) readLoop() {
	defer log.Println("exiting websocket read loop")
	defer p.rWL.Release()
	for {
		p.rWL.Release()
		log.Println("reading message...")
		_, payload, err := p.ws.ReadMessage()
		p.rWL.Acquire()
		if err != nil {
			p.commErr <- err
			return
		}
		if resp := p.callbacks.OnMessage(payload); resp != nil {
			p.writer <- resp
		}
	}
}

func (p *Pipe) writeLoop() {
	defer log.Println("exiting websocket write loop")
	for {
		select {
		case payload := <-p.writer:
			if err := p.ws.WriteMessage(websocket.BinaryMessage, payload); err != nil {
				p.commErr <- err
				return
			}
		case <-p.closer:
			return
		}
	}
}

func (p *Pipe) loop() {
	defer log.Println("exiting websocket loop")
	defer p.wl.Release()
	for {
		if p.callbacks.ConnectionRequired() != 0 {
			if p.ws == nil && p.connected == nil {
				p.writer = make(chan []byte)
				p.commErr = make(chan error, 2)
				p.connected = make(chan *websocket.Conn, 1)
				go p.connecter()
			}
			/*if p.ws != nil {
				p.callbacks.WakeupIn(p.keepAliveDelay)
			}*/
		}
		p.wl.Release()
		select {
		case p.ws = <-p.connected:
			log.Println("websocket connected")
			p.connected = nil
			p.retryDelay = 0
			go p.readLoop()
			go p.writeLoop()
		case err := <-p.commErr:
			log.Println("websocket failed: ", err)
			p.rWL.Release()
			p.connected = nil
			p.commErr = nil
			p.close()
			p.retryDelay = 2 * p.retryDelay
			if d := p.retryDelay; d < minDelay {
				p.retryDelay = minDelay
			}
			if d := p.retryDelay; d > maxDelay {
				p.retryDelay = maxDelay
			}
			p.callbacks.WakeupIn(int64(p.retryDelay))
		case <-p.closer:
			p.close()
			return
		case <-p.waker:
			log.Println("websocket woke up")
		}
	}
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
