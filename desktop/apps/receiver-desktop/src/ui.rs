use std::{cell::RefCell, rc::Rc, time::Duration};

use gtk::prelude::*;
use gtk::{
    gdk, glib, Application, ApplicationWindow, Box as GtkBox, Button, ComboBoxText, Label,
    Orientation, Picture,
};
use gtk4 as gtk;

use crate::{
    cli::Cli,
    discovery::{ConnectionState, DiscoveryHandle, DiscoverySnapshot},
    preview::PreviewStore,
    runtime::ReceiverRuntime,
};

const MAIN_WINDOW_WIDTH: i32 = 1_100;
const MAIN_WINDOW_HEIGHT: i32 = 760;
const STARTUP_WINDOW_WIDTH: i32 = 720;
const STARTUP_WINDOW_HEIGHT: i32 = 240;
const WINDOW_CONTENT_SPACING: i32 = 12;
const MAIN_WINDOW_MARGIN: i32 = 16;
const STARTUP_WINDOW_MARGIN: i32 = 20;
const UI_REFRESH_INTERVAL_MILLIS: u64 = 33;
const SHOW_PREVIEW_LABEL: &str = "Show preview";
const HIDE_PREVIEW_LABEL: &str = "Hide preview";

pub fn run(cli: Cli) {
    let application = Application::builder().application_id("dev.mobilewebcam.receiver").build();
    application.connect_activate(move |application| activate(application, &cli));
    application.run_with_args(&["mobile-webcam-desktop"]);
}

fn activate(application: &Application, cli: &Cli) {
    let (runtime, preview_store, discovery) = match ReceiverRuntime::start(cli) {
        Ok(result) => result,
        Err(error) => {
            show_startup_error(application, &error.to_string());
            return;
        }
    };
    build_window(application, runtime, preview_store, discovery);
}

#[allow(clippy::too_many_lines)]
fn build_window(
    application: &Application,
    runtime: ReceiverRuntime,
    store: PreviewStore,
    discovery: DiscoveryHandle,
) {
    let window = ApplicationWindow::builder()
        .application(application)
        .title("Mobile Webcam Receiver")
        .default_width(MAIN_WINDOW_WIDTH)
        .default_height(MAIN_WINDOW_HEIGHT)
        .build();
    let root = GtkBox::new(Orientation::Vertical, WINDOW_CONTENT_SPACING);
    root.set_margin_top(MAIN_WINDOW_MARGIN);
    root.set_margin_bottom(MAIN_WINDOW_MARGIN);
    root.set_margin_start(MAIN_WINDOW_MARGIN);
    root.set_margin_end(MAIN_WINDOW_MARGIN);

    let heading = Label::new(Some("Mobile Webcam Receiver"));
    heading.set_xalign(0.0);
    heading.add_css_class("title-1");
    root.append(&heading);

    let instructions = Label::new(Some(
        "Open Mobile Webcam on your phone. This receiver finds it automatically on the local network. Approve this computer on the phone the first time.",
    ));
    instructions.set_wrap(true);
    instructions.set_xalign(0.0);
    root.append(&instructions);

    let status = Label::new(Some("Searching for phones on the local network"));
    status.set_xalign(0.0);
    root.append(&status);

    let phone_selector = ComboBoxText::new();
    phone_selector.set_visible(false);
    root.append(&phone_selector);

    let attach_button = Button::with_label("Use selected phone");
    attach_button.set_halign(gtk::Align::Start);
    attach_button.set_visible(false);
    root.append(&attach_button);

    let preview_button = Button::with_label(SHOW_PREVIEW_LABEL);
    preview_button.set_halign(gtk::Align::Start);
    root.append(&preview_button);

    let picture = Picture::new();
    picture.set_hexpand(true);
    picture.set_vexpand(true);
    picture.set_can_shrink(true);
    picture.set_content_fit(gtk::ContentFit::Contain);
    picture.set_visible(false);
    root.append(&picture);

    let stop_button = Button::with_label("Close receiver");
    stop_button.set_halign(gtk::Align::End);
    root.append(&stop_button);
    window.set_child(Some(&root));

    let runtime = Rc::new(RefCell::new(Some(runtime)));
    {
        let discovery = discovery.clone();
        let phone_selector = phone_selector.clone();
        attach_button.connect_clicked(move |_| {
            if let Some(sender_id) = phone_selector.active_id() {
                discovery.attach(sender_id.as_str());
            }
        });
    }
    {
        let picture = picture.clone();
        let discovery = discovery.clone();
        preview_button.connect_clicked(move |button| {
            let show_preview = !picture.is_visible();
            picture.set_visible(show_preview);
            discovery.set_preview_demand(show_preview);
            button.set_label(if show_preview { HIDE_PREVIEW_LABEL } else { SHOW_PREVIEW_LABEL });
        });
    }
    {
        let runtime = runtime.clone();
        let window = window.clone();
        stop_button.connect_clicked(move |_| {
            runtime.borrow_mut().take();
            window.close();
        });
    }
    {
        let runtime = runtime.clone();
        window.connect_close_request(move |_| {
            runtime.borrow_mut().take();
            glib::Propagation::Proceed
        });
    }

    glib::timeout_add_local(Duration::from_millis(UI_REFRESH_INTERVAL_MILLIS), move || {
        for snapshot in discovery.drain() {
            apply_discovery_snapshot(&snapshot, &phone_selector, &attach_button, &status);
        }
        if let Some(frame) = store.take_latest() {
            let bytes = glib::Bytes::from_owned(frame.pixels);
            let texture = gdk::MemoryTexture::new(
                frame.width,
                frame.height,
                gdk::MemoryFormat::R8g8b8a8,
                &bytes,
                frame.stride,
            );
            picture.set_paintable(Some(&texture));
            status.set_text(&format!(
                "Receiving {}x{} video - preview and v4l2loopback output are active",
                frame.width, frame.height
            ));
        }
        glib::ControlFlow::Continue
    });
    window.present();
}

