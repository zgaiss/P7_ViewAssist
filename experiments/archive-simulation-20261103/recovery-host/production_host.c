/* Audited BSP contract: ARM32 little endian Linux 3.0.35 i.MX FG.
 * Derived from ic-vdec-probe-20261102/src/ic_display_play.c (560x315 cache
 * playback) and docs/display-kernel-audit-20261103.md. Device paths deliberately
 * fixed; no environment override, no automatic device discovery/fallback.
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include "ic_display.h"
#include "ic_pixels.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if 1 /* explicit host ARM32 fb ABI seam; device IO is stubbed */
#include <fcntl.h>
#include <linux/fb.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define GET_BLANK 0x8004462bUL
#define SET_POS 0xc0044624UL
#define DP_BASE 0x02740000UL
#define DP_ALPHA 0x40084621U /* same display ioctl used by the proven image projector */
#define MAX_MAP (16u * 1024u * 1024u)
#define MODE_OLD "U:240x320p-60\n"
#define MODE_NEW "U:560x315p-60\n"
#define SYS_FB "/sys/class/graphics/fb1/"
_Static_assert(sizeof(struct fb_var_screeninfo) == 160, "ARM32 var ABI");
_Static_assert(sizeof(struct fb_fix_screeninfo) == 68, "ARM32 fix ABI");
struct video_mode {
    uint32_t name, refresh, xres, yres, pixclock, left, right, upper, lower;
    uint32_t hsync, vsync, sync, vmode, flag;
};
struct position { uint16_t x, y; };
struct dp { uint32_t com, gw, pos; };
_Static_assert(sizeof(struct video_mode) == 56, "ARM32 modelist ABI");
_Static_assert(sizeof(struct position) == 4, "ARM32 position ABI");
struct ic_display {
    int fg, bg, mem, lock, backup_dir;
    int touched, mode_touched, visible, poisoned;
    unsigned char *backup, *fb;
    size_t mapped;
    unsigned stride;
    struct fb_var_screeninfo ov, obv, live_var;
    struct fb_fix_screeninfo of, obf, live_fix;
    volatile uint32_t *regs;
    struct dp od;
    struct position pos;
    char base[96];
};
static int xio(int fd, unsigned long op, void *p) {
    int r;
    do { r = ioctl(fd, op, p); } while (r < 0 && errno == EINTR);
    return r;
}
static int get(int fd, struct fb_var_screeninfo *v, struct fb_fix_screeninfo *f) {
    return xio(fd, FBIOGET_VSCREENINFO, v) || xio(fd, FBIOGET_FSCREENINFO, f) ? -1 : 0;
}
static int blank(int fd, unsigned value) {
    return xio(fd, FBIOBLANK, (void *)(uintptr_t)value);
}
static int is_blank(int fd, unsigned want) {
    unsigned b = 99;
    if (xio(fd, GET_BLANK, &b)) return -1;
    if (b != want) { errno = EIO; return -1; }
    return 0;
}
static struct dp read_dp(const ic_display *d) {
    struct dp v;
    __sync_synchronize();
    v.com = d->regs[0]; v.gw = d->regs[1]; v.pos = d->regs[2];
    __sync_synchronize();
    return v;
}
static int same_dp(struct dp a, struct dp b) {
    return a.com == b.com && a.gw == b.gw && a.pos == b.pos;
}
static int position(ic_display *d, struct position p) {
    struct position q = p;
    if (xio(d->fg, SET_POS, &q)) return -1;
    if (q.x != p.x || q.y != p.y ||
        read_dp(d).pos != ((uint32_t)p.x << 16 | p.y)) {
        errno = EIO; return -1;
    }
    return 0;
}
static uint32_t hash32(const void *data, size_t n) {
    const unsigned char *p = data;
    uint32_t h = 2166136261u;
    while (n--) { h ^= *p++; h *= 16777619u; }
    return h;
}
static int same_geom(const struct fb_var_screeninfo *a,
                     const struct fb_var_screeninfo *b) {
    struct fb_var_screeninfo x = *a, y = *b;
    x.activate = y.activate = 0;
    return !memcmp(&x, &y, sizeof(x));
}
static int same_fix_except_allocation(const struct fb_fix_screeninfo *a,
                                      const struct fb_fix_screeninfo *b) {
    struct fb_fix_screeninfo x = *a, y = *b;
    x.smem_start = y.smem_start = 0;
    x.smem_len = y.smem_len = 0;
    return !memcmp(&x, &y, sizeof(x));
}
static int rgb565(const struct fb_var_screeninfo *v) {
    return v->bits_per_pixel == 16 && v->red.offset == 11 && v->red.length == 5 &&
        !v->red.msb_right && v->green.offset == 5 && v->green.length == 6 &&
        !v->green.msb_right && v->blue.offset == 0 && v->blue.length == 5 &&
        !v->blue.msb_right && !v->transp.length;
}
static int checked_dir(int fd, int exact_private) {
    struct stat s;
    if (fstat(fd, &s)) return -1;
    if (!S_ISDIR(s.st_mode) || s.st_uid != geteuid() ||
        (exact_private ? (s.st_mode & 0777) != 0700 : (s.st_mode & 0022) != 0)) {
        errno = EPERM; return -1;
    }
    return 0;
}
/* Parent creates the session directory. Open each path component without
 * following symlinks; use the directory fd thereafter, never reconstruct a
 * writable pathname. Fixed owner lock persists: NEVER unlink a live lock. */
