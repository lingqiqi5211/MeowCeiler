#[cfg(not(any(target_os = "linux", target_os = "android")))]
compile_error!("NativeRelay prototype requires Linux or Android");

pub mod handoff;
pub mod ipc;
pub mod loader;
pub mod protocol;
pub mod receipt;

pub use native_relay_abi as abi;
