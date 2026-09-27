use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use tokio::sync::mpsc;
use anyhow::{Result, Context, anyhow};
use tracing::{info, error, debug};
use std::sync::atomic::{AtomicBool, Ordering};

pub static AUDIO_ENABLED: AtomicBool = AtomicBool::new(true);

pub async fn start_audio_loop(tx: mpsc::Sender<Vec<u8>>) -> Result<()> {
    let host = cpal::default_host();
    let device = host.default_output_device().context("No output device available")?;
    
    // Use the device's DEFAULT config (the actual Windows mixer format — almost always 48000 Hz stereo).
    // Do NOT use supported_output_configs().next().with_max_sample_rate() — that can return
    // 192000 Hz which causes a complete sample-rate mismatch with the Android AudioTrack at 48000 Hz.
    let config = device.default_output_config().context("No default output config")?;
        
    let sample_format = config.sample_format();
    let stream_config: cpal::StreamConfig = config.into();
    
    info!("Audio loopback: format={:?} rate={:?}Hz channels={}", 
        sample_format, stream_config.sample_rate, stream_config.channels);
    
    let err_fn = |err| error!("Audio stream error: {}", err);
    
    let stream = match sample_format {
        cpal::SampleFormat::F32 => {
            device.build_input_stream(
                stream_config,
                move |data: &[f32], _: &cpal::InputCallbackInfo| {
                    if !AUDIO_ENABLED.load(Ordering::Relaxed) { return; }
                    
                    let mut pcm16 = Vec::with_capacity(data.len() * 2);
                    for &sample in data.iter() {
                        let mut s = (sample * 32767.0) as i32;
                        if s > 32767 { s = 32767; }
                        if s < -32768 { s = -32768; }
                        pcm16.extend_from_slice(&(s as i16).to_le_bytes());
                    }
                    
                    // non-blocking try_send, drop chunk if channel is full
                    let _ = tx.try_send(pcm16);
                },
                err_fn,
                None
            )?
        },
        cpal::SampleFormat::I16 => {
            device.build_input_stream(
                stream_config,
                move |data: &[i16], _: &cpal::InputCallbackInfo| {
                    if !AUDIO_ENABLED.load(Ordering::Relaxed) { return; }
                    
                    let mut pcm16 = Vec::with_capacity(data.len() * 2);
                    for &sample in data.iter() {
                        pcm16.extend_from_slice(&sample.to_le_bytes());
                    }
                    
                    let _ = tx.try_send(pcm16);
                },
                err_fn,
                None
            )?
        },
        _ => return Err(anyhow!("Unsupported sample format: {:?}", sample_format)),
    };
    
    stream.play()?;
    info!("Audio Loopback Started successfully!");
    
    loop {
        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
    }
}
