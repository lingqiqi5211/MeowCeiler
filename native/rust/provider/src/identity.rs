use native_relay::protocol::{Target, invalid};
use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::{self, Read};
use std::mem::{size_of, zeroed};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;

#[derive(Debug, PartialEq, Eq)]
struct Peer {
    pid: i32,
    uid: u32,
    gid: u32,
}

pub(super) struct VerifiedTarget {
    directory: OwnedFd,
    peer: Peer,
    context: CString,
}

fn denied(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, message)
}

fn peer(connection: &OwnedFd) -> io::Result<Peer> {
    let mut kind: libc::c_int = 0;
    let mut kind_len = size_of::<libc::c_int>() as libc::socklen_t;
    if unsafe {
        libc::getsockopt(
            connection.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_TYPE,
            (&mut kind as *mut libc::c_int).cast(),
            &mut kind_len,
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    if kind_len as usize != size_of::<libc::c_int>() || kind != libc::SOCK_STREAM {
        return Err(denied("target must be a connected stream socket"));
    }
    let mut state = libc::pollfd {
        fd: connection.as_raw_fd(),
        events: libc::POLLRDHUP,
        revents: 0,
    };
    if unsafe { libc::poll(&mut state, 1, 0) } < 0 {
        return Err(io::Error::last_os_error());
    }
    if state.revents & (libc::POLLRDHUP | libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
        return Err(denied("target connection is no longer live"));
    }
    let mut cred: libc::ucred = unsafe { zeroed() };
    let mut len = size_of::<libc::ucred>() as libc::socklen_t;
    if unsafe {
        libc::getsockopt(
            connection.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut cred as *mut libc::ucred).cast(),
            &mut len,
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    if len as usize != size_of::<libc::ucred>() || cred.pid <= 0 {
        return Err(denied("missing target peer credentials"));
    }
    Ok(Peer {
        pid: cred.pid,
        uid: cred.uid,
        gid: cred.gid,
    })
}

fn peer_context(connection: &OwnedFd) -> io::Result<CString> {
    let mut bytes = [0u8; 4096];
    let mut len = bytes.len() as libc::socklen_t;
    if unsafe {
        libc::getsockopt(
            connection.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERSEC,
            bytes.as_mut_ptr().cast(),
            &mut len,
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    let bytes = bytes
        .get(..len as usize)
        .ok_or_else(|| invalid("oversized peer context"))?;
    let bytes = bytes.strip_suffix(&[0]).unwrap_or(bytes);
    if bytes.is_empty() {
        return Err(denied("empty peer context"));
    }
    CString::new(bytes).map_err(|_| invalid("invalid peer context"))
}

fn read_bounded(file: File, limit: u64) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        return Err(invalid("identity record too large"));
    }
    Ok(bytes)
}

fn read_at(directory: &OwnedFd, name: &CStr, limit: u64) -> io::Result<Vec<u8>> {
    // Keep a single proc directory open so a reused PID cannot redirect later reads.
    let fd = unsafe {
        libc::openat(
            directory.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    read_bounded(unsafe { File::from_raw_fd(fd) }, limit)
}

fn installed_package(grant: &Target, uid: u32) -> io::Result<()> {
    let name = c"/data/system/packages.list";
    let fd = unsafe {
        libc::open(
            name.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let file = unsafe { File::from_raw_fd(fd) };
    let metadata = file.metadata()?;
    if !metadata.is_file() || ![0, 1000].contains(&metadata.uid()) || metadata.mode() & 0o022 != 0 {
        return Err(denied("untrusted package registry"));
    }
    let bytes = read_bounded(file, 2 * 1024 * 1024)?;
    let records = std::str::from_utf8(&bytes).map_err(|_| invalid("invalid package registry"))?;
    check_package(records, grant, uid)
}

fn check_package(records: &str, grant: &Target, uid: u32) -> io::Result<()> {
    grant.encode()?;
    if !(10000..20000).contains(&uid) {
        return Err(denied(
            "only user-0 ordinary application UIDs are supported",
        ));
    }
    if grant.process != grant.package
        && !grant
            .process
            .strip_prefix(&grant.package)
            .is_some_and(|s| s.starts_with(':') && s.len() > 1)
    {
        return Err(denied("process is outside package grant"));
    }
    let mut owners = 0;
    let mut matched = 0;
    for line in records.lines().filter(|line| !line.trim().is_empty()) {
        let mut fields = line.split_whitespace();
        let name = fields
            .next()
            .ok_or_else(|| invalid("missing package name"))?;
        let package_uid: u32 = fields
            .next()
            .ok_or_else(|| invalid("missing package UID"))?
            .parse()
            .map_err(|_| invalid("invalid package UID"))?;
        if package_uid == uid {
            owners += 1;
        }
        if name == grant.package {
            if package_uid != uid {
                return Err(denied("package UID mismatch"));
            }
            matched += 1;
        }
    }
    if owners != 1 || matched != 1 {
        return Err(denied("missing, duplicate or shared package UID"));
    }
    Ok(())
}

fn check_process(status: &[u8], cmdline: &[u8], expected: &Peer, grant: &Target) -> io::Result<()> {
    let status = std::str::from_utf8(status).map_err(|_| invalid("invalid process status"))?;
    for (field, value, count) in [
        ("Uid:", expected.uid, 4),
        ("Gid:", expected.gid, 4),
        ("Tgid:", expected.pid as u32, 1),
    ] {
        let mut lines = status.lines().filter_map(|line| line.strip_prefix(field));
        let values = lines
            .next()
            .ok_or_else(|| invalid("missing process identity"))?;
        let numbers: Vec<_> = values.split_whitespace().collect();
        if lines.next().is_some()
            || numbers.len() != count
            || numbers.iter().any(|s| s.parse::<u32>().ok() != Some(value))
        {
            return Err(denied("process credentials changed"));
        }
    }
    let end = cmdline
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| invalid("unterminated process name"))?;
    if cmdline[..end] != *grant.process.as_bytes() {
        return Err(denied("process name mismatch"));
    }
    Ok(())
}

impl VerifiedTarget {
    pub(super) fn resolve(connection: &OwnedFd, grant: &Target) -> io::Result<Self> {
        let peer = peer(connection)?;
        installed_package(grant, peer.uid)?;
        let path = CString::new(format!("/proc/{}", peer.pid)).unwrap();
        let raw = unsafe {
            libc::open(
                path.as_ptr(),
                libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            )
        };
        if raw < 0 {
            return Err(io::Error::last_os_error());
        }
        let target = Self {
            directory: unsafe { OwnedFd::from_raw_fd(raw) },
            peer,
            context: peer_context(connection)?,
        };
        target.revalidate(connection, grant)?;
        Ok(target)
    }

    pub(super) fn context(&self) -> &CStr {
        &self.context
    }

    pub(super) fn revalidate(&self, connection: &OwnedFd, grant: &Target) -> io::Result<()> {
        if peer(connection)? != self.peer || peer_context(connection)? != self.context {
            return Err(denied("target connection identity changed"));
        }
        check_process(
            &read_at(&self.directory, c"status", 65536)?,
            &read_at(&self.directory, c"cmdline", 4096)?,
            &self.peer,
            grant,
        )?;
        let context = read_at(&self.directory, c"attr/current", 4096)?;
        let context = context.strip_suffix(b"\n").unwrap_or(&context);
        let context = context.strip_suffix(&[0]).unwrap_or(context);
        if context != self.context.as_bytes() {
            return Err(denied("process security context changed"));
        }
        installed_package(grant, self.peer.uid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixStream;

    fn grant() -> Target {
        Target {
            package: "example.app".into(),
            process: "example.app:worker".into(),
        }
    }

    #[test]
    fn package_grant_rejects_other_shared_missing_and_reassigned_uids() {
        let good = "example.app 10205 0 /data/user/0/example.app\nother.app 10206 0\n";
        assert!(check_package(good, &grant(), 10205).is_ok());
        for records in [
            "",
            "example.app 10206\n",
            "other.app 10205\n",
            "example.app 10205\nother.app 10205\n",
            "example.app 10205\nexample.app 10205\n",
            "example.app invalid\n",
        ] {
            assert!(
                check_package(records, &grant(), 10205).is_err(),
                "{records}"
            );
        }
        for uid in [0, 1000, 20000, 99000, 110205] {
            assert!(check_package(good, &grant(), uid).is_err());
        }
        let mut wrong = grant();
        wrong.process = "example.application:worker".into();
        assert!(check_package(good, &wrong, 10205).is_err());
    }

    #[test]
    fn process_identity_rejects_changed_ids_names_and_ambiguous_status() {
        let peer = Peer {
            pid: 42,
            uid: 10205,
            gid: 10205,
        };
        let good = "Name:\tworker\nTgid:\t42\nUid:\t10205 10205 10205 10205\nGid:\t10205 10205 10205 10205\n";
        assert!(check_process(good.as_bytes(), b"example.app:worker\0", &peer, &grant()).is_ok());
        for status in [
            good.replace("Tgid:\t42", "Tgid:\t43"),
            good.replace("10205 10205 10205 10205", "10205 0 10205 10205"),
            format!("{good}Uid: 10205 10205 10205 10205\n"),
        ] {
            assert!(
                check_process(status.as_bytes(), b"example.app:worker\0", &peer, &grant()).is_err()
            );
        }
        for cmdline in [
            b"example.app:worker".as_slice(),
            b"other.app\0",
            b"example.app:worker2\0",
        ] {
            assert!(check_process(good.as_bytes(), cmdline, &peer, &grant()).is_err());
        }
    }

    #[test]
    fn forwarded_socket_retains_kernel_peer_credentials() {
        let (socket, other) = UnixStream::pair().unwrap();
        let socket: OwnedFd = socket.into();
        let (sender, receiver) = UnixStream::pair().unwrap();
        let sender = native_relay::ipc::Channel::new(sender, std::time::Duration::from_secs(1));
        let receiver = native_relay::ipc::Channel::new(receiver, std::time::Duration::from_secs(1));
        sender.send_fd(&socket).unwrap();
        let duplicate = receiver.recv_fd().unwrap();
        assert_eq!(peer(&socket).unwrap(), peer(&duplicate).unwrap());
        assert_eq!(peer(&duplicate).unwrap().pid, std::process::id() as i32);
        drop(other);
        assert!(peer(&duplicate).is_err());
        let (datagram, _other) = std::os::unix::net::UnixDatagram::pair().unwrap();
        assert!(peer(&datagram.into()).is_err());
        let file: OwnedFd = File::open("/dev/null").unwrap().into();
        assert!(peer(&file).is_err());
        assert!(
            VerifiedTarget::resolve(&file, &grant())
                .map(|target| target.context().to_owned())
                .is_err()
        );
    }
}
