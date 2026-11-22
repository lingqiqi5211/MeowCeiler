#[cfg(any(target_os = "android", test))]
mod identity;
#[cfg(target_os = "android")]
mod selinux;

use native_relay::protocol::Target;
use std::io;
use std::os::fd::OwnedFd;
use std::path::Path;

/// The caller grants the named, currently installed package. Only user 0 and unique
/// application UIDs are supported; this is not a persistent signing-key grant.
pub fn prepare_for_target(
    connection: &OwnedFd,
    grant: &Target,
    path: &Path,
) -> io::Result<OwnedFd> {
    #[cfg(target_os = "android")]
    {
        let target = identity::VerifiedTarget::resolve(connection, grant)?;
        let payload = native_relay::ipc::sealed_payload(path)?;
        selinux::label_payload(&payload, target.context())?;
        target.revalidate(connection, grant)?;
        native_relay::ipc::validate_payload(&payload)?;
        Ok(payload)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (connection, grant, path);
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "target labeling requires Android",
        ))
    }
}
