# DirectLink Protocol (DLP) Version 1

## Message Framing
All DirectLink messages are transmitted over reliable (TCP/QUIC) or unreliable (UDP) channels with the following frame header:
```
[Magic Bytes] - 4 bytes: 'D', 'L', 'P', '1'
[Message Type] - 1 byte
[Payload Length] - 4 bytes (Little Endian, uint32)
[Payload] - variable
```

## Message Types

### 0x01: CLIENT_HELLO
* Direction: Client -> Host
* Payload:
  - Protocol Version (1 byte)
  - Client ID length (1 byte)
  - Client ID string (utf-8)
  - Public Key length (1 byte)
  - Ed25519 Public Key (bytes)

### 0x02: HOST_HELLO
* Direction: Host -> Client
* Payload:
  - Protocol Version (1 byte)
  - Host ID length (1 byte)
  - Host ID string (utf-8)
  - Challenge nonce (32 bytes)
  - Require Authentication (1 byte: 1=true, 0=false)

### 0x03: CAPABILITY_EXCHANGE
* Direction: Both
* Payload: JSON encoded capabilities (supported codecs, resolutions, input mappings) for simplicity and extensibility.

### 0x04: AUTHENTICATION
* Direction: Client -> Host
* Payload:
  - Signature over the challenge nonce (64 bytes)

### 0x05: SESSION_START
* Direction: Host -> Client
* Payload:
  - Status (1 byte: 0=Success, 1=Denied)

### 0x06: VIDEO_FRAME
* Direction: Host -> Client
* Payload:
  - Frame Sequence Number (4 bytes)
  - Timestamp (8 bytes, milliseconds)
  - Is Keyframe (1 byte)
  - Frame Width (2 bytes)
  - Frame Height (2 bytes)
  - Frame Data (remainder)

### 0x07: INPUT_EVENT
* Direction: Client -> Host
* Payload:
  - Event Type (1 byte: 1=MouseMove, 2=MouseButton, 3=KeyDown, 4=KeyUp)
  - If MouseMove:
    - X coordinate (2 bytes)
    - Y coordinate (2 bytes)
  - If MouseButton:
    - Button (1 byte: 1=Left, 2=Right, 3=Middle)
    - State (1 byte: 1=Down, 0=Up)
  - If Key:
    - Keycode (2 bytes)
    - State (1 byte)

### 0x09: DISCONNECT
* Direction: Both
* Payload: None
