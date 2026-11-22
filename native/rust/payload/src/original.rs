use std::ffi::c_void;
use std::sync::OnceLock;

pub(crate) type SymbolLookup =
    unsafe extern "C" fn(*mut c_void, *const libc::c_char) -> *mut c_void;

mod private {
    pub trait Sealed {}
}

pub(crate) trait Signature: private::Sealed + Copy {
    /// # Safety
    /// address must name a process-lifetime function of exactly this C ABI signature.
    unsafe fn from_address(address: *mut c_void) -> Self;
}

macro_rules! signature {
    ($($signature:ty),+ $(,)?) => {$(
        impl private::Sealed for $signature {}
        impl Signature for $signature {
            unsafe fn from_address(address: *mut c_void) -> Self {
                unsafe { std::mem::transmute::<*mut c_void, Self>(address) }
            }
        }
    )+};
}
signature!(SymbolLookup);

// Immutable after publication: an unverified previous slot owner cannot replace it.
pub(crate) struct Original<F: Signature>(OnceLock<F>);

impl<F: Signature> Original<F> {
    pub(crate) const fn new() -> Self {
        Self(OnceLock::new())
    }

    /// # Safety
    /// The address must implement F, including its pointee and return-value ABI,
    /// and remain executable until process exit. Symbol lookup alone is insufficient.
    pub(crate) unsafe fn publish(&self, address: *mut c_void) -> bool {
        if address.is_null() || self.0.get().is_some() {
            return false;
        }
        self.0.set(unsafe { F::from_address(address) }).is_ok()
    }

    pub(crate) fn get(&self) -> F {
        match self.0.get() {
            Some(function) => *function,
            // Replacements must never become callable before publication. Abort
            // on a violated backend contract rather than unwind across the C ABI.
            None => std::process::abort(),
        }
    }
}
