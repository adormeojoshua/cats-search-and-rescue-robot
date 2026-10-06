#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <esp_wifi.h>

// ================================
// WIFI - STATION MODE
// ================================
const char* ssid = "YOUR_HOTSPOT_NAME";
const char* password = "YOUR_HOTSPOT_PASSWORD";

IPAddress local_IP(10, 42, 0, 5);   // outside the Pi's DHCP pool (10.42.0.10-254)
IPAddress gateway(10, 42, 0, 1);    // the Raspberry Pi
IPAddress subnet(255, 255, 255, 0);

// ================================
// BTS7960 #1 - DRIVE MOTOR (front + rear, parallel)
// ================================
#define DRIVE_RPWM 18
#define DRIVE_LPWM 19
#define DRIVE_R_EN 4
#define DRIVE_L_EN 5

// ================================
// BTS7960 #2 - STEERING MOTOR (spring self-centering)
// ================================
#define STEER_RPWM 32
#define STEER_LPWM 33
#define STEER_R_EN 13
#define STEER_L_EN 12

// ================================
// PWM
// ================================
#define PWM_FREQ 5000
#define PWM_RESOLUTION 8

WebServer server(80);

// ================================
// UDP DRIVE CHANNEL
// The app sends 1 byte every 100ms: bitmask of held buttons
// 1=forward, 2=backward, 4=left, 8=right
// ================================
#define UDP_PORT 4210
WiFiUDP udp;

// ================================
// DEADMAN SAFETY
// UDP packets arrive every 100ms. If nothing is heard for DEADMAN_MS
// while a direction is active, all motors stop.
// ================================
#define DEADMAN_ENABLED true
#define DEADMAN_MS 400
unsigned long lastCommandTime = 0;

// ================================
// WIFI RECONNECT (non-blocking)
// ================================
#define RECONNECT_INTERVAL_MS 10000
unsigned long lastReconnectAttempt = 0;
bool wifiWasLost = false;

// ================================
// GEAR SYSTEM
// ================================
#define NUM_GEARS 4
int gearSpeeds[NUM_GEARS] = {100, 150, 200, 255}; // Low, Medium, High, Turbo
int currentGear = 1; // starts on Medium

int driveSpeed = gearSpeeds[currentGear];
int steerSpeed = 140;

bool stateForward = false;
bool stateBackward = false;
bool stateLeft = false;
bool stateRight = false;

// ================================
// REMOTE LOG BUFFER (view at /log)
// ================================
#define LOG_LINES 40
String logBuffer[LOG_LINES];
int logIndex = 0;
unsigned long bootTime = 0;

void logMsg(String msg) {
  unsigned long seconds = (millis() - bootTime) / 1000;
  String line = "[" + String(seconds) + "s] " + msg;

  logBuffer[logIndex] = line;
  logIndex = (logIndex + 1) % LOG_LINES;

  Serial.println(msg);
}


// ================================
// ENABLE PINS
// ================================
void enableMotors() {
  digitalWrite(DRIVE_R_EN, HIGH);
  digitalWrite(DRIVE_L_EN, HIGH);
  digitalWrite(STEER_R_EN, HIGH);
  digitalWrite(STEER_L_EN, HIGH);
}


// ================================
// DRIVE CONTROL
// ================================
void updateDrive() {
  if (stateForward && !stateBackward) {
    ledcWrite(DRIVE_RPWM, 0);
    ledcWrite(DRIVE_LPWM, driveSpeed);
    logMsg("DRIVE: FORWARD");
  } else if (stateBackward && !stateForward) {
    ledcWrite(DRIVE_LPWM, 0);
    ledcWrite(DRIVE_RPWM, driveSpeed);
    logMsg("DRIVE: BACKWARD");
  } else {
    ledcWrite(DRIVE_RPWM, 0);
    ledcWrite(DRIVE_LPWM, 0);
    logMsg("DRIVE: STOP");
  }
}


// ================================
// STEERING CONTROL
// Swapped: LPWM = LEFT, RPWM = RIGHT
// Release = stop power, spring returns wheels to straight
// ================================
void updateSteering() {
  if (stateLeft && !stateRight) {
    ledcWrite(STEER_RPWM, 0);
    ledcWrite(STEER_LPWM, steerSpeed);
    logMsg("STEER: LEFT");
  } else if (stateRight && !stateLeft) {
    ledcWrite(STEER_LPWM, 0);
    ledcWrite(STEER_RPWM, steerSpeed);
    logMsg("STEER: RIGHT");
  } else {
    ledcWrite(STEER_RPWM, 0);
    ledcWrite(STEER_LPWM, 0);
    logMsg("STEER: RELEASED (spring returns to straight)");
  }
}


