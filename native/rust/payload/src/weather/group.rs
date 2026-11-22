use super::{PF_R, PF_X};
use crate::lookup::{executable_private_page, matches_file_region};
use crate::original::{Original, SymbolLookup};
use crate::resident::ResidentImage;
use std::ffi::{CStr, c_void};
use std::os::unix::ffi::OsStrExt;
use std::sync::atomic::{AtomicU32, Ordering};

const GETTER: usize = 0x359380;
const PATCH: usize = GETTER + 8;
const RETURN_THREE: usize = 0x3596dc;
const ORIGINAL: u32 = 0xf9403f40;
const BRANCH: u32 = 0x14000000 | ((RETURN_THREE - PATCH) / 4) as u32;
const GETTER_BYTES: &[u8] = b"\xfd\x79\xbf\xa9\xfd\x03\x0f\xaa\x40\x3f\x40\xf9\x00\x64\x56\xf9\x70\x23\x40\xf9\x1f\x00\x10\x6b\x81\x00\x00\x54\x62\x63\x40\x91\x42\xac\x46\xf9\x6d\xbb\x19\x94\x01\x7c\x41\x93\x40\x00\x00\x36\x01\x70\x40\xf8\x3f\x04\x00\xb1\xa0\x00\x00\x54\xe0\x03\x01\xaa\xef\x03\x1d\xaa\xfd\x79\xc1\xa8\xc0\x03\x5f\xd6\x04\x00\x00\x94\xef\x03\x1d\xaa\xfd\x79\xc1\xa8\xc0\x03\x5f\xd6";
const RETURN_BYTES: &[u8] = b"\x60\x00\x80\xd2\xef\x03\x1d\xaa\xfd\x79\xc1\xa8\xc0\x03\x5f\xd6";
const BUILD_NOTE: &[u8] = b"\x04\x00\x00\x00\x10\x00\x00\x00\x03\x00\x00\x00GNU\x00\x4f\x1b\xda\xed\xb4\xe0\x0d\x68\xde\x7d\x3e\xbc\x77\x51\xf5\x4f";

type Result<T> = std::result::Result<T, &'static CStr>;

const DLSYM_SLOT: usize = 0x11034b0;
const SNAPSHOT_INSTRUCTIONS: usize = 0x2e6940;
const ENGINE_NOTE: &[u8] = b"\x04\x00\x00\x00\x14\x00\x00\x00\x03\x00\x00\x00GNU\x00\xc3\x43\x03\xc3\x81\xe3\xd1\xe1\x63\x50\x42\x6a\x04\xca\x92\xd0\x7e\x0f\x2c\x23";
static DLSYM: Original<SymbolLookup> = Original::new();
static MAPPING_STATE: AtomicU32 = AtomicU32::new(0);

pub(super) struct Prepared {
    engine: ResidentImage,
    original: usize,
}

