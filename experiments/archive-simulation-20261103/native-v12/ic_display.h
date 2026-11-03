#ifndef IC_DISPLAY_H
#define IC_DISPLAY_H
#include <stddef.h>
typedef struct ic_display ic_display;
/* Guardian-only, serialized calls; no fork, signals, watchdog or writer thread
 * is created here. Guardian must stop/reap its writer BEFORE hide/close, and
 * enforce parent-death + frame-timeout leases externally. A cooperative owner
 * lock is NOT proof that OEM/kernel users cannot concurrently change FG.
 *
 * side 1 = (80,224), side 2 = (1280,224); one mutually exclusive 560x315 FG.
 * backup_prefix is an absolute filename prefix inside a precreated, owned,
 * mode-0700 /tmp/p7-viewassist/session-... directory. Backups are O_EXCL, 0600,
 * never overwritten or removed. open configures mode/position but stays blank.
 * frame requires exactly 352800 RGB565LE bytes, fills before first unblank.
 * No BG writes, alpha/key setters, register writes or cross-mode old mappings.
 *
 * BSP RISK: disabling FG temporarily moves it to (0,0) before disabling it.
 * Neither this API nor guardian proves absence of physical transient coverage,
 * tearing, OEM warning occlusion, shared CSC changes or kernel hangs. This is
 * local integration, NOT authorization to deploy or operate on a vehicle.
 *
 * close returns 0 only after geometry (except explicitly logged activate),
 * original mode list/position, original-byte hash, DP and BG state checks.
 * FG DMA address/allocation size and var.activate may differ and are logged;
 * this is NOT bit-for-bit full hardware restoration. Failure is -1 + errno;
 * close releases/frees its handle even on failure. Null close is a no-op.
 * hide blanks only (does not restore mode); close must still be called.
 * On open failure after mutation, restoration is attempted and failures logged.
 */
ic_display *ic_display_open(unsigned side, const char *backup_prefix);
int ic_display_frame(ic_display *, const unsigned char *rgb565, size_t len);
int ic_display_hide(ic_display *);
int ic_display_close(ic_display *);
#endif
