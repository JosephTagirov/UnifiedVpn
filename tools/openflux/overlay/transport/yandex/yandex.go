package yandex

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"openflux/transport"
	"openflux/utils"
)

var (
	cursorPayloadRe = regexp.MustCompile(`"cursor":"[^;]+;([^"]+)"`)
)

type YandexDocsInfo struct {
	CookieStr        string
	BrowserUserAgent string
	Token            string
	DocID            string
	CallbackURL      string
	UserID           string
	Origin           string
	Host             string
	WsURL            string
	Permissions      map[string]interface{}
	OpenCmd          map[string]interface{}
}

type DocSession struct {
	Info        YandexDocsInfo
	Conn        *websocket.Conn
	WriteQueue  chan []byte
	UserID      string
	writeMu     sync.Mutex
	diagnostics *classicAttemptDiagnostics
}

func (s *DocSession) safeWrite(messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.Conn.WriteMessage(messageType, data)
}

type YandexDocsTransport struct {
	*transport.BaseTransport

	url       string
	session   *DocSession
	bootstrap *documentBootstrap

	userCounter atomic.Int32
	baseUserID  string
}

func NewYandexDocsTransport(url string, config transport.TransportConfig) *YandexDocsTransport {
	t := &YandexDocsTransport{
		BaseTransport: transport.NewBaseTransport(config),
		url:           url,
		bootstrap:     newDocumentBootstrap(nil, nil),
	}
	t.baseUserID = randUserID()
	return t
}

func (t *YandexDocsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	t.baseUserID = randUserID()
	utils.SafeGo("yandex.keepAlive", t.keepAliveLoop)
	t.connectToDoc(0)

	return nil
}

func (t *YandexDocsTransport) Stop() error {
	t.Mu.Lock()
	err := t.BaseTransport.Stop()
	session := t.session
	t.session = nil
	t.Mu.Unlock()
	if t.bootstrap != nil {
		t.bootstrap.close()
	}
	if session != nil && session.Conn != nil {
		_ = session.Conn.Close()
	}
	return err
}

func (t *YandexDocsTransport) Send(data []byte) error {
	if !t.IsConnected() {
		return fmt.Errorf("transport not connected")
	}

	t.Mu.RLock()
	session := t.session
	t.Mu.RUnlock()

	if session == nil {
		return fmt.Errorf("no active session")
	}

	select {
	case session.WriteQueue <- data:
		t.RecordSend(len(data))
		return nil
	default:
		return fmt.Errorf("write queue full")
	}
}

