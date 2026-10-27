use std::collections::HashMap;
use std::env;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use windows::core::{w, PCWSTR};
use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Input::KeyboardAndMouse::{ReleaseCapture, SetCapture};
use windows::Win32::UI::WindowsAndMessaging::*;

use handesk_host::decoder::VideoDecoder;

const MAGIC: [u8; 4] = [b'D', b'L', b'P', b'1'];
const PACKET_HELLO: u8 = 0x01;
const PACKET_VIDEO: u8 = 0x06;
const PACKET_INPUT: u8 = 0x07;
const PACKET_REQUEST_IDR: u8 = 0x0A;
const PACKET_RESOLUTION: u8 = 0x0D;

struct ClientState {
    socket: UdpSocket,
    target_addr: SocketAddr,
    width: u32,
    height: u32,
    is_connected: bool,
}

static STATE: Mutex<Option<Arc<Mutex<ClientState>>>> = Mutex::new(None);
static RUNNING: AtomicBool = AtomicBool::new(true);
static MAIN_HWND: AtomicIsize = AtomicIsize::new(0);
static CURRENT_FRAME: Mutex<Option<(u32, u32, Vec<u8>)>> = Mutex::new(None);

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_MOUSEMOVE => {
            let x = (lparam.0 & 0xFFFF) as i16 as i32;
            let y = ((lparam.0 >> 16) & 0xFFFF) as i16 as i32;

            let mut rect = RECT::default();
            let _ = GetClientRect(hwnd, &mut rect);
            let w = (rect.right - rect.left).max(1);
            let h = (rect.bottom - rect.top).max(1);

            let norm_x = ((x.clamp(0, w) as f32 / w as f32) * 65535.0) as u16;
            let norm_y = ((y.clamp(0, h) as f32 / h as f32) * 65535.0) as u16;

            send_input_payload(&[
                0x01,
                (norm_x >> 8) as u8,
                (norm_x & 0xFF) as u8,
                (norm_y >> 8) as u8,
                (norm_y & 0xFF) as u8,
            ]);
            LRESULT(0)
        }
        WM_LBUTTONDOWN => {
            send_input_payload(&[0x02, 1, 1]);
            SetCapture(hwnd);
            LRESULT(0)
        }
        WM_LBUTTONUP => {
            send_input_payload(&[0x02, 1, 0]);
            let _ = ReleaseCapture();
            LRESULT(0)
        }
        WM_RBUTTONDOWN => {
            send_input_payload(&[0x02, 2, 1]);
            LRESULT(0)
        }
        WM_RBUTTONUP => {
            send_input_payload(&[0x02, 2, 0]);
            LRESULT(0)
        }
        WM_MBUTTONDOWN => {
            send_input_payload(&[0x02, 3, 1]);
            LRESULT(0)
        }
        WM_MBUTTONUP => {
            send_input_payload(&[0x02, 3, 0]);
            LRESULT(0)
        }
        WM_MOUSEWHEEL => {
            let delta = ((wparam.0 >> 16) & 0xFFFF) as i16;
            send_input_payload(&[0x03, (delta >> 8) as u8, (delta & 0xFF) as u8]);
            LRESULT(0)
        }
        WM_KEYDOWN | WM_SYSKEYDOWN => {
            let vk = (wparam.0 & 0xFF) as u8;
            send_input_payload(&[0x04, vk, 1]);
            LRESULT(0)
        }
        WM_KEYUP | WM_SYSKEYUP => {
            let vk = (wparam.0 & 0xFF) as u8;
            send_input_payload(&[0x04, vk, 0]);
            LRESULT(0)
        }
        WM_ERASEBKGND => {
            // Avoid flickering: tell Windows background is handled by WM_PAINT
            LRESULT(1)
        }
        WM_PAINT => {
            let mut ps = PAINTSTRUCT::default();
            let hdc = BeginPaint(hwnd, &mut ps);

            let mut rect = RECT::default();
            let _ = GetClientRect(hwnd, &mut rect);
            let client_w = rect.right - rect.left;
            let client_h = rect.bottom - rect.top;

            let frame = CURRENT_FRAME.lock().ok().and_then(|f| f.clone());
            if let Some((frame_w, frame_h, ref rgb_data)) = frame {
                let bmi = BITMAPINFO {
                    bmiHeader: BITMAPINFOHEADER {
                        biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                        biWidth: frame_w as i32,
                        biHeight: -(frame_h as i32), // Top-down DIB
                        biPlanes: 1,
                        biBitCount: 32,
                        biCompression: BI_RGB.0,
                        ..Default::default()
                    },
                    ..Default::default()
                };

                let _ = StretchDIBits(
                    hdc,
                    0,
                    0,
                    client_w,
                    client_h,
                    0,
                    0,
                    frame_w as i32,
                    frame_h as i32,
                    Some(rgb_data.as_ptr() as *const _),
                    &bmi,
                    DIB_RGB_COLORS,
                    SRCCOPY,
                );
            } else {
                let brush = CreateSolidBrush(COLORREF(0x00121212));
                FillRect(hdc, &rect, brush);
                let _ = DeleteObject(brush);

                SetTextColor(hdc, COLORREF(0x00E0E0E0));
                SetBkMode(hdc, TRANSPARENT);

                let status_text = w!("Connecting to Handesk Host... Waiting for video stream");
                let _ = TextOutW(hdc, 20, 20, status_text.as_wide());
            }

            let _ = EndPaint(hwnd, &ps);
            LRESULT(0)
        }
        WM_DESTROY => {
            RUNNING.store(false, Ordering::SeqCst);
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn send_input_payload(payload: &[u8]) {
    if let Ok(guard) = STATE.lock() {
        if let Some(state_arc) = guard.as_ref() {
            if let Ok(state) = state_arc.lock() {
                let mut pkt = Vec::with_capacity(5 + payload.len());
                pkt.extend_from_slice(&MAGIC);
                pkt.push(PACKET_INPUT);
                pkt.extend_from_slice(payload);
                let _ = state.socket.send_to(&pkt, state.target_addr);
            }
        }
    }
}

fn send_idr_request(socket: &UdpSocket, target: SocketAddr) {
    let mut pkt = Vec::with_capacity(5);
    pkt.extend_from_slice(&MAGIC);
    pkt.push(PACKET_REQUEST_IDR);
    let _ = socket.send_to(&pkt, target);
}

fn send_hello(socket: &UdpSocket, target: SocketAddr) {
    let mut pkt = Vec::with_capacity(5);
    pkt.extend_from_slice(&MAGIC);
    pkt.push(PACKET_HELLO);
    let _ = socket.send_to(&pkt, target);
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let target_str = if args.len() > 1 {
        args[1].trim().to_string()
    } else {
        println!("Handesk Client: Please specify target IP (e.g. handesk-client.exe 100.106.3.49)");
        return;
    };

    let target_addr: SocketAddr = if target_str.contains(':') {
        target_str.parse().unwrap_or_else(|_| "127.0.0.1:21118".parse().unwrap())
    } else {
        format!("{}:21118", target_str)
            .parse()
            .unwrap_or_else(|_| "127.0.0.1:21118".parse().unwrap())
    };

    println!("Handesk Client connecting to: {}", target_addr);

    let socket = UdpSocket::bind("0.0.0.0:0").expect("Failed to bind UDP client socket");
    let _ = socket.set_read_timeout(Some(Duration::from_millis(500)));

    let state = Arc::new(Mutex::new(ClientState {
        socket: socket.try_clone().expect("Failed to clone socket"),
        target_addr,
        width: 1280,
        height: 720,
        is_connected: false,
    }));

    {
        let mut guard = STATE.lock().unwrap();
        *guard = Some(state.clone());
    }

    // Blast initial HELLO and IDR
    for _ in 0..4 {
        send_hello(&socket, target_addr);
        send_idr_request(&socket, target_addr);
        thread::sleep(Duration::from_millis(50));
    }

    // Background network and decoder listener
    let state_net = state.clone();
    let sock_net = socket.try_clone().unwrap();
    thread::spawn(move || {
        let mut buf = vec![0u8; 65536];
        let mut frame_assembler: HashMap<u32, (u16, HashMap<u16, Vec<u8>>, Instant)> =
            HashMap::new();
        let mut decoder: Option<VideoDecoder> = None;
        let mut cur_width = 1280u32;
        let mut cur_height = 720u32;

        while RUNNING.load(Ordering::Relaxed) {
            match sock_net.recv_from(&mut buf) {
                Ok((len, src)) => {
                    if src != target_addr || len < 5 {
                        continue;
                    }
                    if &buf[0..4] != &MAGIC {
                        continue;
                    }

                    let ptype = buf[4];
                    match ptype {
                        PACKET_RESOLUTION => {
                            if len >= 13 {
                                let w = u32::from_le_bytes(buf[5..9].try_into().unwrap());
                                let h = u32::from_le_bytes(buf[9..13].try_into().unwrap());
                                println!("Handesk Client: Target display resolution: {}x{}", w, h);
                                cur_width = w;
                                cur_height = h;
                                if let Ok(mut s) = state_net.lock() {
                                    s.width = w;
                                    s.height = h;
                                    s.is_connected = true;
                                }
                                // Initialize or reset decoder for resolution
                                decoder = VideoDecoder::new(w, h, true)
                                    .or_else(|_| VideoDecoder::new(w, h, false))
                                    .ok();
                            }
                        }
                        PACKET_VIDEO => {
                            if len >= 15 {
                                let seq = u32::from_le_bytes(buf[5..9].try_into().unwrap());
                                let chunk_idx = u16::from_le_bytes(buf[9..11].try_into().unwrap());
                                let total_chunks =
                                    u16::from_le_bytes(buf[11..13].try_into().unwrap());
                                let chunk_len =
                                    u16::from_le_bytes(buf[13..15].try_into().unwrap()) as usize;

                                if len >= 15 + chunk_len {
                                    let chunk_data = buf[15..15 + chunk_len].to_vec();
                                    let entry = frame_assembler
                                        .entry(seq)
                                        .or_insert_with(|| (total_chunks, HashMap::new(), Instant::now()));
                                    entry.1.insert(chunk_idx, chunk_data);

                                    if entry.1.len() == entry.0 as usize {
                                        // Complete frame received! Assemble in order:
                                        let mut full_nalu = Vec::new();
                                        for i in 0..total_chunks {
                                            if let Some(c) = entry.1.get(&i) {
                                                full_nalu.extend_from_slice(c);
                                            }
                                        }
                                        frame_assembler.remove(&seq);

                                        if decoder.is_none() {
                                            decoder = VideoDecoder::new(cur_width, cur_height, true)
                                                .or_else(|_| VideoDecoder::new(cur_width, cur_height, false))
                                                .ok();
                                        }

                                        if let Some(dec) = decoder.as_mut() {
                                            if let Ok(Some(rgb)) = dec.decode_frame(&full_nalu) {
                                                if let Ok(mut frame_lock) = CURRENT_FRAME.lock() {
                                                    *frame_lock = Some((cur_width, cur_height, rgb));
                                                }
                                                let hwnd_val = MAIN_HWND.load(Ordering::SeqCst);
                                                if hwnd_val != 0 {
                                                    unsafe {
                                                        let _ = InvalidateRect(HWND(hwnd_val as *mut _), None, false);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        _ => {}
                    }
                }
                Err(_) => {
                    // Periodic IDR request if idle
                    send_idr_request(&sock_net, target_addr);
                }
            }

            // Cleanup stale frames (> 300ms)
            let now = Instant::now();
            frame_assembler.retain(|_, (_, _, t)| now.duration_since(*t).as_millis() < 300);
        }
    });

    // Create Win32 Client Window
    unsafe {
        let instance = GetModuleHandleW(PCWSTR::null()).unwrap();
        let class_name = w!("HandeskClientWindow");

        let wnd_class = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: CS_HREDRAW | CS_VREDRAW,
            lpfnWndProc: Some(window_proc),
            hInstance: instance.into(),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap(),
            lpszClassName: class_name,
            hbrBackground: HBRUSH(GetStockObject(BLACK_BRUSH).0),
            ..Default::default()
        };

        RegisterClassExW(&wnd_class);

        let title: Vec<u16> = format!("Handesk - {}\0", target_str)
            .encode_utf16()
            .collect();

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            class_name,
            PCWSTR(title.as_ptr()),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            1280,
            720,
            HWND::default(),
            HMENU::default(),
            instance,
            None,
        ).expect("Failed to create client window");

        MAIN_HWND.store(hwnd.0 as isize, Ordering::SeqCst);

        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = UpdateWindow(hwnd);

        let mut msg = MSG::default();
        while GetMessageW(&mut msg, HWND::default(), 0, 0).into() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}
