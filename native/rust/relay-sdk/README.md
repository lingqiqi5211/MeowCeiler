# NativeRelay SDK source snapshot

This directory contains the `native-relay-abi` and `native-relay` crates from NativeRelay commit `7d67cbecdbbf637e7d6be62a41a537738d190810`. `SHA256SUMS` identifies the exact imported source files. The workspace manifest includes only these two SDK crates. [Upstream source](https://github.com/lingqiqi5211/NativeRelay/tree/7d67cbecdbbf637e7d6be62a41a537738d190810/crates)

This snapshot makes clean APK builds independent of a local junction. It contains the client protocol, ABI and loader code; it does not package the ZygiskNext manager module or any weather rules. The NativeRelay repository remains the source for SDK changes: refresh this directory deliberately, review the diff and update the checksums together.