func (t *YandexDocsTransport) connectToDoc(attempt int) {
	if !t.IsRunning() {
		return
	}

	utils.Debugf("[YDOCS] connectToDoc attempt ...")

	go func() {
		diagnostics := &classicAttemptDiagnostics{}
		defer func() {
			if r := recover(); r != nil {
				utils.Debugf("[PANIC] recovered in yandex.connect: %v", r)
			}
		}()
		t.Mu.Lock()
		existingSession := t.session
		t.Mu.Unlock()

		var userID string
		if existingSession != nil {
			userID = existingSession.UserID
		} else {
			suffix := fmt.Sprintf("%03d", t.userCounter.Add(1)%1000)
			userID = t.baseUserID + suffix
		}

		info, err := t.fetchDocInfo(t.url, userID)
		if err != nil {
			diagnostics.record(classicDocumentFailed)
			utils.Debugf("[YDOCS] fetchDocInfo failed: %v", err)
			t.scheduleReconnect(attempt)
			return
		}
		diagnostics.record(classicDocumentResolved)

		// Hard TCP dial timeout so a stuck connect/DNS to the balancer host
		// can't hang the whole transport (HandshakeTimeout alone proved
		// insufficient on iOS).
		dialer := websocket.Dialer{
			HandshakeTimeout: 15 * time.Second,
			NetDialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		}
		utils.Debugf("[YDOCS] WebSocket dial")
		ctx := t.bootstrap.ctx
		conn, err := engineIODial(ctx, info, t.bootstrap.client, &dialer, engineIOHandshakeTimeout)
		if err != nil {
			diagnostics.record(classicWebSocketFailed)
			utils.Debugf("[YDOCS] Engine.IO handshake failed: %v", err)
			t.scheduleReconnect(attempt)
			return
		}
		diagnostics.record(classicWebSocketConnected)
		utils.Debugf("[YDOCS] WebSocket connected to %s", info.Host)

		writeQueue := make(chan []byte, t.GetConfig().MaxQueueSize)
		if existingSession != nil {
			writeQueue = existingSession.WriteQueue
		}

		session := &DocSession{
			Info:        info,
			Conn:        conn,
			WriteQueue:  writeQueue,
			UserID:      userID,
			diagnostics: diagnostics,
		}

		if err := t.activateSession(session); err != nil {
			diagnostics.authenticationFailed(err)
			utils.Debugf("[YDOCS] Session authentication cancelled or failed")
			t.scheduleReconnect(attempt)
			return
		}

		if existingSession == nil {
			utils.SafeGo("yandex.writer", t.writerLoop)
		}

		connectedAt := time.Now()
		for t.IsRunning() {
			_, message, err := conn.ReadMessage()
			if err != nil {
				diagnostics.closed(err)
				utils.Debugf("[YDOCS] Read error: %v", err)
				t.disconnectSession(session)
				// If the session was healthy for a while, treat the next
				// connect as fresh (attempt -1 -> next attempt 0) so backoff
				// doesn't keep growing across normal long-lived reconnects.
				next := attempt
				if time.Since(connectedAt) > 15*time.Second {
					next = -1
				}
				t.scheduleReconnect(next)
				return
			}
			t.handleMessage(session, message)
		}
	}()
}

func (t *YandexDocsTransport) activateSession(session *DocSession) error {
	if !t.IsRunning() || t.bootstrap.ctx.Err() != nil {
		_ = session.Conn.Close()
		return errClassicAuthClosed
	}
	pending, err := authenticateClassicSession(t.bootstrap.ctx, session, classicAuthTimeout)
	if err != nil {
		return err
	}
	t.Mu.Lock()
	if !t.IsRunning() || t.bootstrap.ctx.Err() != nil {
		t.Mu.Unlock()
		_ = session.Conn.Close()
		return errClassicAuthClosed
	}
	// The persistent writer and keepalive see only a fully authenticated
	// document session, not merely successfully written authentication frames.
	t.session = session
	t.SetConnected(true)
	t.Mu.Unlock()
	for _, message := range pending {
		if !t.IsRunning() {
			break
		}
		t.handleMessage(session, message)
	}
	return nil
}

func (t *YandexDocsTransport) writerLoop() {
	// One queue survives reconnects. Block while idle, and keep a failed write
	// pending until a replacement session can send it or Stop cancels the loop.
	t.Mu.RLock()
	session := t.session
	t.Mu.RUnlock()
	if session == nil {
		return
	}
	queue := session.WriteQueue
	var pending []byte
	for t.IsRunning() {
		if pending == nil {
			select {
			case <-t.bootstrap.ctx.Done():
				return
			case packet, ok := <-queue:
				if !ok {
					return
				}
				pending = packet
			}
		}

		t.Mu.RLock()
		session = t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil || !t.IsConnected() {
			if !t.waitWhileRunning(15 * time.Millisecond) {
				return
			}
			continue
		}

		payload := base64.StdEncoding.EncodeToString(pending)
		msg := fmt.Sprintf(`42["message",{"type":"cursor","cursor":"18;%s"}]`, payload)
		if err := session.safeWrite(websocket.TextMessage, []byte(msg)); err != nil {
			utils.Debugf("[YDOCS] Write error: %v", err)
			t.disconnectSession(session)
			if !t.waitWhileRunning(15 * time.Millisecond) {
				return
			}
			continue
		}
		pending = nil
	}
}

func (t *YandexDocsTransport) disconnectSession(session *DocSession) {
	t.Mu.Lock()
	if t.session == session {
		t.SetConnected(false)
	}
	t.Mu.Unlock()
	if session != nil && session.Conn != nil {
		_ = session.Conn.Close()
	}
}

