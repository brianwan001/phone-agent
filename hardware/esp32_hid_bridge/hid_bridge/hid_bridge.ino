/*
 * Phone Agent HID Bridge — XIAO ESP32S3 (or any ESP32-S3 with native USB)
 *
 * Plugs into the phone's USB-C port. The phone sees:
 *   - a USB touchscreen digitizer (absolute touches, indistinguishable from a finger)
 *   - a USB keyboard (real ENTER/backspace/ASCII)
 *   - a CDC serial port that the Phone Agent app uses as the command channel
 *
 * Protocol (text lines over CDC, 115200 baud, coords are 0..10000 screen-normalized):
 *   T x y          tap
 *   L x y          long press (900 ms)
 *   S x1 y1 x2 y2  swipe
 *   K key          key press: enter | esc(back) | tab | del | up | down | left | right
 *   Y text\n       type ASCII text (single line, no spaces — send words separately)
 *   P              ping, replies OK
 */
#include <Arduino.h>
#include <Adafruit_TinyUSB_API.h>
#include <Adafruit_USBD_CDC.h>
#include <Adafruit_USBD_HID.h>

// ---------- HID report descriptor: touchscreen (report 1) + keyboard (report 2) ----------
static const uint8_t DESC_HID[] = {
  // Touch screen digitizer, absolute single contact
  0x05, 0x0D,        // Usage Page (Digitizers)
  0x09, 0x04,        // Usage (Touch Screen)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x01,        //   Report ID (1)
  0x09, 0x22,        //   Usage (Finger)
  0xA1, 0x02,        //   Collection (Logical)
  0x09, 0x42,        //     Usage (Tip Switch)
  0x15, 0x00,        //     Logical Minimum (0)
  0x25, 0x01,        //     Logical Maximum (1)
  0x75, 0x01,        //     Report Size (1)
  0x95, 0x01,        //     Report Count (1)
  0x81, 0x02,        //     Input (Data,Var,Abs)
  0x95, 0x07,        //     Report Count (7) — padding
  0x81, 0x03,        //     Input (Const,Var,Abs)
  0x05, 0x01,        //     Usage Page (Generic Desktop)
  0x09, 0x30,        //     Usage (X)
  0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
  0x75, 0x10,        //     Report Size (16)
  0x95, 0x01,        //     Report Count (1)
  0x81, 0x02,        //     Input (Data,Var,Abs)
  0x09, 0x31,        //     Usage (Y)
  0x81, 0x02,        //     Input (Data,Var,Abs)
  0xC0,              //   End Collection
  0x05, 0x0D,        //   Usage Page (Digitizers)
  0x09, 0x54,        //   Usage (Contact Count)
  0x25, 0x0A,        //   Logical Maximum (10)
  0x75, 0x08,        //   Report Size (8)
  0x95, 0x01,        //   Report Count (1)
  0x81, 0x02,        //   Input (Data,Var,Abs)
  0xC0,              // End Collection

  // Standard boot keyboard
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x06,        // Usage (Keyboard)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x02,        //   Report ID (2)
  0x05, 0x07,        //   Usage Page (Keyboard)
  0x19, 0xE0,        //   Usage Minimum (Left Control)
  0x29, 0xE7,        //   Usage Maximum (Right GUI)
  0x15, 0x00,        //   Logical Minimum (0)
  0x25, 0x01,        //   Logical Maximum (1)
  0x75, 0x01,        //   Report Size (1)
  0x95, 0x08,        //   Report Count (8)
  0x81, 0x02,        //   Input (Data,Var,Abs)
  0x95, 0x06,        //   Report Count (6)
  0x75, 0x08,        //   Report Size (8)
  0x15, 0x00,        //   Logical Minimum (0)
  0x25, 0x65,        //   Logical Maximum (101)
  0x05, 0x07,        //   Usage Page (Keyboard)
  0x19, 0x00,        //   Usage Minimum (0)
  0x29, 0x65,        //   Usage Maximum (101)
  0x81, 0x00,        //   Input (Data,Array)
  0xC0               // End Collection
};

Adafruit_USBD_HID usb_hid(DESC_HID, sizeof(DESC_HID), HID_ITF_PROTOCOL_NONE, 2, false);

// ---------- reports ----------
struct __attribute__((packed)) TouchReport {
  uint8_t reportId = 1;
  uint8_t tipAndPadding;  // bit0 = tip switch
  uint16_t x = 0;
  uint16_t y = 0;
  uint8_t contactCount = 1;
};

struct __attribute__((packed)) KeyReport {
  uint8_t reportId = 2;
  uint8_t modifier = 0;
  uint8_t reserved = 0;
  uint8_t keycodes[6] = {0};
};

static TouchReport touch;
static KeyReport key;

static void touchTo(uint16_t nx, uint16_t ny, bool down) {
  touch.tipAndPadding = down ? 0x01 : 0x00;
  touch.x = nx;
  touch.y = ny;
  usb_hid.SendReport(1, &touch, sizeof(touch));
}

static void tapN(uint16_t nx, uint16_t ny, uint16_t holdMs = 40) {
  touchTo(nx, ny, true);
  delay(holdMs);
  touchTo(nx, ny, false);
  delay(30);
}

