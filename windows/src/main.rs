#![windows_subsystem = "windows"]

use anyhow::Result;
use tracing::{info, error};
use eframe::egui;

mod capture;
mod encoder;
mod network;
mod signaling;
mod transport;
mod input;
mod network_udp;
mod network_tcp;
mod audio;

fn main() -> Result<()> {
    unsafe {
        let _ = windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    // Start background network server
    std::thread::spawn(|| {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            use socket2::{Socket, Domain, Type};
            use std::net::SocketAddr;
            
            let socket2_sock = Socket::new(Domain::IPV4, Type::DGRAM, None).unwrap();
            socket2_sock.set_nonblocking(true).unwrap();
            let _ = socket2_sock.set_send_buffer_size(8 * 1024 * 1024);
            socket2_sock.bind(&"0.0.0.0:21118".parse::<SocketAddr>().unwrap().into()).unwrap();
            
            let std_socket: std::net::UdpSocket = socket2_sock.into();
            let socket = std::sync::Arc::new(tokio::net::UdpSocket::from_std(std_socket).unwrap());
            if let Err(e) = network_udp::start_direct_server(socket).await {
                error!("Server crashed: {:?}", e);
            }
        });
    });

    // Start EGUI GUI
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([800.0, 500.0])
            .with_min_inner_size([750.0, 450.0])
            .with_title("DirectLink Desktop"),
        ..Default::default()
    };
    
    eframe::run_native(
        "DirectLink",
        options,
        Box::new(|cc| Ok(Box::new(DirectLinkApp::new(cc)))),
    ).map_err(|e| anyhow::anyhow!("Eframe error: {:?}", e))?;

    Ok(())
}

struct DirectLinkApp {
    local_ip: String,
    remote_input: String,
    show_toast: bool,
}

impl DirectLinkApp {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        // Set dark theme
        cc.egui_ctx.set_visuals(egui::Visuals::dark());

        let mut style = (*cc.egui_ctx.style()).clone();
        
        // Background
        style.visuals.window_fill = egui::Color32::from_rgb(31, 31, 31);
        style.visuals.panel_fill = egui::Color32::from_rgb(31, 31, 31);
        
        // Red accent
        style.visuals.selection.bg_fill = egui::Color32::from_rgb(239, 68, 57);
        style.visuals.widgets.active.bg_fill = egui::Color32::from_rgb(200, 50, 40);
        style.visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(255, 80, 70);
        style.visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(50, 50, 50);
        
        cc.egui_ctx.set_style(style);
        
        let local_ip = local_ip_address::local_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|_| "127.0.0.1".to_string());

        Self {
            local_ip,
            remote_input: String::new(),
            show_toast: false,
        }
    }
}

impl eframe::App for DirectLinkApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        
        // Top Panel for Banner/Menu
        egui::TopBottomPanel::top("top_panel").exact_height(55.0).show(ctx, |ui| {
            ui.add_space(15.0);
            ui.horizontal(|ui| {
                ui.add_space(25.0);
                ui.heading(egui::RichText::new("DirectLink Desktop").color(egui::Color32::from_rgb(239, 68, 57)).size(24.0).strong());
            });
        });
        
        // Central Panel
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(30.0);
            
            ui.horizontal(|ui| {
                ui.add_space(30.0);
                
                // LEFT PANEL (This Desk)
                egui::Frame::none()
                    .fill(egui::Color32::from_rgb(45, 45, 45))
                    .rounding(10.0)
                    .inner_margin(25.0)
                    .show(ui, |ui| {
                        ui.set_width(320.0);
                        ui.set_height(350.0);
                        
                        ui.label(egui::RichText::new("This Desk").size(22.0).strong().color(egui::Color32::WHITE));
                        ui.add_space(5.0);
                        ui.label(egui::RichText::new("Your Address").size(15.0).color(egui::Color32::LIGHT_GRAY));
                        ui.add_space(20.0);
                        
                        ui.label(egui::RichText::new(&self.local_ip).size(38.0).strong().color(egui::Color32::from_rgb(239, 68, 57)).monospace());
                        
                        ui.add_space(40.0);
                        ui.horizontal(|ui| {
                            let (rect, _response) = ui.allocate_exact_size(egui::vec2(12.0, 12.0), egui::Sense::hover());
                            ui.painter().circle_filled(rect.center(), 6.0, egui::Color32::from_rgb(40, 200, 80));
                            ui.add_space(5.0);
                            ui.label(egui::RichText::new("DirectLink Network: Ready").size(15.0));
                        });
                        
                        ui.add_space(20.0);
                        ui.label(egui::RichText::new("Waiting for incoming connection...").color(egui::Color32::LIGHT_GRAY));
                        ui.label(egui::RichText::new("Ensure the Android client uses the address above.").color(egui::Color32::LIGHT_GRAY));
                    });
                    
                ui.add_space(25.0);
                
                // RIGHT PANEL (Remote Desk)
                egui::Frame::none()
                    .fill(egui::Color32::from_rgb(45, 45, 45))
                    .rounding(10.0)
                    .inner_margin(25.0)
                    .show(ui, |ui| {
                        ui.set_width(360.0);
                        ui.set_height(350.0);
                        
                        ui.label(egui::RichText::new("Remote Desk").size(22.0).strong().color(egui::Color32::WHITE));
                        ui.add_space(5.0);
                        ui.label(egui::RichText::new("Connect to another workspace").size(15.0).color(egui::Color32::LIGHT_GRAY));
                        ui.add_space(20.0);
                        
                        ui.label(egui::RichText::new("Remote Address").size(16.0).color(egui::Color32::WHITE));
                        ui.add_space(8.0);
                        
                        let text_edit = egui::TextEdit::singleline(&mut self.remote_input)
                            .font(egui::TextStyle::Heading)
                            .hint_text("e.g. 192.168.1.15")
                            .desired_width(f32::INFINITY);
                        
                        ui.add(text_edit);
                        
                        ui.add_space(25.0);
                        
                        let btn = egui::Button::new(egui::RichText::new("Connect").size(18.0).color(egui::Color32::WHITE).strong())
                            .fill(egui::Color32::from_rgb(239, 68, 57))
                            .min_size(egui::vec2(140.0, 45.0));
                            
                        if ui.add(btn).clicked() {
                            self.show_toast = true;
                        }
                        
                        if self.show_toast {
                            ui.add_space(25.0);
                            ui.label(egui::RichText::new("Client viewer is available on Android. Windows-to-Windows coming soon!").color(egui::Color32::from_rgb(255, 200, 50)).size(15.0));
                        }
                    });
            });
        });
    }
}
