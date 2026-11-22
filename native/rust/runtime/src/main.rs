mod supervisor;

use meow_native_service::{DeliveryPlan, DeliverySession, IdentityMode, Outcome};
use meow_weather_config::WeatherConfig;
use native_relay::{
    ipc::Channel,
    protocol::{Offer, Target},
};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
use std::{
    fs,
    io::{self, Read, Write},
    os::{
        fd::AsRawFd,
        unix::{
            fs::PermissionsExt,
            net::{SocketAddr, UnixListener, UnixStream},
        },
    },
    process::{Command, Stdio},
    time::Duration,
};

const ROOT: &str = "/data/adb/meowceiler-native";
const ENDPOINT: &[u8] = b"native-relay-meow-load-probe-v1";
const CONTROL: &[u8] = b"meowceiler-weather-control-v1";
const PACKAGE: &str = "com.miui.weather2";

fn error(text: &str) -> io::Error {
    io::Error::other(text)
}
fn file(name: &str) -> String {
    format!("{ROOT}/{name}")
}
fn atomic(name: &str, data: &[u8]) -> io::Result<()> {
    let path = file(name);
    let temp = format!("{path}.tmp");
    fs::write(&temp, data)?;
    fs::set_permissions(&temp, fs::Permissions::from_mode(0o600))?;
    fs::rename(temp, path)
}
fn config() -> io::Result<(u64, WeatherConfig)> {
    let bytes = fs::read(file("config"))?;
    if !matches!(bytes.len(), 10 | 15 | 18) {
        return Err(error("invalid saved configuration"));
    }
    let revision = u64::from_le_bytes(bytes[..8].try_into().unwrap());
    let config =
        WeatherConfig::decode(&bytes[8..]).ok_or_else(|| error("invalid weather configuration"))?;
    Ok((revision, config))
}
fn write_status(phase: &str, revision: u64, applied: u64, pid: i32, flags: u64) -> io::Result<()> {
    atomic(
        "status",
        format!("{phase} {revision} {applied} {pid} {flags}\n").as_bytes(),
    )
}
fn connect_control() -> io::Result<Channel> {
    let stream = UnixStream::connect_addr(&SocketAddr::from_abstract_name(CONTROL)?)?;
    // Retain the legacy receipt wait budget while upgrading a running provider.
    let channel = Channel::new(stream, Duration::from_secs(12));
    channel.require_uid(0)?;
    Ok(channel)
}
fn stop() -> io::Result<()> {
    let channel = match connect_control() {
        Ok(channel) => channel,
        Err(e)
            if matches!(
                e.kind(),
                io::ErrorKind::ConnectionRefused | io::ErrorKind::NotFound
            ) =>
        {
            return Ok(());
        }
        Err(e) => return Err(e),
    };
    channel.send_frame(1, b"stop")?;
    let (kind, body) = channel.recv_frame()?;
    if kind != 2 || body != b"stopped" {
        return Err(error("provider did not stop"));
    }
    Ok(())
}
fn provider_state() -> io::Result<Vec<u8>> {
    let channel = connect_control()?;
    channel.send_frame(3, b"status")?;
    let (kind, body) = channel.recv_frame()?;
    if kind != 4 || !matches!(body.as_slice(), b"ready" | b"recovering") {
        return Err(error("provider protocol mismatch"));
    }
    Ok(body)
}
fn start() -> io::Result<()> {
    let (_, cfg) = config()?;
    if !cfg.enabled {
        return Ok(());
    }
    match provider_state() {
        Ok(_) => return Ok(()),
        Err(e)
            if matches!(
                e.kind(),
                io::ErrorKind::ConnectionRefused | io::ErrorKind::NotFound
            ) => {}
        Err(e) => return Err(e),
    }
    let mut child = Command::new(std::env::current_exe()?)
        .arg("supervise")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut output = child
        .stdout
        .take()
        .ok_or_else(|| error("missing readiness pipe"))?;
    if let Err(error) = supervisor::wait_ready(&mut output) {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    Ok(())
}
fn supervise() -> io::Result<()> {
    if unsafe { libc::setsid() } < 0 {
        return Err(io::Error::last_os_error());
    }
    let control = UnixListener::bind_addr(&SocketAddr::from_abstract_name(CONTROL)?)?;
    let mut command = Command::new(std::env::current_exe()?);
    command.arg("serve").arg(std::process::id().to_string());
    supervisor::run(control, &mut command)
}
fn serve() -> io::Result<()> {
    let (revision, cfg) = config()?;
    if !cfg.enabled {
        return Err(error("disabled"));
    }
    let mut payload = fs::read_to_string(file("payload"))?;
    let data = UnixListener::bind_addr(&SocketAddr::from_abstract_name(ENDPOINT)?)?;
    write_status("ready", revision, 0, 0, 0)?;
    println!("ready");
    io::stdout().flush()?;
    loop {
        let (stream, _) = match data.accept() {
            Ok(connection) => connection,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        let manager = Channel::new(stream, Duration::from_secs(5));
        if manager.require_uid(0).is_err() {
            continue;
        }
        if !std::path::Path::new(&payload).is_file() {
            match installed_payload() {
                Ok(path) => {
                    let executable = path.parent().unwrap().join("libmeow_runtime.so");
                    if bootstrap(&executable).is_err()
                        || atomic("payload", path.as_os_str().as_encoded_bytes()).is_err()
                    {
                        let _ = write_status("failed", revision, 0, 0, 0);
                        continue;
                    }
                    payload = path.to_string_lossy().into_owned();
                }
                Err(_) => {
                    let _ = write_status("failed", revision, 0, 0, 0);
                    continue;
                }
            }
        }
        let plan = DeliveryPlan::new(
            Target {
                package: PACKAGE.into(),
                process: PACKAGE.into(),
            },
            payload.clone().into(),
            Offer {
                payload_revision: 2,
                config_revision: revision,
                config: cfg.encode().to_vec(),
            },
        )?;
        let session = DeliverySession::connected(manager, plan, IdentityMode::VerifiedTarget)?;
        let handle = session.handle();
        let result = session.serve();
        let status = handle.status();
        let phase = match result {
            Ok(Outcome::Initialized { .. }) => "initialized",
            Ok(Outcome::TargetFailed) => "unsupported",
            _ => "failed",
        };
        let _ = write_status(
            phase,
            revision,
            status.applied_config_revision.unwrap_or(0),
            status.target_peer.map_or(0, |p| p.pid),
            status.reported_flags.unwrap_or(0),
        );
    }
}
fn restart() -> io::Result<()> {
    for args in [
        vec!["force-stop", PACKAGE],
        vec![
            "start",
            "-W",
            "-n",
            "com.miui.weather2/.ActivityWeatherMain",
        ],
    ] {
        let output = Command::new("/system/bin/am").args(args).output()?;
        if !output.status.success()
            || String::from_utf8_lossy(&output.stdout).contains("Error:")
            || String::from_utf8_lossy(&output.stderr).contains("Error:")
        {
            return Err(error("weather restart failed"));
        }
    }
    Ok(())
}
fn payload_from_package_path(output: &str) -> io::Result<std::path::PathBuf> {
    let base = output
        .lines()
        .filter_map(|line| line.strip_prefix("package:"))
        .find(|path| path.ends_with("/base.apk"))
        .ok_or_else(|| error("installed MeowCeiler package not found"))?;
    let path = std::path::Path::new(base);
    if !path.is_absolute() || !path.starts_with("/data/app") {
        return Err(error("unsupported application installation path"));
    }
    Ok(path
        .parent()
        .unwrap()
        .join("lib/arm64/libmeow_native_payload.so"))
}
fn installed_payload() -> io::Result<std::path::PathBuf> {
    let output = Command::new("/system/bin/timeout")
        .args([
            "1",
            "/system/bin/cmd",
            "package",
            "path",
            "io.github.lingqiqi.meowceiler",
        ])
        .output()?;
    if !output.status.success() {
        return Err(error("could not resolve installed MeowCeiler package"));
    }
    let path = payload_from_package_path(&String::from_utf8_lossy(&output.stdout))?;
    if !path.is_file() {
        return Err(error("installed MeowCeiler payload missing"));
    }
    Ok(path)
}
fn bootstrap(executable: &std::path::Path) -> io::Result<()> {
    let stable = file("runtime");
    if executable != std::path::Path::new(&stable) {
        let temp = format!("{stable}.tmp");
        fs::copy(executable, &temp)?;
        fs::set_permissions(&temp, fs::Permissions::from_mode(0o700))?;
        fs::rename(temp, &stable)?;
    }
    fs::create_dir_all("/data/adb/service.d")?;
    let path = "/data/adb/service.d/meowceiler-native.sh";
    fs::write(
        format!("{path}.tmp"),
        format!("#!/system/bin/sh\n[ -x '{stable}' ] && '{stable}' resume\n"),
    )?;
    fs::set_permissions(format!("{path}.tmp"), fs::Permissions::from_mode(0o700))?;
    fs::rename(format!("{path}.tmp"), path)
}
fn ensure_relay() -> io::Result<()> {
    let root = std::path::Path::new("/data/adb/modules/native_relay_hyos_probe");
    let props = fs::read_to_string(root.join("module.prop"))?;
    let version = props
        .lines()
        .find_map(|line| line.strip_prefix("versionCode="))
        .and_then(|s| s.parse::<u32>().ok())
        .unwrap_or(0);
    if version < 9 || root.join("disable").exists() || root.join("remove").exists() {
        return Err(error(
            "NativeRelay 0.6.0 or newer must be installed and enabled",
        ));
    }
    Ok(())
}
fn run() -> io::Result<()> {
    if unsafe { libc::geteuid() } != 0 || !cfg!(target_os = "android") {
        return Err(error("Android root permission required"));
    }
    fs::create_dir_all(ROOT)?;
    fs::set_permissions(ROOT, fs::Permissions::from_mode(0o700))?;
    let args: Vec<_> = std::env::args().collect();
    let command = args.get(1).map(String::as_str).unwrap_or("status");
    if command == "serve" {
        let parent = args
            .get(2)
            .and_then(|s| s.parse().ok())
            .ok_or_else(|| error("missing supervisor identity"))?;
        supervisor::bind_parent(parent)?;
        return serve();
    }
    if command == "supervise" {
        return supervise();
    }
    // Serializes configuration, boot recovery and UI requests across processes.
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(file("lock"))?;
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(error("operation already in progress"));
    }
    match command {
        "configure" => {
            if args.len() != 5 {
                return Err(error("configure PAYLOAD TOP_BAR_BLUR RESTART"));
            }
            let payload = fs::canonicalize(&args[2])?;
            let enabled = match args[3].as_str() {
                "0" => false,
                "1" => true,
                _ => return Err(error("invalid top bar blur option")),
            };
            let cfg = WeatherConfig { enabled };
            if cfg.enabled {
                ensure_relay()?;
            }
            let revision = match config() {
                Ok((r, _)) => r
                    .checked_add(1)
                    .filter(|v| *v <= u64::MAX >> 1)
                    .ok_or_else(|| error("configuration revision exhausted"))?,
                Err(e) if e.kind() == io::ErrorKind::NotFound => 1,
                Err(e) => return Err(e),
            };
            if !matches!(args[4].as_str(), "0" | "1") {
                return Err(error("invalid restart option"));
            }
            stop()?;
            let mut saved = revision.to_le_bytes().to_vec();
            saved.extend_from_slice(&cfg.encode());
            atomic("payload", payload.as_os_str().as_encoded_bytes())?;
            bootstrap(&std::env::current_exe()?)?;
            atomic("config", &saved)?;
            println!("COMMITTED {revision}");
            io::stdout().flush()?;
            write_status(
                if cfg.enabled { "pending" } else { "disabled" },
                revision,
                0,
                0,
                0,
            )?;
            start()?;
            if args[4] == "1" {
                restart()?;
            }
        }
        "resume" => {
            if let Ok((_, cfg)) = config() {
                let executable = std::env::current_exe()?;
                let payload = if executable == std::path::Path::new(&file("runtime")) {
                    let saved = std::path::PathBuf::from(fs::read_to_string(file("payload"))?);
                    if saved.is_file() {
                        saved
                    } else {
                        installed_payload()?
                    }
                } else {
                    executable
                        .parent()
                        .ok_or_else(|| error("invalid runtime directory"))?
                        .join("libmeow_native_payload.so")
                };
                let current = fs::read(file("payload"))?;
                let moved = payload.as_os_str().as_encoded_bytes() != current;
                if moved {
                    stop()?;
                    atomic("payload", payload.as_os_str().as_encoded_bytes())?;
                }
                if moved || !std::path::Path::new(&file("runtime")).is_file() {
                    bootstrap(&payload.parent().unwrap().join("libmeow_runtime.so"))?;
                }
                if cfg.enabled {
                    ensure_relay()?;
                }
                start()?;
            }
        }
        "status" => {}
        _ => return Err(error("unknown command")),
    }
    let mut status = String::new();
    if let Ok(f) = fs::File::open(file("status")) {
        f.take(1024).read_to_string(&mut status)?;
    }
    if status.is_empty() {
        status = "disabled 0 0 0 0\n".into();
    }
    if let Ok((revision, cfg)) = config() {
        if !cfg.enabled {
            status = format!("disabled {revision} 0 0 0\n");
        } else if !provider_state().is_ok_and(|state| state == b"ready") {
            status = format!("offline {revision} 0 0 0\n");
        }
    }
    print!("{status}");
    Ok(())
}

fn main() {
    if let Err(e) = run() {
        eprintln!("{e}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn update_path_uses_installed_base_apk_not_split() {
        let path = payload_from_package_path(
            "package:/data/app/new/meow/split_config.apk\npackage:/data/app/new/meow/base.apk\n",
        )
        .unwrap();
        assert_eq!(
            path,
            std::path::Path::new("/data/app/new/meow/lib/arm64/libmeow_native_payload.so")
        );
    }

    #[test]
    fn invalid_or_missing_install_path_is_rejected() {
        for input in [
            "",
            "Error: package missing",
            "package:relative/base.apk",
            "package:/data/local/tmp/base.apk",
            "package:/data/app/new/split.apk",
        ] {
            assert!(payload_from_package_path(input).is_err(), "{input}");
        }
    }
}
