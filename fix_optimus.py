import os

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''    // Get DXGI Factory to enumerate adapters correctly for laptops
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
    let dxgi_device: IDXGIDevice = d3d_device.cast()?;

    // 3. Duplicate Output
    let duplication_result = unsafe {
        output1.DuplicateOutput(&dxgi_device)
    };'''

replacement = '''    // Get DXGI Factory to enumerate adapters correctly for laptops
    let factory: windows::Win32::Graphics::Dxgi::IDXGIFactory1 = unsafe { windows::Win32::Graphics::Dxgi::CreateDXGIFactory1().context("Failed to create DXGI factory")? };
    
    let mut best_device: Option<ID3D11Device> = None;
    let mut best_context: Option<ID3D11DeviceContext> = None;
    let mut best_dxgi_device: Option<IDXGIDevice> = None;
    let mut best_output: Option<windows::Win32::Graphics::Dxgi::IDXGIOutput1> = None;
    let mut best_duplication: Option<windows::Win32::Graphics::Dxgi::IDXGIOutputDuplication> = None;
    
    // Bruteforce search: DXGI Desktop Duplication on Laptops (Optimus) returns E_ACCESSDENIED 
    // if you try to capture the desktop using the dGPU instead of the iGPU.
    // We must try EVERY GPU and EVERY monitor until one successfully returns DuplicateOutput.
    'outer: for i in 0..10 {
        if let Ok(adapter) = unsafe { factory.EnumAdapters(i) } {
            for j in 0..5 {
                if let Ok(output) = unsafe { adapter.EnumOutputs(j) } {
                    if let Ok(output1) = output.cast::<windows::Win32::Graphics::Dxgi::IDXGIOutput1>() {
                        
                        let mut dev: Option<ID3D11Device> = None;
                        let mut ctx: Option<ID3D11DeviceContext> = None;
                        
                        let hr = unsafe {
                            D3D11CreateDevice(
                                &adapter,
                                windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN,
                                None,
                                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                Some(&[D3D_FEATURE_LEVEL_11_0]),
                                D3D11_SDK_VERSION,
                                Some(&mut dev),
                                None,
                                Some(&mut ctx),
                            )
                        };
                        
                        if hr.is_ok() {
                            let d3d_device = dev.unwrap();
                            let d3d_context = ctx.unwrap();
                            if let Ok(dxgi_device) = d3d_device.cast::<IDXGIDevice>() {
                                // THE ULTIMATE TEST: Does it let us duplicate?
                                if let Ok(duplication) = unsafe { output1.DuplicateOutput(&dxgi_device) } {
                                    info!("SUCCESS! Found correct GPU (Adapter {}) and Monitor ({}) for Desktop Duplication!", i, j);
                                    best_device = Some(d3d_device);
                                    best_context = Some(d3d_context);
                                    best_dxgi_device = Some(dxgi_device);
                                    best_output = Some(output1);
                                    best_duplication = Some(duplication);
                                    break 'outer;
                                } else {
                                    info!("GPU {} Monitor {} exists, but DuplicateOutput returned Access Denied/Unsupported.", i, j);
                                }
                            }
                        }
                    }
                } else {
                    break; // No more outputs on this adapter
                }
            }
        } else {
            break; // No more adapters
        }
    }
    
    // We didn't find any valid GPU/Monitor combo, we must fallback.
    let duplication_result = match best_duplication {
        Some(d) => Ok(d),
        None => Err(anyhow::anyhow!("E_ACCESSDENIED: No GPU adapter has privileges to capture the DWM Desktop.")),
    };
    
    let d3d_device = best_device.clone();
    let d3d_context = best_context.clone();
    let output1_opt = best_output.clone();
    
    // In order for the rest of the function to compile, if it fails, it will hit the test pattern.
    let output1 = match output1_opt {
        Some(o) => o,
        None => unsafe { factory.EnumAdapters(0)?.EnumOutputs(0)?.cast()? } // Dummy for compilation, will be skipped by is_err()
    };
    
    if let Some(dev) = d3d_device.as_ref() {
        let dxgi_device: IDXGIDevice = dev.cast()?;
    }'''

content = content.replace(target, replacement)

with open(cap_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed!")
