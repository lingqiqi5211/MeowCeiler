use crate::protocol::{MAGIC, MAX_FRAME, WIRE_VERSION, invalid};
use std::ffi::CString;
use std::fs::{self, File};
use std::io::{self, ErrorKind, Read, Seek, SeekFrom};
use std::mem::{size_of, zeroed};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub const MAX_PAYLOAD: u64 = 64 * 1024 * 1024;
const FD_MARKER: u8 = 0xfd;
const REQUIRED_SEALS: i32 =
    libc::F_SEAL_SEAL | libc::F_SEAL_SHRINK | libc::F_SEAL_GROW | libc::F_SEAL_WRITE;

pub struct Channel {
    stream: UnixStream,
    deadline: Instant,
}

fn wait(fd: RawFd, events: i16, deadline: Instant) -> io::Result<()> {
    loop {
        let remaining = deadline
            .checked_duration_since(Instant::now())
            .ok_or_else(|| io::Error::new(ErrorKind::TimedOut, "IPC deadline exceeded"))?;
        let timeout = remaining
            .as_millis()
            .saturating_add(1)
            .min(i32::MAX as u128) as i32;
        let mut poll = libc::pollfd {
            fd,
            events,
            revents: 0,
        };
        // The pollfd lives for the entire call; there is exactly one entry.
        let result = unsafe { libc::poll(&mut poll, 1, timeout) };
        if result < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if result > 0 {
            if poll.revents & libc::POLLNVAL != 0 {
                return Err(io::Error::new(ErrorKind::InvalidInput, "closed descriptor"));
            }
            return Ok(());
        }
    }
}

impl Channel {
    pub fn new(stream: UnixStream, budget: Duration) -> Self {
        Self {
            stream,
            deadline: Instant::now() + budget,
        }
    }

    /// Adopt an existing connection without resetting its transaction deadline.
    pub fn with_deadline(stream: UnixStream, deadline: Instant) -> Self {
        Self { stream, deadline }
    }

    pub fn connect(path: &Path, budget: Duration) -> io::Result<Self> {
        let deadline = Instant::now() + budget;
        let bytes = path.as_os_str().as_bytes();
        let mut address: libc::sockaddr_un = unsafe { zeroed() };
        let abstract_name = bytes.strip_prefix(b"@");
        let name = abstract_name.unwrap_or(bytes);
        if name.is_empty() || name.len() >= address.sun_path.len() || name.contains(&0) {
            return Err(invalid("invalid Unix socket address"));
        }
        address.sun_family = libc::AF_UNIX as libc::sa_family_t;
        let offset = usize::from(abstract_name.is_some());
        for (dst, src) in address.sun_path[offset..].iter_mut().zip(name) {
            *dst = *src as libc::c_char;
        }
        let raw = unsafe {
            libc::socket(
                libc::AF_UNIX,
                libc::SOCK_STREAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
                0,
            )
        };
        if raw < 0 {
            return Err(io::Error::last_os_error());
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let len = std::mem::offset_of!(libc::sockaddr_un, sun_path) + name.len() + 1;
        let result = unsafe {
            libc::connect(
                fd.as_raw_fd(),
                (&address as *const libc::sockaddr_un).cast(),
                len as libc::socklen_t,
            )
        };
        if result < 0 {
            let error = io::Error::last_os_error();
            if error.raw_os_error() != Some(libc::EINPROGRESS) {
                return Err(error);
            }
            wait(fd.as_raw_fd(), libc::POLLOUT, deadline)?;
            let mut code: libc::c_int = 0;
            let mut size = size_of::<libc::c_int>() as libc::socklen_t;
            if unsafe {
                libc::getsockopt(
                    fd.as_raw_fd(),
                    libc::SOL_SOCKET,
                    libc::SO_ERROR,
                    (&mut code as *mut libc::c_int).cast(),
                    &mut size,
                )
            } < 0
            {
                return Err(io::Error::last_os_error());
            }
            if code != 0 {
                return Err(io::Error::from_raw_os_error(code));
            }
        }
        Ok(Self {
            stream: UnixStream::from(fd),
            deadline,
        })
    }

    pub fn require_uid(&self, expected: u32) -> io::Result<()> {
        let mut cred: libc::ucred = unsafe { zeroed() };
        let mut len = size_of::<libc::ucred>() as libc::socklen_t;
        let result = unsafe {
            libc::getsockopt(
                self.stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_PEERCRED,
                (&mut cred as *mut libc::ucred).cast(),
                &mut len,
            )
        };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        if len as usize != size_of::<libc::ucred>() || cred.uid != expected {
            return Err(io::Error::new(
                ErrorKind::PermissionDenied,
                "peer UID mismatch",
            ));
        }
        Ok(())
    }

    fn transfer(&self, bytes: &mut [u8], send: bool) -> io::Result<()> {
        let mut position = 0;
        while position < bytes.len() {
            wait(
                self.stream.as_raw_fd(),
                if send { libc::POLLOUT } else { libc::POLLIN },
                self.deadline,
            )?;
            let slice = &mut bytes[position..];
            let count = unsafe {
                if send {
                    libc::send(
                        self.stream.as_raw_fd(),
                        slice.as_ptr().cast(),
                        slice.len(),
                        libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
                    )
                } else {
                    libc::recv(
                        self.stream.as_raw_fd(),
                        slice.as_mut_ptr().cast(),
                        slice.len(),
                        libc::MSG_DONTWAIT,
                    )
                }
            };
            if count < 0 {
                let error = io::Error::last_os_error();
                if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) {
                    continue;
                }
                return Err(error);
            }
            if count == 0 {
                return Err(io::Error::new(
                    ErrorKind::UnexpectedEof,
                    "peer closed mid-message",
                ));
            }
            position += count as usize;
        }
        Ok(())
    }