/// # Safety
/// Engine and weather mappings must remain resident, with stable checked bytes.
pub(super) unsafe fn prepare(weather: &ResidentImage) -> Result<Prepared> {
    if !cfg!(target_arch = "aarch64") {
        return Err(c"weather device restriction: unsupported architecture");
    }
    let engine = unsafe { ResidentImage::find(c"libhyper_os_flutter.so") }
        .ok_or(c"weather device restriction: engine unavailable")?;
    if !unsafe { engine.matches(0x340, ENGINE_NOTE, PF_R) } {
        return Err(c"weather device restriction: unsupported engine identity");
    }
    let slot = engine
        .address(DLSYM_SLOT, size_of::<usize>(), PF_R)
        .ok_or(c"weather device restriction: invalid engine dlsym slot")?;
    let original = unsafe { (slot as *const usize).read_volatile() };
    if original != libc::dlsym as *const () as usize {
        return Err(c"weather device restriction: engine dlsym already owned");
    }
    let mut info: libc::Dl_info = unsafe { std::mem::zeroed() };
    if unsafe { libc::dladdr(weather.base() as *const c_void, &mut info) } == 0
        || info.dli_fname.is_null()
        || info.dli_fbase as usize != weather.base()
    {
        return Err(c"weather device restriction: weather library path unavailable");
    }
    let name = unsafe { CStr::from_ptr(info.dli_fname) };
    let path = std::path::Path::new(std::ffi::OsStr::from_bytes(name.to_bytes()));
    if !path.is_absolute() || path.file_name() != Some(std::ffi::OsStr::new("libweather_app.so")) {
        return Err(c"weather device restriction: unsupported weather library path");
    }
    let mut file = std::fs::File::open(path.with_file_name("libapp.so"))
        .map_err(|_| c"weather device restriction: Dart image file unavailable")?;
    for (offset, expected) in [
        (0x1c8, BUILD_NOTE),
        (GETTER, GETTER_BYTES),
        (RETURN_THREE, RETURN_BYTES),
    ] {
        if !matches_file_region(&mut file, offset as u64, expected)
            .map_err(|_| c"weather device restriction: unreadable Dart image")?
        {
            return Err(c"weather device restriction: unsupported Dart file");
        }
    }
    Ok(Prepared { engine, original })
}

impl Prepared {
    /// # Safety
    /// The reviewed dlsym ABI and engine must remain valid for process lifetime.
    pub(super) unsafe fn install(self) -> Result<()> {
        if !unsafe { DLSYM.publish(self.original as *mut c_void) } {
            return Err(c"weather device restriction: original publication failed");
        }
        let api = unsafe { &*super::HOST.load(Ordering::Acquire) };
        let mut previous = std::ptr::null_mut();
        let result = unsafe {
            api.plt_hook.unwrap()(
                self.engine.base() as *mut c_void,
                c"dlsym".as_ptr(),
                resolved as *mut c_void,
                &mut previous,
            )
        };
        if result != native_relay_abi::OK {
            return Err(c"weather device restriction: engine mapping hook failed");
        }
        if previous as usize != self.original {
            return Err(c"weather device restriction: engine mapping ownership conflict; forwarding verified original, cold restart required");
        }
        Ok(())
    }
}

unsafe extern "C" fn resolved(handle: *mut c_void, name: *const libc::c_char) -> *mut c_void {
    let original = DLSYM.get();
    let result = unsafe { original(handle, name) };
    let saved_errno = unsafe { *super::errno() };
    if !result.is_null()
        && !name.is_null()
        && super::ACTIVE.load(Ordering::Acquire)
        && unsafe { CStr::from_ptr(name) } == c"_kDartIsolateSnapshotInstructions"
        && MAPPING_STATE
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    {
        // Resolve the engine's actual snapshot, without loading a second image
        // into the payload's linker namespace. No Dart code has run from it yet.
        let outcome = unsafe { ResidentImage::find(c"libapp.so") }
            .ok_or(c"weather device restriction: loaded Dart image unavailable")
            .and_then(|image| unsafe { patch_image(&image, result as usize) });
        match outcome {
            Ok(()) => {
                MAPPING_STATE.store(2, Ordering::Release);
                super::INSTALLED.fetch_or(1 << 7, Ordering::Release);
                super::log(c"weather device restriction removed before engine snapshot entry; cold restart to restore");
            }
            Err(message) => {
                MAPPING_STATE.store(3, Ordering::Release);
                super::ACTIVE.store(false, Ordering::Release);
                super::log(message);
            }
        }
    }
    unsafe { *super::errno() = saved_errno };
    result
}

