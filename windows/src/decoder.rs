use anyhow::{Context, Result};
use std::ptr::null_mut;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::*;

pub struct VideoDecoder {
    transform: IMFTransform,
    width: u32,
    height: u32,
    is_hevc: bool,
}

unsafe impl Send for VideoDecoder {}

impl VideoDecoder {
    pub fn new(width: u32, height: u32, is_hevc: bool) -> Result<Self> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).context("MFStartup failed")?;

            let input_type = MFCreateMediaType()?;
            input_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;

            let subtype = if is_hevc {
                MFVideoFormat_HEVC
            } else {
                MFVideoFormat_H264
            };
            input_type.SetGUID(&MF_MT_SUBTYPE, &subtype)?;

            let type_info = MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: subtype,
            };

            let mut p_mfts = null_mut();
            let mut count = 0;
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_DECODER,
                MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                Some(&type_info),
                None,
                &mut p_mfts,
                &mut count,
            )?;

            if count == 0 {
                // Try software decoders
                MFTEnumEx(
                    MFT_CATEGORY_VIDEO_DECODER,
                    MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                    Some(&type_info),
                    None,
                    &mut p_mfts,
                    &mut count,
                )?;
            }

            if count == 0 || p_mfts.is_null() {
                anyhow::bail!("No compatible video decoder MFT found");
            }

            let activate = (*p_mfts).as_ref().unwrap();
            let transform: IMFTransform = activate.ActivateObject()?;
            CoTaskMemFree(Some(p_mfts as *const _));

            // Set input type
            transform.SetInputType(0, &input_type, 0)?;

            // Set output type to RGB32
            let output_type = MFCreateMediaType()?;
            output_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            output_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)?;
            let ratio = ((width as u64) << 32) | (height as u64);
            output_type.SetUINT64(&MF_MT_FRAME_SIZE, ratio)?;

            let _ = transform.SetOutputType(0, &output_type, 0);

            Ok(Self {
                transform,
                width,
                height,
                is_hevc,
            })
        }
    }

    pub fn decode_frame(&mut self, nalu: &[u8]) -> Result<Option<Vec<u8>>> {
        unsafe {
            let buffer = MFCreateMemoryBuffer(nalu.len() as u32)?;

            let mut dest_ptr = null_mut();
            buffer.Lock(&mut dest_ptr, None, None)?;
            std::ptr::copy_nonoverlapping(nalu.as_ptr(), dest_ptr, nalu.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(nalu.len() as u32)?;

            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;

            let _ = self.transform.ProcessInput(0, &sample, 0);

            // Try to receive output sample
            let mut output_data_buffer = MFT_OUTPUT_DATA_BUFFER::default();
            let mut status = 0;
            let res = self.transform.ProcessOutput(0, std::slice::from_mut(&mut output_data_buffer), &mut status);

            if res.is_ok() {
                if let Some(out_sample) = output_data_buffer.pSample.as_ref() {
                    let out_buf = out_sample.ConvertToContiguousBuffer()?;
                    let mut out_ptr = null_mut();
                    let mut max_len = 0;
                    let mut cur_len = 0;
                    out_buf.Lock(&mut out_ptr, Some(&mut max_len), Some(&mut cur_len))?;

                    let mut rgb = vec![0u8; cur_len as usize];
                    std::ptr::copy_nonoverlapping(out_ptr, rgb.as_mut_ptr(), cur_len as usize);
                    out_buf.Unlock()?;
                    return Ok(Some(rgb));
                }
            }

            Ok(None)
        }
    }
}