// ================================
// STOP EVERYTHING (used by /stop, deadman, and WiFi loss)
// ================================
void stopAll() {
  stateForward = false;
  stateBackward = false;
  stateLeft = false;
  stateRight = false;

  updateDrive();
  updateSteering();
}

bool anyDirectionActive() {
  return stateForward || stateBackward || stateLeft || stateRight;
}


// ================================
// UDP DRIVE HANDLING
// Every packet carries the full button state, so a lost packet
// (including a lost release) is corrected by the next one.
// ================================
void applyMask(uint8_t m) {
  lastCommandTime = millis();

  bool f = m & 1, b = m & 2, l = m & 4, r = m & 8;
  // Opposite directions held together cancel to stop
  if (f && b) { f = false; b = false; }
  if (l && r) { l = false; r = false; }

  bool driveChanged = (f != stateForward) || (b != stateBackward);
  bool steerChanged = (l != stateLeft) || (r != stateRight);

  stateForward = f; stateBackward = b;
  stateLeft = l;    stateRight = r;

  if (driveChanged || steerChanged) enableMotors();
  if (driveChanged) updateDrive();
  if (steerChanged) updateSteering();
}

void handleUdp() {
  bool got = false;
  uint8_t last = 0;
  while (udp.parsePacket() > 0) {
    uint8_t buf[8];
    int n = udp.read(buf, sizeof(buf));
    if (n >= 1) { last = buf[0]; got = true; }
  }
  if (got) applyMask(last);   // only the newest packet matters
}


// ================================
// WEB PAGE
// Note: the web page still drives over HTTP heartbeats (150ms).
// For testing the UDP path, drive from the Android app, not this page.
// ================================
void handleRoot() {

  String html = R"rawliteral(
<!DOCTYPE html>
<html>

<head>

<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">

<title>CATS Robot</title>

<style>

* {
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
  touch-action: manipulation;
}

body {
  background: #222;
  color: white;
  font-family: Arial;
  text-align: center;
}

button {
  width: 140px;
  height: 80px;
  margin: 8px;
  font-size: 20px;
  border: none;
  border-radius: 15px;
  background: #2196F3;
  color: white;
}

button:active {
  background: #0b7dda;
}

.stop {
  background: #f44336;
}

.row {
  display: flex;
  justify-content: center;
}

.gear-btn {
  width: 90px;
  height: 60px;
  font-size: 16px;
  background: #555;
}

.gear-btn.active {
  background: #4CAF50;
}

.log-link {
  display: inline-block;
  margin-top: 20px;
  color: #90caf9;
  font-size: 14px;
}

</style>

</head>

<body>

<h1>CATS Robot</h1>

<h2>Hold to Move / Hold to Turn</h2>

<div class="row">
<button class="gear-btn" id="gear1" onclick="setGear(1)">GEAR 1</button>
<button class="gear-btn" id="gear2" onclick="setGear(2)">GEAR 2</button>
<button class="gear-btn" id="gear3" onclick="setGear(3)">GEAR 3</button>
<button class="gear-btn" id="gear4" onclick="setGear(4)">GEAR 4</button>
</div>

<div class="row">
<button id="btnForward">FORWARD</button>
</div>

<div class="row">
<button id="btnLeft">LEFT</button>
<button class="stop" onclick="sendOnce('/stop')">STOP</button>
<button id="btnRight">RIGHT</button>
</div>

<div class="row">
<button id="btnBackward">BACKWARD</button>
</div>

<a class="log-link" href="/log">View Debug Log</a>

<script>

function sendOnce(command) {
  fetch(command).catch(() => {});
}

function setGear(level) {
  fetch('/gear?level=' + level).catch(() => {});
  document.querySelectorAll('.gear-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('gear' + level).classList.add('active');
}

// While a button is held, resend the command every 150ms (heartbeat).
const HEARTBEAT_MS = 150;

function bindHold(id, downCmd, upCmd) {
  const el = document.getElementById(id);
  let timer = null;

  const down = (e) => {
    e.preventDefault();
    if (timer) return;
    fetch(downCmd).catch(() => {});
    timer = setInterval(() => fetch(downCmd).catch(() => {}), HEARTBEAT_MS);
  };

  const up = (e) => {
    e.preventDefault();
    if (!timer) return;
    clearInterval(timer);
    timer = null;
    fetch(upCmd).catch(() => {});
  };

  el.addEventListener('mousedown', down);
  el.addEventListener('touchstart', down);

  el.addEventListener('mouseup', up);
  el.addEventListener('mouseleave', up);
  el.addEventListener('touchend', up);
  el.addEventListener('touchcancel', up);
}

bindHold('btnForward',  '/state?dir=forward&val=1',  '/state?dir=forward&val=0');
bindHold('btnBackward', '/state?dir=backward&val=1', '/state?dir=backward&val=0');
bindHold('btnLeft',     '/state?dir=left&val=1',     '/state?dir=left&val=0');
bindHold('btnRight',    '/state?dir=right&val=1',    '/state?dir=right&val=0');

document.getElementById('gear2').classList.add('active');

</script>

</body>
</html>
)rawliteral";

  server.send(200, "text/html", html);
}