static void swipeN(uint16_t x1, uint16_t y1, uint16_t x2, uint16_t y2, uint16_t durMs = 500) {
  const int steps = 24;
  touchTo(x1, y1, true);
  delay(60);
  for (int i = 1; i <= steps; i++) {
    touchTo(x1 + (int)(x2 - x1) * i / steps, y1 + (int)(y2 - y1) * i / steps, true);
    delay(durMs / steps);
  }
  touchTo(x2, y2, false);
  delay(40);
}

// ASCII → HID keycode (US layout). Returns keycode, sets shift.
static uint8_t asciiToKey(char c, bool &shift) {
  shift = false;
  if (c >= 'a' && c <= 'z') return 0x04 + (c - 'a');
  if (c >= 'A' && c <= 'Z') { shift = true; return 0x04 + (c - 'A'); }
  if (c >= '1' && c <= '9') return 0x1E + (c - '1');
  if (c == '0') return 0x27;
  static const char *low  = "-=[]\\;,./`";
  static const char *high = "_+{}|<>?~";
  static const uint8_t lowK[]  = {0x2D,0x2E,0x2F,0x30,0x31,0x33,0x34,0x35,0x36};
  static const uint8_t highK[] = {0x2D,0x2E,0x2F,0x30,0x31,0x33,0x34,0x35,0x36};
  for (int i = 0; low[i]; i++) {
    if (c == low[i]) return lowK[i];
    if (c == high[i]) { shift = true; return highK[i]; }
  }
  switch (c) {
    case ' ': return 0x2C; case '\n': return 0x28; case '\t': return 0x2B;
    case '!': shift = true; return 0x1E; case '@': shift = true; return 0x1F;
    case '#': shift = true; return 0x20; case '$': shift = true; return 0x21;
    case '%': shift = true; return 0x22; case '^': shift = true; return 0x23;
    case '&': shift = true; return 0x24; case '*': shift = true; return 0x25;
    case '(': shift = true; return 0x26; case ')': shift = true; return 0x27;
    case '\'': return 0x34; case '"': shift = true; return 0x34;
    case ':': shift = true; return 0x33; case '?': shift = true; return 0x38;
  }
  return 0;
}

static void pressKey(uint8_t code, uint8_t mod = 0, uint16_t holdMs = 40) {
  key.modifier = mod;
  key.keycodes[0] = code;
  usb_hid.SendReport(2, &key, sizeof(key));
  delay(holdMs);
  key.modifier = 0;
  key.keycodes[0] = 0;
  usb_hid.SendReport(2, &key, sizeof(key));
  delay(30);
}

static void typeAscii(const char *s) {
  for (const char *p = s; *p; p++) {
    if (*p == '_') { pressKey(0x2C); delay(12); continue; }  // '_' = space
    bool shift = false;
    uint8_t code = asciiToKey(*p, shift);
    if (code) pressKey(code, shift ? 0x02 : 0);
    delay(12);
  }
}

static uint16_t toRaw(int v10000) {  // 0..10000 → 0..32767
  long v = (long)constrain(v10000, 0, 10000) * 32767L / 10000L;
  return (uint16_t)v;
}

void setup() {
  Serial.begin(115200);
  usb_hid.begin();
  // Wait until USB is mounted (CDC ready)
  while (!Serial.isConnected() && !TinyUSBDevice.mounted()) delay(10);
  Serial.println("HID bridge ready");
}

void loop() {
  static char line[128];
  static int len = 0;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (len > 0) { line[len] = 0; handleLine(line); len = 0; }
    } else if (len < (int)sizeof(line) - 1) {
      line[len++] = c;
    }
  }
  delay(2);
}

static void handleLine(char *line) {
  int a = 0, b = 0, c = 0, d = 0;
  switch (line[0]) {
    case 'T':
      if (sscanf(line + 1, "%d %d", &a, &b) == 2) { tapN(toRaw(a), toRaw(b)); Serial.println("OK"); }
      break;
    case 'L':
      if (sscanf(line + 1, "%d %d", &a, &b) == 2) { tapN(toRaw(a), toRaw(b), 900); Serial.println("OK"); }
      break;
    case 'S':
      if (sscanf(line + 1, "%d %d %d %d", &a, &b, &c, &d) == 4) {
        swipeN(toRaw(a), toRaw(b), toRaw(c), toRaw(d)); Serial.println("OK");
      }
      break;
    case 'K': {
      const char *k = line + 2;
      if (!strcmp(k, "enter")) pressKey(0x28);
      else if (!strcmp(k, "tab")) pressKey(0x2B);
      else if (!strcmp(k, "esc") || !strcmp(k, "back")) pressKey(0x29);
      else if (!strcmp(k, "del")) pressKey(0x2A);
      else if (!strcmp(k, "up")) pressKey(0x52);
      else if (!strcmp(k, "down")) pressKey(0x51);
      else if (!strcmp(k, "left")) pressKey(0x50);
      else if (!strcmp(k, "right")) pressKey(0x4F);
      Serial.println("OK");
      break;
    }
    case 'Y':
      typeAscii(line + 2);
      Serial.println("OK");
      break;
    case 'P':
      Serial.println("OK");
      break;
    default:
      Serial.println("ERR");
  }
}
