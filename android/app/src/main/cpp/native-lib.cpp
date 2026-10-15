#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <thread>
#include <atomic>
#include <vector>
#include <chrono>
#include <mutex>
#include <poll.h>
#include <fcntl.h>
#include <queue>
#include <condition_variable>

#define LOG_TAG "HandeskNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* g_jvm = nullptr;
jobject g_obj = nullptr;
jmethodID g_onAudioData_method = nullptr;
jmethodID g_onStatusUpdate_method = nullptr;
jmethodID g_onResolutionUpdate_method = nullptr;
jmethodID g_onTextFocusUpdate_method = nullptr;

static int32_t g_last_notified_w = 0;
static int32_t g_last_notified_h = 0;

static void notify_resolution_change(int32_t w, int32_t h) {
    if (w <= 0 || h <= 0 || !g_jvm || !g_obj || !g_onResolutionUpdate_method) return;
    if (w == g_last_notified_w && h == g_last_notified_h) return;
    g_last_notified_w = w;
    g_last_notified_h = h;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnvStat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) {
            attached = true;
        }
    }
    if (env) {
        LOGI("Notifying Java of desktop resolution: %dx%d (Ratio: %.4f)", w, h, (float)w / (float)h);
        env->CallVoidMethod(g_obj, g_onResolutionUpdate_method, (jint)w, (jint)h);
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

std::atomic<bool> is_running(false);
std::thread network_thread;
int udp_socket = -1;
struct sockaddr_in target_addr_global;
std::mutex send_mutex;

std::atomic<bool> g_is_tailscale{false};

// Rate-limited keyframe request: 250ms on Tailscale WAN to avoid congestion collapse, 100ms on LAN
static void send_idr_request() {
    static std::chrono::steady_clock::time_point last_req;
    auto now = std::chrono::steady_clock::now();
    int min_interval = g_is_tailscale.load() ? 250 : 100;
    if (now - last_req < std::chrono::milliseconds(min_interval)) return;
    last_req = now;
    if (udp_socket < 0) return;

    uint8_t req_packet[] = {'D', 'L', 'P', '1', 0x0A};
    std::lock_guard<std::mutex> lock(send_mutex);
    sendto(udp_socket, req_packet, sizeof(req_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
}

AMediaCodec* decoder = nullptr;
ANativeWindow* window = nullptr;
bool has_received_idr = false;
std::atomic<bool> needs_recovery{false};

std::mutex queue_mutex;
std::condition_variable queue_cv;
std::queue<std::vector<uint8_t>> decode_queue;
std::thread decode_thread;

struct FrameSlot {
    uint32_t seq = 0;
    uint16_t total_chunks = 0;
    uint16_t received_chunks = 0;
    size_t total_bytes = 0;
    std::vector<std::vector<uint8_t>> chunks;
    bool in_use = false;
};

struct MultiFrameReassembler {
    static const int NUM_SLOTS = 16;
    FrameSlot slots[NUM_SLOTS];
    uint32_t highest_completed_seq = 0;

    void on_chunk(uint32_t seq, uint16_t chunk_idx, uint16_t total_chunks, uint16_t chunk_len, const uint8_t* payload) {
        if (seq <= highest_completed_seq && highest_completed_seq > 0) {
            return;
        }

        int idx = seq % NUM_SLOTS;
        FrameSlot& slot = slots[idx];

        if (!slot.in_use || slot.seq != seq) {
            if (slot.in_use && slot.received_chunks < slot.total_chunks) {
                // Incomplete frame was overwritten: a reference frame was dropped!
                // Signal immediate recovery so subsequent P-frames aren't decoded into ghosted artifacts!
                needs_recovery.store(true);
                send_idr_request();
            }
            slot.seq = seq;
            slot.total_chunks = total_chunks;
            slot.received_chunks = 0;
            slot.total_bytes = 0;
            slot.chunks.assign(total_chunks, std::vector<uint8_t>());
            slot.in_use = true;
        }

        if (chunk_idx < slot.total_chunks && slot.chunks[chunk_idx].empty()) {
            slot.chunks[chunk_idx].assign(payload, payload + chunk_len);
            slot.received_chunks++;
            slot.total_bytes += chunk_len;

            if (slot.received_chunks == slot.total_chunks) {
                // Check if an intermediate frame was dropped between highest_completed_seq and seq
                if (highest_completed_seq > 0 && seq > highest_completed_seq + 1) {
                    // Frame gap detected: intermediate reference frame missing!
                    needs_recovery.store(true);
                    send_idr_request();
                }

                std::vector<uint8_t> complete_frame;
                complete_frame.reserve(slot.total_bytes);
                for (const auto& c : slot.chunks) {
                    complete_frame.insert(complete_frame.end(), c.begin(), c.end());
                }

                highest_completed_seq = seq;
                slot.in_use = false;

                std::lock_guard<std::mutex> lock(queue_mutex);
                if (decode_queue.size() > 30) {
                    while (decode_queue.size() > 5) decode_queue.pop();
                }
                decode_queue.push(std::move(complete_frame));
                queue_cv.notify_one();
            }
        }
    }
};

std::mutex decoder_mutex;
std::atomic<bool> has_surface{false};

long long current_time_ms() {
    struct timespec res;
    clock_gettime(CLOCK_MONOTONIC, &res);
    return (long long)res.tv_sec * 1000 + res.tv_nsec / 1000000;
}

void decode_nalu(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (!decoder) return;

    // Detect Keyframe (VPS 32, SPS 33, PPS 34, IDR 19/20, CRA 21)
    bool is_keyframe = false;
    for (size_t i = 0; i < data.size() - 4; ++i) {
        bool is_4byte = (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1);
        bool is_3byte = (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1);
        
        if (is_4byte && i + 4 < data.size()) {
            int nalu_type = (data[i + 4] & 0x7E) >> 1;
            if (nalu_type == 19 || nalu_type == 20 || nalu_type == 21 || nalu_type == 32) is_keyframe = true;
            i += 3;
        } else if (is_3byte && i + 3 < data.size()) {
            int nalu_type = (data[i + 3] & 0x7E) >> 1;
            if (nalu_type == 19 || nalu_type == 20 || nalu_type == 21 || nalu_type == 32) is_keyframe = true;
            i += 2;
        }
    }

    bool skip_input = false;
    if (!has_received_idr && !is_keyframe) {
        send_idr_request();
        skip_input = true;
    } else if (needs_recovery && !is_keyframe) {
        send_idr_request();
        skip_input = true;
    }

    if (!skip_input) {
        if (is_keyframe && needs_recovery) {
            AMediaCodec_flush(decoder);
            needs_recovery = false;
        }
        has_received_idr = true;

        ssize_t in_idx = AMediaCodec_dequeueInputBuffer(decoder, 10000);
        if (in_idx >= 0) {
            size_t buf_size = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(decoder, in_idx, &buf_size);
            if (buf && buf_size >= data.size()) {
                memcpy(buf, data.data(), data.size());
                AMediaCodec_queueInputBuffer(decoder, in_idx, 0, data.size(), current_time_ms() * 1000, 0);
            } else if (buf) {
                LOGE("Input buffer too small: buf_size=%zu, frame_len=%zu", buf_size, data.size());
            }
        } else {
            send_idr_request();
        }
    }

    AMediaCodecBufferInfo info;
    ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    while (out_idx >= 0 || out_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        if (out_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* out_fmt = AMediaCodec_getOutputFormat(decoder);
            if (out_fmt) {
                int32_t w = 0, h = 0;
                AMediaFormat_getInt32(out_fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
                AMediaFormat_getInt32(out_fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
                int32_t crop_l = 0, crop_r = 0, crop_t = 0, crop_b = 0;
                if (AMediaFormat_getInt32(out_fmt, "crop-left", &crop_l) &&
                    AMediaFormat_getInt32(out_fmt, "crop-right", &crop_r) &&
                    AMediaFormat_getInt32(out_fmt, "crop-top", &crop_t) &&
                    AMediaFormat_getInt32(out_fmt, "crop-bottom", &crop_b)) {
                    w = crop_r - crop_l + 1;
                    h = crop_b - crop_t + 1;
                }
                AMediaFormat_delete(out_fmt);
                if (w > 0 && h > 0) {
                    LOGI("Hardware MediaCodec stream output format changed: %dx%d (Ratio: %.4f)", w, h, (float)w / (float)h);
                    notify_resolution_change(w, h);
                }
            }
        } else if (out_idx >= 0) {
            AMediaCodec_releaseOutputBuffer(decoder, out_idx, has_surface.load());
        }
        out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    }
}

void decode_worker_loop() {
    while (is_running) {
        std::vector<uint8_t> frame;
        {
            std::unique_lock<std::mutex> lock(queue_mutex);
            queue_cv.wait(lock, [] { return !decode_queue.empty() || !is_running; });
            if (!is_running) break;
            frame = std::move(decode_queue.front());
            decode_queue.pop();
        }
        if (!frame.empty()) {
            decode_nalu(frame);
        }
    }
}

void network_loop(std::string ip) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;

    udp_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (udp_socket < 0) {
        LOGE("Failed to create UDP socket");
        if (attached) g_jvm->DetachCurrentThread();
        return;
    }

    // Set non-blocking
    int flags = fcntl(udp_socket, F_GETFL, 0);
    fcntl(udp_socket, F_SETFL, flags | O_NONBLOCK);

    // Expand OS receive buffer to 4MB for high-throughput video bursts
    int rcvbuf = 4 * 1024 * 1024;
    setsockopt(udp_socket, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    std::string host = ip;
    int port = 21118;
    size_t colon_pos = ip.find(':');
    if (colon_pos != std::string::npos) {
        host = ip.substr(0, colon_pos);
        port = std::stoi(ip.substr(colon_pos + 1));
    }

    memset(&target_addr_global, 0, sizeof(target_addr_global));
    target_addr_global.sin_family = AF_INET;
    target_addr_global.sin_port = htons(port);

    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    std::string port_str = std::to_string(port);
    if (getaddrinfo(host.c_str(), port_str.c_str(), &hints, &res) == 0 && res != nullptr) {
        memcpy(&target_addr_global, res->ai_addr, sizeof(target_addr_global));
        freeaddrinfo(res);
    } else {
        inet_pton(AF_INET, host.c_str(), &target_addr_global.sin_addr);
    }

    bool is_tailscale = (host.rfind("100.", 0) == 0);
    g_is_tailscale.store(is_tailscale);
    LOGI("[HANDESK UDP] Connecting to %s (%s) - Pure High-Performance UDP Engine",
         is_tailscale ? "TAILSCALE WAN" : "LOCAL LAN", host.c_str());

    // Initial Handshake over UDP
    uint8_t hello_packet[] = {'D', 'L', 'P', '1', 0x01};
    sendto(udp_socket, hello_packet, sizeof(hello_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
    send_idr_request();

    std::vector<uint8_t> buf(65536);
    MultiFrameReassembler reassembler;
    uint32_t rx_bytes_accum = 0;
    uint32_t rx_bytes_sec = 0;
    auto last_rate_calc = std::chrono::steady_clock::now();
    auto last_hello_send = std::chrono::steady_clock::now();
    bool connection_confirmed = false;

    while (is_running) {
        struct pollfd fd;
        fd.fd = udp_socket;
        fd.events = POLLIN;
        int poll_timeout = is_tailscale ? 2 : 1;
        int ret = poll(&fd, 1, poll_timeout);

        if (ret > 0 && (fd.revents & POLLIN)) {
            struct sockaddr_in src_addr;
            socklen_t addr_len = sizeof(src_addr);
            ssize_t len = recvfrom(udp_socket, buf.data(), buf.size(), 0, (struct sockaddr*)&src_addr, &addr_len);
            
            if (len >= 5 && buf[0] == 'D' && buf[1] == 'L' && buf[2] == 'P' && buf[3] == '1') {
                rx_bytes_accum += len;
                connection_confirmed = true;
                uint8_t type = buf[4];

                // ── 0x06: VIDEO Chunk (Multi-Frame Jitter-Immune Reassembly) ─
                if (type == 0x06 && len >= 15) {
                    uint32_t seq = 0;
                    uint16_t chunk_idx = 0;
                    uint16_t total_chunks = 0;
                    uint16_t chunk_len = 0;
                    memcpy(&seq, buf.data() + 5, 4);
                    memcpy(&chunk_idx, buf.data() + 9, 2);
                    memcpy(&total_chunks, buf.data() + 11, 2);
                    memcpy(&chunk_len, buf.data() + 13, 2);

                    if (len >= 15 + chunk_len && chunk_idx < total_chunks && total_chunks > 0) {
                        reassembler.on_chunk(seq, chunk_idx, total_chunks, chunk_len, buf.data() + 15);
                    }
                }
                // ── 0x08: AUDIO Direct Datagram ──────────────────────────────
                else if (type == 0x08 && len > 5) {
                    if (attached && g_obj && g_onAudioData_method) {
                        jsize pcm_len = len - 5;
                        jbyteArray j_pcm = env->NewByteArray(pcm_len);
                        env->SetByteArrayRegion(j_pcm, 0, pcm_len, (jbyte*)(buf.data() + 5));
                        env->CallVoidMethod(g_obj, g_onAudioData_method, j_pcm);
                        env->DeleteLocalRef(j_pcm);
                    }
                }
                // ── 0x01: HELLO Echo Confirmation ────────────────────────────
                else if (type == 0x01) {
                    LOGI("Handesk server confirmed HELLO handshake");
                    send_idr_request();
                }
                // ── 0x09: Settings / Status Packet from Host ─────────────────
                else if (type == 0x09 && len >= 13) {
                    uint32_t fps = 0;
                    uint32_t bitrate = 0;
                    memcpy(&fps, buf.data() + 5, 4);
                    memcpy(&bitrate, buf.data() + 9, 4);
                    LOGI("Handesk host status update: %u FPS, %u bps", fps, bitrate);
                    if (attached && g_obj && g_onStatusUpdate_method) {
                        env->CallVoidMethod(g_obj, g_onStatusUpdate_method, (jint)fps, (jint)bitrate);
                    }
                }
                // ── 0x0B / 0x02: PING (Echo PONG with telemetry) ────────────
                else if ((type == 0x0B || type == 0x02) && len >= 13) {
                    uint8_t pong_packet[17];
                    memcpy(pong_packet, "DLP1\x0C", 5);
                    memcpy(pong_packet + 5, buf.data() + 5, 8);
                    uint32_t throughput = rx_bytes_sec;
                    memcpy(pong_packet + 13, &throughput, 4);
                    std::lock_guard<std::mutex> lock(send_mutex);
                    sendto(udp_socket, pong_packet, sizeof(pong_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
                }
                // ── 0x0D: Desktop Resolution Handshake ───────────────────────
                else if (type == 0x0D && len >= 13) {
                    uint32_t w = 0, h = 0;
                    memcpy(&w, buf.data() + 5, 4);
                    memcpy(&h, buf.data() + 9, 4);
                    LOGI("Handesk host desktop resolution packet: %ux%u", w, h);
                    notify_resolution_change((int32_t)w, (int32_t)h);
                }
                // ── 0x0E: Text/Caret Focus Update ───────────────────────────
                else if (type == 0x0E && len >= 6) {
                    bool focused = buf[5] != 0;
                    if (attached && g_obj && g_onTextFocusUpdate_method) {
                        env->CallVoidMethod(g_obj, g_onTextFocusUpdate_method, (jboolean)focused, 0, 0, 0, 0);
                    }
                }
            }
        }

        // Periodic keepalive / handshake re-transmission until server confirms
        auto now_steady = std::chrono::steady_clock::now();
        if (!connection_confirmed && now_steady - last_hello_send >= std::chrono::milliseconds(500)) {
            last_hello_send = now_steady;
            sendto(udp_socket, hello_packet, sizeof(hello_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
        }

        // Periodic 1-second telemetry logging
        if (now_steady - last_rate_calc >= std::chrono::seconds(1)) {
            rx_bytes_sec = rx_bytes_accum;
            rx_bytes_accum = 0;
            last_rate_calc = now_steady;

            size_t q_size = 0;
            {
                std::lock_guard<std::mutex> lock(queue_mutex);
                q_size = decode_queue.size();
            }
            LOGI("[CLIENT UDP TELEMETRY] RxRate: %.2f Mbps | DecodeQueue: %zu", 
                 (rx_bytes_sec * 8.0f) / 1000000.0f, q_size);
        }
    }

    close(udp_socket);
    udp_socket = -1;
    LOGI("Handesk Network loop shutting down");
    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_connectNative(JNIEnv* env, jobject thiz, jstring ip_jstr, jobject surface) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (is_running) return;
    
    const char* ip_cstr = env->GetStringUTFChars(ip_jstr, nullptr);
    env->GetJavaVM(&g_jvm);
    if (g_obj) env->DeleteGlobalRef(g_obj);
    g_obj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_onAudioData_method = env->GetMethodID(clazz, "onAudioData", "([B)V");
    g_onStatusUpdate_method = env->GetMethodID(clazz, "onStatusUpdate", "(II)V");
    g_onResolutionUpdate_method = env->GetMethodID(clazz, "onResolutionUpdate", "(II)V");
    g_onTextFocusUpdate_method = env->GetMethodID(clazz, "onTextFocusUpdate", "(ZIIII)V");
    env->DeleteLocalRef(clazz);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(ip_jstr, ip_cstr);

    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
        has_surface = true;
    } else {
        window = nullptr;
        has_surface = false;
    }
    
    decoder = AMediaCodec_createDecoderByType("video/hevc");
    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/hevc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 1920);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 1080);
    AMediaFormat_setInt32(format, "max-input-size", 2 * 1024 * 1024);
    
    // Realtime ultra-low-latency mode
    AMediaFormat_setInt32(format, "low-latency", 1);
    AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(format, "latency", 0);
    AMediaFormat_setInt32(format, "priority", 0);
    
    AMediaCodec_configure(decoder, format, window, nullptr, 0);
    AMediaCodec_start(decoder);
    AMediaFormat_delete(format);

    has_received_idr = false;
    is_running = true;
    decode_thread = std::thread(decode_worker_loop);
    network_thread = std::thread(network_loop, ip);
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_disconnectNative(JNIEnv* env, jobject thiz) {
    is_running = false;
    queue_cv.notify_all();
    if (decode_thread.joinable()) {
        decode_thread.join();
    }
    if (network_thread.joinable()) {
        network_thread.join();
    }
    {
        std::lock_guard<std::mutex> lock(queue_mutex);
        while (!decode_queue.empty()) decode_queue.pop();
    }
    
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (decoder) {
        AMediaCodec_stop(decoder);
        AMediaCodec_delete(decoder);
        decoder = nullptr;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    has_surface = false;
    g_last_notified_w = 0;
    g_last_notified_h = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_sendInputNative(JNIEnv* env, jobject thiz, jbyteArray packet) {
    if (udp_socket < 0 || !is_running) return;
    jsize len = env->GetArrayLength(packet);
    if (len <= 0) return;
    jbyte* buffer_ptr = env->GetByteArrayElements(packet, nullptr);
    if (buffer_ptr) {
        std::lock_guard<std::mutex> lock(send_mutex);
        sendto(udp_socket, (const char*)buffer_ptr, len, 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
        env->ReleaseByteArrayElements(packet, buffer_ptr, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_updateSurfaceNative(JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (decoder && is_running) {
        if (surface == nullptr) {
            has_surface = false;
            return;
        }
        ANativeWindow* new_window = ANativeWindow_fromSurface(env, surface);
        if (new_window) {
            AMediaCodec_setOutputSurface(decoder, new_window);
            if (window) ANativeWindow_release(window);
            window = new_window;
            has_surface = true;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_sendSettingsNative(JNIEnv* env, jobject thiz, jint fps, jint bitrate, jboolean is_auto) {
    if (udp_socket < 0 || !is_running) return;
    uint8_t pkt[14];
    memcpy(pkt, "DLP1\x09", 5);
    uint32_t u_fps = (uint32_t)fps;
    uint32_t u_bitrate = (uint32_t)bitrate;
    memcpy(pkt + 5, &u_fps, 4);
    memcpy(pkt + 9, &u_bitrate, 4);
    pkt[13] = is_auto ? 1 : 0;
    std::lock_guard<std::mutex> lock(send_mutex);
    sendto(udp_socket, (const char*)pkt, sizeof(pkt), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
}

