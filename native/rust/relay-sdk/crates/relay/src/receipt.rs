//! Optional initialization receipt on the already verified target connection.
//! A receipt is a target self-report, not proof of continued liveness or visible effects.
use crate::protocol::{MAGIC, MAX_FRAME, Offer, WIRE_VERSION, invalid};
use std::io;
use std::os::fd::AsRawFd;

pub const TARGET_REQUEST_RECEIPT: u16 = 5;
pub const OFFER_RECEIPT: u16 = 6;
pub const LOAD_RECEIPT: u16 = 7;
const BODY_LEN: usize = 44;

#[derive(Debug, PartialEq, Eq)]
pub struct ReceiptOffer {
    pub ticket: [u8; 16],
    pub offer: Offer,
}

impl ReceiptOffer {
    pub fn new(offer: Offer) -> io::Result<Self> {
        let mut ticket = [0u8; 16];
        let count = unsafe {
            libc::getrandom(
                ticket.as_mut_ptr().cast(),
                ticket.len(),
                libc::GRND_NONBLOCK,
            )
        };
        if count < 0 {
            return Err(io::Error::last_os_error());
        }
        if count != ticket.len() as isize || ticket == [0; 16] {
            return Err(invalid("receipt ticket unavailable"));
        }
        let value = Self { ticket, offer };
        value.encode()?;
        Ok(value)
    }

    pub fn encode(&self) -> io::Result<Vec<u8>> {
        let body = self.offer.encode()?;
        if self.ticket == [0; 16] || body.len() > MAX_FRAME - 16 {
            return Err(invalid("invalid receipt offer"));
        }
        let mut bytes = self.ticket.to_vec();
        bytes.extend_from_slice(&body);
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> io::Result<Self> {
        if !(32..=MAX_FRAME).contains(&bytes.len()) {
            return Err(invalid("invalid receipt offer size"));
        }
        let ticket = bytes[..16].try_into().unwrap();
        if ticket == [0; 16] {
            return Err(invalid("empty receipt ticket"));
        }
        Ok(Self {
            ticket,
            offer: Offer::decode(&bytes[16..])?,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LoadOutcome {
    Initialized,
    Failed,
}

#[derive(Debug, PartialEq, Eq)]
pub struct LoadReceipt {
    pub ticket: [u8; 16],
    pub payload_revision: u64,
    pub config_revision: u64,
    pub flags: u64,
    pub outcome: LoadOutcome,
}

impl LoadReceipt {
    pub fn encode(&self) -> io::Result<[u8; BODY_LEN]> {
        if self.ticket == [0; 16]
            || self.payload_revision == 0
            || self.config_revision == 0
            || (self.outcome == LoadOutcome::Failed && self.flags != 0)
        {
            return Err(invalid("invalid load receipt"));
        }
        let mut bytes = [0; BODY_LEN];
        bytes[..16].copy_from_slice(&self.ticket);
        bytes[16..24].copy_from_slice(&self.payload_revision.to_le_bytes());
        bytes[24..32].copy_from_slice(&self.config_revision.to_le_bytes());
        bytes[32..40].copy_from_slice(&self.flags.to_le_bytes());
        let status: u32 = match self.outcome {
            LoadOutcome::Initialized => 1,
            LoadOutcome::Failed => 2,
        };
        bytes[40..44].copy_from_slice(&status.to_le_bytes());
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> io::Result<Self> {
        if bytes.len() != BODY_LEN {
            return Err(invalid("invalid load receipt size"));
        }
        let value = Self {
            ticket: bytes[..16].try_into().unwrap(),
            payload_revision: u64::from_le_bytes(bytes[16..24].try_into().unwrap()),
            config_revision: u64::from_le_bytes(bytes[24..32].try_into().unwrap()),
            flags: u64::from_le_bytes(bytes[32..40].try_into().unwrap()),
            outcome: match u32::from_le_bytes(bytes[40..44].try_into().unwrap()) {
                1 => LoadOutcome::Initialized,
                2 => LoadOutcome::Failed,
                _ => return Err(invalid("unknown load outcome")),
            },
        };
        value.encode()?;
        Ok(value)
    }

    pub fn matches(&self, expected: &ReceiptOffer) -> bool {
        self.ticket == expected.ticket
            && self.payload_revision == expected.offer.payload_revision
            && self.config_revision == expected.offer.config_revision
    }

    /// One best-effort send from the target, including on EINTR/partial writes. No wait/retry.
    pub fn send_once(&self, socket: &impl AsRawFd) -> io::Result<()> {
        let body = self.encode()?;
        let mut bytes = [0u8; 12 + BODY_LEN];
        bytes[..4].copy_from_slice(&MAGIC);
        bytes[4..6].copy_from_slice(&WIRE_VERSION.to_le_bytes());
        bytes[6..8].copy_from_slice(&LOAD_RECEIPT.to_le_bytes());
        bytes[8..12].copy_from_slice(&(BODY_LEN as u32).to_le_bytes());
        bytes[12..].copy_from_slice(&body);
        let count = unsafe {
            libc::send(
                socket.as_raw_fd(),
                bytes.as_ptr().cast(),
                bytes.len(),
                libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
            )
        };
        if count < 0 {
            return Err(io::Error::last_os_error());
        }
        if count != bytes.len() as isize {
            return Err(invalid("incomplete receipt send"));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc::Channel;
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    #[test]
    fn tickets_and_receipts_reject_stale_and_malformed_data() {
        let offer = || Offer {
            payload_revision: 2,
            config_revision: 3,
            config: vec![1, 1],
        };
        let a = ReceiptOffer::new(offer()).unwrap();
        let b = ReceiptOffer::new(offer()).unwrap();
        assert_ne!(a.ticket, b.ticket);
        assert_eq!(ReceiptOffer::decode(&a.encode().unwrap()).unwrap(), a);
        let mut receipt = LoadReceipt {
            ticket: a.ticket,
            payload_revision: 2,
            config_revision: 3,
            flags: 1,
            outcome: LoadOutcome::Initialized,
        };
        assert!(receipt.matches(&a));
        assert!(!receipt.matches(&b));
        receipt.config_revision = 4;
        assert!(!receipt.matches(&a));
        let bytes = receipt.encode().unwrap();
        for n in 0..bytes.len() {
            assert!(LoadReceipt::decode(&bytes[..n]).is_err());
        }
        assert_eq!(LoadReceipt::decode(&bytes).unwrap(), receipt);
        let mut invalid = bytes;
        invalid[40] = 9;
        assert!(LoadReceipt::decode(&invalid).is_err());
        receipt.outcome = LoadOutcome::Failed;
        assert!(receipt.encode().is_err());
    }

    #[test]
    fn target_sends_one_frame_and_closed_peer_does_not_signal() {
        let (sender, receiver) = UnixStream::pair().unwrap();
        let receipt = LoadReceipt {
            ticket: [1; 16],
            payload_revision: 2,
            config_revision: 3,
            flags: 0,
            outcome: LoadOutcome::Failed,
        };
        receipt.send_once(&sender).unwrap();
        let receiver = Channel::new(receiver, Duration::from_secs(1));
        let (kind, bytes) = receiver.recv_frame().unwrap();
        assert_eq!(kind, LOAD_RECEIPT);
        assert_eq!(LoadReceipt::decode(&bytes).unwrap(), receipt);
        drop(receiver);
        assert!(receipt.send_once(&sender).is_err());
    }
}
