import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                    Ok(Message::Binary(bin)) => {
                        crate::input::handle_input_payload(&bin);
                    }"""

replacement = """                    Ok(Message::Binary(bin)) => {
                        // Strip UDP packet header (DLP1 + 0x07) if present
                        if bin.len() >= 5 && &bin[0..4] == b"DLP1" && bin[4] == 0x07 {
                            crate::input::handle_input_payload(&bin[5..]);
                        } else {
                            crate::input::handle_input_payload(&bin);
                        }
                    }"""

content = content.replace(target, replacement)
with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Input handler fixed to strip UDP header!")
