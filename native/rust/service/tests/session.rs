use meow_native_service::{DeliveryPlan, DeliverySession, IdentityMode, Outcome, Phase};
use native_relay::handoff::{receive_delivery, send_tracked};
use native_relay::ipc::{Channel, validate_payload};
use native_relay::protocol::{OFFER, Offer, REJECTED, REQUEST, TARGET_REQUEST, Target};
use native_relay::receipt::{
    LoadOutcome, LoadReceipt, OFFER_RECEIPT, ReceiptOffer, TARGET_REQUEST_RECEIPT,
};
use std::fs::{self, File};
use std::io::{Read, Write};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{SocketAddr, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Fixture {
    dir: PathBuf,
    endpoint: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let name = format!(
            "meow-session-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        );
        let dir = std::env::temp_dir().join(&name);
        fs::create_dir(&dir).unwrap();
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap();
        fs::write(dir.join("payload"), b"immutable test bytes").unwrap();
        Self {
            dir,
            endpoint: format!("@{name}").into(),
        }
    }

    fn target() -> Target {
        Target {
            package: "test.meow".into(),
            process: "test.meow:worker".into(),
        }
    }

    fn plan(&self, missing: bool) -> DeliveryPlan {
        DeliveryPlan::new(
            Self::target(),
            self.dir.join(if missing { "absent" } else { "payload" }),
            Offer {
                payload_revision: 17,
                config_revision: 29,
                config: vec![1, 0],
            },
        )
        .unwrap()
    }

    fn bind(&self, budget: Duration) -> DeliverySession {
        DeliverySession::bind(
            &self.endpoint,
            self.plan(false),
            IdentityMode::UnlabeledTest,
            budget,
        )
        .unwrap()
    }

    fn connect(&self) -> Channel {
        Channel::connect(&self.endpoint, Duration::from_secs(2)).unwrap()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

fn tracked_delivery(f: &Fixture) -> (UnixStream, ReceiptOffer) {
    tracked_delivery_through(f.connect())
}

fn tracked_delivery_through(client: Channel) -> (UnixStream, ReceiptOffer) {
    let (relay, target) = UnixStream::pair().unwrap();
    client
        .send_frame(TARGET_REQUEST_RECEIPT, &Fixture::target().encode().unwrap())
        .unwrap();
    client.send_fd(&relay).unwrap();
    let (kind, bytes) = client.recv_frame().unwrap();
    assert_eq!(kind, OFFER_RECEIPT);
    let offer = ReceiptOffer::decode(&bytes).unwrap();
    let payload = client.recv_fd().unwrap();
    validate_payload(&payload).unwrap();
    send_tracked(&relay, &payload, &offer).unwrap();
    drop(relay);
    drop(client);
    let (payload, received, ticket) = receive_delivery(&target).unwrap();
    validate_payload(&payload).unwrap();
    assert_eq!(received, offer.offer);
    assert_eq!(ticket, Some(offer.ticket));
    (target, offer)
}

fn receipt_for(offer: &ReceiptOffer) -> LoadReceipt {
    LoadReceipt {
        ticket: offer.ticket,
        payload_revision: offer.offer.payload_revision,
        config_revision: offer.offer.config_revision,
        flags: 0xf01,
        outcome: LoadOutcome::Initialized,
    }
}

#[test]
fn connected_session_accepts_cold_start_receipt_after_delivery_budget() {
    let f = Fixture::new();
    let (manager, client) = UnixStream::pair().unwrap();
    let session = DeliverySession::connected(
        Channel::new(manager, Duration::from_secs(2)),
        f.plan(false),
        IdentityMode::UnlabeledTest,
    ).unwrap();
    let handle = session.handle();
    let worker = std::thread::spawn(|| session.serve());
    let (target, offer) = tracked_delivery_through(Channel::new(client, Duration::from_secs(2)));
    std::thread::sleep(Duration::from_secs(6));
    assert_eq!(handle.status().phase, Phase::AwaitingReceipt);
    receipt_for(&offer).send_once(&target).unwrap();
    assert_eq!(worker.join().unwrap().unwrap(), Outcome::Initialized { flags: 0xf01 });
    assert_eq!(handle.status().applied_config_revision, Some(29));
}

#[test]
fn matching_receipt_records_versions_and_connection_peer() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_secs(2));
    let handle = session.handle();
    let worker = std::thread::spawn(|| session.serve());
    let (target, offer) = tracked_delivery(&f);
    receipt_for(&offer).send_once(&target).unwrap();
    assert_eq!(
        worker.join().unwrap().unwrap(),
        Outcome::Initialized { flags: 0xf01 }
    );
    let status = handle.status();
    assert_eq!(status.phase, Phase::Initialized);
    assert!(status.sent_to_relay);
    assert_eq!(status.target_peer.unwrap().pid as u32, std::process::id());
    assert_eq!(status.target_peer.unwrap().uid, unsafe { libc::geteuid() });
    assert_eq!(status.applied_payload_revision, Some(17));
    assert_eq!(status.applied_config_revision, Some(29));
    assert_eq!(status.reported_flags, Some(0xf01));
    assert!(!handle.cancel());
    drop(f.bind(Duration::from_secs(1)));
}

#[test]
fn failed_or_mismatched_receipt_never_records_applied_state() {
    let f = Fixture::new();
    for case in 0..4 {
        let session = f.bind(Duration::from_secs(2));
        let handle = session.handle();
        let worker = std::thread::spawn(|| session.serve());
        let (target, offer) = tracked_delivery(&f);
        let mut receipt = receipt_for(&offer);
        match case {
            0 => {
                receipt.outcome = LoadOutcome::Failed;
                receipt.flags = 0;
            }
            1 => receipt.ticket[0] ^= 1,
            2 => receipt.payload_revision += 1,
            _ => receipt.config_revision += 1,
        }
        receipt.send_once(&target).unwrap();
        let result = worker.join().unwrap();
        if case == 0 {
            assert_eq!(result.unwrap(), Outcome::TargetFailed);
            assert_eq!(handle.status().phase, Phase::TargetFailed);
        } else {
            assert_eq!(result.unwrap_err().kind(), std::io::ErrorKind::InvalidData);
            assert_eq!(handle.status().phase, Phase::Failed);
        }
        assert!(handle.status().sent_to_relay);
        assert_eq!(handle.status().applied_payload_revision, None);
        assert_eq!(handle.status().applied_config_revision, None);
        assert_eq!(handle.status().reported_flags, None);
    }
}

#[test]
fn missing_partial_and_cancelled_receipts_keep_delivery_but_not_application() {
    let f = Fixture::new();
    for case in 0..3 {
        let started = Instant::now();
        let session = f.bind(Duration::from_millis(250));
        let handle = session.handle();
        let worker = std::thread::spawn(|| session.serve());
        let (mut target, _) = tracked_delivery(&f);
        if case == 1 {
            target.write_all(b"NRL").unwrap();
        }
        if case == 2 {
            assert!(handle.cancel());
        }
        let error = worker.join().unwrap().unwrap_err().kind();
        if case == 2 {
            // Cancellation may land before the wait or during its fixed deadline.
            assert!(matches!(
                error,
                std::io::ErrorKind::Interrupted | std::io::ErrorKind::TimedOut
            ));
        } else {
            assert_eq!(error, std::io::ErrorKind::TimedOut);
        }
        assert!(started.elapsed() < Duration::from_secs(1));
        let status = handle.status();
        assert!(status.sent_to_relay);
        assert_eq!(
            status.phase,
            if case == 2 {
                Phase::Cancelled
            } else {
                Phase::TimedOut
            }
        );
        assert_eq!(status.applied_config_revision, None);
        let mut byte = [0];
        assert_eq!(target.read(&mut byte).unwrap(), 0);
        drop(f.bind(Duration::from_secs(1)));
    }
}

#[test]
fn tracked_request_requires_target_fd_within_original_budget() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_millis(100));
    let handle = session.handle();
    let client = f.connect();
    client
        .send_frame(TARGET_REQUEST_RECEIPT, &Fixture::target().encode().unwrap())
        .unwrap();
    assert_eq!(
        session.serve().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
    assert!(!handle.status().sent_to_relay);
    assert_eq!(handle.status().applied_config_revision, None);
}

