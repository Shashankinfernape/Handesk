$file = "windows\src\capture.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)bgra_buffer\[dst_start\.\.dst_start\+row_width\]\.copy_from_slice\(&src_slice\[src_start\.\.src_start\+row_width\]\);\s*\}\s*d3d_context\.Unmap\(&staging_texture, 0\);'
$replacement = 'bgra_buffer[dst_start..dst_start+row_width].copy_from_slice(&src_slice[src_start..src_start+row_width]);
                        }
                        d3d_context.Unmap(&staging_texture, 0);
                        
                        // DEBUG: Draw a 100x100 RED square in the top left corner of the buffer
                        for y in 0..100 {
                            for x in 0..100 {
                                let idx = (y * (width as usize) + x) * 4;
                                bgra_buffer[idx] = 0;       // B
                                bgra_buffer[idx+1] = 0;     // G
                                bgra_buffer[idx+2] = 255;   // R
                                bgra_buffer[idx+3] = 255;   // A
                            }
                        }'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
