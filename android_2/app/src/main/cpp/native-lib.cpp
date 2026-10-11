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
#include <map>
#include <chrono>
#include <mutex>
#include <poll.h>
#include <fcntl.h>
#include <queue>
#include <condition_variable>
#include "ikcp.h"

#define LOG_TAG "NativeClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* g_jvm = nullptr;
jobject g_obj = nullptr;
jmethodID g_onAudioData_method = nullptr;
jmethodID g_onStatusUpdate_method = nullptr;
jmethodID g_onResolutionUpdate_method = nullptr;

std::atomic<bool> is_running(false);
std::thread network_thread;
int udp_socket = -1;
struct sockaddr_in target_addr_global;

ikcpcb* g_kcp = nullptr;
std::mutex send_mutex;

int udp_output(const char *buf, int len, ikcpcb *kcp, void *user) {
    if (udp_socket >= 0) {
        sendto(udp_socket, buf, len, 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
    }
    return 0;
}

// Rate-limited keyframe request: at most one every 300ms, with redundant transmission for WAN packet loss
static void send_idr_request() {
    static std::chrono::steady_clock::time_point last_req;
    auto now = std::chrono::steady_clock::now();
    if (now - last_req < std::chrono::milliseconds(300)) return;
    last_req = now;
    if (!g_kcp) return;
    uint8_t req_packet[] = {'D', 'L', 'P', '1', 0x0A};
    uint32_t len = sizeof(req_packet);
    {
        std::lock_guard<std::mutex> lock(send_mutex);
        ikcp_send(g_kcp, (const char*)&len, 4);
        ikcp_send(g_kcp, (const char*)req_packet, len);
    }
}

AMediaCodec* decoder = nullptr;
ANativeWindow* window = nullptr;
bool has_received_idr = false;

struct FrameBuffer {
    uint16_t total_chunks;
    std::map<uint16_t, std::vector<uint8_t>> chunks;
    long long timestamp_ms;
};

#include <mutex>
#include <atomic>

std::mutex decoder_mutex;
std::atomic<bool> has_surface{false};

long long current_time_ms() {
    struct timespec res;
    clock_gettime(CLOCK_MONOTONIC, &res);
    return (long long)res.tv_sec * 1000 + res.tv_nsec / 1000000;
}

std::atomic<bool> needs_recovery{false};

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
        // Only flush if we are recovering from a dropped frame
        if (is_keyframe && needs_recovery) {
            AMediaCodec_flush(decoder);
            needs_recovery = false;
        }
        has_received_idr = true;

        // Wait up to 10ms for decoder space.
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
            // Decoder queue full or busy! If input buffer couldn't be queued, mark recovery needed
            needs_recovery = true;
            send_idr_request();
        }
    }

    AMediaCodecBufferInfo info;
    ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    ssize_t last_out_idx = -1;
    
    // Drain all available frames from the decoder
    while (out_idx >= 0) {
        if (last_out_idx >= 0) {
            // We found a newer frame! Drop the older one without rendering to catch up to real-time.
            AMediaCodec_releaseOutputBuffer(decoder, last_out_idx, false);
        }
        last_out_idx = out_idx;
        out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    }
    
    // Render ONLY the absolute latest frame we pulled out
    if (last_out_idx >= 0) {
        AMediaCodec_releaseOutputBuffer(decoder, last_out_idx, has_surface.load());
    }
}

