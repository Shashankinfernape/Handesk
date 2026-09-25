use windows::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_MOUSE, INPUT_KEYBOARD, MOUSEINPUT, KEYBDINPUT,
    MOUSEEVENTF_MOVE, MOUSEEVENTF_ABSOLUTE,
    MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
    MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP,
    MOUSEEVENTF_WHEEL, KEYEVENTF_KEYUP,
};
use std::mem::size_of;

pub fn handle_input_payload(payload: &[u8]) {
    if payload.is_empty() { return; }
    let sub_type = payload[0];
    match sub_type {
        0x01 => { // MouseMove — normalized coords 0–65535
            if payload.len() >= 5 {
                let norm_x = u16::from_be_bytes([payload[1], payload[2]]) as i32;
                let norm_y = u16::from_be_bytes([payload[3], payload[4]]) as i32;
                // MOUSEEVENTF_ABSOLUTE expects 0–65535 — we send it directly!
                inject_mouse_move(norm_x, norm_y);
            }
        }
        0x02 => { // MouseButton
            if payload.len() >= 3 {
                let button = payload[1];
                let down = payload[2] == 1;
                inject_mouse_button(button, down);
            }
        }
        0x03 => { // Scroll
            if payload.len() >= 3 {
                let delta = i16::from_be_bytes([payload[1], payload[2]]) as i32;
                inject_scroll(delta);
            }
        }
        0x04 => { // KeyEvent
            if payload.len() >= 3 {
                let vk_code = payload[1] as u16;
                let down = payload[2] == 1;
                inject_key(vk_code, down);
            }
        }
        _ => {}
    }
}

fn inject_mouse_move(norm_x: i32, norm_y: i32) {
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx: norm_x,
        dy: norm_y,
        mouseData: 0,
        dwFlags: MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE,
        time: 0,
        dwExtraInfo: 0,
    };
    unsafe {
        let _ = SendInput(&[input], size_of::<INPUT>() as i32);
    }
}

fn inject_mouse_button(button: u8, down: bool) {
    let flags = match (button, down) {
        (1, true)  => MOUSEEVENTF_LEFTDOWN,
        (1, false) => MOUSEEVENTF_LEFTUP,
        (2, true)  => MOUSEEVENTF_RIGHTDOWN,
        (2, false) => MOUSEEVENTF_RIGHTUP,
        (3, true)  => MOUSEEVENTF_MIDDLEDOWN,
        (3, false) => MOUSEEVENTF_MIDDLEUP,
        _ => return,
    };
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx: 0, dy: 0, mouseData: 0,
        dwFlags: flags, time: 0, dwExtraInfo: 0,
    };
    unsafe {
        let _ = SendInput(&[input], size_of::<INPUT>() as i32);
    }
}

fn inject_scroll(delta: i32) {
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx: 0, dy: 0,
        mouseData: delta as u32,
        dwFlags: MOUSEEVENTF_WHEEL,
        time: 0, dwExtraInfo: 0,
    };
    unsafe {
        let _ = SendInput(&[input], size_of::<INPUT>() as i32);
    }
}

fn inject_key(vk_code: u16, down: bool) {
    let mut input = INPUT::default();
    input.r#type = INPUT_KEYBOARD;
    input.Anonymous.ki = KEYBDINPUT {
        wVk: windows::Win32::UI::Input::KeyboardAndMouse::VIRTUAL_KEY(vk_code),
        wScan: 0,
        dwFlags: if down { windows::Win32::UI::Input::KeyboardAndMouse::KEYBD_EVENT_FLAGS(0) } else { KEYEVENTF_KEYUP },
        time: 0,
        dwExtraInfo: 0,
    };
    unsafe {
        let _ = SendInput(&[input], size_of::<INPUT>() as i32);
    }
}
