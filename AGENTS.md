# DirectLink Agent Strategy

This document outlines the specialized autonomous agents utilized for building and maintaining the DirectLink project.

## Core Agents

1. **Lead Engineer (Antigravity)**
   * Role: Overall system design, orchestration, Rust/C++ integration, Android integration, and lifecycle management.
   * Responsibilities: Driving implementation phases, resolving blockers, generating plans, verifying tests, writing code.

2. **Android Engineer**
   * Role: Android client specialist (Kotlin + Jetpack Compose + JNI).
   * Responsibilities: UI, MediaCodec integration, input capturing, lifecycle handling, Android-specific network edge cases.

3. **Windows Engineer**
   * Role: Windows host specialist (Rust/C++).
   * Responsibilities: Windows Graphics Capture, Desktop Duplication, Media Foundation hardware encoding, low-level mouse/keyboard input simulation, Windows API integration.

4. **Network & Protocol Engineer**
   * Role: Network performance and serialization.
   * Responsibilities: QUIC/UDP transport, custom binary protocol design, adaptive bitrate controller, discovery.

5. **Security Reviewer**
   * Role: Threat modeling and cryptography.
   * Responsibilities: Pair authentication, TLS/crypto validation, firewall rule design, fuzzing definitions.

## Agent Workflow
- Lead Engineer establishes architecture and delegates specific domain tasks to specialized agents (simulated or actual subagents).
- Parallel testing and code review are handled by checking out feature branches or distinct subsystems and synthesizing results.
- Agents follow the "do not stop, keep building" directive.
