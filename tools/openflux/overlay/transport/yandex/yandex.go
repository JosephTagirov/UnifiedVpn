package yandex

import (
	"context"
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
	clientConfigRe  = regexp.MustCompile(`(?s)<script[^>]*id="client-config"[^>]*>(.*?)</script>`)
)

type YandexDocsInfo struct {
	CookieStr   string
	Token       string
	DocID       string
	CallbackURL string
	UserID      string
	Origin      string
	Host        string
	WsURL       string
	Permissions map[string]interface{}
	OpenCmd     map[string]interface{}
}

type DocSession struct {
	Info       YandexDocsInfo
	Conn       *websocket.Conn
	WriteQueue chan []byte
	UserID     string
	writeMu    sync.Mutex
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
			utils.Debugf("[YDOCS] fetchDocInfo failed: %v", err)
			t.scheduleReconnect(attempt)
			return
		}

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
		headers := http.Header{}
		headers.Set("User-Agent", "Mozilla/5.0")
		headers.Set("Origin", info.Origin)
		headers.Set("Cookie", info.CookieStr)
		headers.Set("Host", info.Host)

		utils.Debugf("[YDOCS] WebSocket dial")
		ctx := t.bootstrap.ctx
		conn, resp, err := dialer.DialContext(ctx, info.WsURL, headers)
		if err != nil {
			status := 0
			if resp != nil {
				status = resp.StatusCode
			}
			utils.Debugf("[YDOCS] WebSocket dial failed (http %d)", status)
			t.scheduleReconnect(attempt)
			return
		}
		utils.Debugf("[YDOCS] WebSocket connected to %s", info.Host)

		writeQueue := make(chan []byte, t.GetConfig().MaxQueueSize)
		if existingSession != nil {
			writeQueue = existingSession.WriteQueue
		}

		session := &DocSession{
			Info:       info,
			Conn:       conn,
			WriteQueue: writeQueue,
			UserID:     userID,
		}

		if err := t.activateSession(session); err != nil {
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
	failure := fmt.Errorf("Yandex session authentication failed")
	if !t.IsRunning() || t.bootstrap.ctx.Err() != nil {
		_ = session.Conn.Close()
		return failure
	}
	stopCancel := context.AfterFunc(t.bootstrap.ctx, func() { _ = session.Conn.Close() })
	defer stopCancel()
	if err := session.Conn.SetWriteDeadline(time.Now().Add(15 * time.Second)); err != nil {
		_ = session.Conn.Close()
		return failure
	}
	authToken, _ := json.Marshal(map[string]string{"token": session.Info.Token})
	authData := map[string]interface{}{
		"type": "auth", "docid": session.Info.DocID, "token": "fghhfgsjdgfjs",
		"user": map[string]interface{}{"id": session.UserID}, "editorType": 0,
		"lastOtherSaveTime": -1, "permissions": session.Info.Permissions,
		"openCmd": session.Info.OpenCmd, "coEditingMode": "fast", "jwtOpen": session.Info.Token,
	}
	messagePart, err := json.Marshal([]interface{}{"message", authData})
	if err != nil || session.safeWrite(websocket.TextMessage, append([]byte("40"), authToken...)) != nil ||
		session.safeWrite(websocket.TextMessage, append([]byte("42"), messagePart...)) != nil ||
		session.Conn.SetWriteDeadline(time.Time{}) != nil {
		_ = session.Conn.Close()
		return failure
	}
	// Only expose the socket after both auth frames, so the persistent writer
	// and keepalive cannot send a cursor frame ahead of authentication.
	t.Mu.Lock()
	defer t.Mu.Unlock()
	if !t.IsRunning() || t.bootstrap.ctx.Err() != nil {
		_ = session.Conn.Close()
		return failure
	}
	t.session = session
	t.SetConnected(true)
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
		html, err := t.bootstrap.fetch(url)
		if err != nil {
			return YandexDocsInfo{}, err
		}
		info, err := parseDocumentInfo(html, userID)
		if err != nil || !allowedBalancer(info.Origin) || info.Permissions["edit"] != true {
			t.bootstrap.publish(ErrDocumentAccess)
			return YandexDocsInfo{}, ErrDocumentAccess
		}
		info.CookieStr = t.bootstrap.cookiesFor(info.Origin)
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

func parseDocumentInfo(html, userID string) (YandexDocsInfo, error) {
	matches := clientConfigRe.FindStringSubmatch(html)
	if len(matches) < 2 {
		// Help diagnose: is this a login page, a new-editor page, etc.?
		hint := "no client-config script"
		if strings.Contains(html, "passport") || strings.Contains(strings.ToLower(html), "login") {
			hint = "looks like a login page (doc not public?)"
		}
		return YandexDocsInfo{}, fmt.Errorf("config not found: %s", hint)
	}

	var config map[string]interface{}
	if err := json.Unmarshal([]byte(matches[1]), &config); err != nil {
		return YandexDocsInfo{}, fmt.Errorf("client-config is not valid JSON: %w", err)
	}

	officeAction, ok := config["officeActionData"].(map[string]interface{})
	if !ok || officeAction == nil {
		return YandexDocsInfo{}, fmt.Errorf("officeActionData missing - will reconnect")
	}

	editorConfigRaw, ok := officeAction["editor_config"].(map[string]interface{})
	if !ok || editorConfigRaw == nil {
		return YandexDocsInfo{}, fmt.Errorf("editor_config nil - will reconnect")
	}

	balancerURL, ok := officeAction["balancer_url"].(string)
	if !ok || balancerURL == "" {
		return YandexDocsInfo{}, fmt.Errorf("officeActionData.balancer_url missing - will reconnect")
	}
	host := strings.TrimPrefix(balancerURL, "https://")

	document, ok := editorConfigRaw["document"].(map[string]interface{})
	if !ok || document == nil {
		return YandexDocsInfo{}, fmt.Errorf("editor_config.document missing - will reconnect")
	}

	token, ok := editorConfigRaw["token"].(string)
	if !ok || token == "" {
		return YandexDocsInfo{}, fmt.Errorf("editor_config.token missing - will reconnect")
	}

	docKey, ok := document["key"].(string)
	if !ok || docKey == "" {
		return YandexDocsInfo{}, fmt.Errorf("editor_config.document.key missing - will reconnect")
	}

	perms, _ := document["permissions"].(map[string]interface{})
	if perms == nil {
		perms = make(map[string]interface{})
	}

	return YandexDocsInfo{
		Token:       token,
		DocID:       docKey,
		Origin:      balancerURL,
		Host:        host,
		WsURL:       fmt.Sprintf("wss://%s/2024.1.1-375/doc/%s/c/?EIO=4&transport=websocket", host, docKey),
		Permissions: perms,
		OpenCmd: map[string]interface{}{
			"c":      "open",
			"id":     docKey,
			"userid": userID,
			"format": document["fileType"],
			"url":    document["url"],
			"title":  document["title"],
			"lcid":   25,
		},
	}, nil
}

func randUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}
