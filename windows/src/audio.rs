use anyhow::{anyhow, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use opus_pure::{Application, OpusEncoder, MAX_PACKET_BYTES};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::mpsc;
use tracing::{error, info};

pub static AUDIO_ENABLED: AtomicBool = AtomicBool::new(true);

// Opus frame size: 20ms at 48000 Hz = 960 samples per channel
const OPUS_FRAME_SAMPLES: usize = 960;
// Stereo: 960 * 2 channels = 1920 i16 samples per frame
const OPUS_FRAME_SIZE: usize = OPUS_FRAME_SAMPLES * 2;

pub async fn start_audio_loop(tx: mpsc::Sender<Vec<u8>>) -> Result<()> {
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .context("No output device available")?;

    // Use the device's DEFAULT config (the actual Windows mixer format — almost always 48000 Hz stereo).
    let config = device
        .default_output_config()
        .context("No default output config")?;

    let sample_format = config.sample_format();
    let stream_config: cpal::StreamConfig = config.into();

    let channels = stream_config.channels as usize;

    info!(
        "Audio loopback: format={:?} rate={:?}Hz channels={}",
        sample_format, stream_config.sample_rate, channels
    );

    // Create Opus encoder — 48kHz stereo, AUDIO mode, 64kbps
    // Compresses 1.5 Mbps raw PCM → ~64 kbps (23x reduction for internet!)
    let mut encoder = OpusEncoder::new(48000, 2, Application::Audio)
        .map_err(|e| anyhow!("Failed to create Opus encoder: {:?}", e))?;
    encoder.bitrate_bps = 64_000;

    let err_fn = |err| {
        // Buffer underrun/overrun is completely normal during silence or system audio idle.
        // Do NOT treat it as a fatal stream death!
        tracing::debug!("Audio stream non-fatal event: {}", err);
    };

    // PCM sample accumulator — collect until we have one full Opus frame (20ms)
    let (pcm_tx, pcm_rx) = std::sync::mpsc::sync_channel::<Vec<i16>>(200);

    let stream = match sample_format {
        cpal::SampleFormat::F32 => {
            let pcm_tx = pcm_tx.clone();
            device.build_input_stream(
                stream_config,
                move |data: &[f32], _: &cpal::InputCallbackInfo| {
                    if !AUDIO_ENABLED.load(Ordering::Relaxed) {
                        return;
                    }
                    let stereo = to_stereo_i16_f32(data, channels);
                    let _ = pcm_tx.try_send(stereo);
                },
                err_fn,
                None,
            )?
        }
        cpal::SampleFormat::I16 => {
            let pcm_tx = pcm_tx.clone();
            device.build_input_stream(
                stream_config,
                move |data: &[i16], _: &cpal::InputCallbackInfo| {
                    if !AUDIO_ENABLED.load(Ordering::Relaxed) {
                        return;
                    }
                    let stereo = to_stereo_i16(data, channels);
                    let _ = pcm_tx.try_send(stereo);
                },
                err_fn,
                None,
            )?
        }
        _ => return Err(anyhow!("Unsupported sample format: {:?}", sample_format)),
    };

    stream.play()?;
    info!("Audio Loopback Started! Encoding with Opus at 64kbps (internet-optimized)");

    let mut raw_accumulator: Vec<i16> = Vec::with_capacity(OPUS_FRAME_SIZE * 8);
    let mut opus_buf = vec![0u8; MAX_PACKET_BYTES];
    // stream_config.sample_rate is a u32 primitive in this cpal version
    let sample_rate_val = stream_config.sample_rate;
    let ratio = sample_rate_val as f64 / 48000.0;
    let mut raw_idx: f64 = 0.0;

    loop {
        match pcm_rx.try_recv() {
            Ok(chunk) => {
                raw_accumulator.extend_from_slice(&chunk);

                while (raw_idx + ratio * OPUS_FRAME_SAMPLES as f64).ceil() as usize
                    <= raw_accumulator.len() / 2
                {
                    let mut frame = vec![0i16; OPUS_FRAME_SIZE];
                    for i in 0..OPUS_FRAME_SAMPLES {
                        let in_idx = raw_idx + (i as f64 * ratio);
                        let idx1 = in_idx.floor() as usize;
                        let idx2 = (idx1 + 1).min((raw_accumulator.len() / 2) - 1);
                        let frac = in_idx - idx1 as f64;

                        let l1 = raw_accumulator[idx1 * 2] as f64;
                        let l2 = raw_accumulator[idx2 * 2] as f64;
                        frame[i * 2] = (l1 + frac * (l2 - l1)) as i16;

                        let r1 = raw_accumulator[idx1 * 2 + 1] as f64;
                        let r2 = raw_accumulator[idx2 * 2 + 1] as f64;
                        frame[i * 2 + 1] = (r1 + frac * (r2 - r1)) as i16;
                    }

                    match encoder.encode_s16(&frame, OPUS_FRAME_SAMPLES, &mut opus_buf) {
                        Ok(n) if n > 0 => {
                            let _ = tx.try_send(opus_buf[..n].to_vec());
                        }
                        Ok(_) => {}
                        Err(e) => error!("Opus encode error: {:?}", e),
                    }

                    raw_idx += ratio * OPUS_FRAME_SAMPLES as f64;
                }

                let consumed = raw_idx.floor() as usize;
                if consumed > 0 {
                    raw_accumulator.drain(..consumed * 2);
                    raw_idx -= consumed as f64;
                }
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => {
                tokio::time::sleep(tokio::time::Duration::from_millis(1)).await;
            }
            Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                error!("Audio channel disconnected. CPAL stream crashed. Exiting audio loop.");
                break Err(anyhow!("CPAL audio stream disconnected"));
            }
        }
    }
}

fn to_stereo_i16_f32(data: &[f32], channels: usize) -> Vec<i16> {
    if channels == 2 {
        data.iter()
            .map(|&s| (s * 32767.0).clamp(-32768.0, 32767.0) as i16)
            .collect()
    } else if channels == 1 {
        data.iter()
            .flat_map(|&s| {
                let v = (s * 32767.0).clamp(-32768.0, 32767.0) as i16;
                [v, v]
            })
            .collect()
    } else {
        data.chunks(channels)
            .flat_map(|f| {
                let l =
                    (f.get(0).copied().unwrap_or(0.0) * 32767.0).clamp(-32768.0, 32767.0) as i16;
                let r =
                    (f.get(1).copied().unwrap_or(0.0) * 32767.0).clamp(-32768.0, 32767.0) as i16;
                [l, r]
            })
            .collect()
    }
}

fn to_stereo_i16(data: &[i16], channels: usize) -> Vec<i16> {
    if channels == 2 {
        data.to_vec()
    } else if channels == 1 {
        data.iter().flat_map(|&s| [s, s]).collect()
    } else {
        data.chunks(channels)
            .flat_map(|f| {
                [
                    f.get(0).copied().unwrap_or(0),
                    f.get(1).copied().unwrap_or(0),
                ]
            })
            .collect()
    }
}
