// Starts glibc's loader inside Android's app sandbox. The sandbox answers some system calls with SIGSYS, and glibc makes
// two of them (set_robust_list, rseq) before any of its libraries could install a handler. So this program, which uses no
// libc, installs the handler first, maps the loader into this same process and jumps to it. There is no exec, so the
// handler survives, and it answers each blocked call with ENOSYS, which glibc already knows how to handle.
//
// Usage: launcher <glibc loader> <loader arguments...>
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;

#define SYS_write 64
#define SYS_openat 56
#define SYS_close 57
#define SYS_pread64 67
#define SYS_mmap 222
#define SYS_exit_group 94
#define SYS_rt_sigaction 134
#define PAGE 4096UL
#define PT_LOAD 1
#define AT_PHDR 3
#define AT_PHENT 4
#define AT_PHNUM 5
#define AT_BASE 7
#define AT_ENTRY 9

struct elf_header {
    unsigned char ident[16];
    u16 type, machine;
    u32 version;
    u64 entry, phoff, shoff;
    u32 flags;
    u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};

struct program_header {
    u32 type, flags;
    u64 offset, vaddr, paddr, filesz, memsz, align;
};

static inline long sys6(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

static void write_text(const char *text) {
    long length = 0;
    while (text[length]) length++;
    sys6(SYS_write, 2, (long)text, length, 0, 0, 0);
}

static void fail(const char *text) {
    write_text("[launcher] ");
    write_text(text);
    write_text("\n");
    sys6(SYS_exit_group, 127, 0, 0, 0, 0, 0);
}

#define MAX_CALL 1024
static volatile char seen[MAX_CALL];

static void log_blocked(long call) {
    char digits[24];
    int position = 22;
    digits[23] = 0;
    digits[22] = '\n';
    do {
        digits[--position] = '0' + call % 10;
        call /= 10;
    } while (call > 0);
    write_text("[launcher] blocked system call ");
    write_text(&digits[position]);
}

// siginfo: the system call number is at offset 24. ucontext: mcontext starts at offset 176, and x0 follows fault_address.
static void on_blocked(int signal, void *info, void *context) {
    (void)signal;
    int call = *(int *)((char *)info + 24);
    if (call >= 0 && call < MAX_CALL && !seen[call]) {
        seen[call] = 1;
        log_blocked(call);
    }
    *(u64 *)((char *)context + 184) = (u64)-38; // -ENOSYS
}

struct kernel_action {
    void *handler;
    u64 flags;
    void *restorer;
    u64 mask;
};

static u64 map_loader(const char *path, struct elf_header *header) {
    long file = sys6(SYS_openat, -100, (long)path, 0, 0, 0, 0);
    if (file < 0) fail("can't open the loader");
    if (sys6(SYS_pread64, file, (long)header, sizeof(*header), 0, 0, 0) != sizeof(*header)) fail("can't read the loader");
    static struct program_header headers[32];
    if (header->phnum > 32) fail("too many program headers");
    long size = header->phnum * sizeof(struct program_header);
    if (sys6(SYS_pread64, file, (long)headers, size, header->phoff, 0, 0) != size) fail("can't read program headers");

    u64 end = 0;
    for (int i = 0; i < header->phnum; i++) {
        if (headers[i].type != PT_LOAD) continue;
        u64 last = headers[i].vaddr + headers[i].memsz;
        if (last > end) end = last;
    }
    end = (end + PAGE - 1) & ~(PAGE - 1);
    // PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS: reserves one range for all the segments.
    long base = sys6(SYS_mmap, 0, end, 0, 0x22, -1, 0);
    if (base < 0 && base > -4096) fail("can't reserve memory for the loader");

    for (int i = 0; i < header->phnum; i++) {
        struct program_header *segment = &headers[i];
        if (segment->type != PT_LOAD) continue;
        int protection = ((segment->flags & 4) ? 1 : 0) | ((segment->flags & 2) ? 2 : 0) | ((segment->flags & 1) ? 4 : 0);
        u64 start = segment->vaddr & ~(PAGE - 1);
        u64 skipped = segment->vaddr - start;
        u64 file_end = (segment->vaddr + segment->filesz + PAGE - 1) & ~(PAGE - 1);
        u64 memory_end = (segment->vaddr + segment->memsz + PAGE - 1) & ~(PAGE - 1);
        if (segment->filesz > 0) {
            // MAP_PRIVATE | MAP_FIXED
            long mapped = sys6(SYS_mmap, base + start, file_end - start, protection | 2, 0x12, file, segment->offset - skipped);
            if (mapped < 0 && mapped > -4096) fail("can't map a loader segment");
        }
        if (memory_end > file_end) {
            // MAP_PRIVATE | MAP_FIXED | MAP_ANONYMOUS
            long mapped = sys6(SYS_mmap, base + file_end, memory_end - file_end, protection | 2, 0x32, -1, 0);
            if (mapped < 0 && mapped > -4096) fail("can't map the loader's zeroed memory");
        }
        // The end of the last file page must read as zeros too.
        if (segment->memsz > segment->filesz) {
            char *zero = (char *)(base + segment->vaddr + segment->filesz);
            char *stop = (char *)(base + file_end);
            while (zero < stop) *zero++ = 0;
        }
        // mprotect: back to what the segment asked for.
        sys6(226, base + start, (memory_end > file_end ? memory_end : file_end) - start, protection, 0, 0, 0);
    }
    sys6(SYS_close, file, 0, 0, 0, 0, 0);
    return (u64)base;
}

__attribute__((noreturn)) void start(u64 *stack) {
    struct kernel_action action = {(void *)on_blocked, 4 /* SA_SIGINFO */ | 0x40000000 /* SA_NODEFER */, 0, 0};
    if (sys6(SYS_rt_sigaction, 31, (long)&action, 0, 8, 0, 0) != 0) fail("can't install the handler");

    long count = (long)stack[0];
    if (count < 2) fail("usage: launcher <loader> <arguments>");
    char **arguments = (char **)(stack + 1);
    char **environment = arguments + count + 1;
    long environment_count = 0;
    while (environment[environment_count]) environment_count++;
    u64 *auxiliary = (u64 *)(environment + environment_count + 1);
    long auxiliary_words = 0;
    while (auxiliary[auxiliary_words] != 0) auxiliary_words += 2;
    auxiliary_words += 2;

    struct elf_header header;
    u64 base = map_loader(arguments[1], &header);

    // The stack the loader expects: as if the kernel had started it directly, with this program's name removed.
    long words = 1 + count + environment_count + 1 + auxiliary_words;
    u64 *fresh = (u64 *)(((u64)stack - (words + 4) * sizeof(u64) - 4096) & ~15UL);
    long at = 0;
    fresh[at++] = count - 1;
    for (long i = 1; i < count; i++) fresh[at++] = (u64)arguments[i];
    fresh[at++] = 0;
    for (long i = 0; i < environment_count; i++) fresh[at++] = (u64)environment[i];
    fresh[at++] = 0;
    for (long i = 0; i < auxiliary_words; i += 2) {
        u64 key = auxiliary[i];
        u64 value = auxiliary[i + 1];
        if (key == AT_PHDR) value = base + header.phoff;
        if (key == AT_PHNUM) value = header.phnum;
        if (key == AT_PHENT) value = header.phentsize;
        if (key == AT_ENTRY) value = base + header.entry;
        if (key == AT_BASE) value = 0;
        fresh[at++] = key;
        fresh[at++] = value;
    }

    // Fixed registers: the instructions below clear x0, x29 and x30, so neither value may live in one of those.
    register u64 *fresh_stack __asm__("x17") = fresh;
    register u64 entry __asm__("x16") = base + header.entry;
    __asm__ volatile(
        "mov sp, %0\n"
        "mov x0, xzr\n"
        "mov x29, xzr\n"
        "mov x30, xzr\n"
        "br %1\n"
        : : "r"(fresh_stack), "r"(entry));
    __builtin_unreachable();
}

__asm__(
    ".text\n"
    ".global _start\n"
    "_start:\n"
    "  mov x0, sp\n"
    "  bl start\n");

void *memset(void *destination, int value, unsigned long size) {
    volatile char *out = destination;
    while (size--) *out++ = (char)value;
    return destination;
}

void *memcpy(void *destination, const void *source, unsigned long size) {
    volatile char *out = destination;
    const char *in = source;
    while (size--) *out++ = *in++;
    return destination;
}
