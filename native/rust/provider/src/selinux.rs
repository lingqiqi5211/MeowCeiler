use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::{AsRawFd, OwnedFd};

struct Library(*mut libc::c_void);
impl Drop for Library {
    fn drop(&mut self) {
        unsafe {
            libc::dlclose(self.0);
        }
    }
}
impl Library {
    fn symbol(&self, name: &CStr) -> io::Result<*mut libc::c_void> {
        let value = unsafe { libc::dlsym(self.0, name.as_ptr()) };
        if value.is_null() {
            return Err(io::Error::other(format!("missing SELinux symbol {name:?}")));
        }
        Ok(value)
    }
}

type GetContext = unsafe extern "C" fn(i32, *mut *mut libc::c_char) -> i32;
type SetContext = unsafe extern "C" fn(i32, *const libc::c_char) -> i32;
type FreeContext = unsafe extern "C" fn(*mut libc::c_char);
type Class = unsafe extern "C" fn(*const libc::c_char) -> u16;
type Create = unsafe extern "C" fn(
    *const libc::c_char,
    *const libc::c_char,
    u16,
    *mut *mut libc::c_char,
) -> i32;
type Check = unsafe extern "C" fn(
    *const libc::c_char,
    *const libc::c_char,
    *const libc::c_char,
    *const libc::c_char,
    *mut libc::c_void,
) -> i32;

struct Selinux {
    get: GetContext,
    set: SetContext,
    free: FreeContext,
    class: Class,
    create: Create,
    check: Check,
    _library: Library,
}

impl Selinux {
    fn open() -> io::Result<Self> {
        let handle =
            unsafe { libc::dlopen(c"libselinux.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
        if handle.is_null() {
            return Err(io::Error::other("cannot open libselinux"));
        }
        let library = Library(handle);
        // Resolve the C API once in the provider, never inside a target Hook callback.
        Ok(unsafe {
            Self {
                get: std::mem::transmute::<*mut libc::c_void, GetContext>(
                    library.symbol(c"fgetfilecon")?,
                ),
                set: std::mem::transmute::<*mut libc::c_void, SetContext>(
                    library.symbol(c"fsetfilecon")?,
                ),
                free: std::mem::transmute::<*mut libc::c_void, FreeContext>(
                    library.symbol(c"freecon")?,
                ),
                class: std::mem::transmute::<*mut libc::c_void, Class>(
                    library.symbol(c"string_to_security_class")?,
                ),
                create: std::mem::transmute::<*mut libc::c_void, Create>(
                    library.symbol(c"security_compute_create")?,
                ),
                check: std::mem::transmute::<*mut libc::c_void, Check>(
                    library.symbol(c"selinux_check_access")?,
                ),
                _library: library,
            }
        })
    }

    fn context(&self, call: impl FnOnce(*mut *mut libc::c_char) -> i32) -> io::Result<CString> {
        let mut raw = std::ptr::null_mut();
        let result = call(&mut raw);
        let error = io::Error::last_os_error();
        let context = if raw.is_null() {
            None
        } else {
            let context = unsafe { CStr::from_ptr(raw) }.to_owned();
            unsafe { (self.free)(raw) };
            Some(context)
        };
        if result < 0 {
            return Err(error);
        }
        context
            .filter(|c| !c.as_bytes().is_empty())
            .ok_or_else(|| io::Error::other("empty SELinux context"))
    }
}

pub(super) fn label_payload(fd: &OwnedFd, target: &CStr) -> io::Result<()> {
    native_relay::ipc::validate_payload(fd)?;
    let api = Selinux::open()?;
    let original = api.context(|out| unsafe { (api.get)(fd.as_raw_fd(), out) })?;
    let class = unsafe { (api.class)(c"file".as_ptr()) };
    if class == 0 {
        return Err(io::Error::other("SELinux file class unavailable"));
    }
    let derived =
        api.context(|out| unsafe { (api.create)(target.as_ptr(), original.as_ptr(), class, out) })?;
    for permission in [c"read", c"getattr", c"map", c"execute"] {
        if unsafe {
            (api.check)(
                target.as_ptr(),
                derived.as_ptr(),
                c"file".as_ptr(),
                permission.as_ptr(),
                std::ptr::null_mut(),
            )
        } < 0
        {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                format!("target cannot {permission:?} derived payload"),
            ));
        }
    }
    if unsafe { (api.set)(fd.as_raw_fd(), derived.as_ptr()) } < 0 {
        return Err(io::Error::last_os_error());
    }
    let actual = api.context(|out| unsafe { (api.get)(fd.as_raw_fd(), out) })?;
    if actual != derived {
        return Err(io::Error::other("payload label verification failed"));
    }
    native_relay::ipc::validate_payload(fd)
}