// ================================
// LOG PAGE (newest first, auto-refresh every 3s)
// ================================
void handleLog() {
  String html = "<!DOCTYPE html><html><head>";
  html += "<meta http-equiv='refresh' content='3'>";
  html += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
  html += "<title>CATS Robot Log</title>";
  html += "<style>";
  html += "body{background:#111;color:#0f0;font-family:monospace;font-size:14px;padding:10px;}";
  html += "a{color:#90caf9;}";
  html += "h2{color:#fff;font-family:Arial;}";
  html += "</style></head><body>";
  html += "<h2>CATS Robot Debug Log</h2>";
  html += "<p><a href='/'>&larr; Back to controls</a></p>";
  html += "<p>Uptime: " + String((millis() - bootTime) / 1000) + "s | Free heap: " + String(ESP.getFreeHeap()) + " bytes</p>";
  html += "<p>WiFi RSSI: " + String(WiFi.RSSI()) + " dBm | Deadman: " + String(DEADMAN_ENABLED ? "ON" : "OFF") + "</p>";
  html += "<pre>";

  for (int i = 0; i < LOG_LINES; i++) {
    int idx = (logIndex - 1 - i + LOG_LINES) % LOG_LINES;
    if (logBuffer[idx].length() > 0) {
      html += logBuffer[idx] + "\n";
    }
  }

  html += "</pre></body></html>";

  server.send(200, "text/html", html);
}


// ================================
// STATE ROUTE (HTTP, used by the web page)
// ================================
void handleState() {
  if (!server.hasArg("dir") || !server.hasArg("val")) {
    server.send(400, "text/plain", "Missing args");
    return;
  }

  String dir = server.arg("dir");
  bool val = server.arg("val") == "1";

  lastCommandTime = millis();

  bool prevF = stateForward, prevB = stateBackward;
  bool prevL = stateLeft, prevR = stateRight;

  enableMotors();

  if (dir == "forward") {
    stateForward = val;
    if (val) { stateBackward = false; }
  }
  if (dir == "backward") {
    stateBackward = val;
    if (val) { stateForward = false; }
  }
  if (dir == "left") {
    stateLeft = val;
    if (val) { stateRight = false; }
  }
  if (dir == "right") {
    stateRight = val;
    if (val) { stateLeft = false; }
  }

  bool driveChanged = (prevF != stateForward) || (prevB != stateBackward);
  bool steerChanged = (prevL != stateLeft) || (prevR != stateRight);

  if (driveChanged) updateDrive();
  if (steerChanged) updateSteering();

  server.send(200, "text/plain", "OK");
}


// ================================
// GEAR ROUTE
// ================================
void handleGear() {
  if (!server.hasArg("level")) {
    server.send(400, "text/plain", "Missing level");
    return;
  }

  int level = server.arg("level").toInt();

  if (level < 1 || level > NUM_GEARS) {
    server.send(400, "text/plain", "Invalid gear");
    return;
  }

  lastCommandTime = millis();

  currentGear = level - 1;
  driveSpeed = gearSpeeds[currentGear];

  logMsg("GEAR: " + String(level) + " (speed=" + String(driveSpeed) + ")");

  updateDrive();

  server.send(200, "text/plain", "Gear " + String(level));
}


// ================================
// STOP ROUTE
// ================================
void handleStop() {
  lastCommandTime = millis();
  stopAll();
  server.send(200, "text/plain", "Stop");
}


