use native_relay::ipc::Channel;
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixListener;
use std::process::{Child, ChildStdout, Command, Stdio};
use std::time::{Duration, Instant};

const START_BUDGET: Duration = Duration::from_secs(5);
const HEALTHY_WINDOW: Duration = Duration::from_secs(60);

pub(super) fn wait_ready(output: &mut ChildStdout) -> io::Result<()> {
    let deadline = Instant::now() + START_BUDGET;
    for expected in b"ready\n" {
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "provider readiness timed out",
                ));
            }
            let mut fd = libc::pollfd {
                fd: output.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            };
            let result = unsafe { libc::poll(&mut fd, 1, timeout(remaining)) };
            if result < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(error);
            }
            if result == 0 {
                continue;
            }
            let mut byte = [0];
            if output.read(&mut byte)? != 1 || byte[0] != *expected {
                return Err(io::Error::other("provider could not start"));
            }
            break;
        }
    }
    Ok(())
}

fn timeout(duration: Duration) -> i32 {
    duration.as_millis().max(1).min(i32::MAX as u128) as i32
}

fn backoff(failures: u32) -> Duration {
    Duration::from_secs((1u64 << failures.saturating_sub(1).min(5)).min(30))
}

struct Worker {
    child: Child,
    output: ChildStdout,
    started: Instant,
}

impl Worker {
    fn spawn(command: &mut Command) -> io::Result<Self> {
        let mut child = command
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()?;
        let output = child.stdout.take().expect("piped worker stdout");
        let mut worker = Self {
            child,
            output,
            started: Instant::now(),
        };
        wait_ready(&mut worker.output)?;
        Ok(worker)
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

// The worker keeps stdout open for its lifetime. EOF is an exit event, not a heartbeat.
pub(super) fn run(control: UnixListener, command: &mut Command) -> io::Result<()> {
    control.set_nonblocking(true)?;
    let mut worker = Some(Worker::spawn(command)?);
    println!("ready");
    io::stdout().flush()?;
    let mut failures = 0u32;
    let mut retry = None::<Instant>;
    loop {
        let mut fds = [
            libc::pollfd {
                fd: control.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: worker.as_ref().map_or(-1, |w| w.output.as_raw_fd()),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let wait = retry.map_or(-1, |at| {
            let left = at.saturating_duration_since(Instant::now());
            if left.is_zero() { 0 } else { timeout(left) }
        });
        if unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as _, wait) } < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if fds[1].revents != 0 {
            if worker
                .as_ref()
                .is_some_and(|w| w.started.elapsed() >= HEALTHY_WINDOW)
            {
                failures = 0;
            }
            drop(worker.take());
            failures = failures.saturating_add(1);
            retry = Some(Instant::now() + backoff(failures));
        }
        if fds[0].revents & libc::POLLIN != 0 {
            match control.accept() {
                Ok((stream, _)) => {
                    let channel = Channel::new(stream, Duration::from_secs(1));
                    if channel.require_uid(unsafe { libc::geteuid() }).is_ok() {
                        if let Ok((kind, body)) = channel.recv_frame() {
                            if kind == 1 && body == b"stop" {
                                drop(worker.take());
                                drop(control);
                                channel.send_frame(2, b"stopped")?;
                                return Ok(());
                            }
                            if kind == 3 && body == b"status" {
                                let state: &[u8] = if worker.is_some() {
                                    b"ready"
                                } else {
                                    b"recovering"
                                };
                                let _ = channel.send_frame(4, state);
                            }
                        }
                    }
                }
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                    ) => {}
                Err(error) => return Err(error),
            }
        }
        if fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
            return Err(io::Error::other("provider control listener failed"));
        }
        if retry.is_some_and(|at| Instant::now() >= at) {
            match Worker::spawn(command) {
                Ok(next) => {
                    worker = Some(next);
                    retry = None;
                }
                Err(_) => {
                    failures = failures.saturating_add(1);
                    retry = Some(Instant::now() + backoff(failures));
                }
            }
        }
    }
}

pub(super) fn bind_parent(expected: i32) -> io::Result<()> {
    if expected <= 1
        || unsafe { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) } != 0
        || unsafe { libc::getppid() } != expected
    {
        return Err(io::Error::other("provider supervisor unavailable"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixStream;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);

    #[test]
    fn crash_backoff_is_bounded() {
        let values: Vec<_> = (1..=8).map(|n| backoff(n).as_secs()).collect();
        assert_eq!(values, [1, 2, 4, 8, 16, 30, 30, 30]);
        assert_eq!(backoff(u32::MAX), Duration::from_secs(30));
    }

    #[test]
    fn failed_readiness_reaps_worker() {
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "printf 'wrong\\n'; exec sleep 30"]);
        assert!(Worker::spawn(&mut command).is_err());
    }

    #[test]
    fn worker_crash_recovers_and_stop_does_not_respawn() {
        check_lifecycle(false);
    }

    #[test]
    fn stop_during_backoff_cancels_recovery() {
        check_lifecycle(true);
    }

    fn check_lifecycle(stop_during_backoff: bool) {
        let directory = std::env::temp_dir().join(format!(
            "meow-supervisor-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&directory).unwrap();
        let socket = directory.join("control");
        let pid_file = directory.join("worker");
        let listener = UnixListener::bind(&socket).unwrap();
        let worker_file = pid_file.clone();
        let thread = std::thread::spawn(move || {
            let mut command = Command::new("/bin/sh");
            command.args([
                "-c",
                "echo $$ > \"$1\"; printf 'ready\\n'; exec sleep 30",
                "worker",
            ]);
            command.arg(worker_file);
            run(listener, &mut command)
        });
        let request = |kind, body: &[u8]| {
            let channel = Channel::new(
                UnixStream::connect(&socket).unwrap(),
                Duration::from_secs(2),
            );
            channel.send_frame(kind, body).unwrap();
            channel.recv_frame().unwrap()
        };
        assert_eq!(request(3, b"status"), (4, b"ready".to_vec()));
        let first: i32 = std::fs::read_to_string(&pid_file)
            .unwrap()
            .trim()
            .parse()
            .unwrap();
        assert_eq!(unsafe { libc::kill(first, libc::SIGKILL) }, 0);
        let deadline = Instant::now() + Duration::from_secs(4);
        let second = loop {
            let (_, status) = request(3, b"status");
            let pid = std::fs::read_to_string(&pid_file).unwrap();
            let pid = pid.trim().parse::<i32>().unwrap_or(first);
            if stop_during_backoff && status == b"recovering" {
                break first;
            }
            if !stop_during_backoff && status == b"ready" && pid != first {
                break pid;
            }
            assert!(Instant::now() < deadline, "worker was not recovered");
            std::thread::sleep(Duration::from_millis(20));
        };
        assert_eq!(request(1, b"stop"), (2, b"stopped".to_vec()));
        thread.join().unwrap().unwrap();
        assert_eq!(unsafe { libc::kill(second, 0) }, -1);
        assert!(UnixStream::connect(&socket).is_err());
        if stop_during_backoff {
            assert_eq!(
                std::fs::read_to_string(&pid_file).unwrap().trim(),
                first.to_string()
            );
        }
        std::fs::remove_dir_all(directory).unwrap();
    }
}
