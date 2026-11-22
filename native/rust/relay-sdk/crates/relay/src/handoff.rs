//! Best-effort handoff to a latency-sensitive entry point. Stream fragmentation is a skip,
//! never a reason for the receiver to wait. A tracked handoff permits one later target receipt.
use crate::protocol::{MAX_FRAME, Offer, invalid};
use crate::receipt::ReceiptOffer;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};

const MAGIC: &[u8; 4] = b"NRF1";
const HEADER: usize = 8;

pub fn send_ready(socket: &impl AsRawFd, payload: &impl AsRawFd, offer: &Offer) -> io::Result<()> {
    send_packet(socket, payload, MAGIC, &offer.encode()?)
}

pub fn send_tracked(
    socket: &impl AsRawFd,
    payload: &impl AsRawFd,
    offer: &ReceiptOffer,
) -> io::Result<()> {
    send_packet(socket, payload, b"NRF2", &offer.encode()?)
}

fn send_packet(
    socket: &impl AsRawFd,
    payload: &impl AsRawFd,
    magic: &[u8; 4],
    body: &[u8],
) -> io::Result<()> {
    let mut packet = Vec::with_capacity(HEADER + body.len());
    packet.extend_from_slice(magic);
    packet.extend_from_slice(&(body.len() as u32).to_le_bytes());
    packet.extend_from_slice(body);
    let mut iov = libc::iovec {
        iov_base: packet.as_mut_ptr().cast(),
        iov_len: packet.len(),
    };
    let mut control = [0usize; 8];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
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
            .write_unaligned(payload.as_raw_fd());
    }
    let sent = unsafe {
        libc::sendmsg(
            socket.as_raw_fd(),
            &msg,
            libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
        )
    };
    if sent < 0 {
        return Err(io::Error::last_os_error());
    }
    if sent as usize != packet.len() {
        return Err(invalid("incomplete ready handoff"));
    }
    Ok(())
}

pub fn receive_ready(socket: &impl AsRawFd) -> io::Result<(OwnedFd, Offer)> {
    let (fd, offer, ticket) = receive_delivery(socket)?;
    if ticket.is_some() {
        return Err(invalid("tracked handoff requires receipt support"));
    }
    Ok((fd, offer))
}

