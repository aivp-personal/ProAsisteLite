"""Patches a copy of glibc so that set_robust_list and rseq return ENOSYS without entering the kernel.

glibc makes these calls with every signal blocked (in fork, and when a thread starts), so Android's sandbox kills the
process instead of raising a signal a handler could answer. Each `svc #0` that follows a load of one of those call numbers
becomes `movn x0, #37`, which leaves -38 (-ENOSYS) in x0, the same as the kernel's answer. glibc works without both calls.

Every site must be found and changed, or this fails: a new glibc needs a person to look at it.
"""
import struct
import sys

SVC = 0xD4000001
RETURN_ENOSYS = 0x928004A0  # movn x0, #37
BLOCKED = {99: "set_robust_list", 293: "rseq"}
MAX_DISTANCE = 12  # Instructions allowed between loading the number and the svc.


def load_x8(number):
    return 0xD2800008 | (number << 5)


def is_any_load_x8(word):
    return (word & 0xFFE0001F) == 0xD2800008


def patch(source, destination):
    with open(source, "rb") as file:
        data = bytearray(file.read())
    words = len(data) // 4
    wanted = {load_x8(number): name for number, name in BLOCKED.items()}
    patched = {name: 0 for name in BLOCKED.values()}
    for index in range(words):
        word = struct.unpack_from("<I", data, index * 4)[0]
        if word not in wanted:
            continue
        name = wanted[word]
        for ahead in range(1, MAX_DISTANCE + 1):
            following = struct.unpack_from("<I", data, (index + ahead) * 4)[0] if index + ahead < words else None
            if following == SVC:
                struct.pack_into("<I", data, (index + ahead) * 4, RETURN_ENOSYS)
                patched[name] += 1
                break
            if following is None or is_any_load_x8(following):
                sys.exit(f"{name} is loaded at {index * 4:#x} with no system call after it")
        else:
            sys.exit(f"{name} is loaded at {index * 4:#x} with no system call after it")
    missing = [name for name, count in patched.items() if count == 0]
    if missing:
        sys.exit(f"Nothing to patch for {missing}")
    with open(destination, "wb") as file:
        file.write(data)
    print(f"patched glibc: {patched}")


if __name__ == "__main__":
    patch(sys.argv[1], sys.argv[2])