unsafe fn patch_image(image: &ResidentImage, instructions: usize) -> Result<()> {
    if image.address(SNAPSHOT_INSTRUCTIONS, 4, PF_R | PF_X) != Some(instructions)
        || !unsafe { image.matches(0x1c8, BUILD_NOTE, PF_R) }
        || !unsafe { image.matches(GETTER, GETTER_BYTES, PF_R | PF_X) }
        || !unsafe { image.matches(RETURN_THREE, RETURN_BYTES, PF_R | PF_X) }
    {
        return Err(c"weather device restriction: resolved snapshot or code mismatch");
    }
    unsafe { replace_word(image.base() + PATCH, ORIGINAL, BRANCH) }
}

fn page_is_restored(page: usize, size: usize) -> bool {
    std::fs::read_to_string("/proc/self/maps")
        .is_ok_and(|maps| executable_private_page(&maps, page, size))
}

// The caller guarantees a resident, readable executable mapping and a checked
// instruction boundary. This changes only one word, never unloads the image.
unsafe fn replace_word(address: usize, expected: u32, replacement: u32) -> Result<()> {
    let size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if size <= 0 || !(size as usize).is_power_of_two() || address & 3 != 0 {
        return Err(c"weather device restriction: invalid page or instruction alignment");
    }
    let size = size as usize;
    let page = address & !(size - 1);
    if !page_is_restored(page, size) {
        return Err(c"weather device restriction: code page is not private RX");
    }
    // Require the kernel's process-wide instruction synchronization before any
    // code write; a local ISB alone does not synchronize other executing cores.
    if unsafe { libc::syscall(libc::SYS_membarrier, 1 << 6, 0, 0) } != 0 {
        return Err(c"weather device restriction: instruction synchronization unavailable");
    }
    let ptr = page as *mut c_void;
    if unsafe {
        libc::mprotect(
            ptr,
            size,
            libc::PROT_READ | libc::PROT_WRITE | libc::PROT_EXEC,
        )
    } != 0
    {
        return Err(c"weather device restriction: code write permission denied");
    }
    let word = unsafe { &*(address as *const AtomicU32) };
    let changed = word
        .compare_exchange(expected, replacement, Ordering::AcqRel, Ordering::Acquire)
        .is_ok();
    let synchronized = !changed || unsafe { synchronize_instruction(address) };
    if unsafe { libc::mprotect(ptr, size, libc::PROT_READ | libc::PROT_EXEC) } == 0
        && page_is_restored(page, size)
        && synchronized
    {
        return if changed {
            Ok(())
        } else {
            Err(c"weather device restriction: instruction ownership conflict")
        };
    }
    // Restore only our own word; do not overwrite a competing patch.
    let mut rollback_synchronized = !changed;
    if changed
        && unsafe {
            libc::mprotect(
                ptr,
                size,
                libc::PROT_READ | libc::PROT_WRITE | libc::PROT_EXEC,
            )
        } == 0
    {
        if word
            .compare_exchange(replacement, expected, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            rollback_synchronized = unsafe { synchronize_instruction(address) };
        }
    }
    let restored = unsafe { libc::mprotect(ptr, size, libc::PROT_READ | libc::PROT_EXEC) } == 0
        && page_is_restored(page, size);
    if restored && rollback_synchronized && word.load(Ordering::Acquire) == expected {
        Err(c"weather device restriction: installation rolled back after synchronization or protection failure")
    } else {
        Err(c"weather device restriction: rollback incomplete; cold restart required")
    }
}

unsafe fn synchronize_instruction(address: usize) -> bool {
    #[cfg(target_arch = "aarch64")]
    // A four-byte aligned instruction occupies one cache line. Clean data to
    // PoU before invalidating instruction caches in the inner-shareable domain.
    unsafe {
        core::arch::asm!(
            "dc cvau, {address}", "dsb ish", "ic ivau, {address}", "dsb ish", "isb",
            address = in(reg) address, options(nostack, preserves_flags),
        );
    }
    #[cfg(not(target_arch = "aarch64"))]
    let _ = address;
    unsafe { libc::syscall(libc::SYS_membarrier, 1 << 5, 0, 0) == 0 }
}
