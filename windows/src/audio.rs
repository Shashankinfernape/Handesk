use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use tokio::sync::mpsc;
use anyhow::{Result, Context, anyhow};
use tracing::{info, error, debug};
use std::sync::atomic::{AtomicBool, Ordering};

pub static AUDIO_ENABLED: AtomicBool = AtomicBool::new(true);

pub async fn start_audio_loop(tx: mpsc::Sender<Vec<u8>>) -> Result<()> {
    let host = cpal::default_host();
    let device = host.default_output_device().context("No output device available")?;
    
    info!("Loopback Capture Device: {}", device.name().unwrap_or_else(|_| "Unknown".to_string()));
    
    let config = device.supported_output_configs()?
        .next()
        .context("No supported config")?
        .with_max_sample_rate();
        
    let sample_format = config.sample_format();
    let stream_config: cpal::StreamConfig = config.into();
    
    info!("Audio Stream Config: {:?}", stream_config);
    
    let err_fn = |err| error!("An error occurred on the audio stream: {}", err);
    
    let stream = match sample_format {
        cpal::SampleFormat::F32 => {
            device.build_input_stream(
                &stream_config.into(),
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
        _ => return Err(anyhow!("Unsupported sample format (F32 expected)")),
    };
    
    stream.play()?;
    info!("Audio Loopback Started successfully!");
    
    loop {
        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
    }
}