#[test]
fn transferred_snapshot_is_not_reported_as_applied() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_secs(2));
    let handle = session.handle();
    assert_eq!(handle.status().phase, Phase::Ready);
    let worker = std::thread::spawn(|| session.serve());
    let client = f.connect();
    client
        .send_frame(REQUEST, &Fixture::target().encode().unwrap())
        .unwrap();
    let (kind, bytes) = client.recv_frame().unwrap();
    assert_eq!(kind, OFFER);
    let offer = Offer::decode(&bytes).unwrap();
    assert_eq!((offer.payload_revision, offer.config_revision), (17, 29));
    assert_eq!(offer.config, [1, 0]);
    let fd = client.recv_fd().unwrap();
    validate_payload(&fd).unwrap();
    let mut bytes = Vec::new();
    File::from(fd).read_to_end(&mut bytes).unwrap();
    assert_eq!(bytes, b"immutable test bytes");
    assert_eq!(worker.join().unwrap().unwrap(), Outcome::SentToRelay);
    let status = handle.status();
    assert_eq!(status.phase, Phase::SentToRelay);
    assert_eq!(status.applied_config_revision, None);
    assert!(!handle.cancel());
    drop(f.bind(Duration::from_secs(1)));
}

#[test]
fn mismatched_target_and_identity_mode_never_prepare_payload() {
    let f = Fixture::new();
    for (kind, target) in [
        (
            REQUEST,
            Target {
                package: "test.meow".into(),
                process: "test.meow:other".into(),
            },
        ),
        (TARGET_REQUEST, Fixture::target()),
    ] {
        let session = DeliverySession::bind(
            &f.endpoint,
            f.plan(true),
            IdentityMode::UnlabeledTest,
            Duration::from_secs(2),
        )
        .unwrap();
        let handle = session.handle();
        let worker = std::thread::spawn(|| session.serve());
        let client = f.connect();
        client.send_frame(kind, &target.encode().unwrap()).unwrap();
        assert_eq!(client.recv_frame().unwrap().0, REJECTED);
        assert_eq!(worker.join().unwrap().unwrap(), Outcome::Rejected);
        assert_eq!(handle.status().phase, Phase::Rejected);
    }
}

