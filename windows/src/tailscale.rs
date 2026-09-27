use anyhow::{Result, Context};
use tracing::{info, warn};
use std::process::Command;
use std::os::windows::process::CommandExt;

// CREATE_NO_WINDOW flag to hide the command prompt popping up
const CREATE_NO_WINDOW: u32 = 0x08000000;

pub fn setup_and_connect(auth_key: &str) -> Result<()> {
    info!("Checking if Tailscale is installed...");
    
    // Check if tailscale CLI is already in PATH or installed
    let is_installed = Command::new("tailscale")
        .arg("version")
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .is_ok();

    if !is_installed {
        info!("Tailscale not found. Silently installing from bundled installer...");
        
        let installer_path = std::env::current_dir()?.join("tailscale-setup.exe");
        
        if !installer_path.exists() {
            warn!("tailscale-setup.exe not found next to the host executable! Cannot install Tailscale.");
            return Ok(());
        }

        let status = Command::new(&installer_path)
            .arg("/quiet") // Silent install
            .creation_flags(CREATE_NO_WINDOW)
            .status()
            .context("Failed to launch Tailscale installer")?;
            
        if status.success() {
            info!("Tailscale silently installed successfully!");
        } else {
            warn!("Tailscale installer returned non-zero status. It may have failed or require a reboot.");
        }
    } else {
        info!("Tailscale is already installed on this PC.");
    }

    info!("Silently authenticating and connecting Tailscale...");
    
    // Run `tailscale up` with the auth key
    let mut args = vec!["up", "--unattended", "--reset"];
    if !auth_key.is_empty() {
        args.push("--authkey");
        args.push(auth_key);
    }
    
    let _ = Command::new("tailscale")
        .args(&args)
        .creation_flags(CREATE_NO_WINDOW)
        .output();
        
    info!("Tailscale network interface should now be active.");
    
    Ok(())
}
