use native_relay::ipc::{Channel, PrivateListener, validate_payload};
use native_relay::protocol::{OFFER, REJECTED, REQUEST, Target, invalid};
use std::io;
use std::path::Path;
use std::time::Duration;

fn run() -> io::Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 5 {
        return Err(invalid(
            "usage: relay-once SOCKET PROVIDER_SOCKET PACKAGE PROCESS",
        ));
    }
    let target = Target {
        package: args[3].clone(),
        process: args[4].clone(),
    };
    target.encode()?;
    let budget = Duration::from_secs(5);
    let listener = PrivateListener::bind(Path::new(&args[1]))?;
    println!("ready");
    let client = listener.accept(budget)?;
    // Test harness only: all participants run as one UID. Production requires framework provenance.
    let uid = unsafe { libc::geteuid() };
    client.require_uid(uid)?;
    let (kind, request) = client.recv_frame()?;
    if kind != REQUEST || Target::decode(&request)? != target {
        client.send_frame(REJECTED, b"target mismatch")?;
        return Ok(());
    }
    let provider = match Channel::connect(Path::new(&args[2]), budget) {
        Ok(channel) => channel,
        Err(error) => {
            client.send_frame(REJECTED, b"provider unavailable")?;
            return Err(error);
        }
    };
    provider.require_uid(uid)?;
    provider.send_frame(REQUEST, &request)?;
    let (kind, descriptor) = provider.recv_frame()?;
    if kind != OFFER {
        client.send_frame(REJECTED, b"provider rejected request")?;
        return Err(invalid("provider did not offer payload"));
    }
    let fd = provider.recv_fd()?;
    validate_payload(&fd)?;
    // Descriptor body is opaque to the manager; only the provider and payload interpret configuration.
    client.send_frame(OFFER, &descriptor)?;
    client.send_fd(&fd)?;
    drop(fd);
    println!("delivered; no payload retained");
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("relay: {error}");
        std::process::exit(1);
    }
}