#[test]
fn preparation_failure_closes_connection_and_listener() {
    let f = Fixture::new();
    let session = DeliverySession::bind(
        &f.endpoint,
        f.plan(true),
        IdentityMode::UnlabeledTest,
        Duration::from_secs(2),
    )
    .unwrap();
    let handle = session.handle();
    let worker = std::thread::spawn(|| session.serve());
    let client = f.connect();
    client
        .send_frame(REQUEST, &Fixture::target().encode().unwrap())
        .unwrap();
    assert_eq!(client.recv_frame().unwrap().0, REJECTED);
    assert_eq!(
        worker.join().unwrap().unwrap_err().kind(),
        std::io::ErrorKind::NotFound
    );
    assert!(client.recv_fd().is_err());
    assert_eq!(handle.status().phase, Phase::Failed);
    assert_eq!(handle.status().applied_config_revision, None);
    drop(f.bind(Duration::from_secs(1)));
}

#[test]
fn partial_frame_does_not_restart_the_budget() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_millis(100));
    let handle = session.handle();
    let name = f.endpoint.to_str().unwrap().strip_prefix('@').unwrap();
    let mut client =
        UnixStream::connect_addr(&SocketAddr::from_abstract_name(name).unwrap()).unwrap();
    client.write_all(b"NRL").unwrap();
    let started = Instant::now();
    assert_eq!(
        session.serve().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
    assert!(started.elapsed() < Duration::from_secs(1));
    assert_eq!(handle.status().phase, Phase::TimedOut);
    let mut byte = [0];
    assert_eq!(client.read(&mut byte).unwrap(), 0);
    drop(f.bind(Duration::from_secs(1)));
}

#[test]
fn no_peer_expires_and_pre_cancelled_session_never_waits() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_millis(30));
    let handle = session.handle();
    assert_eq!(
        session.serve().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
    assert_eq!(handle.status().phase, Phase::TimedOut);
    let session = f.bind(Duration::from_secs(2));
    let handle = session.handle();
    assert!(handle.cancel());
    let started = Instant::now();
    assert_eq!(
        session.serve().unwrap_err().kind(),
        std::io::ErrorKind::Interrupted
    );
    assert!(started.elapsed() < Duration::from_secs(1));
    assert_eq!(handle.status().phase, Phase::Cancelled);
    assert!(!handle.cancel());
}

#[test]
fn bind_conflict_and_drop_do_not_leave_an_idle_session() {
    let f = Fixture::new();
    let session = f.bind(Duration::from_secs(2));
    let handle = session.handle();
    let collision = DeliverySession::bind(
        &f.endpoint,
        f.plan(false),
        IdentityMode::UnlabeledTest,
        Duration::from_secs(2),
    );
    assert_eq!(
        collision.err().unwrap().kind(),
        std::io::ErrorKind::AddrInUse
    );
    drop(session);
    assert_eq!(handle.status().phase, Phase::Cancelled);
    let next = f.bind(Duration::from_secs(1));
    assert_ne!(
        handle.status().session_id,
        next.handle().status().session_id
    );
    drop(next);
}

#[test]
fn budget_starts_at_bind_and_invalid_budget_never_opens_endpoint() {
    let f = Fixture::new();
    for budget in [Duration::ZERO, Duration::from_secs(6)] {
        assert!(
            DeliverySession::bind(
                &f.endpoint,
                f.plan(false),
                IdentityMode::UnlabeledTest,
                budget
            )
            .is_err()
        );
    }
    let session = f.bind(Duration::from_millis(10));
    let handle = session.handle();
    std::thread::sleep(Duration::from_millis(20));
    assert_eq!(
        session.serve().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
    assert_eq!(handle.status().phase, Phase::TimedOut);
}

#[test]
fn verified_mode_never_falls_back_on_non_android_or_non_root() {
    if cfg!(target_os = "android") && unsafe { libc::geteuid() } == 0 {
        return;
    }
    let f = Fixture::new();
    let result = DeliverySession::bind(
        Path::new(&f.endpoint),
        f.plan(false),
        IdentityMode::VerifiedTarget,
        Duration::from_secs(1),
    );
    assert_eq!(
        result.err().unwrap().kind(),
        std::io::ErrorKind::PermissionDenied
    );
    drop(f.bind(Duration::from_secs(1)));
}
