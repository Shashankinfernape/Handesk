# DirectLink Development Roadmap

## Phase 0: Project Foundation (Current)
- [x] Repository analysis and structure creation
- [x] Define AGENTS.md
- [x] Create roadmap
- [ ] Initialize Windows Host (Rust)
- [ ] Initialize Android Client (Kotlin/Compose)
- [ ] Establish build system (Cargo/Gradle)

## Phase 1: Core Networking & Basic Streaming
- [ ] Implement UDP/QUIC Transport abstraction (Rust shared lib)
- [ ] Local LAN Discovery (mDNS/UDP broadcast)
- [ ] Basic Protocol definition (handshake, video packet, input packet)
- [ ] Windows screen capture (Windows Graphics Capture)
- [ ] Uncompressed/basic encoded screen transfer
- [ ] Android receive and render (Surface)

## Phase 2: Performance & Hardware Acceleration
- [ ] Windows Media Foundation hardware encoding (H.264)
- [ ] Android MediaCodec hardware decoding
- [ ] Adaptive bitrate (RTT/Loss -> Encoder Quality)
- [ ] Frame pacing and latency metrics
- [ ] Performance HUD on Android

## Phase 3: Control & Input
- [ ] Android Touch-to-Mouse mapping
- [ ] Windows Input Injection (SendInput)
- [ ] Android Keyboard to Windows injection
- [ ] Gesture support

## Phase 4: Audio & Clipboard
- [ ] Windows Audio Capture (WASAPI)
- [ ] Android Audio Playback (AudioTrack/Oboe)
- [ ] Bidirectional Clipboard synchronization
- [ ] Multi-monitor detection and selection

## Phase 5: Security & UI Polish
- [ ] Cryptographic Identity (Ed25519)
- [ ] QR Code pairing
- [ ] Session Encryption (ChaCha20-Poly1305 / TLS)
- [ ] Android modern UI (Devices, Settings, Discovery)
- [ ] Windows Host UI (Dashboard, Pairings)

## Phase 6: Release & Hardening
- [ ] Automated End-to-End tests
- [ ] Disconnect/Reconnect logic optimization
- [ ] Network degradation scenarios (packet loss simulators)
- [ ] Build installers (Windows MSI/EXE, Android APK/AAB)
