#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <thread>
#include <atomic>
#include <vector>
#include <map>

#define LOG_TAG "NativeClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* g_jvm = nullptr;
jobject g_obj = nullptr;
jmethodID g_onAudioData_method = nullptr;

std::atomic<bool> is_running(false);
std::thread network_thread;
int udp_socket = -1;

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

void decode_nalu(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (!decoder) return;

    // Detect Keyframe (VPS 32, SPS 33, PPS 34, IDR 19/20)
    bool is_keyframe = false;
    for (size_t i = 0; i < data.size() - 4; ++i) {
        bool is_4byte = (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1);
        bool is_3byte = (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1);
        
        if (is_4byte && i + 4 < data.size()) {
            int nalu_type = (data[i + 4] & 0x7E) >> 1;
            if (nalu_type == 19 || nalu_type == 20 || nalu_type == 32) is_keyframe = true;
            i += 3;
        } else if (is_3byte && i + 3 < data.size()) {
            int nalu_type = (data[i + 3] & 0x7E) >> 1;
            if (nalu_type == 19 || nalu_type == 20 || nalu_type == 32) is_keyframe = true;
            i += 2;
        }
    }

    if (!has_received_idr && !is_keyframe) return;
    has_received_idr = true;

    ssize_t in_idx = AMediaCodec_dequeueInputBuffer(decoder, 16000);
    if (in_idx >= 0) {
        size_t buf_size = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(decoder, in_idx, &buf_size);
        if (buf && buf_size >= data.size()) {
            memcpy(buf, data.data(), data.size());
            AMediaCodec_queueInputBuffer(decoder, in_idx, 0, data.size(), current_time_ms() * 1000, 0);
        }
    }

    AMediaCodecBufferInfo info;
    ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    while (out_idx >= 0) {
        AMediaCodec_releaseOutputBuffer(decoder, out_idx, has_surface.load());
        out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
    }
}

struct sockaddr_in target_addr_global;

void network_loop(std::string ip) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    LOGI("Network loop started for %s", ip.c_str());
    
    udp_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (udp_socket < 0) return;

    memset(&target_addr_global, 0, sizeof(target_addr_global));
    target_addr_global.sin_family = AF_INET;
    target_addr_global.sin_port = htons(21118);
    inet_pton(AF_INET, ip.c_str(), &target_addr_global.sin_addr);

    // Send HELLO packet
    uint8_t hello_packet[] = {'D', 'L', 'P', '1', 0x01};
    sendto(udp_socket, hello_packet, sizeof(hello_packet), 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));

    std::map<uint32_t, FrameBuffer> frame_buffers;
    uint8_t buf[2048];

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 100000;
    setsockopt(udp_socket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    while (is_running) {
        ssize_t len = recvfrom(udp_socket, buf, sizeof(buf), 0, nullptr, nullptr);
        if (len < 15) continue;

        if (buf[0] != 'D' || buf[1] != 'L' || buf[2] != 'P' || buf[3] != '1') continue;

        if (buf[4] == 0x08) {
            uint16_t chunk_len = (uint16_t)buf[13] | ((uint16_t)buf[14] << 8);
            if (chunk_len == 0 || (size_t)(15 + chunk_len) > (size_t)len) continue;
            if (attached && g_obj && g_onAudioData_method) {
                jbyteArray j_pcm = env->NewByteArray(chunk_len);
                env->SetByteArrayRegion(j_pcm, 0, chunk_len, (jbyte*)(buf + 15));
                env->CallVoidMethod(g_obj, g_onAudioData_method, j_pcm);
                env->DeleteLocalRef(j_pcm);
            }
            continue;
        }

        if (buf[4] != 0x06) continue;

        uint32_t frame_id = (buf[8] << 24) | (buf[7] << 16) | (buf[6] << 8) | buf[5];
        uint16_t chunk_idx = (buf[10] << 8) | buf[9];
        uint16_t total_chunks = (buf[12] << 8) | buf[11];
        uint16_t chunk_len = (buf[14] << 8) | buf[13];
        
        if (15 + chunk_len > len) continue;

        std::vector<uint8_t> payload(buf + 15, buf + 15 + chunk_len);
        
        long long now = current_time_ms();
        if (frame_buffers.find(frame_id) == frame_buffers.end()) {
            // Aggressive Garbage Collection: Prevent memory leak from dropped UDP packets
            if (frame_buffers.size() > 15) {
                // Keep only the most recent 5 frames, erase the rest
                auto it = frame_buffers.begin();
                while (frame_buffers.size() > 5 && it != frame_buffers.end()) {
                    it = frame_buffers.erase(it);
                }
            }
            frame_buffers[frame_id] = {total_chunks, {}, now};
        }
        
        frame_buffers[frame_id].chunks[chunk_idx] = payload;
        
        if (frame_buffers[frame_id].chunks.size() == total_chunks) {
            size_t max_offset = 0;
            for (auto const& [idx, chunk] : frame_buffers[frame_id].chunks) {
                max_offset = std::max(max_offset, (size_t)(idx * 1024 + chunk.size()));
            }
            
            std::vector<uint8_t> full_frame(max_offset);
            for (auto const& [idx, chunk] : frame_buffers[frame_id].chunks) {
                memcpy(full_frame.data() + (idx * 1024), chunk.data(), chunk.size());
            }

            decode_nalu(full_frame);
            
            // Clean up old frames
            auto it = frame_buffers.begin();
            while (it != frame_buffers.end()) {
                if (it->first <= frame_id) it = frame_buffers.erase(it);
                else ++it;
            }
        }
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
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 1600);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 900);
    
    // Low latency mode
    AMediaFormat_setInt32(format, "low-latency", 1);
    
    AMediaCodec_configure(decoder, format, window, nullptr, 0);
    AMediaCodec_start(decoder);
    AMediaFormat_delete(format);

    has_received_idr = false;
    is_running = true;
    network_thread = std::thread(network_loop, ip);
}

extern "C" JNIEXPORT void JNICALL
Java_com_directlink_client_NativeClient_disconnectNative(JNIEnv* env, jobject thiz) {
    is_running = false;
    if (network_thread.joinable()) {
        network_thread.join();
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
    if (udp_socket < 0 || !is_running) return;
    
    jsize len = env->GetArrayLength(packet);
    if (len <= 0) return;
    
    jbyte* buffer_ptr = env->GetByteArrayElements(packet, nullptr);
    if (buffer_ptr) {
        sendto(udp_socket, buffer_ptr, len, 0, (struct sockaddr*)&target_addr_global, sizeof(target_addr_global));
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
