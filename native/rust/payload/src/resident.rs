use crate::lookup::{Image, PF_R, Range};
use std::ffi::{CStr, c_void};

// This is a residency assertion, not an owning dynamic-linker handle.
pub(crate) struct ResidentImage {
    image: Image,
}

impl ResidentImage {
    /// # Safety
    /// The selected DSO must remain mapped for the entire process. The caller must
    /// establish this from its loader contract, not from its name or program headers.
    pub(crate) unsafe fn find(name: &CStr) -> Option<Self> {
        struct Search<'a> {
            name: &'a [u8],
            found: Option<Image>,
            invalid: bool,
        }
        unsafe extern "C" fn visit(
            info: *mut libc::dl_phdr_info,
            _: usize,
            data: *mut c_void,
        ) -> libc::c_int {
            // dl_iterate_phdr lends both the headers and our context for this callback.
            let info = unsafe { &*info };
            let search = unsafe { &mut *data.cast::<Search<'_>>() };
            if info.dlpi_name.is_null()
                || unsafe { CStr::from_ptr(info.dlpi_name) }
                    .to_bytes()
                    .rsplit(|b| *b == b'/')
                    .next()
                    != Some(search.name)
            {
                return 0;
            }
            if search.found.is_some() || info.dlpi_phnum > 64 || info.dlpi_phdr.is_null() {
                search.invalid = true;
                return 1;
            }
            let mut image = Image {
                base: info.dlpi_addr as usize,
                ..Image::default()
            };
            for header in
                unsafe { std::slice::from_raw_parts(info.dlpi_phdr, info.dlpi_phnum as usize) }
            {
                if header.p_type == libc::PT_DYNAMIC
                    && image
                        .dynamic
                        .replace((header.p_vaddr as usize, header.p_memsz as usize))
                        .is_some()
                {
                    search.invalid = true;
                    return 1;
                }
                if header.p_type != libc::PT_LOAD {
                    continue;
                }
                let range = image
                    .base
                    .checked_add(header.p_vaddr as usize)
                    .and_then(|start| {
                        start.checked_add(header.p_memsz as usize).map(|end| Range {
                            start,
                            end,
                            flags: header.p_flags,
                        })
                    });
                let Some(range) = range else {
                    search.invalid = true;
                    return 1;
                };
                if image.count == image.ranges.len() {
                    search.invalid = true;
                    return 1;
                }
                image.ranges[image.count] = range;
                image.count += 1;
            }
            search.found = Some(image);
            0
        }
        let mut search = Search {
            name: name.to_bytes(),
            found: None,
            invalid: false,
        };
        unsafe { libc::dl_iterate_phdr(Some(visit), (&mut search as *mut Search<'_>).cast()) };
        if search.invalid {
            None
        } else {
            search.found.map(|image| Self { image })
        }
    }

    pub(crate) fn base(&self) -> usize {
        self.image.base
    }

    pub(crate) fn address(&self, offset: usize, len: usize, flags: u32) -> Option<usize> {
        self.image.address(offset, len, flags)
    }

    /// # Safety
    /// The range must remain readable and must not be concurrently written during
    /// this copy. Residency and ELF permission flags do not establish synchronization.
    unsafe fn read(&self, offset: usize, out: &mut [u8]) -> Option<()> {
        let address = self.address(offset, out.len(), PF_R)?;
        if !out.is_empty() {
            unsafe {
                std::ptr::copy_nonoverlapping(address as *const u8, out.as_mut_ptr(), out.len())
            };
        }
        Some(())
    }

    /// # Safety
    /// The checked bytes must stay readable and unchanged throughout comparison.
    pub(crate) unsafe fn matches(&self, offset: usize, expected: &[u8], flags: u32) -> bool {
        if self.address(offset, expected.len(), flags | PF_R).is_none() {
            return false;
        }
        let mut bytes = [0; 128];
        for (index, chunk) in expected.chunks(bytes.len()).enumerate() {
            let Some(offset) = index
                .checked_mul(bytes.len())
                .and_then(|n| offset.checked_add(n))
            else {
                return false;
            };
            if unsafe { self.read(offset, &mut bytes[..chunk.len()]) }.is_none()
                || bytes[..chunk.len()] != *chunk
            {
                return false;
            }
        }
        true
    }
}
