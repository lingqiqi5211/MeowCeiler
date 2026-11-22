mod group;

use crate::lookup::{PF_R, PF_X};
use crate::resident::ResidentImage;

use native_relay_abi::*;
use std::ffi::CStr;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU32, Ordering};

static ACTIVE: AtomicBool = AtomicBool::new(false);

static HOST: AtomicPtr<HostApiV1> = AtomicPtr::new(std::ptr::null_mut());
static ATTEMPTED: AtomicBool = AtomicBool::new(false);
static INSTALLED: AtomicU32 = AtomicU32::new(0);

/// # Safety
/// host must point to an aligned, readable table whose declared size is valid.
/// The table and callbacks must remain immutable and usable until process exit.
pub(super) unsafe extern "C" fn bind(host: *const HostApiV1) -> i32 {
    if host.is_null()
        || unsafe { std::ptr::addr_of!((*host).struct_size).read() } < size_of::<HostApiV1>() as u32
        || unsafe { std::ptr::addr_of!((*host).abi_version).read() } != ABI_VERSION
    {
        return HOST_UNAVAILABLE;
    }
    let api = unsafe { &*host };
    if api.capabilities & HOST_CAP_PLT == 0 || api.plt_hook.is_none() || api.log.is_none() {
        return HOST_UNAVAILABLE;
    }
    match HOST.compare_exchange(
        std::ptr::null_mut(),
        host.cast_mut(),
        Ordering::AcqRel,
        Ordering::Acquire,
    ) {
        Ok(_) => OK,
        Err(_) => ALREADY_INITIALIZED,
    }
}

pub(super) fn bound() -> bool {
    !HOST.load(Ordering::Acquire).is_null()
}

pub(super) fn flags() -> u64 {
    u64::from(INSTALLED.load(Ordering::Acquire)) << 8
}

/// # Safety
/// The selected startup DSOs and their original callees must remain resident until
/// process exit. Their checked metadata/code must be stable during lookup; callers
/// must serialize slot inspection with other installers. This function does not pin DSOs.
pub(super) unsafe fn install() -> i32 {
    if !bound() {
        return HOST_UNAVAILABLE;
    }
    if ATTEMPTED.swap(true, Ordering::AcqRel) {
        return ALREADY_INITIALIZED;
    }
    let Some(weather) = (unsafe { ResidentImage::find(c"libweather_app.so") }) else {
        log(c"weather top bar: weather image unavailable");
        return HOOK_FAILED;
    };
    let group = match unsafe { group::prepare(&weather) } {
        Ok(group) => group,
        Err(message) => {
            log(message);
            return HOOK_FAILED;
        }
    };
    if let Err(message) = unsafe { group.install() } {
        log(message);
        return HOOK_FAILED;
    }
    ACTIVE.store(true, Ordering::Release);
    OK
}

fn log(message: &CStr) {
    let host = HOST.load(Ordering::Acquire);
    if !host.is_null() {
        unsafe { (*host).log.unwrap()(message.as_ptr()) };
    }
}

unsafe fn errno() -> *mut i32 {
    #[cfg(target_os = "android")]
    {
        unsafe { libc::__errno() }
    }
    #[cfg(target_os = "linux")]
    {
        unsafe { libc::__errno_location() }
    }
}
