# Handesk (DirectLink)

Handesk is a high-performance, low-latency remote desktop application that lets you control your Windows PC directly from your Android device. It uses hardware-accelerated video encoding (H.265) and a custom UDP protocol to deliver flawless 60fps streaming and instant touch interactions.

## 🚀 Download Latest Release

Get the latest version of Handesk for your devices:

- 🖥️ **[Download Handesk for Windows](https://github.com/Shashankinfernape/Handesk/releases/latest/download/Handesk-Windows-Newest.zip)**
- 📱 **[Download Handesk for Android](https://github.com/Shashankinfernape/Handesk/releases/latest/download/Handesk-Android.apk)**

*(Note: Download the assets from the [Releases page](https://github.com/Shashankinfernape/Handesk/releases))*

---

## 📖 Walkthrough: How to Setup and Use Handesk

### Step 1: Set up the Windows Host
1. Download and extract `Handesk-Windows-Newest.zip` to a folder on your PC.
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

## 💻 System Requirements & Supported GPUs

Handesk uses **hardware-accelerated H.265 (HEVC)** encoding for ultra-low latency. It works on both **integrated graphics (laptops with no dedicated GPU)** and **discrete graphics cards**.

### 1. Integrated Graphics (iGPU) — Laptops with No Dedicated GPU

You do **not** need a gaming GPU. Any standard office, student, or thin-and-light laptop with integrated graphics is supported:

* **Intel (Intel QuickSync Video)**
  * **Supported:** Intel Core 7th Gen (2016) or newer
  * **Starting Models:**
    * Intel Core i3 / i5 / i7 / i9 (7000-series, e.g., `i5-7200U`, `i7-7700`)
    * Intel HD Graphics 620 / 630 or newer
    * Intel UHD Graphics (600, 620, 630, 730, 770, etc.)
    * Intel Iris Plus Graphics (G4 / G7)
    * Intel Iris Xe Graphics (11th, 12th, 13th, 14th Gen)
    * Intel Core Ultra (Series 1 & 2 / Arc Graphics)
* **AMD (AMD VCN / Radeon Vega & RDNA)**
  * **Supported:** AMD Ryzen 2000 APU series (2018) or newer
  * **Starting Models:**
    * AMD Ryzen 3 / 5 / 7 (2000G / 2000U series and newer, e.g., `Ryzen 5 2500U`, `Ryzen 5 3500U`, `Ryzen 5 5500U`, `Ryzen 7 7730U`)
    * AMD Radeon Vega 3 / 6 / 8 / 10 / 11
    * AMD Radeon 600M / 700M / 800M series (RDNA2 / RDNA3)

---

### 2. Dedicated Graphics Cards (dGPU) — Desktops & Gaming Laptops

* **NVIDIA (NVENC Hardware Encoder)**
  * **Supported:** GeForce 900 / 10-series or newer
  * **Starting Models:**
    * GeForce GTX 950 / GTX 960 (desktop GM206)
    * GeForce GTX 10-series (`GTX 1050`, `1050 Ti`, `1060`, `1070`, `1080`)
    * GeForce GTX 16-series (`GTX 1650`, `1660`, `1660 Ti`, `1660 Super`)
    * GeForce RTX 20-series (`RTX 2060`, `2070`, `2080`)
    * GeForce RTX 30-series (`RTX 3050`, `3060`, `3070`, `3080`, `3090`)
    * GeForce RTX 40-series (`RTX 4050`, `4060`, `4070`, `4080`, `4090`)
    * NVIDIA Quadro / RTX Workstation (`P1000+`, `T600+`, `A2000+`)
* **AMD Radeon (AMF Hardware Encoder)**
  * **Supported:** Radeon RX 400 series or newer
  * **Starting Models:**
    * Radeon RX 460 / 470 / 480
    * Radeon RX 550 / 560 / 570 / 580 / 590
    * Radeon Vega series (`Vega 56`, `Vega 64`, `Radeon VII`)
    * Radeon RX 5000 series (`RX 5500 XT`, `5600 XT`, `5700 XT`)
    * Radeon RX 6000 series (`RX 6600`, `6700 XT`, `6800 XT`, etc. — *note: RX 6500 XT lacks hardware encoder*)
    * Radeon RX 7000 series (`RX 7600`, `7700 XT`, `7800 XT`, `7900 XT`)
* **Intel Arc (Dedicated)**
  * **Supported:** All Intel Arc discrete GPUs (`Arc A310`, `A380`, `A580`, `A750`, `A770`)

---

### 3. Unsupported Hardware
* **Intel 5th Gen (2014) or older** (e.g., `i5-4200U`, `i7-4790K` — only support older H.264, no H.265 hardware encoder).
* **NVIDIA GTX 970 / GTX 980** (first-gen Maxwell GM204 lacks HEVC encoder).
* **AMD pre-Polaris** (e.g., `R9 200/300` series, `HD 7000/8000` series).
* **Older Celeron / Pentium N-series** (where Intel physically omitted QuickSync silicon).

---

### 4. Operating System & Network Requirements
* **Windows Host:** Windows 10 or Windows 11 (64-bit).
* **Android Client:** Android 8.0 (Oreo) or higher with hardware HEVC decoding.
* **Network:** Wi-Fi (5GHz recommended for lowest jitter) or Tailscale for remote internet play.
* **Permissions:** Allow `handesk_ui.exe` through Windows Defender Firewall when prompted.