// ================================
// WIFI EVENT LOGGING + KEEP POWER-SAVE OFF
// ================================
void WiFiEvent(WiFiEvent_t event) {
  switch (event) {
    case ARDUINO_EVENT_WIFI_STA_CONNECTED:
      logMsg("WiFi event: STA_CONNECTED");
      break;
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
      WiFi.setSleep(false);
      esp_wifi_set_ps(WIFI_PS_NONE);
      logMsg("WiFi event: GOT_IP " + WiFi.localIP().toString());
      break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
      logMsg("WiFi event: STA_DISCONNECTED");
      break;
    default:
      break;
  }
}


// ================================
// SETUP
// ================================
void setup() {

  Serial.begin(115200);
  delay(1000);

  bootTime = millis();

  logMsg("==============================");
  logMsg("CATS ROBOT STARTING (v4: UDP drive + deadman)");
  logMsg("==============================");

  esp_reset_reason_t reason = esp_reset_reason();
  logMsg("Reset reason code: " + String(reason));
  // 1 = POWERON, 3 = SW_RESET, 4 = OWDT, 5 = DEEPSLEEP, 6 = SDIO,
  // 7 = TG0WDT_SYS, 12 = TG1WDT_SYS, 14 = BROWNOUT (varies by IDF version)

  pinMode(DRIVE_R_EN, OUTPUT);
  pinMode(DRIVE_L_EN, OUTPUT);
  pinMode(STEER_R_EN, OUTPUT);
  pinMode(STEER_L_EN, OUTPUT);

  enableMotors();
  logMsg("All EN pins = HIGH");

  ledcAttach(DRIVE_RPWM, PWM_FREQ, PWM_RESOLUTION);
  ledcAttach(DRIVE_LPWM, PWM_FREQ, PWM_RESOLUTION);
  ledcAttach(STEER_RPWM, PWM_FREQ, PWM_RESOLUTION);
  ledcAttach(STEER_LPWM, PWM_FREQ, PWM_RESOLUTION);

  updateDrive();
  updateSteering();

  // ================================
  // STATION MODE: connect to Pi hotspot
  // ================================
  WiFi.onEvent(WiFiEvent);
  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  WiFi.setAutoReconnect(true);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
  WiFi.setSleep(false);
  esp_wifi_set_ps(WIFI_PS_NONE);

  if (!WiFi.config(local_IP, gateway, subnet)) {
    logMsg("STA Failed to configure static IP");
  }

  WiFi.begin(ssid, password);

  logMsg("Connecting to " + String(ssid));

  int retries = 0;
  while (WiFi.status() != WL_CONNECTED && retries < 40) {
    delay(500);
    retries++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    WiFi.setSleep(false);
    esp_wifi_set_ps(WIFI_PS_NONE);
    logMsg("WiFi connected! IP: " + WiFi.localIP().toString());
  } else {
    logMsg("WiFi connection FAILED. Will keep retrying in loop.");
  }

  server.on("/", handleRoot);
  server.on("/state", handleState);
  server.on("/gear", handleGear);
  server.on("/stop", handleStop);
  server.on("/log", handleLog);
  server.on("/ping", []() { server.send(200, "text/plain", "OK"); });

  server.begin();
  udp.begin(UDP_PORT);

  lastCommandTime = millis();

  logMsg("Web server started, UDP listening on " + String(UDP_PORT));
  logMsg("==============================");
  logMsg("READY");
}


// ================================
// LOOP
// ================================
void loop() {
  server.handleClient();
  handleUdp();

  // ---- DEADMAN: stop if a held direction has gone quiet ----
  if (DEADMAN_ENABLED && anyDirectionActive() &&
      (millis() - lastCommandTime > DEADMAN_MS)) {
    logMsg("DEADMAN: no command for " + String(DEADMAN_MS) + "ms, stopping");
    stopAll();
  }

  // ---- WIFI WATCHDOG: stop motors on link loss, reconnect without blocking ----
  if (WiFi.status() != WL_CONNECTED) {
    if (!wifiWasLost) {
      wifiWasLost = true;
      logMsg("WiFi lost. Stopping motors.");
      stopAll();
    }

    if (millis() - lastReconnectAttempt > RECONNECT_INTERVAL_MS) {
      lastReconnectAttempt = millis();
      logMsg("Reconnecting...");
      WiFi.reconnect();   // no disconnect() first, so an in-progress attempt isn't aborted
    }
  } else if (wifiWasLost) {
    wifiWasLost = false;
    logMsg("WiFi restored");
  }
}