func (t *YandexDocsTransport) waitWhileRunning(delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-t.bootstrap.ctx.Done():
		return false
	case <-timer.C:
		return t.IsRunning()
	}
}

func (t *YandexDocsTransport) keepAliveLoop() {
	ticker := time.NewTicker(t.GetConfig().KeepAliveInterval)
	defer ticker.Stop()
	keepAliveMsg := `42["message",{"type":"cursor","cursor":"18;---KA---"}]`

	for t.IsRunning() {
		select {
		case <-t.bootstrap.ctx.Done():
			return
		case <-ticker.C:
		}
		t.Mu.Lock()
		session := t.session
		t.Mu.Unlock()

		if session != nil && session.Conn != nil {
			if err := session.safeWrite(websocket.TextMessage, []byte(keepAliveMsg)); err != nil {
				utils.Debugf("[YDOCS] Keep-alive failed: %v", err)
				t.disconnectSession(session)
			}
		}
	}
}

func (t *YandexDocsTransport) handleMessage(session *DocSession, data []byte) {
	if t.acknowledgeCoAuthoring(session, data) {
		return
	}
	text := string(data)

	if strings.Contains(text, "---KA---") {
		return
	}

	// Socket.IO ping - respond with pong (use safeWrite)
	if text == "2" {
		if session != nil && session.Conn != nil {
			session.safeWrite(websocket.TextMessage, []byte("3"))
		}
		return
	}
	if text == "3" {
		return
	}

	if strings.Contains(text, "saveChanges") || strings.Contains(text, "cursor") {
		base64Str := t.extractBase64String(text)
		if base64Str == "" {
			return
		}

		decoded, err := base64.StdEncoding.DecodeString(base64Str)
		if err != nil {
			utils.Debugf("[YDOCS] Base64 decode error: %v", err)
			return
		}

		t.RecordReceive(len(decoded))
		t.CallReceive(decoded)
	}
}

func (t *YandexDocsTransport) extractBase64String(response string) string {
	if strings.Contains(response, "saveChanges") {
		marker := `"excelAdditionalInfo":"`
		left := strings.Index(response, marker) + len(marker)
		if left < len(marker) {
			return ""
		}
		right := strings.Index(response[left:], `"`)
		if right == -1 {
			return ""
		}
		return response[left : left+right]
	}

	matches := cursorPayloadRe.FindStringSubmatch(response)
	if len(matches) > 1 {
		return matches[1]
	}
	return ""
}

func (t *YandexDocsTransport) scheduleReconnect(attempt int) {
	next := attempt + 1
	if !t.IsRunning() || next >= t.GetConfig().MaxReconnectAttempts {
		return
	}

	// Back off before retrying so a server that closes us immediately doesn't
	// turn into a tight connect/close loop (previously reconnect was instant).
	d := reconnectBackoff(next)
	utils.Debugf("[YDOCS] reconnecting in %v (attempt %d)", d, next)
	if !t.waitWhileRunning(d) {
		return
	}

	t.RecordReconnect()
	t.connectToDoc(next)
}

// The upstream 1.5s floor reduces duplicate participants during failure streaks.
// Its capped shift gives a maximum base of 24s, plus up to 50% jitter.
func reconnectBackoff(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	shift := n - 1
	if shift > 4 {
		shift = 4
	}
	d := 1500 * time.Millisecond * time.Duration(1<<uint(shift))
	// add up to +50% jitter
	d += time.Duration(rand.Int63n(int64(d/2) + 1))
	return d
}

