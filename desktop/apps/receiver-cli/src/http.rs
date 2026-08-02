use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpStream, ToSocketAddrs},
    time::Duration,
};

use anyhow::{bail, Context, Result};
use receiver_core::ReceiverDiagnostics;
use serde::de::DeserializeOwned;

const HTTP_SCHEME: &str = "http://";
const DEFAULT_HTTP_PORT: u16 = 80;
const HTTP_TIMEOUT: Duration = Duration::from_secs(5);
const RESPONSE_HEADER_SEPARATOR: &[u8] = b"\r\n\r\n";

pub fn get_diagnostics(base_url: &str, session_id: &str) -> Result<ReceiverDiagnostics> {
    if session_id.is_empty()
        || session_id.contains('/')
        || session_id.chars().any(char::is_whitespace)
    {
        bail!("session ID contains an invalid path character");
    }
    let endpoint = HttpEndpoint::parse(base_url)?;
    let path = format!("/v1/sessions/{session_id}/diagnostics");
    get_json(&endpoint, &path)
}

#[derive(Debug)]
struct HttpEndpoint {
    host: String,
    port: u16,
}

impl HttpEndpoint {
    fn parse(base_url: &str) -> Result<Self> {
        let authority = base_url
            .strip_prefix(HTTP_SCHEME)
            .with_context(|| format!("receiver URL must start with {HTTP_SCHEME}"))?
            .trim_end_matches('/');
        if authority.is_empty() || authority.contains('/') {
            bail!("receiver URL must contain only an HTTP authority");
        }
        if let Some(address) = authority.strip_prefix('[') {
            let (host, port) = address
                .split_once(']')
                .with_context(|| "IPv6 receiver URL is missing a closing bracket")?;
            let port = port
                .strip_prefix(':')
                .map(parse_port)
                .transpose()
                .context("invalid receiver URL port")?
                .unwrap_or(DEFAULT_HTTP_PORT);
            return Ok(Self { host: host.to_owned(), port });
        }
        let (host, port) = if let Some((host, port)) = authority.rsplit_once(':') {
            (host, parse_port(port)?)
        } else {
            (authority, DEFAULT_HTTP_PORT)
        };
        if host.is_empty() {
            bail!("receiver URL host is empty");
        }
        if host.contains(':') {
            bail!("IPv6 receiver URLs must use brackets");
        }
        Ok(Self { host: host.to_owned(), port })
    }

    fn socket_addresses(&self) -> Result<impl Iterator<Item = SocketAddr>> {
        let authority = if self.host.contains(':') {
            format!("[{}]:{}", self.host, self.port)
        } else {
            format!("{}:{}", self.host, self.port)
        };
        authority.to_socket_addrs().context("resolve receiver URL")
    }
}

fn parse_port(port: &str) -> Result<u16> {
    port.parse().context("receiver URL port is not a valid number")
}

fn get_json<T: DeserializeOwned>(endpoint: &HttpEndpoint, path: &str) -> Result<T> {
    let address = endpoint
        .socket_addresses()?
        .next()
        .context("receiver URL did not resolve to an address")?;
    let mut stream = TcpStream::connect_timeout(&address, HTTP_TIMEOUT)
        .with_context(|| format!("connect to receiver at {address}"))?;
    stream.set_read_timeout(Some(HTTP_TIMEOUT)).context("configure receiver response timeout")?;
    stream.set_write_timeout(Some(HTTP_TIMEOUT)).context("configure receiver request timeout")?;
    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\nAccept: application/json\r\n\r\n",
        endpoint.host
    );
    stream.write_all(request.as_bytes()).context("send diagnostics request")?;
    let mut response = Vec::new();
    stream.read_to_end(&mut response).context("read diagnostics response")?;
    let separator_index = response
        .windows(RESPONSE_HEADER_SEPARATOR.len())
        .position(|window| window == RESPONSE_HEADER_SEPARATOR)
        .context("receiver response did not contain an HTTP header separator")?;
    let (headers, body_with_separator) = response.split_at(separator_index);
    let body = &body_with_separator[RESPONSE_HEADER_SEPARATOR.len()..];
    let status = headers
        .split(|byte| *byte == b'\n')
        .next()
        .and_then(|line| line.split(|byte| *byte == b' ').nth(1))
        .and_then(|code| std::str::from_utf8(code).ok())
        .and_then(|code| code.trim().parse::<u16>().ok())
        .context("receiver response did not contain an HTTP status")?;
    if !(200..300).contains(&status) {
        let body = String::from_utf8_lossy(body);
        bail!("receiver diagnostics request returned HTTP {status}: {body}");
    }
    serde_json::from_slice(body).context("decode receiver diagnostics JSON")
}
