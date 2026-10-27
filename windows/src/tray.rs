use anyhow::Result;
use tracing::{error, info};
use tray_icon::{
    menu::{Menu, MenuItem},
    TrayIcon, TrayIconBuilder,
};

pub struct HandeskTray {
    pub tray_icon: TrayIcon,
    pub open_id: tray_icon::menu::MenuId,
    pub copy_id: tray_icon::menu::MenuId,
    pub exit_id: tray_icon::menu::MenuId,
}

pub fn create_tray() -> Result<HandeskTray> {
    let icon_bytes = include_bytes!("tray-icon.ico");
    let image = image::load_from_memory(icon_bytes)
        .map_err(|e| anyhow::anyhow!("Failed to parse embedded tray icon: {:?}", e))?
        .into_rgba8();
    let (width, height) = image.dimensions();
    let icon = tray_icon::Icon::from_rgba(image.into_raw(), width, height)
        .map_err(|e| anyhow::anyhow!("Failed to create tray icon from rgba: {:?}", e))?;

    let tray_menu = Menu::new();
    let open_i = MenuItem::new("Open Handesk", true, None);
    let copy_i = MenuItem::new("Copy Local Address", true, None);
    let exit_i = MenuItem::new("Exit Handesk", true, None);

    let open_id = open_i.id().clone();
    let copy_id = copy_i.id().clone();
    let exit_id = exit_i.id().clone();

    tray_menu.append_items(&[&open_i, &copy_i, &exit_i])
        .map_err(|e| anyhow::anyhow!("Failed to append tray menu items: {:?}", e))?;

    let builder = TrayIconBuilder::new()
        .with_id("handesk")
        .with_menu(Box::new(tray_menu))
        .with_tooltip("Handesk Remote Desktop (Ready)")
        .with_icon(icon)
        .with_menu_on_left_click(false);

    let tray_icon = builder.build()
        .map_err(|e| anyhow::anyhow!("Failed to build TrayIcon: {:?}", e))?;

    info!("Handesk System Tray icon successfully initialized (RustDesk style)!");

    Ok(HandeskTray {
        tray_icon,
        open_id,
        copy_id,
        exit_id,
    })
}
