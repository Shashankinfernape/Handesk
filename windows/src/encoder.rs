use anyhow::{Context, Result};
use tracing::{info, debug, error};
use windows::core::Interface;
use windows::Win32::System::Com::*;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::Graphics::Direct3D11::*;

fn pack_ratio(high: u32, low: u32) -> u64 {
    ((high as u64) << 32) | (low as u64)
}

/// VBV buffer size in bits: ~2 frames worth at 60 FPS (e.g. 8 Mbps -> ~33 KB max frame).
fn vbv_bits(bitrate: u32) -> u32 {
    (bitrate / 60).saturating_mul(2).max(200_000)
}

pub struct MFEncoder {
    width: u32,
    height: u32,
    transform: IMFTransform,
    event_gen: IMFMediaEventGenerator,
    sample_time: i64,
    can_input: bool,
    cached_header: Vec<u8>,
    _dxgi_manager: IMFDXGIDeviceManager,
}

unsafe impl Send for MFEncoder {}

impl MFEncoder {
    pub fn new(width: u32, height: u32, device: &ID3D11Device) -> Result<Self> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).context("MFStartup failed")?;

            let mut reset_token = 0;
            let mut dxgi_manager_ptr: Option<IMFDXGIDeviceManager> = None;
            MFCreateDXGIDeviceManager(&mut reset_token, &mut dxgi_manager_ptr)?;
            let dxgi_manager = dxgi_manager_ptr.unwrap();
            
            if let Ok(multithread) = device.cast::<ID3D11Multithread>() {
                let _ = multithread.SetMultithreadProtected(true);
                info!("Enabled ID3D11Multithread protection on GPU Device in MFEncoder");
            }

            dxgi_manager.ResetDevice(device, reset_token)?;
            
            let dxgi_manager_unk: windows::core::IUnknown = dxgi_manager.cast()?;
            let dxgi_manager_raw = std::mem::transmute_copy::<windows::core::IUnknown, usize>(&dxgi_manager_unk);

            // 1. Find Hardware HEVC Encoder
            let mut p_mfts = std::ptr::null_mut();
            let mut count = 0;
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_ASYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                None,
                Some(&MFT_REGISTER_TYPE_INFO {
                    guidMajorType: MFMediaType_Video,
                    guidSubtype: MFVideoFormat_HEVC,
                }),
                &mut p_mfts,
                &mut count,
            )?;
            
            if count == 0 {
                return Err(anyhow::anyhow!("No Hardware HEVC encoder found!"));
            }

            let mft_ptrs = std::slice::from_raw_parts(p_mfts, count as usize);
            let mft_activate = mft_ptrs[0].as_ref().unwrap();
            let transform: IMFTransform = mft_activate.ActivateObject()?;
            windows::Win32::System::Com::CoTaskMemFree(Some(p_mfts as *const std::ffi::c_void));

            // REQUIRED for Async MFTs
            let attributes = transform.GetAttributes()?;
            attributes.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1)?;

            // Pass DXGI Manager to HEVC Encoder so it operates in zero-copy GPU mode
            let _ = transform.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, dxgi_manager_raw);

            // 2. Configure HEVC Output FIRST
            let mt_out = MFCreateMediaType()?;
            mt_out.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            mt_out.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_HEVC)?;
            mt_out.SetUINT64(&MF_MT_FRAME_SIZE, pack_ratio(width, height))?;
            mt_out.SetUINT64(&MF_MT_FRAME_RATE, pack_ratio(60, 1))?;
            mt_out.SetUINT32(&MF_MT_INTERLACE_MODE, 2)?;
            let default_bitrate = crate::capture::TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed).max(10_000_000);
            mt_out.SetUINT32(&MF_MT_AVG_BITRATE, default_bitrate)?;
            transform.SetOutputType(0, &mt_out, 0)?;

            // 3. Setup Codec API for Ultra-Low-Latency Real-Time Streaming
            if let Ok(codec_api) = transform.cast::<ICodecAPI>() {
                let var_true = windows::core::VARIANT::from(true);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonLowLatency, &var_true);
                let _ = codec_api.SetValue(&CODECAPI_AVLowLatencyMode, &var_true);
                
                let var_zero = windows::core::VARIANT::from(0u32);
                let _ = codec_api.SetValue(&CODECAPI_AVEncMPVDefaultBPictureCount, &var_zero);
                
                // 300 GOP (5 s safety-net refresh). Client IDR requests handle loss recovery.
                let var_gop = windows::core::VARIANT::from(300u32);
                let _ = codec_api.SetValue(&CODECAPI_AVEncMPVGOPSize, &var_gop);

                // Rate Control Mode: 0 = CBR (constant low-latency, prevents UDP packet bursts)
                let var_rate_control = windows::core::VARIANT::from(0u32);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonRateControlMode, &var_rate_control);

                let var_bitrate = windows::core::VARIANT::from(default_bitrate);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonMeanBitRate, &var_bitrate);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonMaxBitRate, &var_bitrate);

                // CRITICAL: Cap the VBV buffer so no single frame (incl. keyframes) exceeds
                // ~2 frames worth of bits. Without this, IDRs hit 150 KB = 150 UDP packets,
                // one lost packet kills the frame, client asks for another IDR -> stuck loop.
                let var_buf = windows::core::VARIANT::from(vbv_bits(default_bitrate));
                let hr = codec_api.SetValue(&CODECAPI_AVEncCommonBufferSize, &var_buf);
                info!("VBV buffer cap set: {} bits ({:?}) for {} bps", vbv_bits(default_bitrate), hr, default_bitrate);

                // High quality visual tuning (75/100)
                let var_quality = windows::core::VARIANT::from(75u32);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonQualityVsSpeed, &var_quality);
            }

            // 4. Configure Input Type (ARGB32 matches DXGI desktop format natively on RTX 2060)
            let mt_in = MFCreateMediaType()?;
            mt_in.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            mt_in.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_ARGB32)?;
            mt_in.SetUINT64(&MF_MT_FRAME_SIZE, pack_ratio(width, height))?;
            mt_in.SetUINT64(&MF_MT_FRAME_RATE, pack_ratio(60, 1))?;
            mt_in.SetUINT32(&MF_MT_INTERLACE_MODE, 2)?;
            transform.SetInputType(0, &mt_in, 0)?;

            let event_gen: IMFMediaEventGenerator = transform.cast()?;

            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;

            info!("Initialized Zero-Copy GPU HEVC Hardware Encoder (Async MFT, CBR, LowLatency)");

            Ok(Self {
                width,
                height,
                transform,
                event_gen,
                sample_time: 0,
                can_input: false,
                cached_header: Vec::new(),
                _dxgi_manager: dxgi_manager,
            })
        }
    }

    pub fn set_bitrate(&self, bitrate: u32) {
        unsafe {
            if let Ok(codec_api) = self.transform.cast::<ICodecAPI>() {
                let var_bitrate = windows::core::VARIANT::from(bitrate);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonMeanBitRate, &var_bitrate);
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonMaxBitRate, &var_bitrate);
                let var_buf = windows::core::VARIANT::from(vbv_bits(bitrate));
                let _ = codec_api.SetValue(&CODECAPI_AVEncCommonBufferSize, &var_buf);
            }
        }
    }

    pub fn force_idr(&self) {
        unsafe {
            if let Ok(codec_api) = self.transform.cast::<ICodecAPI>() {
                // Must be VT_UI4 (1u32)
                let var_one = windows::core::VARIANT::from(1u32);
                let _ = codec_api.SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &var_one);
                debug!("Forced hardware IDR keyframe via CodecAPI (VT_UI4)");
            }
        }
    }

    pub fn encode_frame(&mut self, _bgra_data: &[u8]) -> Result<Vec<u8>> {
        Ok(vec![])
    }

    pub fn encode_frame_gpu(&mut self, texture: Option<&ID3D11Texture2D>) -> Result<Vec<u8>> {
        unsafe {
            let mut out_bytes = Vec::new();

            // 1. Drain pending events before input
            loop {
                match self.event_gen.GetEvent(MF_EVENT_FLAG_NO_WAIT) {
                    Ok(event) => {
                        let ev_type = event.GetType()?;
                        if ev_type == 601 { // METransformNeedInput
                            self.can_input = true;
                        } else if ev_type == 602 { // METransformHaveOutput
                            self.drain_one_sample(&mut out_bytes);
                        }
                    }
                    Err(_) => break,
                }
            }

            // 2. Feed the new frame if MFT is ready and we have one
            if self.can_input {
                if let Some(tex) = texture {
                    match MFCreateDXGISurfaceBuffer(
                        &ID3D11Texture2D::IID,
                        &tex.cast::<windows::core::IUnknown>().unwrap(),
                        0,
                        false,
                    ) {
                        Ok(buffer) => {
                            if let Ok(sample_in) = MFCreateSample() {
                                let _ = sample_in.AddBuffer(&buffer);
                                let _ = sample_in.SetSampleTime(self.sample_time);
                                let _ = sample_in.SetSampleDuration(166666);
                                self.sample_time += 166666;
                                match self.transform.ProcessInput(0, &sample_in, 0) {
                                    Ok(_) => self.can_input = false,
                                    Err(e) => error!("ProcessInput failed: {:?}", e),
                                }
                            }
                        }
                        Err(e) => error!("MFCreateDXGISurfaceBuffer failed: {:?}", e),
                    }
                }
            }

            // 3. Drain output produced by this frame
            loop {
                match self.event_gen.GetEvent(MF_EVENT_FLAG_NO_WAIT) {
                    Ok(event) => {
                        let ev_type = event.GetType()?;
                        if ev_type == 601 { // METransformNeedInput
                            self.can_input = true;
                        } else if ev_type == 602 { // METransformHaveOutput
                            self.drain_one_sample(&mut out_bytes);
                        }
                    }
                    Err(_) => break,
                }
            }

            // 4. Ensure EVERY Keyframe has VPS + SPS + PPS
            if !out_bytes.is_empty() {
                let has_vps = contains_nalu(&out_bytes, 32);
                let is_keyframe = contains_nalu(&out_bytes, 19) 
                    || contains_nalu(&out_bytes, 20) 
                    || contains_nalu(&out_bytes, 21);
                
                if has_vps {
                    if let Some(vcl_pos) = find_first_vcl_pos(&out_bytes) {
                        self.cached_header = out_bytes[..vcl_pos].to_vec();
                        info!("Cached HEVC Parameter Sets (VPS/SPS/PPS): {} bytes", self.cached_header.len());
                    }
                } else if is_keyframe {
                    if !self.cached_header.is_empty() {
                        let mut complete_keyframe = self.cached_header.clone();
                        complete_keyframe.extend_from_slice(&out_bytes);
                        info!("Prepended cached VPS/SPS/PPS ({} bytes) to keyframe (total {} bytes)", self.cached_header.len(), complete_keyframe.len());
                        return Ok(complete_keyframe);
                    } else {
                        error!("Keyframe generated before VPS/SPS/PPS header was cached!");
                    }
                }
            }

            Ok(out_bytes)
        }
    }

    unsafe fn drain_one_sample(&self, out_bytes: &mut Vec<u8>) {
        let mut data_buf = MFT_OUTPUT_DATA_BUFFER {
            dwStreamID: 0,
            pSample: core::mem::ManuallyDrop::new(None),
            dwStatus: 0,
            pEvents: core::mem::ManuallyDrop::new(None),
        };
        let mut status = 0;
        match self.transform.ProcessOutput(0, std::slice::from_mut(&mut data_buf), &mut status) {
            Ok(_) => {
                // IMPORTANT: We must take ownership of the returned COM object out of ManuallyDrop so it gets dropped!
                let sample_opt = core::mem::ManuallyDrop::into_inner(data_buf.pSample);
                let _events_opt = core::mem::ManuallyDrop::into_inner(data_buf.pEvents); // ensure events are dropped too

                if let Some(sample) = sample_opt {
                    match sample.ConvertToContiguousBuffer() {
                        Ok(buffer) => {
                            let mut ptr = std::ptr::null_mut();
                            let mut cur_len = 0;
                            match buffer.Lock(&mut ptr, None, Some(&mut cur_len)) {
                                Ok(_) => {
                                    let slice = std::slice::from_raw_parts(ptr, cur_len as usize);
                                    out_bytes.extend_from_slice(slice);
                                    let _ = buffer.Unlock();
                                }
                                Err(e) => error!("Buffer Lock failed: {:?}", e),
                            }
                        }
                        Err(e) => error!("ConvertToContiguousBuffer failed: {:?}", e),
                    }
                }
            }
            Err(e) => {
                if e.code() != windows::core::HRESULT(-1072875853) { // MF_E_TRANSFORM_NEED_MORE_INPUT
                    error!("ProcessOutput failed: {:?}", e);
                }
            }
        }
    }
}

fn contains_nalu(data: &[u8], target: u8) -> bool {
    let mut i = 0;
    while i < data.len().saturating_sub(4) {
        if data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
            let nalu_type = (data[i+4] >> 1) & 0x3F;
            if nalu_type == target { return true; }
            i += 4;
        } else if data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
            let nalu_type = (data[i+3] >> 1) & 0x3F;
            if nalu_type == target { return true; }
            i += 3;
        } else {
            i += 1;
        }
    }
    false
}

fn find_first_vcl_pos(data: &[u8]) -> Option<usize> {
    let mut i = 0;
    while i < data.len().saturating_sub(4) {
        if data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
            let nalu_type = (data[i+4] >> 1) & 0x3F;
            if nalu_type <= 31 { return Some(i); }
            i += 4;
        } else if data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
            let nalu_type = (data[i+3] >> 1) & 0x3F;
            if nalu_type <= 31 { return Some(i); }
            i += 3;
        } else {
            i += 1;
        }
    }
    None
}
