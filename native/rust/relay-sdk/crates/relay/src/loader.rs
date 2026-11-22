use crate::abi::{self, PayloadApiV1, PayloadStateV1};
use crate::ipc::validate_payload;
use crate::protocol::invalid;
use std::cell::Cell;
use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::{IntoRawFd, OwnedFd};
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};

static LOAD_ATTEMPTED: AtomicBool = AtomicBool::new(false);

// The initial prototype deliberately has no unload operation: payload callbacks may outlive callers.
pub struct LoadedPayload {
    api: NonNull<PayloadApiV1>,
    _handle: NonNull<libc::c_void>,
    host_bound: Cell<bool>,
}

#[cfg(target_os = "android")]
#[repr(C)]
struct AndroidDlExtInfo {
    flags: u64,
    reserved_addr: *mut libc::c_void,
    reserved_size: usize,
    relro_fd: libc::c_int,
    library_fd: libc::c_int,
    library_fd_offset: i64,
    library_namespace: *mut libc::c_void,
}

#[cfg(target_os = "android")]
#[link(name = "dl")]
unsafe extern "C" {
    fn android_dlopen_ext(
        name: *const libc::c_char,
        flags: libc::c_int,
        info: *const AndroidDlExtInfo,
    ) -> *mut libc::c_void;
}

fn loader_error() -> io::Error {
    let pointer = unsafe { libc::dlerror() };
    let message = if pointer.is_null() {
        "dynamic loader failed".into()
    } else {
        unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned()
    };
    io::Error::other(message)
}

impl LoadedPayload {
    /// Loads executable code from an authenticated provider. ABI validation is not sandboxing.
    ///
    /// # Safety
    /// The caller must trust the payload and all its dependencies. ELF constructors execute before
    /// the entry table can be validated. Its table and callbacks must remain valid until process exit.
    pub unsafe fn load(fd: OwnedFd) -> io::Result<Self> {
        validate_payload(&fd)?;
        LOAD_ATTEMPTED
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| {
                io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "payload load already attempted; restart required",
                )
            })?;
        // Pin one identity for the process lifetime, including failed ABI checks and dropped handles.
        // Constructors may have run; retrying or reusing this fd pathname is not a safe rollback.
        let identity_fd = fd.into_raw_fd();
        let name = CString::new(format!("/proc/self/fd/{identity_fd}")).unwrap();
        #[cfg(target_os = "android")]
        let handle = {
            let info = AndroidDlExtInfo {
                flags: 0x10, // ANDROID_DLEXT_USE_LIBRARY_FD, android/dlext.h
                reserved_addr: std::ptr::null_mut(),
                reserved_size: 0,
                relro_fd: -1,
                library_fd: identity_fd,
                library_fd_offset: 0,
                library_namespace: std::ptr::null_mut(),
            };
            unsafe { android_dlopen_ext(name.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL, &info) }
        };
        #[cfg(not(target_os = "android"))]
        let handle = unsafe { libc::dlopen(name.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
        let handle = NonNull::new(handle).ok_or_else(loader_error)?;
        // Pin even failed loads: constructors may already have installed process callbacks.
        unsafe {
            libc::dlerror();
        }
        let entry = unsafe { libc::dlsym(handle.as_ptr(), abi::ENTRY_SYMBOL.as_ptr().cast()) };
        if entry.is_null() {
            return Err(loader_error());
        }
        let entry: abi::Entry = unsafe { std::mem::transmute(entry) };
        let table = NonNull::new(unsafe { entry() }.cast_mut())
            .ok_or_else(|| invalid("null payload ABI"))?;
        // Only the fixed prefix is read until version and size are accepted.
        let size = unsafe { std::ptr::addr_of!((*table.as_ptr()).struct_size).read() };
        let version = unsafe { std::ptr::addr_of!((*table.as_ptr()).abi_version).read() };
        if size < size_of::<PayloadApiV1>() as u32 || version != abi::ABI_VERSION {
            return Err(invalid("unsupported payload ABI"));
        }
        let api = unsafe { table.as_ref() };
        if api.initialize.is_none()
            || api.query_state.is_none()
            || (api.capabilities & abi::CAP_CONFIG_UPDATE != 0 && api.apply_config.is_none())
        {
            return Err(invalid("missing payload callback"));
        }
        unsafe { validate_host_tail(table.as_ptr()) }?;
        Ok(Self {
            api: table,
            _handle: handle,
            host_bound: Cell::new(false),
        })
    }

    /// Binds process-lifetime host callbacks before initialization; old payloads need no binding.
    ///
    /// # Safety
    /// The callbacks must obey HostApiV1 and remain callable until process exit. A payload can
    /// retain and call them from arbitrary native code; this is a trusted-code interface.
    pub unsafe fn bind_host(&self, host: &'static abi::HostApiV1) -> io::Result<()> {
        if unsafe { self.api.as_ref() }.capabilities & abi::CAP_HOST_API == 0 {
            return Ok(());
        }
        if self.host_bound.get() {
            return Err(invalid("host already bound"));
        }
        let extended = unsafe { &*self.api.as_ptr().cast::<abi::PayloadApiWithHostV1>() };
        check(unsafe { extended.bind_host.unwrap()(host) })?;
        self.host_bound.set(true);
        Ok(())
    }

    pub fn initialize(&self, config: &[u8], revision: u64) -> io::Result<()> {
        if unsafe { self.api.as_ref() }.capabilities & abi::CAP_HOST_API != 0
            && !self.host_bound.get()
        {
            return Err(invalid("payload requires host capabilities"));
        }
        let callback = unsafe { self.api.as_ref() }.initialize.unwrap();
        check(unsafe { callback(config.as_ptr(), config.len(), revision) })
    }

    pub fn apply_config(&self, config: &[u8], revision: u64) -> io::Result<()> {
        let api = unsafe { self.api.as_ref() };
        if api.capabilities & abi::CAP_CONFIG_UPDATE == 0 {
            return Err(invalid("configuration update requires restart"));
        }
        let callback = api
            .apply_config
            .ok_or_else(|| invalid("no config callback"))?;
        check(unsafe { callback(config.as_ptr(), config.len(), revision) })
    }

    pub fn state(&self) -> io::Result<PayloadStateV1> {
        let callback = unsafe { self.api.as_ref() }.query_state.unwrap();
        let mut state = PayloadStateV1::default();
        check(unsafe { callback(&mut state) })?;
        Ok(state)
    }
}

