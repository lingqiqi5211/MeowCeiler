#![deny(unsafe_op_in_unsafe_fn)]

mod lookup;
mod original;
mod resident;
mod weather;

use native_relay_abi::*;
use std::sync::atomic::{AtomicU64, Ordering};

// revision and enabled occupy one atomic word so callbacks cannot observe mixed snapshots.
static STATE: AtomicU64 = AtomicU64::new(0);
const MAX_REVISION: u64 = u64::MAX >> 1;

fn publish(bytes: &[u8], revision: u64) -> i32 {
    let Some(config) = meow_weather_config::WeatherConfig::decode(bytes) else {
        return INVALID_CONFIG;
    };
    if revision == 0 || revision > MAX_REVISION {
        return INVALID_CONFIG;
    }
    let enabled = config.enabled;
    let next = (revision << 1) | u64::from(enabled);
    match STATE.compare_exchange(0, next, Ordering::AcqRel, Ordering::Acquire) {
        Ok(_) => OK,
        Err(_) => ALREADY_INITIALIZED,
    }
}

unsafe extern "C" fn initialize(bytes: *const u8, len: usize, revision: u64) -> i32 {
    if !weather::bound() {
        return HOST_UNAVAILABLE;
    }
    if bytes.is_null() || !matches!(len, 2 | 7 | 10) {
        return INVALID_CONFIG;
    }
    let result = publish(unsafe { std::slice::from_raw_parts(bytes, len) }, revision);
    if result == OK && STATE.load(Ordering::Acquire) & 1 != 0 {
        // The current adapter is restricted to the reviewed resident startup DSOs.
        // Host V1 cannot pin arbitrary libraries or serialize independent installers.
        return unsafe { weather::install() };
    }
    result
}

unsafe extern "C" fn query_state(out: *mut PayloadStateV1) -> i32 {
    if out.is_null() {
        return INVALID_CONFIG;
    }
    let state = STATE.load(Ordering::Acquire);
    if state == 0 {
        return NOT_INITIALIZED;
    }
    let flags = (state & 1) | weather::flags();
    unsafe {
        out.write(PayloadStateV1 {
            config_revision: state >> 1,
            flags,
        });
    }
    OK
}

static API: PayloadApiWithHostV1 = PayloadApiWithHostV1 {
    base: PayloadApiV1 {
        struct_size: size_of::<PayloadApiWithHostV1>() as u32,
        abi_version: ABI_VERSION,
        capabilities: CAP_HOST_API,
        initialize: Some(initialize),
        apply_config: None,
        query_state: Some(query_state),
    },
    bind_host: Some(weather::bind),
};

#[unsafe(no_mangle)]
pub extern "C" fn native_relay_payload_v1() -> *const PayloadApiV1 {
    &API.base
}
