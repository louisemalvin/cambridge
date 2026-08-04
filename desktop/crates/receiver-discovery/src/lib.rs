use std::{ffi::OsString, thread, thread::JoinHandle};

use mdns_sd::{DaemonEvent, ServiceDaemon, ServiceInfo};
use receiver_protocol::V2_PROTOCOL_VERSION;
use thiserror::Error;
use tracing::{debug, warn};

pub const SERVICE_TYPE: &str = "_mobile-webcam._tcp.local.";
pub const PROTOCOL_VERSION: receiver_protocol::ProtocolVersion = V2_PROTOCOL_VERSION;
pub const TXT_PROTOCOL_VERSION: &str = "version";
pub const TXT_DISPLAY_NAME: &str = "name";
pub const TXT_AUTHENTICATION: &str = "auth";
pub const AUTHENTICATION_REQUIRED: &str = "required";
pub const AUTHENTICATION_NONE: &str = "none";
pub const DEFAULT_HOST_LABEL: &str = "mobile-webcam";
pub const LOCAL_DOMAIN_SUFFIX: &str = ".local.";

const MONITOR_THREAD_NAME: &str = "mobile-webcam-mdns-monitor";
const SERVICE_NAME_MAX_LENGTH: usize = 63;
const HOST_LABEL_SEPARATOR: char = '-';

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveryConfig {
    pub display_name: String,
    pub control_port: u16,
    pub authentication_required: bool,
}