fn apply_discovery_snapshot(
    snapshot: &DiscoverySnapshot,
    phone_selector: &ComboBoxText,
    attach_button: &Button,
    status: &Label,
) {
    phone_selector.remove_all();
    for phone in &snapshot.phones {
        phone_selector.append(Some(&phone.sender_id), &phone.display_name);
    }
    if let Some(selected) = snapshot.selected_sender_id.as_deref() {
        phone_selector.set_active_id(Some(selected));
    } else if !snapshot.phones.is_empty() {
        phone_selector.set_active(Some(0));
    }
    let needs_selection = snapshot.phones.len() > 1;
    phone_selector.set_visible(needs_selection);
    attach_button.set_visible(needs_selection);

    status.set_text(match &snapshot.connection {
        ConnectionState::Searching => "Searching for phones on the local network",
        ConnectionState::PairedStandby(name) => {
            return status.set_text(&format!("Paired with {name} - waiting for webcam use"));
        }
        ConnectionState::WaitingForSelection => "Choose which phone to use",
        ConnectionState::Connecting(name) => {
            return status.set_text(&format!("Webcam requested - starting phone camera on {name}"));
        }
        ConnectionState::WaitingForApproval(name) => {
            return status.set_text(&format!("Approve this computer on {name}"));
        }
        ConnectionState::CameraPermissionRequired(name) => {
            return status.set_text(&format!("Allow camera access on {name}"));
        }
        ConnectionState::Connected(name) => {
            return status.set_text(&format!("Streaming from {name}"));
        }
        ConnectionState::Stopping(name) => return status.set_text(&format!("Stopping {name}")),
        ConnectionState::Error(message) => return status.set_text(message),
    });
}

fn show_startup_error(application: &Application, error: &str) {
    let window = ApplicationWindow::builder()
        .application(application)
        .title("Mobile Webcam Receiver")
        .default_width(STARTUP_WINDOW_WIDTH)
        .default_height(STARTUP_WINDOW_HEIGHT)
        .build();
    let root = GtkBox::new(Orientation::Vertical, WINDOW_CONTENT_SPACING);
    root.set_margin_top(STARTUP_WINDOW_MARGIN);
    root.set_margin_bottom(STARTUP_WINDOW_MARGIN);
    root.set_margin_start(STARTUP_WINDOW_MARGIN);
    root.set_margin_end(STARTUP_WINDOW_MARGIN);
    let label = Label::new(Some(&format!("Receiver could not start:\n\n{error}")));
    label.set_wrap(true);
    label.set_xalign(0.0);
    root.append(&label);
    let close = Button::with_label("Close");
    close.set_halign(gtk::Align::End);
    let window_for_close = window.clone();
    close.connect_clicked(move |_| window_for_close.close());
    root.append(&close);
    window.set_child(Some(&root));
    window.present();
}
