#![no_std]

pub const ABI_VERSION: u32 = 1;
pub const CAP_CONFIG_UPDATE: u64 = 1;
pub const CAP_HOST_API: u64 = 2;
pub const HOST_CAP_PLT: u64 = 1;
pub const OK: i32 = 0;
pub const INVALID_CONFIG: i32 = 1;
pub const STALE_REVISION: i32 = 2;
pub const NOT_INITIALIZED: i32 = 3;
pub const ALREADY_INITIALIZED: i32 = 4;
pub const HOST_UNAVAILABLE: i32 = 5;
pub const HOOK_FAILED: i32 = 6;
pub const ENTRY_SYMBOL: &[u8] = b"native_relay_payload_v1\0";

#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct PayloadStateV1 {
    pub config_revision: u64,
    pub flags: u64,
}

pub type Configure = unsafe extern "C" fn(*const u8, usize, u64) -> i32;
pub type QueryState = unsafe extern "C" fn(*mut PayloadStateV1) -> i32;

// Buffers are borrowed only for the call. No Rust-owned allocations cross this ABI.
#[repr(C)]
pub struct PayloadApiV1 {
    pub struct_size: u32,
    pub abi_version: u32,
    pub capabilities: u64,
    pub initialize: Option<Configure>,
    pub apply_config: Option<Configure>,
    pub query_state: Option<QueryState>,
}

pub type Entry = unsafe extern "C" fn() -> *const PayloadApiV1;

pub type PltHook = unsafe extern "C" fn(
    *mut core::ffi::c_void,
    *const core::ffi::c_char,
    *mut core::ffi::c_void,
    *mut *mut core::ffi::c_void,
) -> i32;
pub type Log = unsafe extern "C" fn(*const core::ffi::c_char);

// A process-lifetime table; pointers and strings follow the trusted native caller contract.
// PLT installation requires the caller to publish a valid forwarding target BEFORE patching.
// This API does not serialize competing installers or make target code unloadable.
#[repr(C)]
pub struct HostApiV1 {
    pub struct_size: u32,
    pub abi_version: u32,
    pub capabilities: u64,
    pub plt_hook: Option<PltHook>,
    pub log: Option<Log>,
}

pub type BindHost = unsafe extern "C" fn(*const HostApiV1) -> i32;

// Optional tail, read only when CAP_HOST_API is set and struct_size covers this type.
// Old v1 payloads retain their original size and need no host callback.
#[repr(C)]
pub struct PayloadApiWithHostV1 {
    pub base: PayloadApiV1,
    pub bind_host: Option<BindHost>,
}
