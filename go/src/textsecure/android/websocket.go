package android

import (
	"crypto/tls"
	"crypto/x509"
	"net/http"
	"net/url"
	"strings"
	"time"

	"bitbucket.org/elias/android/log"
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
		notifier   chan struct{}
		changer    chan struct{}
		retryDelay time.Duration
		pingDelay  time.Duration
		minPongs   int
		notified   bool
		lastAct    time.Time
		pio        *pipeIO
		wl         WakeLock
		rWL        WakeLock
	}
	pipeIO struct {
		pinged    bool
		pongs     int
		ponger    chan struct{}
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
		ConnectionRequired() bool
	}
	WakeLock interface {
		Acquire()
		Release()
	}
)

const (
	minDelay = 1 * time.Second
	maxDelay = 15 * time.Minute

	pingWait      = 30 * time.Second
	pingDelayStep = 30 * time.Second
	minPingDelay  = 2 * time.Minute
	maxPingDelay  = 15 * time.Minute
)

func InitLog(host, dir string) {
	log.Init(host, dir)
}

func NewPipe(url string, wl, rWL WakeLock, creds CredentialsProvider, callbacks Callbacks) *Pipe {
	url = strings.Replace(url, "https://", "wss://", 1) + "/v1/websocket/"
	return &Pipe{
		url:       url,
		certPool:  x509.NewCertPool(),
		creds:     creds,
		callbacks: callbacks,
		waker:     make(chan struct{}),
		notifier:  make(chan struct{}),
		changer:   make(chan struct{}),
		closer:    make(chan struct{}),
		wl:        wl,
		rWL:       rWL,
		minPongs:  1,
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

func (p *Pipe) Changed() {
	p.wl.Acquire()
	p.changer <- struct{}{}
}

func (p *Pipe) Wakeup() {
	p.wl.Acquire()
	p.waker <- struct{}{}
}

func (p *Pipe) Notify() {
	p.wl.Acquire()
	p.notifier <- struct{}{}
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
	dialer := &websocket.Dialer{
		TLSClientConfig:  &tls.Config{RootCAs: pipe.certPool},
		HandshakeTimeout: 30 * time.Second,
	}
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
		select {
		case p.ponger <- struct{}{}:
		default:
		}
		if resp := p.callbacks.OnMessage(payload); resp != nil {
			select {
			case p.writer <- resp:
				log.Println("wrote response")
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

func (p *Pipe) newPipeIO() *pipeIO {
	return &pipeIO{
		writer:    make(chan []byte),
		commErr:   make(chan error, 2),
		connected: make(chan *websocket.Conn, 1),
		ponger:    make(chan struct{}),
		closer:    make(chan struct{}),
		wl:        p.rWL,
		callbacks: p.callbacks,
	}
}

func (p *Pipe) connect() {
	p.pio = p.newPipeIO()
	p.pio.wl.Acquire()
	go p.pio.connecter(p)
}

func (p *pipeIO) ping() {
	p.pinged = true
	select {
	case p.writer <- p.callbacks.NewKeepAliveMessage():
		log.Println("sent ping")
	default:
		log.Println("no ping - write queue full")
	}
}

func (p *Pipe) checkTimeout() {
	var wakeup time.Duration
	defer func() {
		p.wakeupIn(wakeup)
	}()
	now := time.Now()
	if !p.pio.pinged {
		p.pingDelay = clampDuration(p.pingDelay, minPingDelay, maxPingDelay)
		nextPing := p.lastAct.Add(p.pingDelay)
		if p.notified || now.After(nextPing) {
			p.pio.ping()
			p.lastAct = now
			wakeup = pingWait
		} else {
			wakeup = nextPing.Sub(now)
		}
	} else {
		timeout := p.lastAct.Add(pingWait)
		if now.After(timeout) {
			if p.pio.pongs == 0 {
				p.pingDelay -= pingDelayStep
				p.minPongs *= 2
				log.Printf("decreased websocket timeout to %s, min pongs %d", p.pingDelay, p.minPongs)
			}
			p.pio.close()
			p.pio = nil
			wakeup = p.retryDelay
			return
		}
		wakeup = timeout.Sub(now)
	}
}

func (p *Pipe) pong() {
	if p.pio.pinged && !p.notified {
		p.pio.pongs++
		if p.pio.pongs >= p.minPongs {
			p.pingDelay += pingDelayStep
			p.pio.pongs = 0
			log.Printf("increased websocket timeout to %s", p.pingDelay)
		}
	}
	p.notified = false
	p.pio.pinged = false
	p.lastAct = time.Now()
}

func (p *Pipe) wakeupIn(d time.Duration) {
	log.Printf("waiting for %s", d)
	p.callbacks.WakeupIn(int64(d))
}

func (p *Pipe) checkConnect() {
	if p.callbacks.ConnectionRequired() && p.pio == nil {
		p.connect()
	}
}

func (p *Pipe) loop() {
	defer log.Println("exiting websocket loop")
	defer p.wl.Release()
	for {
		if p.callbacks.ConnectionRequired() {
			if p.pio != nil {
				if p.pio.ws != nil {
					p.checkTimeout()
				}
			} else {
				p.wakeupIn(p.retryDelay)
			}
		}
		p.wl.Release()
		var cPIO pipeIO
		if p.pio != nil {
			cPIO = *p.pio
		}
		select {
		case p.pio.ws = <-cPIO.connected:
			log.Println("websocket connected")
			p.pio.connected = nil
			p.notified = false
			p.retryDelay = 0
			p.pong()
			go p.pio.readLoop()
			go p.pio.writeLoop()
		case <-cPIO.ponger:
			log.Println("websocket read activity")
			p.pong()
		case err := <-cPIO.commErr:
			log.Println("websocket failed: ", err)
			p.pio.close()
			p.pio = nil
			p.retryDelay = clampDuration(2*p.retryDelay, minDelay, maxDelay)
			p.rWL.Release()
		case <-p.closer:
			if p.pio != nil {
				p.pio.close()
			}
			return
		case <-p.changer:
			log.Println("websocket change event")
			p.notified = true
			p.pingDelay = 0
			p.minPongs = 1
			if pio := p.pio; pio != nil {
				pio.pongs = 0
			}
			p.retryDelay = 0
		case <-p.notifier:
			log.Println("websocket notified")
			p.notified = true
			p.checkConnect()
		case <-p.waker:
			log.Println("websocket woke up")
			p.checkConnect()
		}
	}
}

func clampDuration(d, min, max time.Duration) time.Duration {
	if d < min {
		return min
	}
	if d > max {
		return max
	}
	return d
}

func decodeCert(encCert []byte) (*x509.Certificate, error) {
	return x509.ParseCertificate(encCert)
}