pub fn receive_delivery(socket: &impl AsRawFd) -> io::Result<(OwnedFd, Offer, Option<[u8; 16]>)> {
    let mut packet = [0u8; HEADER + MAX_FRAME];
    let mut iov = libc::iovec {
        iov_base: packet.as_mut_ptr().cast(),
        iov_len: packet.len(),
    };
    let mut control = [0usize; 16];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = size_of_val(&control) as _;
    // No poll, timeout, retry or second read, including on EINTR and partial stream delivery.
    let count = unsafe {
        libc::recvmsg(
            socket.as_raw_fd(),
            &mut msg,
            libc::MSG_DONTWAIT | libc::MSG_CMSG_CLOEXEC,
        )
    };
    if count < 0 {
        return Err(io::Error::last_os_error());
    }
    let mut descriptor = None;
    let mut malformed = false;
    let mut first_control = None;
    // Adopt every delivered FD before checking the envelope, including truncated ancillary data.
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            first_control.get_or_insert((
                (*cmsg).cmsg_level,
                (*cmsg).cmsg_type,
                (*cmsg).cmsg_len as usize,
            ));
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let base = libc::CMSG_LEN(0) as usize;
                let len = (*cmsg).cmsg_len as usize;
                if len < base {
                    malformed = true;
                    break;
                }
                malformed |= (len - base) % size_of::<RawFd>() != 0;
                for index in 0..(len - base) / size_of::<RawFd>() {
                    let fd = OwnedFd::from_raw_fd(
                        libc::CMSG_DATA(cmsg)
                            .cast::<RawFd>()
                            .add(index)
                            .read_unaligned(),
                    );
                    if descriptor.is_some() {
                        malformed = true;
                    } else {
                        descriptor = Some(fd);
                    }
                }
            } else if (*cmsg).cmsg_level == libc::SOL_SOCKET
                && (*cmsg).cmsg_type == libc::SCM_CREDENTIALS
            {
                // SO_PASSCRED adds kernel metadata; it does not authorize this payload.
                malformed |= (*cmsg).cmsg_len as usize
                    != libc::CMSG_LEN(size_of::<libc::ucred>() as u32) as usize;
            } else {
                malformed = true;
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    if malformed || msg.msg_flags & (libc::MSG_CTRUNC | libc::MSG_TRUNC) != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "invalid ready descriptors: malformed={malformed} flags={:#x} fd={} first_control={first_control:?}",
                msg.msg_flags,
                descriptor.is_some(),
            ),
        ));
    }
    let fd = descriptor.ok_or_else(|| invalid("missing ready descriptor"))?;
    if count < HEADER as isize || (&packet[..4] != MAGIC && &packet[..4] != b"NRF2") {
        return Err(invalid("invalid ready envelope"));
    }
    let size = u32::from_le_bytes(packet[4..8].try_into().unwrap()) as usize;
    if size > MAX_FRAME || count as usize != HEADER + size {
        return Err(invalid("incomplete ready envelope"));
    }
    let body = &packet[HEADER..HEADER + size];
    if &packet[..4] == b"NRF2" {
        let tracked = ReceiptOffer::decode(body)?;
        Ok((fd, tracked.offer, Some(tracked.ticket)))
    } else {
        Ok((fd, Offer::decode(body)?, None))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::os::unix::net::UnixStream;
    use std::time::{Duration, Instant};

    #[test]
    fn tracked_handoff_preserves_ticket_and_legacy_reader_rejects_it() {
        let (sender, receiver) = UnixStream::pair().unwrap();
        let offer = ReceiptOffer::new(Offer {
            payload_revision: 2,
            config_revision: 3,
            config: vec![1, 0],
        })
        .unwrap();
        let file = File::open("/dev/null").unwrap();
        send_tracked(&sender, &file, &offer).unwrap();
        let (fd, actual, ticket) = receive_delivery(&receiver).unwrap();
        assert_eq!(actual, offer.offer);
        assert_eq!(ticket, Some(offer.ticket));
        assert_ne!(
            unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
        drop(fd);
        let (read_end, write_end) = UnixStream::pair().unwrap();
        send_tracked(&sender, &write_end, &offer).unwrap();
        drop(write_end);
        assert!(receive_ready(&receiver).is_err());
        let mut byte = 0u8;
        assert_eq!(
            unsafe {
                libc::recv(
                    read_end.as_raw_fd(),
                    (&mut byte as *mut u8).cast(),
                    1,
                    libc::MSG_DONTWAIT,
                )
            },
            0
        );
    }

    #[test]
    fn idle_or_partial_peer_never_blocks() {
        let (mut sender, receiver) = UnixStream::pair().unwrap();
        let start = Instant::now();
        assert_eq!(
            receive_ready(&receiver).unwrap_err().kind(),
            io::ErrorKind::WouldBlock
        );
        sender.write_all(b"NRF").unwrap();
        assert!(receive_ready(&receiver).is_err());
        assert!(start.elapsed() < Duration::from_secs(1));
    }

    #[test]
    fn offer_and_fd_are_delivered_together() {
        let (sender, receiver) = UnixStream::pair().unwrap();
        let file = File::open("/dev/null").unwrap();
        let offer = Offer {
            payload_revision: 2,
            config_revision: 3,
            config: vec![1, 0],
        };
        send_ready(&sender, &file, &offer).unwrap();
        drop(sender);
        let (fd, actual) = receive_ready(&receiver).unwrap();
        assert_eq!(actual, offer);
        assert_ne!(
            unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
    }

    #[test]
    fn credentials_enabled_socket_delivers_offer_and_fd() {
        let (sender, receiver) = UnixStream::pair().unwrap();
        let enabled: libc::c_int = 1;
        assert_eq!(
            unsafe {
                libc::setsockopt(
                    receiver.as_raw_fd(),
                    libc::SOL_SOCKET,
                    libc::SO_PASSCRED,
                    (&enabled as *const libc::c_int).cast(),
                    size_of_val(&enabled) as libc::socklen_t,
                )
            },
            0
        );
        let file = File::open("/dev/null").unwrap();
        let offer = Offer {
            payload_revision: 1,
            config_revision: 1,
            config: vec![1, 1],
        };
        send_ready(&sender, &file, &offer).unwrap();
        let (fd, actual) = receive_ready(&receiver).unwrap();
        assert_eq!(actual, offer);
        assert_ne!(
            unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
    }

    #[test]
    fn rejected_handoffs_close_all_received_descriptors() {
        let offer = Offer {
            payload_revision: 1,
            config_revision: 1,
            config: vec![1, 1],
        };
        let body = offer.encode().unwrap();
        let mut valid = Vec::from(*MAGIC);
        valid.extend_from_slice(&(body.len() as u32).to_le_bytes());
        valid.extend_from_slice(&body);
        let mut bad_revision = valid.clone();
        bad_revision[HEADER..HEADER + 8].fill(0);
        for (packet, rights) in [
            (b"NRF".as_slice(), 1),
            (&valid, 2),
            (&valid, 64),
            (&bad_revision, 1),
        ] {
            let (sender, receiver) = UnixStream::pair().unwrap();
            let (peer, payload) = UnixStream::pair().unwrap();
            let mut control = [0usize; 40];
            let mut iov = libc::iovec {
                iov_base: packet.as_ptr().cast_mut().cast(),
                iov_len: packet.len(),
            };
            let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
            msg.msg_iov = &mut iov;
            msg.msg_iovlen = 1;
            msg.msg_control = control.as_mut_ptr().cast();
            msg.msg_controllen =
                unsafe { libc::CMSG_SPACE((rights * size_of::<RawFd>()) as u32) } as _;
            unsafe {
                let cmsg = libc::CMSG_FIRSTHDR(&msg);
                (*cmsg).cmsg_level = libc::SOL_SOCKET;
                (*cmsg).cmsg_type = libc::SCM_RIGHTS;
                (*cmsg).cmsg_len = libc::CMSG_LEN((rights * size_of::<RawFd>()) as u32) as _;
                for index in 0..rights {
                    libc::CMSG_DATA(cmsg)
                        .cast::<RawFd>()
                        .add(index)
                        .write_unaligned(payload.as_raw_fd());
                }
                assert_eq!(
                    libc::sendmsg(sender.as_raw_fd(), &msg, libc::MSG_NOSIGNAL),
                    packet.len() as isize
                );
            }
            drop(payload);
            assert!(receive_ready(&receiver).is_err());
            let mut byte = 0u8;
            // EOF proves that neither adopted nor kernel-truncated duplicates keep the writer alive.
            assert_eq!(
                unsafe {
                    libc::recv(
                        peer.as_raw_fd(),
                        (&mut byte as *mut u8).cast(),
                        1,
                        libc::MSG_DONTWAIT,
                    )
                },
                0
            );
        }
    }

    #[test]
    fn invalid_offer_is_not_published() {
        let (sender, receiver) = UnixStream::pair().unwrap();
        let file = File::open("/dev/null").unwrap();
        let offer = Offer {
            payload_revision: 0,
            config_revision: 1,
            config: vec![],
        };
        assert!(send_ready(&sender, &file, &offer).is_err());
        assert_eq!(
            receive_ready(&receiver).unwrap_err().kind(),
            io::ErrorKind::WouldBlock
        );
    }
}