std::mutex queue_mutex;
std::condition_variable queue_cv;
std::queue<std::vector<uint8_t>> decode_queue;
std::thread decode_thread;

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

    // Send HELLO packet over UDP directly
    uint8_t hello_packet[] = {'D', 'L', 'P', '1', 0x01};
    sendto(udp_socket, hello_packet, sizeof(hello_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
    
    // Init KCP
    g_kcp = ikcp_create(0x11223344, nullptr);
    g_kcp->output = udp_output;
    g_kcp->stream = 1; // ENABLE STREAM MODE
    ikcp_nodelay(g_kcp, 1, 5, 2, 1); // 5ms nodelay interval for ultra-low latency
    ikcp_wndsize(g_kcp, 2048, 2048);

    send_idr_request();

    
    std::vector<uint8_t> buf(65536);
    std::vector<char> recv_buf(512 * 1024);
    std::vector<uint8_t> stream_buffer;
    uint32_t rx_bytes_accum = 0;
    uint32_t rx_bytes_sec = 0;
    auto last_rate_calc = std::chrono::steady_clock::now();

    while (is_running) {
        auto now_ms = current_time_ms();
        {
            std::lock_guard<std::mutex> lock(send_mutex);
            ikcp_update(g_kcp, now_ms);
        }

        struct pollfd fd;
        fd.fd = udp_socket;
        fd.events = POLLIN;
        int ret = poll(&fd, 1, 2); // 2ms sleep/wait for instant wake-up
        
        if (ret > 0 && (fd.revents & POLLIN)) {
            struct sockaddr_in src_addr;
            socklen_t addr_len = sizeof(src_addr);
            ssize_t len = recvfrom(udp_socket, buf.data(), buf.size(), 0, (struct sockaddr*)&src_addr, &addr_len);
            if (len > 0) {
                std::lock_guard<std::mutex> lock(send_mutex);
                ikcp_input(g_kcp, (const char*)buf.data(), len);
            }
        }

        // Check for KCP stream data
        while (true) {
            int len = 0;
            {
                std::lock_guard<std::mutex> lock(send_mutex);
                len = ikcp_recv(g_kcp, recv_buf.data(), recv_buf.size());
            }
            if (len <= 0) break;
            
            rx_bytes_accum += len;
            stream_buffer.insert(stream_buffer.end(), recv_buf.data(), recv_buf.data() + len);
        }

        auto now_clock = std::chrono::steady_clock::now();
        if (now_clock - last_rate_calc >= std::chrono::seconds(1)) {
            rx_bytes_sec = rx_bytes_accum;
            rx_bytes_accum = 0;
            last_rate_calc = now_clock;

            size_t q_size = 0;
            {
                std::lock_guard<std::mutex> lock(queue_mutex);
                q_size = decode_queue.size();
            }
            int wait_snd = 0;
            {
                std::lock_guard<std::mutex> lock(send_mutex);
                if (g_kcp) wait_snd = ikcp_waitsnd(g_kcp);
            }
            LOGI("[CLIENT TELEMETRY] RxRate: %.2f Mbps | DecodeQueue: %zu | KCP wait_snd: %d pkts", 
                 (rx_bytes_sec * 8.0f) / 1000000.0f, q_size, wait_snd);
        }

        while (stream_buffer.size() >= 4) {
            uint32_t packet_len = 0;
            memcpy(&packet_len, stream_buffer.data(), 4);
            
            // Assuming little endian. Since both Windows and Android are little endian, it's fine.
            if (stream_buffer.size() >= 4 + packet_len) {
                uint8_t* payload = stream_buffer.data() + 4;
                if (packet_len >= 5 && payload[0] == 'D' && payload[1] == 'L' && payload[2] == 'P' && payload[3] == '1') {
                    uint8_t type = payload[4];
                    if (type == 0x06) {
                        std::vector<uint8_t> nalu(payload + 5, payload + packet_len);
                        if (!nalu.empty()) {
                            std::lock_guard<std::mutex> lock(queue_mutex);
                            // Drop queued frames if backlogged to prevent latency buildup on network jitter (12 frames = ~200ms at 60fps)
                            if (decode_queue.size() > 12) {
                                while (!decode_queue.empty()) decode_queue.pop();
                                needs_recovery = true;
                                send_idr_request();
                            }
                            decode_queue.push(std::move(nalu));
                            queue_cv.notify_one();
                        }
                    } else if (type == 0x08) {
                        if (attached && g_obj && g_onAudioData_method) {
                            jsize pcm_len = packet_len - 5;
                            jbyteArray j_pcm = env->NewByteArray(pcm_len);
                            env->SetByteArrayRegion(j_pcm, 0, pcm_len, (jbyte*)(payload + 5));
                            env->CallVoidMethod(g_obj, g_onAudioData_method, j_pcm);
                            env->DeleteLocalRef(j_pcm);
                        }
                    } else if (type == 0x0B && packet_len >= 13) {
                        // PING received: echo back PONG immediately with timestamp and measured throughput
                        uint8_t pong_packet[17];
                        memcpy(pong_packet, "DLP1\x0C", 5);
                        memcpy(pong_packet + 5, payload + 5, 8);
                        uint32_t throughput = rx_bytes_sec;
                        memcpy(pong_packet + 13, &throughput, 4);
                        uint32_t resp_len = sizeof(pong_packet);
                        {
                            std::lock_guard<std::mutex> lock(send_mutex);
                            if (g_kcp) {
                                ikcp_send(g_kcp, (const char*)&resp_len, 4);
                                ikcp_send(g_kcp, (const char*)pong_packet, resp_len);
                            }
                        }
                    } else if (type == 0x0D && packet_len >= 13) {
                        uint32_t w = 0, h = 0;
                        memcpy(&w, payload + 5, 4);
                        memcpy(&h, payload + 9, 4);
                        LOGI("Windows host native desktop resolution: %ux%u", w, h);
                        if (attached && g_obj && g_onResolutionUpdate_method) {
                            env->CallVoidMethod(g_obj, g_onResolutionUpdate_method, (jint)w, (jint)h);
                        }
                    }
                }
                stream_buffer.erase(stream_buffer.begin(), stream_buffer.begin() + 4 + packet_len);
            } else {
                break; // Need more data
            }
        }
    }

    if (g_kcp) {
        ikcp_release(g_kcp);
        g_kcp = nullptr;
    }
    close(udp_socket);
    udp_socket = -1;
    LOGI("Network loop shutting down");
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
    AMediaFormat_setInt32(format, "priority", 0); // Realtime priority
    
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
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_sendInputNative(JNIEnv* env, jobject thiz, jbyteArray packet) {
    if (!g_kcp || !is_running) return;
    jsize len = env->GetArrayLength(packet);
    if (len <= 0) return;
    jbyte* buffer_ptr = env->GetByteArrayElements(packet, nullptr);
    if (buffer_ptr) {
        std::lock_guard<std::mutex> lock(send_mutex);
        uint32_t pkt_len = len;
        ikcp_send(g_kcp, (const char*)&pkt_len, 4);
        ikcp_send(g_kcp, (const char*)buffer_ptr, pkt_len);
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
