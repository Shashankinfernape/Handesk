# Handesk (DirectLink)

Handesk is a high-performance, low-latency remote desktop application that lets you control your Windows PC directly from your Android device. It uses hardware-accelerated video encoding (H.265) and a custom UDP protocol to deliver flawless 60fps streaming and instant touch interactions.

## 🚀 Download Latest Release (3 Oct 2026)

Get the latest version of Handesk for your devices:

- 🖥️ **[Download Handesk for Windows](https://github.com/Shashankinfernape/Handesk/releases/latest/download/Handesk-Windows.zip)**
- 📱 **[Download Handesk for Android](https://github.com/Shashankinfernape/Handesk/releases/latest/download/Handesk-Android.apk)**

*(Note: Click the links above once the release is published on GitHub, or download the files from the [Releases page](https://github.com/Shashankinfernape/Handesk/releases))*

---

## 📖 Walkthrough: How to Setup and Use Handesk

### Step 1: Set up the Windows Host
1. Download and extract `Handesk-Windows.zip` to a folder on your PC.
2. Run `handesk_ui.exe`. 
3. The app will launch and show a **"Service Running"** indicator along with your PC's IP addresses. (Keep this window open).

### Step 2: Set up the Android App
1. Download `Handesk-Android.apk` and install it on your Android phone or tablet.
2. Open the Handesk app on your Android device.

### Step 3: Connect
1. Ensure both your PC and Android device are on the **same Wi-Fi network**, or connected via **Tailscale** (see below).
2. Look at the Windows Handesk app and note your **IP Address** (e.g., `192.168.1.100` or `100.x.x.x`).
3. In the Android app, enter this IP address and tap **Connect**.
4. You will instantly see your PC screen! Use touch to move the mouse, tap to click, and use the on-screen controls to type or disconnect.

---

## 🌐 Powered by Tailscale (Play Anywhere)

Handesk works perfectly on your local Wi-Fi, but what if you want to control your PC when you leave the house? **Handesk is fully optimized for Tailscale.**

**What is Tailscale?**
[Tailscale](https://tailscale.com/) is a zero-config VPN that creates a secure, private network (a "mesh network") between your devices, no matter where they are in the world. It is built on WireGuard®, ensuring military-grade encryption and blazing fast speeds.

**Why is it reliable for Handesk?**
- **No Port Forwarding:** You don't need to mess with your router settings.
- **Direct P2P Connections:** Tailscale punches through firewalls to connect your phone *directly* to your PC, minimizing latency.
- **Auto-Detection:** Handesk automatically detects when you are using a Tailscale IP (`100.x.x.x`) and automatically adjusts its UDP packet pacing and MTU sizes. This prevents router buffer overflows and ensures a smooth 60fps stream even over 4G/5G mobile networks!

**How to use it:**
1. Install Tailscale on your Windows PC and your Android device.
2. Log in to both using the same account.
3. Open Handesk on Windows — it will automatically detect and display your `Tailscale IP` on the dashboard!
4. Enter that IP into the Android app to connect from anywhere.

---

## 💻 System Requirements

### Windows Host
- **OS:** Windows 10 or Windows 11 (64-bit)
- **GPU:** A dedicated or integrated graphics card with hardware video encoding support (NVIDIA NVENC, Intel QuickSync, or AMD AMF).
- **Network:** A stable Wi-Fi connection or Ethernet (recommended for host).
- **Permissions:** You may be prompted by Windows Defender Firewall to allow `handesk_ui.exe` through on private/public networks. Please allow it so the Android app can connect.

### Android Client
- **OS:** Android 8.0 (Oreo) or higher.
- **Hardware:** Most modern smartphones and tablets are supported. A device with a hardware HEVC (H.265) decoder is required for optimal battery life and performance.
