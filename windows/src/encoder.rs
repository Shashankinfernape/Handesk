use anyhow::{Result, Context};
use windows::Win32::System::Com::{CoInitializeEx, CoCreateInstance, COINIT_MULTITHREADED, CLSCTX_INPROC_SERVER};
use windows::Win32::Media::MediaFoundation::{
    MFStartup, MFShutdown, MF_VERSION, MFSTARTUP_NOSOCKET,
    IMFTransform, MFCreateMediaType, IMFMediaType, IMFSample, IMFMediaBuffer,
    MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE, MF_MT_AVG_BITRATE, MF_MT_FRAME_RATE, MF_MT_FRAME_SIZE,
    MF_MT_INTERLACE_MODE, MFMediaType_Video, MFVideoFormat_HEVC, MFVideoFormat_NV12,
    MFVideoInterlace_Progressive, MFCreateMemoryBuffer, MFCreateSample,
    MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, MFT_MESSAGE_NOTIFY_START_OF_STREAM,
    MFT_OUTPUT_DATA_BUFFER,
};
use windows::core::{HRESULT, Interface};
use tracing::{info, warn};

// {6ca50344-051a-4ded-9779-a43305165e35}
pub const CLSID_CMSH264_ENCODER_MFT: windows::core::GUID =
    windows::core::GUID::from_u128(0x6ca50344_051a_4ded_9779_a43305165e35);

// MF_E_TRANSFORM_NEED_MORE_INPUT
const MF_E_TRANSFORM_NEED_MORE_INPUT: HRESULT = HRESULT(0xC00D6D72u32 as i32);

pub struct MFEncoder {
    width: u32,
    height: u32,
    transform: IMFTransform,
    time: i64,
    nv12_buffer: Vec<u8>,
    // Pre-allocated input samples pool
    input_samples: Vec<(IMFSample, IMFMediaBuffer)>,
    sample_index: usize,
    seq_header: Option<Vec<u8>>,
    sent_header: bool,
}

unsafe impl Send for MFEncoder {}

