import os

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''    // 1. Create D3D11 Device
    let mut device: Option<ID3D11Device> = None;
    let mut context: Option<ID3D11DeviceContext> = None;
    unsafe {
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            None,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            Some(&[D3D_FEATURE_LEVEL_11_0]),
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            Some(&mut context),
        ).context("Failed to create D3D11 device")?;
    }

    let d3d_device = device.unwrap();
    let d3d_context = context.unwrap();

    // 2. Get DXGI structures
    let dxgi_device: IDXGIDevice = d3d_device.cast()?;
    let adapter: IDXGIAdapter = unsafe { dxgi_device.GetAdapter()? };
    
    // Get primary output (monitor 0)
    let output: IDXGIOutput = unsafe { adapter.EnumOutputs(0)? };
    let output1: IDXGIOutput1 = output.cast()?;'''

replacement = '''    // Get DXGI Factory to enumerate adapters correctly for laptops
    let factory: windows::Win32::Graphics::Dxgi::IDXGIFactory1 = unsafe { windows::Win32::Graphics::Dxgi::CreateDXGIFactory1().context("Failed to create DXGI factory")? };
    
    let mut target_adapter: Option<windows::Win32::Graphics::Dxgi::IDXGIAdapter> = None;
    let mut target_output: Option<windows::Win32::Graphics::Dxgi::IDXGIOutput1> = None;
    
    for i in 0..10 {
        if let Ok(adapter) = unsafe { factory.EnumAdapters(i) } {
            if let Ok(output) = unsafe { adapter.EnumOutputs(0) } {
                if let Ok(out1) = output.cast::<windows::Win32::Graphics::Dxgi::IDXGIOutput1>() {
                    target_adapter = Some(adapter);
                    target_output = Some(out1);
                    break;
                }
            }
        }
    }
    
    if target_adapter.is_none() || target_output.is_none() {
        return Err(anyhow::anyhow!("No active displays found on any GPU adapter!"));
    }
    
    let adapter = target_adapter.unwrap();
    let output1 = target_output.unwrap();

    // 1. Create D3D11 Device on the EXACT adapter that has the monitor!
    let mut device: Option<ID3D11Device> = None;
    let mut context: Option<ID3D11DeviceContext> = None;
    unsafe {
        D3D11CreateDevice(
            &adapter,
            windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN, // MUST be UNKNOWN when passing adapter
            None,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            Some(&[D3D_FEATURE_LEVEL_11_0]),
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            Some(&mut context),
        ).context("Failed to create D3D11 device on adapter")?;
    }

    let d3d_device = device.unwrap();
    let d3d_context = context.unwrap();
    let dxgi_device: IDXGIDevice = d3d_device.cast()?;'''

content = content.replace(target, replacement)

# Fix test pattern generator to produce valid NV12!
target_pattern = '''        let mut bgra_buffer = vec![255u8; (width * height * 4) as usize];
        
        let mut frame_count = 0;
        loop {
            let color = (frame_count % 255) as u8;
            for i in (0..bgra_buffer.len()).step_by(4) {
                bgra_buffer[i] = color;
                bgra_buffer[i+1] = color;
                bgra_buffer[i+2] = color;
                bgra_buffer[i+3] = 255;
            }
            
            match encoder.encode_frame(&bgra_buffer) {'''

replacement_pattern = '''        let mut nv12_buffer = vec![128u8; (width * height + (width * height / 2)) as usize];
        
        let mut frame_count: u32 = 0;
        loop {
            let y_color = (frame_count % 255) as u8;
            for i in 0..(width * height) as usize {
                nv12_buffer[i] = y_color; // Grayscale pulse
            }
            // UV plane remains 128 (neutral color)
            
            match encoder.encode_frame(&nv12_buffer) {'''

content = content.replace(target_pattern, replacement_pattern)

with open(cap_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed!")