    pub fn send_frame(&self, kind: u16, body: &[u8]) -> io::Result<()> {
        if body.len() > MAX_FRAME {
            return Err(invalid("frame too large"));
        }
        let mut bytes = Vec::with_capacity(12 + body.len());
        bytes.extend_from_slice(&MAGIC);
        bytes.extend_from_slice(&WIRE_VERSION.to_le_bytes());
        bytes.extend_from_slice(&kind.to_le_bytes());
        bytes.extend_from_slice(&(body.len() as u32).to_le_bytes());
        bytes.extend_from_slice(body);
        self.transfer(&mut bytes, true)
    }

    pub fn recv_frame(&self) -> io::Result<(u16, Vec<u8>)> {
        let mut header = [0u8; 12];
        self.transfer(&mut header, false)?;
        if header[..4] != MAGIC
            || u16::from_le_bytes(header[4..6].try_into().unwrap()) != WIRE_VERSION
        {
            return Err(invalid("wire magic or version mismatch"));
        }
        let kind = u16::from_le_bytes(header[6..8].try_into().unwrap());
        let len = u32::from_le_bytes(header[8..12].try_into().unwrap()) as usize;
        if len > MAX_FRAME {
            return Err(invalid("frame too large"));
        }
        let mut bytes = vec![0; len];
        self.transfer(&mut bytes, false)?;
        Ok((kind, bytes))
    }

    pub fn send_fd(&self, fd: &impl AsRawFd) -> io::Result<()> {
        let mut marker = FD_MARKER;
        let mut iov = libc::iovec {
            iov_base: (&mut marker as *mut u8).cast(),
            iov_len: 1,
        };
        let mut control = [0usize; 8];
        let mut msg: libc::msghdr = unsafe { zeroed() };
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = unsafe { libc::CMSG_SPACE(size_of::<RawFd>() as u32) } as _;
        unsafe {
            let cmsg = libc::CMSG_FIRSTHDR(&msg);
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len = libc::CMSG_LEN(size_of::<RawFd>() as u32) as _;
            libc::CMSG_DATA(cmsg)
                .cast::<RawFd>()
                .write_unaligned(fd.as_raw_fd());
        }
        loop {
            wait(self.stream.as_raw_fd(), libc::POLLOUT, self.deadline)?;
            let result = unsafe {
                libc::sendmsg(
                    self.stream.as_raw_fd(),
                    &msg,
                    libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
                )
            };
            if result == 1 {
                return Ok(());
            }
            if result >= 0 {
                return Err(invalid("FD transfer did not send marker"));
            }
            let error = io::Error::last_os_error();
            if !matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) {
                return Err(error);
            }
        }
    }

    pub fn recv_fd(&self) -> io::Result<OwnedFd> {
        loop {
            wait(self.stream.as_raw_fd(), libc::POLLIN, self.deadline)?;
            let mut marker = 0u8;
            let mut iov = libc::iovec {
                iov_base: (&mut marker as *mut u8).cast(),
                iov_len: 1,
            };
            let mut control = [0usize; 16];
            let mut msg: libc::msghdr = unsafe { zeroed() };
            msg.msg_iov = &mut iov;
            msg.msg_iovlen = 1;
            msg.msg_control = control.as_mut_ptr().cast();
            msg.msg_controllen = size_of_val(&control) as _;
            let result = unsafe {
                libc::recvmsg(
                    self.stream.as_raw_fd(),
                    &mut msg,
                    libc::MSG_DONTWAIT | libc::MSG_CMSG_CLOEXEC,
                )
            };
            if result < 0 {
                let error = io::Error::last_os_error();
                if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) {
                    continue;
                }
                return Err(error);
            }
            let mut descriptors = Vec::new();
            let mut unexpected = false;
            // Adopt all returned rights before validating; malformed/truncated messages must not leak FDs.
            unsafe {
                let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
                while !cmsg.is_null() {
                    if (*cmsg).cmsg_level == libc::SOL_SOCKET
                        && (*cmsg).cmsg_type == libc::SCM_RIGHTS
                    {
                        let base = libc::CMSG_LEN(0) as usize;
                        let len = (*cmsg).cmsg_len as usize;
                        if len < base {
                            unexpected = true;
                            break;
                        }
                        let count = (len - base) / size_of::<RawFd>();
                        unexpected |= (len - base) % size_of::<RawFd>() != 0;
                        for index in 0..count {
                            let raw = libc::CMSG_DATA(cmsg)
                                .cast::<RawFd>()
                                .add(index)
                                .read_unaligned();
                            descriptors.push(OwnedFd::from_raw_fd(raw));
                        }
                    } else {
                        unexpected = true;
                    }
                    cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
                }
            }
            if result != 1
                || marker != FD_MARKER
                || unexpected
                || msg.msg_flags & (libc::MSG_CTRUNC | libc::MSG_TRUNC) != 0
                || descriptors.len() != 1
            {
                return Err(invalid("expected exactly one descriptor"));
            }
            return Ok(descriptors.pop().unwrap());
        }
    }
}

