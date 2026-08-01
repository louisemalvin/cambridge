use std::{cell::RefCell, rc::Rc, time::Duration};

use gtk::prelude::*;
use gtk::{
    gdk, glib, Application, ApplicationWindow, Box as GtkBox, Button, Label, Orientation, Picture,
};
use gtk4 as gtk;

use crate::{cli::Cli, preview::PreviewStore, runtime::ReceiverRuntime};

pub fn run(cli: Cli) {
    let application = Application::builder().application_id("dev.mobilewebcam.receiver").build();
    application.connect_activate(move |application| activate(application, &cli));
    application.run_with_args(&["mobile-webcam-desktop"]);
}

fn activate(application: &Application, cli: &Cli) {
    let (runtime, preview_store) = match ReceiverRuntime::start(cli) {
        Ok(result) => result,
        Err(error) => {
            show_startup_error(application, &error.to_string());
            return;
        }
    };
    build_window(application, cli, runtime, preview_store);
}

fn build_window(
    application: &Application,
    cli: &Cli,
    runtime: ReceiverRuntime,
    store: PreviewStore,
) {
    let window = ApplicationWindow::builder()
        .application(application)
        .title("Mobile Webcam Receiver")
        .default_width(1100)
        .default_height(760)
        .build();
    let root = GtkBox::new(Orientation::Vertical, 12);
    root.set_margin_top(16);
    root.set_margin_bottom(16);
    root.set_margin_start(16);
    root.set_margin_end(16);

    let heading = Label::new(Some("Mobile Webcam Receiver"));
    heading.set_xalign(0.0);
    heading.add_css_class("title-1");
    root.append(&heading);

    let instructions = Label::new(Some(&format!(
        "On the phone, enter this computer's LAN IP and control port {}. OBS can use the installed v4l2loopback camera after a stream starts.",
        cli.control_port
    )));
    instructions.set_wrap(true);
    instructions.set_xalign(0.0);
    root.append(&instructions);

    let status = Label::new(Some(&format!(
        "Ready. Control API: http://{}:{} - waiting for phone video",
        display_listen_address(cli),
        cli.control_port
    )));
    status.set_xalign(0.0);
    root.append(&status);

    let picture = Picture::new();
    picture.set_hexpand(true);
    picture.set_vexpand(true);
    picture.set_can_shrink(true);
    picture.set_content_fit(gtk::ContentFit::Contain);
    root.append(&picture);

    let stop_button = Button::with_label("Close receiver");
    stop_button.set_halign(gtk::Align::End);
    root.append(&stop_button);
    window.set_child(Some(&root));

    let runtime = Rc::new(RefCell::new(Some(runtime)));
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

    glib::timeout_add_local(Duration::from_millis(33), move || {
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

fn show_startup_error(application: &Application, error: &str) {
    let window = ApplicationWindow::builder()
        .application(application)
        .title("Mobile Webcam Receiver")
        .default_width(720)
        .default_height(240)
        .build();
    let root = GtkBox::new(Orientation::Vertical, 12);
    root.set_margin_top(20);
    root.set_margin_bottom(20);
    root.set_margin_start(20);
    root.set_margin_end(20);
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

fn display_listen_address(cli: &Cli) -> String {
    match cli.listen {
        std::net::IpAddr::V4(address) if address.is_unspecified() => "<computer LAN IP>".to_owned(),
        std::net::IpAddr::V6(address) if address.is_unspecified() => {
            "<computer LAN IPv6>".to_owned()
        }
        address => address.to_string(),
    }
}