// Called only after the fixed prefix has been checked on a trusted, process-lifetime table.
unsafe fn validate_host_tail(table: *const PayloadApiV1) -> io::Result<()> {
    let api = unsafe { &*table };
    if api.capabilities & abi::CAP_HOST_API != 0 {
        if api.struct_size < size_of::<abi::PayloadApiWithHostV1>() as u32 {
            return Err(invalid("truncated host-capable payload ABI"));
        }
        let extended = unsafe { &*table.cast::<abi::PayloadApiWithHostV1>() };
        if extended.bind_host.is_none() {
            return Err(invalid("missing host binding callback"));
        }
    }
    Ok(())
}

fn check(result: i32) -> io::Result<()> {
    if result == abi::OK {
        Ok(())
    } else {
        Err(io::Error::other(format!("payload returned {result}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use abi::{HostApiV1, PayloadApiWithHostV1};

    unsafe extern "C" fn configure(_: *const u8, _: usize, _: u64) -> i32 {
        abi::OK
    }
    unsafe extern "C" fn bind(_: *const HostApiV1) -> i32 {
        abi::OK
    }
    const BASE: PayloadApiV1 = PayloadApiV1 {
        struct_size: size_of::<PayloadApiV1>() as u32,
        abi_version: abi::ABI_VERSION,
        capabilities: 0,
        initialize: Some(configure),
        apply_config: None,
        query_state: None,
    };
    static HOST: HostApiV1 = HostApiV1 {
        struct_size: size_of::<HostApiV1>() as u32,
        abi_version: abi::ABI_VERSION,
        capabilities: 0,
        plt_hook: None,
        log: None,
    };
    static EXTENDED: PayloadApiWithHostV1 = PayloadApiWithHostV1 {
        base: PayloadApiV1 {
            struct_size: size_of::<PayloadApiWithHostV1>() as u32,
            capabilities: abi::CAP_HOST_API,
            ..BASE
        },
        bind_host: Some(bind),
    };

    #[test]
    fn legacy_table_does_not_require_or_read_extension() {
        let table = BASE;
        assert!(unsafe { validate_host_tail(&table) }.is_ok());
    }

    #[test]
    fn host_flag_requires_complete_tail_and_callback() {
        let short = PayloadApiV1 {
            capabilities: abi::CAP_HOST_API,
            ..BASE
        };
        assert!(unsafe { validate_host_tail(&short) }.is_err());
        let missing = PayloadApiWithHostV1 {
            base: PayloadApiV1 {
                struct_size: size_of::<PayloadApiWithHostV1>() as u32,
                capabilities: abi::CAP_HOST_API,
                ..BASE
            },
            bind_host: None,
        };
        assert!(unsafe { validate_host_tail(&missing.base) }.is_err());
        assert!(unsafe { validate_host_tail(&EXTENDED.base) }.is_ok());
    }

    #[test]
    fn initialization_requires_successful_single_binding() {
        let payload = LoadedPayload {
            api: NonNull::from(&EXTENDED.base),
            _handle: NonNull::dangling(),
            host_bound: Cell::new(false),
        };
        assert!(payload.initialize(&[1, 1], 1).is_err());
        unsafe { payload.bind_host(&HOST) }.unwrap();
        assert!(unsafe { payload.bind_host(&HOST) }.is_err());
        payload.initialize(&[1, 1], 1).unwrap();
    }
}