impl DiscoveryConfig {
    pub fn validate(&self) -> Result<(), DiscoveryError> {
        if self.display_name.trim().is_empty() {
            return Err(DiscoveryError::InvalidConfiguration(
                "receiver display name must not be empty",
            ));
        }
        if self.display_name.len() > SERVICE_NAME_MAX_LENGTH {
            return Err(DiscoveryError::InvalidConfiguration(
                "receiver display name is too long for DNS-SD",
            ));
        }
        if self.control_port == 0 {
            return Err(DiscoveryError::InvalidConfiguration(
                "receiver control port must be assigned",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Error)]
pub enum DiscoveryError {
    #[error("invalid discovery configuration: {0}")]
    InvalidConfiguration(&'static str),
    #[error("could not read the local hostname: {0}")]
    Hostname(#[source] std::io::Error),
    #[error("could not create the mDNS daemon: {0}")]
    CreateDaemon(mdns_sd::Error),
    #[error("could not create the mDNS service: {0}")]
    CreateService(mdns_sd::Error),
    #[error("could not register the mDNS service: {0}")]
    RegisterService(mdns_sd::Error),
    #[error("could not monitor the mDNS daemon: {0}")]
    Monitor(mdns_sd::Error),
    #[error("could not start the mDNS monitor thread: {0}")]
    MonitorThread(#[source] std::io::Error),
}

pub struct ReceiverDiscoveryPublisher {
    daemon: ServiceDaemon,
    monitor_thread: Option<JoinHandle<()>>,
}

impl ReceiverDiscoveryPublisher {
    pub fn start(config: &DiscoveryConfig) -> Result<Self, DiscoveryError> {
        config.validate()?;
        let host_name = local_host_name()?;
        let service_info = build_service_info(config, &host_name)?;
        let daemon = ServiceDaemon::new().map_err(DiscoveryError::CreateDaemon)?;
        if let Err(error) = daemon.register(service_info) {
            let _ = daemon.shutdown();
            return Err(DiscoveryError::RegisterService(error));
        }

        let monitor = daemon.monitor().map_err(|error| {
            let _ = daemon.shutdown();
            DiscoveryError::Monitor(error)
        })?;
        let monitor_thread = thread::Builder::new()
            .name(MONITOR_THREAD_NAME.to_owned())
            .spawn(move || {
                while let Ok(event) = monitor.recv() {
                    match event {
                        DaemonEvent::Error(error) => warn!(%error, "mDNS daemon error"),
                        DaemonEvent::NameChange(change) => debug!(
                            original = %change.original,
                            new_name = %change.new_name,
                            "mDNS service name changed after a conflict",
                        ),
                        _ => {}
                    }
                }
            })
            .map_err(|error| {
                let _ = daemon.shutdown();
                DiscoveryError::MonitorThread(error)
            })?;

        Ok(Self { daemon, monitor_thread: Some(monitor_thread) })
    }
}

impl Drop for ReceiverDiscoveryPublisher {
    fn drop(&mut self) {
        let _ = self.daemon.shutdown();
        if let Some(thread) = self.monitor_thread.take() {
            let _ = thread.join();
        }
    }
}

fn build_service_info(
    config: &DiscoveryConfig,
    host_name: &str,
) -> Result<ServiceInfo, DiscoveryError> {
    let authentication =
        if config.authentication_required { AUTHENTICATION_REQUIRED } else { AUTHENTICATION_NONE };
    let protocol_version = PROTOCOL_VERSION.to_string();
    let properties = [
        (TXT_PROTOCOL_VERSION, protocol_version.as_str()),
        (TXT_DISPLAY_NAME, config.display_name.as_str()),
        (TXT_AUTHENTICATION, authentication),
    ];
    ServiceInfo::new(
        SERVICE_TYPE,
        &config.display_name,
        host_name,
        "",
        config.control_port,
        &properties[..],
    )
    .map(ServiceInfo::enable_addr_auto)
    .map_err(DiscoveryError::CreateService)
}

fn local_host_name() -> Result<String, DiscoveryError> {
    let raw_hostname: OsString = hostname::get().map_err(DiscoveryError::Hostname)?;
    let raw_hostname = raw_hostname.to_string_lossy();
    let label = raw_hostname
        .trim_end_matches(LOCAL_DOMAIN_SUFFIX.trim_end_matches('.'))
        .split('.')
        .next()
        .map(sanitize_host_label)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| DEFAULT_HOST_LABEL.to_owned());
    Ok(format!("{label}{LOCAL_DOMAIN_SUFFIX}"))
}

fn sanitize_host_label(value: &str) -> String {
    let sanitized: String = value
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || character == HOST_LABEL_SEPARATOR {
                character.to_ascii_lowercase()
            } else {
                HOST_LABEL_SEPARATOR
            }
        })
        .collect();
    sanitized.trim_matches(HOST_LABEL_SEPARATOR).chars().take(SERVICE_NAME_MAX_LENGTH).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn service_info_contains_discovery_contract() {
        let config = DiscoveryConfig {
            display_name: "Office Receiver".to_owned(),
            control_port: 5001,
            authentication_required: true,
        };
        let service = build_service_info(&config, "receiver.local.").unwrap();
        let protocol_version = PROTOCOL_VERSION.to_string();

        assert_eq!(service.get_type(), SERVICE_TYPE);
        assert_eq!(service.get_port(), config.control_port);
        assert_eq!(
            service.get_property_val_str(TXT_PROTOCOL_VERSION),
            Some(protocol_version.as_str())
        );
        assert_eq!(service.get_property_val_str(TXT_DISPLAY_NAME), Some("Office Receiver"));
        assert_eq!(service.get_property_val_str(TXT_AUTHENTICATION), Some(AUTHENTICATION_REQUIRED));
    }

    #[test]
    fn host_labels_are_safe_and_bounded() {
        assert_eq!(sanitize_host_label("Office PC"), "office-pc");
        assert_eq!(sanitize_host_label("---"), "");
        assert!(
            sanitize_host_label(&"a".repeat(SERVICE_NAME_MAX_LENGTH + 1)).len()
                <= SERVICE_NAME_MAX_LENGTH
        );
    }

    #[test]
    fn invalid_configuration_is_rejected_before_network_setup() {
        let config = DiscoveryConfig {
            display_name: " ".to_owned(),
            control_port: 5001,
            authentication_required: false,
        };
        assert!(matches!(config.validate(), Err(DiscoveryError::InvalidConfiguration(_))));
    }

    #[test]
    fn oversized_display_names_are_rejected() {
        let config = DiscoveryConfig {
            display_name: "a".repeat(SERVICE_NAME_MAX_LENGTH + 1),
            control_port: 5001,
            authentication_required: false,
        };

        assert!(matches!(config.validate(), Err(DiscoveryError::InvalidConfiguration(_))));
    }
}
