/* Compile with baseline ARMEL flags, never -mfpu=neon or hard-float.
 * Keeping row scratch here leaves the default-off converter stack unchanged. */
#include "ic_pixels_simd.h"
#include "../ic_pixels.h"
#include <errno.h>
#if !defined(__linux__) || !defined(__arm__) || defined(__ARM_PCS_VFP)
#error "NEON dispatcher requires Linux ARM32 base AAPCS"
#endif
#if defined(__ARM_NEON)
#error "Compile the dispatcher with baseline flags, not NEON"
#endif
#include <elf.h>
#include <asm/hwcap.h>
/* The target's historical headers predate sys/auxv.h, although its libc
 * exports getauxval. This is the libc ABI declaration, not a substitute.
 * An unresolved weak symbol stays on scalar. This does not promise loading
 * on older libc: the final ELF symbol-version requirements still apply. */
extern unsigned long getauxval(unsigned long type) __attribute__((weak));
int ic_pixels_neon_available(void) {
    int saved_errno = errno;
    unsigned long caps = getauxval ? getauxval(AT_HWCAP) : 0;
    errno = saved_errno;
    return (caps & HWCAP_NEON) != 0;
}
void ic_pixels_gather_neon(const unsigned char *y, const unsigned char *u,
                           const unsigned char *v, const unsigned *sx,
                           unsigned char *dst, unsigned n) {
    unsigned char yy[IC_PIXELS_WIDTH], uu[IC_PIXELS_WIDTH], vv[IC_PIXELS_WIDTH];
    for (unsigned dx = 0; dx < n; ++dx) {
        unsigned x = sx[dx];
        yy[dx] = y[x];
        uu[dx] = u[x / 2u];
        vv[dx] = v[x / 2u];
    }
    ic_pixels_row_neon(yy, uu, vv, dst, n);
}