func (t *YandexDocsTransport) fetchDocInfo(url, userID string) (YandexDocsInfo, error) {
	if t.bootstrap != nil {
		result, err := t.bootstrap.fetch(url)
		if err != nil {
			return YandexDocsInfo{}, err
		}
		var info YandexDocsInfo
		if result.classicDocument != nil {
			info = classicDocumentInfo(result.classicDocument, userID)
		} else {
			info, err = parseDocumentInfo(result.html, userID)
		}
		fileType, _ := info.OpenCmd["format"].(string)
		downloadURL, _ := info.OpenCmd["url"].(string)
		title, _ := info.OpenCmd["title"].(string)
		metadata := ClassicDocument{BalancerURL: info.Origin, Token: info.Token, DocumentKey: info.DocID,
			FileType: fileType, DownloadURL: downloadURL, Title: title, Edit: info.Permissions["edit"] == true}
		if err != nil || !validClassicDocumentFields(&metadata) {
			t.bootstrap.publish(ErrDocumentAccess)
			return YandexDocsInfo{}, ErrDocumentAccess
		}
		info.CookieStr = t.bootstrap.cookiesFor(info.Origin)
		info.BrowserUserAgent = t.bootstrap.requestUserAgent()
		return info, nil
	}
	// Retain the upstream parser's standalone test path; production constructors
	// always install the bounded, origin-checked bootstrap above.
	client := &http.Client{
		// Cap redirects so an auth/login redirect loop fails fast instead of
		// hanging until the timeout (a private doc redirects to passport).
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return fmt.Errorf("stopped after 10 redirects (login required? doc not public?)")
			}
			return nil
		},
		Timeout: 15 * time.Second,
	}

	utils.Debugf("[YDOCS] fetchDocInfo GET %s", url)
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("User-Agent", "Mozilla/5.0")
	resp, err := client.Do(req)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	defer resp.Body.Close()

	htmlBytes, _ := io.ReadAll(resp.Body)
	html := string(htmlBytes)
	utils.Debugf("[YDOCS] response status=%d finalURL=%s body=%dB", resp.StatusCode, resp.Request.URL.String(), len(html))

	var cookies []string
	for _, c := range resp.Cookies() {
		cookies = append(cookies, fmt.Sprintf("%s=%s", c.Name, c.Value))
	}

	info, err := parseDocumentInfo(html, userID)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	info.CookieStr = strings.Join(cookies, "; ")
	return info, nil
}

func parseDocumentInfo(body, userID string) (YandexDocsInfo, error) {
	script, _, err := clientConfigScript(body)
	if err != nil {
		return YandexDocsInfo{}, ErrDocumentAccess
	}
	var config struct {
		OfficeAction struct {
			EditorType  string `json:"office_online_editor_type"`
			BalancerURL string `json:"balancer_url"`
			Editor      struct {
				Token    string `json:"token"`
				Document struct {
					Key         string                 `json:"key"`
					FileType    string                 `json:"fileType"`
					URL         string                 `json:"url"`
					Title       *string                `json:"title"`
					Permissions map[string]interface{} `json:"permissions"`
				} `json:"document"`
			} `json:"editor_config"`
		} `json:"officeActionData"`
	}
	if json.Unmarshal([]byte(script), &config) != nil || config.OfficeAction.EditorType == "volga" {
		return YandexDocsInfo{}, ErrDocumentAccess
	}
	action := config.OfficeAction
	document := action.Editor.Document
	if action.BalancerURL == "" {
		return YandexDocsInfo{}, fmt.Errorf("client-config balancer_url is missing")
	}
	if _, ok := checkedBalancerOrigin(action.BalancerURL); !ok || !visibleASCII(action.Editor.Token, 16384) ||
		!visibleASCII(document.Key, 512) || document.Key == "." || document.Key == ".." {
		return YandexDocsInfo{}, ErrDocumentAccess
	}
	title := ""
	if document.Title != nil {
		title = *document.Title
	}
	metadata := ClassicDocument{BalancerURL: action.BalancerURL, Token: action.Editor.Token,
		DocumentKey: document.Key, FileType: document.FileType, DownloadURL: document.URL,
		Title: title, Edit: document.Permissions["edit"] == true}
	info := classicDocumentInfo(&metadata, userID)
	info.Permissions = document.Permissions
	if info.Permissions == nil {
		info.Permissions = make(map[string]interface{})
	}
	return info, nil
}

func randUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}