static int prepare_backup(ic_display *d, const char *prefix) {
    const char *root = "/tmp/p7-viewassist/", *leaf, *slash;
    char session[128];
    int rootfd = -1;
    struct stat s;
    if (!prefix || strncmp(prefix, root, strlen(root))) goto invalid;
    leaf = prefix + strlen(root);
    slash = strchr(leaf, '/');
    if (!slash || strncmp(leaf, "session-", 8) || slash - leaf <= 8 ||
        (size_t)(slash - leaf) >= sizeof(session) || !slash[1] ||
        strlen(slash + 1) >= sizeof(d->base) || strchr(slash + 1, '/') ||
        strstr(leaf, "..")) goto invalid;
    for (const char *p = leaf; *p; ++p)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '-' || *p == '_' ||
              *p == '.' || *p == '/')) goto invalid;
    memcpy(session, leaf, (size_t)(slash - leaf));
    session[slash - leaf] = 0;
    strcpy(d->base, slash + 1);
    rootfd = open("/tmp/p7-viewassist", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (rootfd < 0 || checked_dir(rootfd, 0)) goto fail;
    d->backup_dir = openat(rootfd, session, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (d->backup_dir < 0 || checked_dir(d->backup_dir, 1)) goto fail;
    d->lock = openat(rootfd, "display.owner.lock",
                     O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (d->lock < 0 || fstat(d->lock, &s)) goto fail;
    if (!S_ISREG(s.st_mode) || s.st_uid != geteuid() || s.st_nlink != 1 ||
        (s.st_mode & 0777) != 0600) { errno = EPERM; goto fail; }
    if (flock(d->lock, LOCK_EX | LOCK_NB)) goto fail;
    close(rootfd);
    return 0;
invalid:
    errno = EINVAL;
fail:
    { int e = errno; if (rootfd >= 0) close(rootfd); errno = e; }
    return -1;
}
static int save(ic_display *d, const char *suffix, const void *buf, size_t len) {
    char name[160];
    if (snprintf(name, sizeof(name), "%s.%s", d->base, suffix) >= (int)sizeof(name)) {
        errno = ENAMETOOLONG; return -1;
    }
    int fd = openat(d->backup_dir, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    const unsigned char *p = buf;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { int e = n ? errno : EIO; close(fd); errno = e; return -1; }
        p += n; len -= (size_t)n;
    }
    int r = fsync(fd), e = errno;
    if (close(fd) && !r) { r = -1; e = errno; }
    errno = e;
    return r;
}
static int sys_write(const char *path, const void *buf, size_t len, int zero_ok) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n;
    do { n = write(fd, buf, len); } while (n < 0 && errno == EINTR);
    int e = errno;
    if (close(fd) && n == (ssize_t)len) return -1;
    if (n == (ssize_t)len || (zero_ok && n == 0)) return 0;
    errno = n < 0 ? e : EIO;
    return -1;
}
static int mode_list_has(const char *name, int exact) {
    char buf[256];
    int fd = open(SYS_FB "modes", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n;
    do { n = read(fd, buf, sizeof(buf) - 1); } while (n < 0 && errno == EINTR);
    int e = errno;
    close(fd);
    if (n < 0) { errno = e; return -1; }
    if (n >= (ssize_t)sizeof(buf) - 1) { errno = EOVERFLOW; return -1; }
    buf[n] = 0;
    if (exact ? strcmp(buf, name) != 0 : strstr(buf, name) == NULL) {
        errno = EIO; return -1;
    }
    return 0;
}
static struct video_mode mode_of(const struct fb_var_screeninfo *v) {
    struct video_mode m = {0};
    m.refresh = 60; m.xres = v->xres; m.yres = v->yres;
    m.pixclock = v->pixclock; m.left = v->left_margin; m.right = v->right_margin;
    m.upper = v->upper_margin; m.lower = v->lower_margin;
    m.hsync = v->hsync_len; m.vsync = v->vsync_len; m.sync = v->sync; m.vmode = v->vmode;
    return m;
}
/* BSP sysfs activate sets USEREVENT; plain PUT_VAR silently did not apply
 * geometry. No mapping may survive these transitions. Initial sole baseline
 * modelist is enforced, so this reconstructs that exact registered mode. */
static int configure_mode(ic_display *d, int wide) {
    struct video_mode modes[2] = { mode_of(&d->ov), mode_of(&d->ov) };
    const char *name = wide ? MODE_NEW : MODE_OLD;
    const char *virt = wide ? "560,945\n" : "240,960\n";
    if (d->fb != MAP_FAILED) { errno = EBUSY; return -1; }
    modes[1].xres = 560; modes[1].yres = 315;
    if (sys_write(SYS_FB "modes", modes, wide ? sizeof(modes) : sizeof(modes[0]), 1) ||
        mode_list_has(name, !wide) ||
        sys_write(SYS_FB "mode", name, strlen(name), 0) ||
        sys_write(SYS_FB "virtual_size", virt, strlen(virt), 0)) return -1;
    return 0;
}
static int check_bg(ic_display *d) {
    struct fb_var_screeninfo v;
    struct fb_fix_screeninfo f;
    if (get(d->bg, &v, &f) || memcmp(&v, &d->obv, sizeof(v)) ||
        memcmp(&f, &d->obf, sizeof(f)) || is_blank(d->bg, 0)) {
        fprintf(stderr, "IC_DISPLAY BG geometry/fix/blank changed (BG is read-only)\n");
        errno = EIO; return -1;
    }
    return 0;
}
static int wide_geometry(const struct fb_var_screeninfo *v, const struct fb_fix_screeninfo *f) {
    return v->xres == 560 && v->yres == 315 && v->xres_virtual == 560 &&
        v->yres_virtual == 945 && !v->xoffset && !v->yoffset && rgb565(v) &&
        f->line_length >= 1120 && f->line_length <= 4096 &&
        f->smem_len >= (uint64_t)f->line_length * v->yres_virtual && f->smem_len <= MAX_MAP;
}
static int unmap_fb(ic_display *d) {
    if (d->fb == MAP_FAILED) return 0;
    if (munmap(d->fb, d->mapped)) return -1;
    d->fb = MAP_FAILED; d->mapped = 0;
    return 0;
}
static int restore(ic_display *d) {
    int ok = 1, blank_ok;
    struct fb_var_screeninfo v;
    struct fb_fix_screeninfo f;
    if (unmap_fb(d)) return -1; /* Never transition with a surviving mapping. */
    blank_ok = !blank(d->fg, 4) && !is_blank(d->fg, 4);
    if (!blank_ok) ok = 0;
    d->visible = 0;
    struct position p = { (uint16_t)(d->od.pos >> 16), (uint16_t)d->od.pos };
    if (blank_ok) {
        if (position(d, p)) ok = 0;
        if (d->mode_touched && configure_mode(d, 0)) {
            fprintf(stderr, "IC_DISPLAY RESTORE mode configuration failed\n"); ok = 0;
        }
        /* Verify position again AFTER mode transition; setter is not a getter. */
        if (position(d, p)) ok = 0;
    }
    if (!blank_ok || get(d->fg, &v, &f) || !same_geom(&v, &d->ov) ||
        !same_fix_except_allocation(&f, &d->of) ||
        f.smem_len < d->of.smem_len || f.smem_len > MAX_MAP ||
        mode_list_has(MODE_OLD, 1)) {
        fprintf(stderr, "IC_DISPLAY RESTORE geometry/modelist mismatch; no pixel write\n");
        ok = 0;
    } else {
        unsigned char *p = mmap(NULL, f.smem_len, PROT_READ | PROT_WRITE, MAP_SHARED, d->fg, 0);
        if (p == MAP_FAILED) ok = 0;
        else {
            memcpy(p, d->backup, d->of.smem_len);
            __sync_synchronize();
            uint32_t h = hash32(p, d->of.smem_len), want = hash32(d->backup, d->of.smem_len);
            if (h != want) ok = 0;
            fprintf(stderr, "IC_DISPLAY RESTORE pixels_fnv=%08x expected=%08x bytes=%u "
                    "allocation=%08lx/%u original=%08lx/%u activate=%08x original=%08x "
                    "allocation_and_activate_may_differ=1\n", h, want, d->of.smem_len,
                    (unsigned long)f.smem_start, f.smem_len, (unsigned long)d->of.smem_start, d->of.smem_len,
                    v.activate, d->ov.activate);
            if (munmap(p, f.smem_len)) ok = 0;
        }
    }
    int32_t alpha[2] = {1, (int32_t)((d->od.gw >> 24) & 0xffu)};
    if (xio(d->bg, DP_ALPHA, alpha)) ok = 0;
    if (check_bg(d)) ok = 0;
    struct dp dp = read_dp(d);
    if (!same_dp(dp, d->od) || is_blank(d->fg, 4)) ok = 0;
    fprintf(stderr, "IC_DISPLAY RESTORE_%s dp=%08x/%08x/%08x expected=%08x/%08x/%08x "
            "physical_transient_not_excluded=1\n", ok ? "OK" : "FAILED",
            dp.com, dp.gw, dp.pos, d->od.com, d->od.gw, d->od.pos);
    if (!ok) { errno = EIO; return -1; }
    return 0;
}
static void release(ic_display *d) {
    if (!d) return;
    if (d->fb != MAP_FAILED) (void)munmap(d->fb, d->mapped);
    if (d->regs != MAP_FAILED) (void)munmap((void *)d->regs, 4096);
    if (d->fg >= 0) close(d->fg);
    if (d->bg >= 0) close(d->bg);
    if (d->mem >= 0) close(d->mem);
    if (d->backup_dir >= 0) close(d->backup_dir);
    /* Closing the lock fd LAST releases the cooperative single-owner lease. */
    if (d->lock >= 0) close(d->lock);
    free(d->backup); free(d);
}
ic_display *ic_display_open(unsigned side, const char *prefix) {
    if (side != 1 && side != 2) { errno = EINVAL; return NULL; }
    ic_display *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->fg = d->bg = d->mem = d->lock = d->backup_dir = -1;
    d->fb = MAP_FAILED; d->regs = MAP_FAILED;
    d->pos.x = side == 1 ? 80 : 1280; d->pos.y = 224;
    if (prepare_backup(d, prefix)) goto fail;
    d->fg = open("/dev/fb1", O_RDWR | O_CLOEXEC);
    d->bg = open("/dev/fb0", O_RDONLY | O_CLOEXEC);
    d->mem = open("/dev/mem", O_RDONLY | O_CLOEXEC);
    if (d->fg < 0 || d->bg < 0 || d->mem < 0 ||
        get(d->fg, &d->ov, &d->of) || get(d->bg, &d->obv, &d->obf) ||
        is_blank(d->fg, 4) || is_blank(d->bg, 0)) goto fail;
    if (strncmp(d->of.id, "DISP3 FG", 16) ||
        d->ov.xres != 240 || d->ov.yres != 320 || d->ov.xres_virtual != 240 ||
        d->ov.yres_virtual != 960 || d->ov.xoffset || d->ov.yoffset || !rgb565(&d->ov) ||
        d->of.line_length != 480 || d->of.smem_len < 460800 || d->of.smem_len > MAX_MAP ||
        strncmp(d->obf.id, "DISP3 BG - DI1", 16) ||
        d->obv.xres != 1920 || d->obv.yres != 720 || d->obv.bits_per_pixel != 32) {
        errno = ENOTSUP; goto fail;
    }
    d->regs = mmap(NULL, 4096, PROT_READ, MAP_SHARED, d->mem, DP_BASE);
    if (d->regs == MAP_FAILED) goto fail;
    d->od = read_dp(d);
    if (d->od.com != 4 || d->od.gw != 0x80000000u || d->od.pos != 0) {
        errno = EBUSY; goto fail;
    }
    if (mode_list_has(MODE_OLD, 1)) goto fail;
    d->backup = malloc(d->of.smem_len);
    if (!d->backup) goto fail;
    d->mapped = d->of.smem_len;
    d->fb = mmap(NULL, d->mapped, PROT_READ, MAP_SHARED, d->fg, 0);
    if (d->fb == MAP_FAILED) goto fail;
    memcpy(d->backup, d->fb, d->mapped);
    if (unmap_fb(d)) goto fail;
    if (save(d, "original.var", &d->ov, sizeof(d->ov)) ||
        save(d, "original.fix", &d->of, sizeof(d->of)) ||
        save(d, "original.bgvar", &d->obv, sizeof(d->obv)) ||
        save(d, "original.bgfix", &d->obf, sizeof(d->obf)) ||
        save(d, "original.dp", &d->od, sizeof(d->od)) ||
        save(d, "original.modes", MODE_OLD, strlen(MODE_OLD)) ||
        save(d, "original.pixels", d->backup, d->of.smem_len) || fsync(d->backup_dir)) goto fail;
    /* Recheck the baseline immediately before mutation, not only at open. */
    struct fb_var_screeninfo v;
    struct fb_fix_screeninfo f;
    if (get(d->fg, &v, &f) || memcmp(&v, &d->ov, sizeof(v)) ||
        memcmp(&f, &d->of, sizeof(f)) || !same_dp(read_dp(d), d->od) ||
        is_blank(d->fg, 4) || check_bg(d)) { errno = EBUSY; goto fail; }
    fprintf(stderr, "IC_DISPLAY WARNING BSP disable temporarily sets FG (0,0); "
            "physical transient coverage NOT excluded; no vehicle operation authorized\n");
    d->touched = 1;
    if (blank(d->fg, 4) || is_blank(d->fg, 4)) goto fail;
    d->mode_touched = 1;
    if (configure_mode(d, 1) || get(d->fg, &v, &f) || !wide_geometry(&v, &f) ||
        is_blank(d->fg, 4)) { errno = EIO; goto fail; }
    d->mapped = f.smem_len; d->stride = f.line_length;
    d->live_var = v; d->live_fix = f;
    d->fb = mmap(NULL, d->mapped, PROT_READ | PROT_WRITE, MAP_SHARED, d->fg, 0);
    if (d->fb == MAP_FAILED) goto fail;
    memset(d->fb, 0, d->mapped);
    __sync_synchronize();
    if (position(d, d->pos) || is_blank(d->fg, 4) || check_bg(d)) goto fail;
    /* Match the previously proven image projector: make the FG plane opaque
     * instead of inheriting the OEM BG global alpha (0x80). The driver ioctl
     * updates COM/GW and the shadow latch; restore() applies the saved BG alpha. */
    int32_t alpha[2] = {1, 255};
    if (xio(d->fg, DP_ALPHA, alpha)) goto fail;
    struct dp expected = d->od;
    expected.com = (d->od.com & ~0x7u) | 0x6u; /* FG alpha selected+enabled, still blank */
    expected.gw = (d->od.gw & 0x00ffffffu) | 0xff000000u;
    expected.pos = (uint32_t)d->pos.x << 16 | d->pos.y;
    if (!same_dp(read_dp(d), expected)) { errno = EIO; goto fail; }
    return d;
fail:
    { int e = errno ? errno : EIO;
      if (d->touched && restore(d)) {
          fprintf(stderr, "IC_DISPLAY open failed and restoration FAILED; backups retained\n");
          e = EIO;
      }
      release(d); errno = e; return NULL; }
}
int ic_display_frame(ic_display *d, const unsigned char *rgb, size_t len) {
    if (!d || !rgb || len != IC_PIXELS_RGB565_BYTES) { errno = EINVAL; return -1; }
    if (d->poisoned) { errno = EIO; return -1; }
    struct fb_var_screeninfo v;
    struct fb_fix_screeninfo f;
    struct dp expected = d->od;
    expected.com = (d->od.com & ~0x7u) | (d->visible ? 0x7u : 0x6u);
    expected.gw = (d->od.gw & 0x00ffffffu) | 0xff000000u;
    expected.pos = (uint32_t)d->pos.x << 16 | d->pos.y;
    /* GET validation before each write: never knowingly write a stale mapping.
     * An uncooperative concurrent mode setter can still race this check. */
    if (get(d->fg, &v, &f) || !wide_geometry(&v, &f) ||
        !same_geom(&v, &d->live_var) || memcmp(&f, &d->live_fix, sizeof(f)) ||
        f.smem_len != d->mapped || f.line_length != d->stride ||
        is_blank(d->fg, d->visible ? 0 : 4) || !same_dp(read_dp(d), expected) || check_bg(d)) goto bad;
    for (unsigned y = 0; y < IC_PIXELS_HEIGHT; ++y)
        memcpy(d->fb + (size_t)y * d->stride, rgb + (size_t)y * IC_PIXELS_WIDTH * 2u,
               IC_PIXELS_WIDTH * 2u);
    __sync_synchronize();
    if (!d->visible) {
        /* The WHOLE first image is populated before enabling FG. */
        if (blank(d->fg, 0) || is_blank(d->fg, 0)) goto bad;
        d->visible = 1;
    }
    expected.com = (d->od.com & ~0x7u) | 0x7u;
    expected.gw = (d->od.gw & 0x00ffffffu) | 0xff000000u;
    if (get(d->fg, &v, &f) || !same_geom(&v, &d->live_var) ||
        memcmp(&f, &d->live_fix, sizeof(f)) ||
        !same_dp(read_dp(d), expected) || check_bg(d)) goto bad;
    return 0;
bad:
    d->poisoned = 1;
    errno = EIO;
    /* Guardian must stop writer then call hide/close; do not fork or race it. */
    return -1;
}
int ic_display_hide(ic_display *d) {
    if (!d) { errno = EINVAL; return -1; }
    int r = blank(d->fg, 4) || is_blank(d->fg, 4);
    /* BSP blank resets FG position; restore the requested hidden position. */
    if (!r && position(d, d->pos)) r = 1;
    d->visible = 0;
    int32_t alpha[2] = {1, (int32_t)((d->od.gw >> 24) & 0xffu)};
    if (!r && xio(d->bg, DP_ALPHA, alpha)) r = 1;
    struct dp expected = d->od;
    expected.pos = (uint32_t)d->pos.x << 16 | d->pos.y;
    if (!same_dp(read_dp(d), expected) || check_bg(d)) r = 1;
    if (r) { d->poisoned = 1; errno = EIO; return -1; }
    return 0;
}
int ic_display_close(ic_display *d) {
    if (!d) return 0;
    int r = d->touched ? restore(d) : 0;
    int e = errno;
    release(d);
    if (r) errno = e ? e : EIO;
    return r;
}
#else
/* Explicitly unsupported rather than a successful fake display on host. */
struct ic_display { int unused; };
ic_display *ic_display_open(unsigned side, const char *prefix) {
    (void)side; (void)prefix; errno = ENOTSUP; return NULL;
}
int ic_display_frame(ic_display *d, const unsigned char *p, size_t n) {
    (void)d; (void)p; (void)n; errno = ENOTSUP; return -1;
}
int ic_display_hide(ic_display *d) { (void)d; errno = ENOTSUP; return -1; }
int ic_display_close(ic_display *d) {
    if (!d) return 0;
    errno = ENOTSUP; return -1;
}
#endif
