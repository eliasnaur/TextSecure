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
		url        string
		certPool   *x509.CertPool
		creds      CredentialsProvider
		callbacks  Callbacks
		closer     chan struct{}
		waker      chan struct{}
		retryDelay time.Duration
		wl         WakeLock
		rWL        WakeLock
	}
	pipeIO struct {
		closer    chan struct{}
		ws        *websocket.Conn
		writer    chan []byte
		commErr   chan error
		connected chan *websocket.Conn
		wl        WakeLock
		callbacks Callbacks
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

func (p *pipeIO) connect(pipe *Pipe) (*websocket.Conn, error) {
	log.Println("connecting to websocket " + pipe.url)
	user, pass := pipe.creds.User(), pipe.creds.Password()
	authURL := pipe.url + "?login=" + url.QueryEscape(user) + "&password=" + url.QueryEscape(pass)
	dialer := &websocket.Dialer{TLSClientConfig: &tls.Config{RootCAs: pipe.certPool}}
	ws, _, err := dialer.Dial(authURL, http.Header{})
	return ws, err
}

func (p *pipeIO) connecter(pipe *Pipe) {
	ws, err := p.connect(pipe)
	if err != nil {
		p.commErr <- err
		return
	}
	p.connected <- ws
}

func (p *pipeIO) readLoop() {
	defer log.Println("exiting websocket read loop")
	defer p.wl.Release()
	for {
		p.wl.Release()
		log.Println("reading message...")
		_, payload, err := p.ws.ReadMessage()
		p.wl.Acquire()
		if err != nil {
			p.commErr <- err
			return
		}
		if resp := p.callbacks.OnMessage(payload); resp != nil {
			select {
			case p.writer <- resp:
			case <-p.closer:
				return
			}
		}
	}
}

func (p *pipeIO) writeLoop() {
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

func (p *pipeIO) close() {
	log.Println("closing pipeIO")
	if p.ws != nil {
		p.ws.Close()
	}
	close(p.closer)
}

func (p *Pipe) loop() {
	defer log.Println("exiting websocket loop")
	defer p.wl.Release()
	var pio *pipeIO
	for {
		if p.callbacks.ConnectionRequired() != 0 {
			if pio == nil {
				pio = &pipeIO{
					writer:    make(chan []byte),
					commErr:   make(chan error, 2),
					connected: make(chan *websocket.Conn, 1),
					closer:    make(chan struct{}),
					wl:        p.rWL,
					callbacks: p.callbacks,
				}
				pio.wl.Acquire()
				go pio.connecter(p)
			}
			/*if p.ws != nil {
				p.callbacks.WakeupIn(p.keepAliveDelay)
			}*/
		}
		p.wl.Release()
		var cPIO pipeIO
		if pio != nil {
			cPIO = *pio
		}
		select {
		case pio.ws = <-cPIO.connected:
			log.Println("websocket connected")
			pio.connected = nil
			p.retryDelay = 0
			go pio.readLoop()
			go pio.writeLoop()
		case err := <-cPIO.commErr:
			log.Println("websocket failed: ", err)
			pio.close()
			pio = nil
			p.retryDelay = 2 * p.retryDelay
			if d := p.retryDelay; d < minDelay {
				p.retryDelay = minDelay
			}
			if d := p.retryDelay; d > maxDelay {
				p.retryDelay = maxDelay
			}
			p.callbacks.WakeupIn(int64(p.retryDelay))
			p.rWL.Release()
		case <-p.closer:
			if pio != nil {
				pio.close()
			}
			return
		case <-p.waker:
			log.Println("websocket woke up")
			if pio == nil {
				break
			}
			select {
			case pio.writer <- p.callbacks.NewKeepAliveMessage():
			default:
				// Write queue is full
			}
		}
	}
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
