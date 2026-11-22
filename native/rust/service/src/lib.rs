//! One delivery session for an external worker. No executor, daemon or automatic target restart.
//! IPC shares one deadline; file preparation and cancellation are cooperative between stages.
use native_relay::ipc::{Channel, PrivateListener, sealed_payload};
use native_relay::protocol::{OFFER, Offer, REJECTED, REQUEST, TARGET_REQUEST, Target, invalid};
use native_relay::receipt::{
    LOAD_RECEIPT, LoadOutcome, LoadReceipt, OFFER_RECEIPT, ReceiptOffer, TARGET_REQUEST_RECEIPT,
};
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const MAX_SESSION_BUDGET: Duration = Duration::from_secs(5);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IdentityMode {
    VerifiedTarget,
    /// Explicit opt-in for same-UID standalone harnesses; never a verification fallback.
    UnlabeledTest,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Ready,
    Receiving,
    Preparing,
    SentToRelay,
    AwaitingReceipt,
    Initialized,
    TargetFailed,
    Rejected,
    TimedOut,
    Cancelled,
    Failed,
}

impl Phase {
    fn terminal(self) -> bool {
        !matches!(
            self,
            Self::Ready | Self::Receiving | Self::Preparing | Self::AwaitingReceipt
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Status {
    /// Process-local correlation only, not a persistent grant or process identity.
    pub session_id: u64,
    pub phase: Phase,
    pub payload_revision: u64,
    pub config_revision: u64,
    /// Successful send to relay; cancellation/timeout cannot revoke the transferred FD.
    pub sent_to_relay: bool,
    /// Scoped to this session's verified, retained socket, never used as a reusable PID grant.
    pub target_peer: Option<TargetPeer>,
    pub applied_payload_revision: Option<u64>,
    /// Set only on a matching target initialization receipt, not on FD delivery.
    pub applied_config_revision: Option<u64>,
    pub reported_flags: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TargetPeer {
    pub pid: i32,
    pub uid: u32,
}

fn target_peer(socket: &impl AsRawFd) -> io::Result<TargetPeer> {
    let mut peer: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of_val(&peer) as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut peer as *mut libc::ucred).cast(),
            &mut len,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    if len as usize != std::mem::size_of_val(&peer) || peer.pid <= 0 {
        return Err(invalid("missing receipt peer identity"));
    }
    Ok(TargetPeer {
        pid: peer.pid,
        uid: peer.uid,
    })
}

struct Shared {
    status: Mutex<Status>,
    cancelled: AtomicBool,
}

#[derive(Clone)]
pub struct SessionHandle(Arc<Shared>);

impl SessionHandle {
    pub fn status(&self) -> Status {
        *self.0.status.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Requests cancellation at the next stage boundary. Does not wake an IPC wait,
    /// interrupt file I/O, or recall an FD already sent. Final status is authoritative.
    pub fn cancel(&self) -> bool {
        if self.status().phase.terminal() {
            return false;
        }
        self.0.cancelled.store(true, Ordering::Release);
        true
    }

    fn set_phase(&self, phase: Phase) {
        self.0
            .status
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .phase = phase;
    }
}

pub struct DeliveryPlan {
    target: Target,
    payload: PathBuf,
    offer: Offer,
}

impl DeliveryPlan {
    /// Takes an immutable snapshot. Config bytes belong to the payload, not NativeRelay.
    pub fn new(target: Target, payload: PathBuf, offer: Offer) -> io::Result<Self> {
        target.encode()?;
        offer.encode()?;
        Ok(Self {
            target,
            payload,
            offer,
        })
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    SentToRelay,
    Initialized { flags: u64 },
    TargetFailed,
    Rejected,
}

pub struct DeliverySession {
    listener: Option<PrivateListener>,
    manager: Option<Channel>,
    plan: DeliveryPlan,
    mode: IdentityMode,
    deadline: Instant,
    handle: SessionHandle,
}

impl DeliverySession {
    /// The endpoint is exclusive until this session exits or is dropped. A caller should
    /// retain only the handle for status/cancellation, and move the session to its worker.
    pub fn bind(
        endpoint: &Path,
        plan: DeliveryPlan,
        mode: IdentityMode,
        budget: Duration,
    ) -> io::Result<Self> {
        if budget.is_zero() || budget > MAX_SESSION_BUDGET {
            return Err(invalid("delivery budget must be within 0..=5 seconds"));
        }
        if mode == IdentityMode::VerifiedTarget
            && (!cfg!(target_os = "android") || unsafe { libc::geteuid() } != 0)
        {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "verified delivery requires an Android root worker",
            ));
        }
        let deadline = Instant::now() + budget;
        let listener = PrivateListener::bind(endpoint)?;
        let handle = SessionHandle(Arc::new(Shared {
            status: Mutex::new(Status {
                session_id: NEXT_ID.fetch_add(1, Ordering::Relaxed),
                phase: Phase::Ready,
                payload_revision: plan.offer.payload_revision,
                config_revision: plan.offer.config_revision,
                sent_to_relay: false,
                target_peer: None,
                applied_payload_revision: None,
                applied_config_revision: None,
                reported_flags: None,
            }),
            cancelled: AtomicBool::new(false),
        }));
        Ok(Self {
            listener: Some(listener),
            manager: None,
            plan,
            mode,
            deadline,
            handle,
        })
    }

    /// Serves an already accepted connection; idle waiting belongs to the caller's event loop.
    pub fn connected(manager: Channel, plan: DeliveryPlan, mode: IdentityMode) -> io::Result<Self> {
        if mode == IdentityMode::VerifiedTarget
            && (!cfg!(target_os = "android") || unsafe { libc::geteuid() } != 0)
        {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "verified delivery requires Android root",
            ));
        }
        let handle = SessionHandle(Arc::new(Shared {
            status: Mutex::new(Status {
                session_id: NEXT_ID.fetch_add(1, Ordering::Relaxed),
                phase: Phase::Ready,
                payload_revision: plan.offer.payload_revision,
                config_revision: plan.offer.config_revision,
                sent_to_relay: false,
                target_peer: None,
                applied_payload_revision: None,
                applied_config_revision: None,
                reported_flags: None,
            }),
            cancelled: AtomicBool::new(false),
        }));
        Ok(Self {
            listener: None,
            manager: Some(manager),
            plan,
            mode,
            // Accepted production sessions include cold host startup before the receipt.
            // The manager channel retains its own shorter delivery deadline.
            deadline: Instant::now() + Duration::from_secs(10),
            handle,
        })
    }

    pub fn handle(&self) -> SessionHandle {
        self.handle.clone()
    }

    fn remaining(&self) -> io::Result<Duration> {
        if self.handle.0.cancelled.load(Ordering::Acquire) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "delivery cancelled",
            ));
        }
        self.deadline
            .checked_duration_since(Instant::now())
            .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "delivery deadline exceeded"))
    }

    /// Consumes the listener and all temporary descriptors on every exit. Must run off
    /// the host main thread. Sending to the relay is not evidence of target initialization.
    pub fn serve(mut self) -> io::Result<Outcome> {
        let result = self.transfer();
        let phase = match &result {
            Ok(Outcome::SentToRelay) => Phase::SentToRelay,
            Ok(Outcome::Initialized { .. }) => Phase::Initialized,
            Ok(Outcome::TargetFailed) => Phase::TargetFailed,
            Ok(Outcome::Rejected) => Phase::Rejected,
            Err(_) if self.handle.0.cancelled.load(Ordering::Acquire) => Phase::Cancelled,
            Err(error) if error.kind() == io::ErrorKind::TimedOut => Phase::TimedOut,
            Err(_) => Phase::Failed,
        };
        let mut status = self
            .handle
            .0
            .status
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        status.phase = phase;
        if let Ok(Outcome::Initialized { flags }) = &result {
            status.applied_payload_revision = Some(self.plan.offer.payload_revision);
            status.applied_config_revision = Some(self.plan.offer.config_revision);
            status.reported_flags = Some(*flags);
        }
        drop(status);
        result
    }

    fn transfer(&mut self) -> io::Result<Outcome> {
        let manager = match self.manager.take() {
            Some(manager) => manager,
            None => self
                .listener
                .as_ref()
                .ok_or_else(|| invalid("missing listener"))?
                .accept(self.remaining()?)?,
        };
        self.remaining()?;
        self.handle.set_phase(Phase::Receiving);
        let manager_uid = match self.mode {
            IdentityMode::VerifiedTarget => 0,
            IdentityMode::UnlabeledTest => unsafe { libc::geteuid() },
        };
        manager.require_uid(manager_uid)?;
        let (kind, bytes) = manager.recv_frame()?;
        self.remaining()?;
        let expected = match self.mode {
            IdentityMode::VerifiedTarget => TARGET_REQUEST,
            IdentityMode::UnlabeledTest => REQUEST,
        };
        let tracked = kind == TARGET_REQUEST_RECEIPT;
        if (!tracked && kind != expected) || Target::decode(&bytes)? != self.plan.target {
            manager.send_frame(REJECTED, b"target or identity mode mismatch")?;
            return Ok(Outcome::Rejected);
        }
        let connection = if self.mode == IdentityMode::VerifiedTarget || tracked {
            Some(manager.recv_fd()?)
        } else {
            None
        };
        self.remaining()?;
        self.handle.set_phase(Phase::Preparing);
        let peer = connection.as_ref().map(target_peer).transpose()?;
        if self.mode == IdentityMode::UnlabeledTest
            && peer.is_some_and(|peer| peer.uid != manager_uid)
        {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "test target UID mismatch",
            ));
        }
        let prepared = match (self.mode, connection.as_ref()) {
            (IdentityMode::VerifiedTarget, Some(connection)) => {
                meow_native_provider::prepare_for_target(
                    connection,
                    &self.plan.target,
                    &self.plan.payload,
                )
            }
            (IdentityMode::UnlabeledTest, _) => sealed_payload(&self.plan.payload),
            _ => return Err(invalid("verified delivery missing target connection")),
        };
        self.remaining()?;
        let payload = match prepared {
            Ok(payload) => payload,
            Err(error) => {
                let _ = manager.send_frame(
                    REJECTED,
                    b"target authorization or payload preparation failed",
                );
                return Err(error);
            }
        };
        let receipt_offer = if tracked {
            Some(ReceiptOffer::new(Offer {
                payload_revision: self.plan.offer.payload_revision,
                config_revision: self.plan.offer.config_revision,
                config: self.plan.offer.config.clone(),
            })?)
        } else {
            None
        };
        match &receipt_offer {
            Some(offer) => manager.send_frame(OFFER_RECEIPT, &offer.encode()?)?,
            None => manager.send_frame(OFFER, &self.plan.offer.encode()?)?,
        }
        self.remaining()?;
        manager.send_fd(&payload)?;
        {
            let mut status = self
                .handle
                .0
                .status
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            status.sent_to_relay = true;
            status.target_peer = peer;
        }
        drop(payload);
        drop(manager);
        let Some(offer) = receipt_offer else {
            return Ok(Outcome::SentToRelay);
        };
        self.handle.set_phase(Phase::AwaitingReceipt);
        self.remaining()?;
        let socket = connection.ok_or_else(|| invalid("missing receipt connection"))?;
        // The companion only writes the delivery. This worker is the sole receipt reader.
        let target = Channel::with_deadline(UnixStream::from(socket), self.deadline);
        let (kind, bytes) = target.recv_frame()?;
        self.remaining()?;
        if kind != LOAD_RECEIPT {
            return Err(invalid("unexpected receipt frame"));
        }
        let receipt = LoadReceipt::decode(&bytes)?;
        if !receipt.matches(&offer) {
            return Err(invalid("stale or mismatched receipt"));
        }
        Ok(match receipt.outcome {
            LoadOutcome::Initialized => Outcome::Initialized {
                flags: receipt.flags,
            },
            LoadOutcome::Failed => Outcome::TargetFailed,
        })
    }
}

impl Drop for DeliverySession {
    fn drop(&mut self) {
        if !self.handle.status().phase.terminal() {
            self.handle.set_phase(if std::thread::panicking() {
                Phase::Failed
            } else {
                Phase::Cancelled
            });
        }
    }
}