pub struct PrivateListener {
    listener: UnixListener,
    filesystem: Option<(PathBuf, u64, u64)>,
}

impl PrivateListener {
    /// `@name` selects the abstract namespace. It has no filesystem permissions;
    /// callers must authenticate every accepted channel with `require_uid`.
    pub fn bind(path: &Path) -> io::Result<Self> {
        if let Some(name) = path.as_os_str().as_bytes().strip_prefix(b"@") {
            if name.is_empty() || name.contains(&0) {
                return Err(invalid("invalid abstract socket name"));
            }
            let listener = UnixListener::bind_addr(&SocketAddr::from_abstract_name(name)?)?;
            listener.set_nonblocking(true)?;
            return Ok(Self {
                listener,
                filesystem: None,
            });
        }
        let parent = path
            .parent()
            .ok_or_else(|| invalid("socket needs parent directory"))?;
        let metadata = fs::symlink_metadata(parent)?;
        if !metadata.is_dir()
            || metadata.uid() != unsafe { libc::geteuid() }
            || metadata.permissions().mode() & 0o077 != 0
        {
            return Err(io::Error::new(
                ErrorKind::PermissionDenied,
                "socket parent must be a private directory owned by this UID",
            ));
        }
        let listener = UnixListener::bind(path)?;
        let metadata = fs::symlink_metadata(path)?;
        let bound = Self {
            listener,
            filesystem: Some((path.to_owned(), metadata.dev(), metadata.ino())),
        };
        bound.listener.set_nonblocking(true)?;
        Ok(bound)
    }

    pub fn accept(&self, budget: Duration) -> io::Result<Channel> {
        let deadline = Instant::now() + budget;
        loop {
            wait(self.listener.as_raw_fd(), libc::POLLIN, deadline)?;
            match self.listener.accept() {
                Ok((stream, _)) => return Ok(Channel { stream, deadline }),
                Err(error)
                    if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::Interrupted) =>
                {
                    continue;
                }
                Err(error) => return Err(error),
            }
        }
    }
}

impl Drop for PrivateListener {
    fn drop(&mut self) {
        if let Some((path, dev, ino)) = &self.filesystem {
            if let Ok(meta) = fs::symlink_metadata(path) {
                if (meta.dev(), meta.ino()) == (*dev, *ino) {
                    let _ = fs::remove_file(path);
                }
            }
        }
    }
}

pub fn sealed_payload(path: &Path) -> io::Result<OwnedFd> {
    let mut source = File::open(path)?;
    let metadata = source.metadata()?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_PAYLOAD {
        return Err(invalid(
            "payload must be a nonempty regular file within size limit",
        ));
    }
    let name = CString::new("native-relay-payload").unwrap();
    let raw =
        unsafe { libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING) };
    if raw < 0 {
        return Err(io::Error::last_os_error());
    }
    let mut file = unsafe { File::from_raw_fd(raw) };
    let copied = io::copy(&mut (&mut source).take(MAX_PAYLOAD + 1), &mut file)?;
    if copied != metadata.len() || copied > MAX_PAYLOAD {
        return Err(invalid("payload changed size during publication"));
    }
    if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_ADD_SEALS, REQUIRED_SEALS) } < 0 {
        return Err(io::Error::last_os_error());
    }
    file.seek(SeekFrom::Start(0))?;
    // The receiver gets a read-only descriptor in addition to immutable backing storage.
    let readonly = File::open(format!("/proc/self/fd/{}", file.as_raw_fd()))?;
    Ok(readonly.into())
}