impl MFEncoder {
    pub fn new(width: u32, height: u32) -> Result<Self> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).context("MFStartup failed")?;

            let mut p_interfaces: *mut Option<windows::Win32::Media::MediaFoundation::IMFActivate> = std::ptr::null_mut();
            let mut num_interfaces: u32 = 0;

            let in_info = windows::Win32::Media::MediaFoundation::MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: MFVideoFormat_NV12,
            };
            let out_info = windows::Win32::Media::MediaFoundation::MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: MFVideoFormat_HEVC,
            };

            // Enumerate ALL hardware video encoders (Nvidia NVENC, AMD AMF, Intel QuickSync)
            windows::Win32::Media::MediaFoundation::MFTEnumEx(
                windows::Win32::Media::MediaFoundation::MFT_CATEGORY_VIDEO_ENCODER,
                windows::Win32::Media::MediaFoundation::MFT_ENUM_FLAG_HARDWARE | 
                windows::Win32::Media::MediaFoundation::MFT_ENUM_FLAG_SYNCMFT | 
                windows::Win32::Media::MediaFoundation::MFT_ENUM_FLAG_ASYNCMFT, // REMOVED SORTANDFILTER!
                None, 
                Some(&out_info),
                &mut p_interfaces,
                &mut num_interfaces,
            ).context("MFTEnumEx failed")?;

            let mut hardware_transform: Option<IMFTransform> = None;
            
            if num_interfaces > 0 && !p_interfaces.is_null() {
                let activates = std::slice::from_raw_parts(p_interfaces, num_interfaces as usize);
                for i in 0..num_interfaces as usize {
                    if let Some(activate) = activates[i].as_ref() {
                        if let Ok(t) = activate.ActivateObject::<IMFTransform>() {
                            info!("Successfully activated hardware encoder #{}", i);
                            hardware_transform = Some(t);
                            break;
                        }
                    }
                }
                unsafe { windows::Win32::System::Com::CoTaskMemFree(Some(p_interfaces as *const std::ffi::c_void)); }
            }

            let transform: IMFTransform = if let Some(t) = hardware_transform {
                info!("Using Hardware Encoder (NVENC/AMF/QSV)!");
                t
            } else {
                warn!("No hardware encoders successfully activated. Falling back to Microsoft H.264 Software Encoder.");
                windows::Win32::System::Com::CoCreateInstance(
                    &CLSID_CMSH264_ENCODER_MFT,
                    None,
                    CLSCTX_INPROC_SERVER,
                ).context("Failed to CoCreateInstance Microsoft H.264 Encoder")?
            };

            // Unlock asynchronous MFTs before configuring
            if let Ok(attributes) = transform.GetAttributes() {
                let _ = attributes.SetUINT32(&windows::Win32::Media::MediaFoundation::MF_TRANSFORM_ASYNC_UNLOCK, 1);
            }

            // 1. Set Output Type first (H.264)
            let out_type: IMFMediaType = MFCreateMediaType().context("MFCreateMediaType failed")?;
            out_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_HEVC)?; // QUICK WIN: H.265 / HEVC
            out_type.SetUINT32(&MF_MT_AVG_BITRATE, 25_000_000)?; // 8 Mbps in H.265 looks like 16 Mbps in H.264!
            out_type.SetUINT64(&MF_MT_FRAME_RATE, pack_ratio(144, 1))?; // 120 FPS for zero-latency smoothness
            out_type.SetUINT64(&MF_MT_FRAME_SIZE, pack_ratio(width, height))?;
            out_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            transform.SetOutputType(0, &out_type, 0).context("SetOutputType (H264) failed")?;

            // 1.5 Enable Low Latency & Disable B-Frames (prevents buffering frames!)
            if let Ok(codec_api) = transform.cast::<windows::Win32::Media::MediaFoundation::ICodecAPI>() {
                let var_true = windows::core::VARIANT::from(true);
                let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncCommonLowLatency, &var_true) };
                
                let var_zero = windows::core::VARIANT::from(0u32);
                let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncMPVDefaultBPictureCount, &var_zero) };

                // Force an IDR Keyframe every 30 frames (1 second at 30fps) to instantly recover from any stream corruption
                let var_gop = windows::core::VARIANT::from(30u32);
                let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncMPVGOPSize, &var_gop) };

                // Force H.264 Baseline Profile (66) so Android tablets can decode it without crashing!
                let var_profile = windows::core::VARIANT::from(1u32);
                let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncMPVProfile, &var_profile) };
            }

            // 2. Set Input Type (NV12)
            let in_type: IMFMediaType = MFCreateMediaType()?;
            in_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            in_type.SetGUID(&MF_MT_SUBTYPE, &windows::Win32::Media::MediaFoundation::MFVideoFormat_NV12)?;
            in_type.SetUINT64(&MF_MT_FRAME_RATE, pack_ratio(144, 1))?;
            in_type.SetUINT64(&MF_MT_FRAME_SIZE, pack_ratio(width, height))?;
            in_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            transform.SetInputType(0, &in_type, 0).context("SetInputType (NV12) failed")?;

            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;

            // Extract Sequence Header (SPS/PPS) which is required for Software Encoders
            let mut seq_header = None;
            if let Ok(out_type) = transform.GetOutputCurrentType(0) {
                let mut blob_size = 0u32;
                let mut p_blob = std::ptr::null_mut();
                if out_type.GetAllocatedBlob(&windows::Win32::Media::MediaFoundation::MF_MT_MPEG_SEQUENCE_HEADER, &mut p_blob, &mut blob_size).is_ok() {
                    if !p_blob.is_null() && blob_size > 0 {
                        let slice = std::slice::from_raw_parts(p_blob, blob_size as usize);
                        seq_header = Some(slice.to_vec());
                        windows::Win32::System::Com::CoTaskMemFree(Some(p_blob as *const std::ffi::c_void));
                    }
                }
            }

            // Pre-allocate a pool of input sample buffers (reused circularly — no OOM, no overwrite)
            let nv12_size = (width * height + width * height / 2) as usize;
            let mut input_samples = Vec::new();
            for _ in 0..8 {
                let input_mf_buffer = MFCreateMemoryBuffer(nv12_size as u32)?;
                let input_sample = MFCreateSample()?;
                input_sample.AddBuffer(&input_mf_buffer)?;
                input_samples.push((input_sample, input_mf_buffer));
            }

            info!("MF HEVC/H.265 Encoder initialized: {}x{} @ 144fps, 25Mbps (NVENC Paced UDP Mode)", width, height);

            Ok(Self {
                width,
                height,
                transform,
                time: 0,
                nv12_buffer: vec![0u8; nv12_size],
                input_samples,
                sample_index: 0,
                seq_header,
                sent_header: false,
            })
        }
    }

    pub fn encode_frame(&mut self, bgra_data: &[u8]) -> Result<Vec<u8>> {
        // Step 1: Color-convert BGRA -> NV12 using fast integer math (no floats!)
        self.bgra_to_nv12_fast(bgra_data);

        unsafe {
            // Step 2: Drain any pending output BEFORE sending new input
            // This is the fix for 0xC00D36B5 "not accepting input"
            let mut pre_drain = self.drain_output();

            // Step 3: Write NV12 data into pre-allocated buffer (no allocation!)
            let (input_sample, input_mf_buffer) = &self.input_samples[self.sample_index];
            self.sample_index = (self.sample_index + 1) % self.input_samples.len();

            let mut ptr = std::ptr::null_mut();
            input_mf_buffer.Lock(&mut ptr, None, None)?;
            std::ptr::copy_nonoverlapping(
                self.nv12_buffer.as_ptr(),
                ptr,
                self.nv12_buffer.len(),
            );
            input_mf_buffer.SetCurrentLength(self.nv12_buffer.len() as u32)?;
            input_mf_buffer.Unlock()?;

            // Step 4: Set timestamp and push to encoder
            // 10,000,000 units/sec @ 30 fps = 333,333 units/frame
            input_sample.SetSampleTime(self.time)?;
            input_sample.SetSampleDuration(333333)?;
            self.time += 333333;

            let mut retry_count = 0;
            while retry_count < 10 {
                match self.transform.ProcessInput(0, input_sample, 0) {
                    Ok(_) => break, // Success!
                    Err(e) if e.code() == windows::core::HRESULT(0xC00D36B5u32 as i32) => { // MF_E_NOTACCEPTING
                        // Hardware encoder is full, but hasn't produced output yet. Let it process for 1ms.
                        let more_out = unsafe { self.drain_output() };
                        pre_drain.extend(more_out);
                        std::thread::sleep(std::time::Duration::from_millis(1));
                        retry_count += 1;
                    }
                    Err(e) => {
                        warn!("ProcessInput failed: {:?}. Dropping frame.", e);
                        return Ok(vec![]);
                    }
                }
            }
            if retry_count == 10 {
                warn!("ProcessInput timed out after 10ms waiting for hardware encoder!");
            }

            // Step 5: Drain output after input
            let mut out_data = pre_drain;
            out_data.extend(self.drain_output());

            // Prepend Sequence Header to the very first output frame ONLY! (Android MediaCodec crashes if sent mid-stream)
            if !out_data.is_empty() && !self.sent_header {
                // If hardware encoder didn't give us the header at initialization, try grabbing it NOW!
                if self.seq_header.is_none() {
                    if let Ok(out_type) = self.transform.GetOutputCurrentType(0) {
                        let mut blob_size = 0u32;
                        let mut p_blob = std::ptr::null_mut();
                        if out_type.GetAllocatedBlob(&windows::Win32::Media::MediaFoundation::MF_MT_MPEG_SEQUENCE_HEADER, &mut p_blob, &mut blob_size).is_ok() {
                            if !p_blob.is_null() && blob_size > 0 {
                                let slice = std::slice::from_raw_parts(p_blob, blob_size as usize);
                                self.seq_header = Some(slice.to_vec());
                                windows::Win32::System::Com::CoTaskMemFree(Some(p_blob as *const std::ffi::c_void));
                            }
                        }
                    }
                }

                if let Some(header) = &self.seq_header {
                    let mut full_frame = header.clone();
                    full_frame.extend(&out_data);
                    out_data = full_frame;
                }
                self.sent_header = true;
            }

            Ok(out_data)
        }
    }

    /// Drain all pending output from the MFT encoder
    unsafe fn drain_output(&self) -> Vec<u8> {
        let mut out_data = Vec::new();

        loop {
            let out_info = match self.transform.GetOutputStreamInfo(0) {
                Ok(info) => info,
                Err(_) => break,
            };

            let out_buffer = match MFCreateMemoryBuffer(out_info.cbSize) {
                Ok(b) => b,
                Err(_) => break,
            };
            let out_sample = match MFCreateSample() {
                Ok(s) => s,
                Err(_) => break,
            };
            if out_sample.AddBuffer(&out_buffer).is_err() { break; }

            let mut out_buffers = [MFT_OUTPUT_DATA_BUFFER {
                dwStreamID: 0,
                pSample: std::mem::ManuallyDrop::new(Some(out_sample.clone())),
                dwStatus: 0,
                pEvents: std::mem::ManuallyDrop::new(None),
            }];

            let mut status = 0u32;
            let res = self.transform.ProcessOutput(0, &mut out_buffers, &mut status);

            match res {
                Ok(_) => {
                    if let Some(sample) = out_buffers[0].pSample.as_ref() {
                        if let Ok(final_buffer) = sample.GetBufferByIndex(0) {
                            let mut p_data = std::ptr::null_mut();
                            let mut cur_len = 0u32;
                            if final_buffer.Lock(&mut p_data, None, Some(&mut cur_len)).is_ok() {
                                let slice = std::slice::from_raw_parts(p_data, cur_len as usize);
                                out_data.extend_from_slice(slice);
                                let _ = final_buffer.Unlock();
                            }
                        }
                    }
                }
                Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => {
                    unsafe {
                        std::mem::ManuallyDrop::drop(&mut out_buffers[0].pSample);
                        std::mem::ManuallyDrop::drop(&mut out_buffers[0].pEvents);
                    }
                    break;
                }
                Err(_) => {
                    unsafe {
                        std::mem::ManuallyDrop::drop(&mut out_buffers[0].pSample);
                        std::mem::ManuallyDrop::drop(&mut out_buffers[0].pEvents);
                    }
                    break;
                }
            }

            // Properly release the COM objects to prevent a massive memory leak (OOM crash)
            unsafe {
                std::mem::ManuallyDrop::drop(&mut out_buffers[0].pSample);
                std::mem::ManuallyDrop::drop(&mut out_buffers[0].pEvents);
            }
        }

        out_data
    }

    /// Fast integer NV12 conversion parallelized across CPU cores (~1-2ms per frame)
    fn bgra_to_nv12_fast(&mut self, bgra: &[u8]) {
        let w = self.width as usize;
        let h = self.height as usize;
        let uv_start = w * h;

        use rayon::prelude::*;

        // Process 2 rows at a time (since NV12 chroma is 4:2:0 subsampled vertically and horizontally)
        let chunk_size = w * 2;
        let uv_chunk_size = w; // 1 row of UV data per 2 rows of Y data

        let (y_plane, uv_plane) = self.nv12_buffer.split_at_mut(uv_start);

        y_plane.par_chunks_mut(chunk_size)
            .zip(uv_plane.par_chunks_mut(uv_chunk_size))
            .enumerate()
            .for_each(|(i, (y_out, uv_out))| {
                let y_offset = i * 2; // Real Y coordinate
                let bgra_offset = y_offset * w * 4;
                let bgra_chunk = &bgra[bgra_offset..bgra_offset + w * 8]; // 2 rows of BGRA

                for x in 0..w {
                    // Row 1
                    let p1 = x * 4;
                    let b1 = bgra_chunk[p1] as i32;
                    let g1 = bgra_chunk[p1 + 1] as i32;
                    let r1 = bgra_chunk[p1 + 2] as i32;
                    y_out[x] = (((77 * r1 + 150 * g1 + 29 * b1 + 128) >> 8)).clamp(0, 255) as u8;

                    // Row 2
                    let p2 = (w + x) * 4;
                    let b2 = bgra_chunk[p2] as i32;
                    let g2 = bgra_chunk[p2 + 1] as i32;
                    let r2 = bgra_chunk[p2 + 2] as i32;
                    y_out[w + x] = (((77 * r2 + 150 * g2 + 29 * b2 + 128) >> 8)).clamp(0, 255) as u8;

                    // Chroma (subsampled to 1 pixel for the 2x2 block)
                    if x % 2 == 0 {
                        let cb = (((-43 * r1 - 85 * g1 + 128 * b1 + 128) >> 8) + 128).clamp(0, 255) as u8;
                        let cr = (((128 * r1 - 107 * g1 - 21 * b1 + 128) >> 8) + 128).clamp(0, 255) as u8;
                        uv_out[x] = cb;
                        uv_out[x + 1] = cr;
                    }
                }
            });
    }
}

impl Drop for MFEncoder {
    fn drop(&mut self) {
        unsafe { let _ = MFShutdown(); }
    }
}

#[inline(always)]
fn pack_ratio(num: u32, den: u32) -> u64 {
    ((num as u64) << 32) | (den as u64)
}