pub fn validate_payload(fd: &impl AsRawFd) -> io::Result<()> {
    let mut stat: libc::stat = unsafe { zeroed() };
    if unsafe { libc::fstat(fd.as_raw_fd(), &mut stat) } < 0 {
        return Err(io::Error::last_os_error());
    }
    if stat.st_mode & libc::S_IFMT != libc::S_IFREG
        || stat.st_size <= 0
        || stat.st_size as u64 > MAX_PAYLOAD
    {
        return Err(invalid("invalid payload descriptor"));
    }
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    let seals = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GET_SEALS) };
    if flags < 0
        || flags & libc::O_ACCMODE != libc::O_RDONLY
        || seals < 0
        || seals & REQUIRED_SEALS != REQUIRED_SEALS
    {
        return Err(invalid("payload must be read-only and sealed"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn abstract_socket_round_trip_and_release() {
        let name = format!("@native-relay-test-{}", std::process::id());
        let path = Path::new(&name);
        let listener = PrivateListener::bind(path).unwrap();
        assert!(PrivateListener::bind(path).is_err());
        let client = Channel::connect(path, Duration::from_secs(1)).unwrap();
        let server = listener.accept(Duration::from_secs(1)).unwrap();
        server.require_uid(unsafe { libc::geteuid() }).unwrap();
        assert!(server.require_uid(unsafe { libc::geteuid() } ^ 1).is_err());
        client.send_frame(1, b"abstract").unwrap();
        assert_eq!(server.recv_frame().unwrap(), (1, b"abstract".to_vec()));
        drop(client);
        drop(server);
        drop(listener);
        assert!(Channel::connect(path, Duration::from_millis(20)).is_err());
        assert!(PrivateListener::bind(path).is_ok());
        assert!(PrivateListener::bind(Path::new("@")).is_err());
        assert!(Channel::connect(Path::new("@"), Duration::from_secs(1)).is_err());
    }

    #[test]
    fn fd_transfer_sets_cloexec_and_checks_peer() {
        let (a, b) = UnixStream::pair().unwrap();
        let a = Channel::new(a, Duration::from_secs(1));
        let b = Channel::new(b, Duration::from_secs(1));
        b.require_uid(unsafe { libc::geteuid() }).unwrap();
        assert!(b.require_uid(unsafe { libc::geteuid() } ^ 1).is_err());
        let file = File::open("/dev/null").unwrap();
        a.send_frame(7, b"hello").unwrap();
        a.send_fd(&file).unwrap();
        assert_eq!(b.recv_frame().unwrap(), (7, b"hello".to_vec()));
        let fd = b.recv_fd().unwrap();
        assert_ne!(
            unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
        assert!(validate_payload(&fd).is_err());
    }

    #[test]
    fn sealed_payload_survives_sender_closure_and_cannot_change() {
        let path = Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/Cargo.toml"));
        let fd = sealed_payload(path).unwrap();
        let (a, b) = UnixStream::pair().unwrap();
        let a = Channel::new(a, Duration::from_secs(1));
        let b = Channel::new(b, Duration::from_secs(1));
        a.send_fd(&fd).unwrap();
        drop(fd);
        drop(a);
        let received = b.recv_fd().unwrap();
        validate_payload(&received).unwrap();
        assert_eq!(unsafe { libc::ftruncate(received.as_raw_fd(), 0) }, -1);
        assert_eq!(
            unsafe { libc::fcntl(received.as_raw_fd(), libc::F_ADD_SEALS, 0) },
            -1
        );
        let mut file = File::from(received);
        let mut actual = Vec::new();
        file.read_to_end(&mut actual).unwrap();
        assert_eq!(actual, fs::read(path).unwrap());
    }

    #[test]
    fn missing_fd_and_oversize_frame_are_rejected() {
        let (mut a, b) = UnixStream::pair().unwrap();
        a.write_all(&[FD_MARKER]).unwrap();
        assert!(Channel::new(b, Duration::from_secs(1)).recv_fd().is_err());
        let (mut a, b) = UnixStream::pair().unwrap();
        let mut header = Vec::from(MAGIC);
        header.extend_from_slice(&WIRE_VERSION.to_le_bytes());
        header.extend_from_slice(&1u16.to_le_bytes());
        header.extend_from_slice(&((MAX_FRAME + 1) as u32).to_le_bytes());
        a.write_all(&header).unwrap();
        assert!(
            Channel::new(b, Duration::from_secs(1))
                .recv_frame()
                .is_err()
        );
    }

    #[test]
    fn idle_peer_has_a_total_deadline() {
        let (_a, b) = UnixStream::pair().unwrap();
        let start = Instant::now();
        let error = Channel::new(b, Duration::from_millis(20))
            .recv_frame()
            .unwrap_err();
        assert_eq!(error.kind(), ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(1));
    }
}
