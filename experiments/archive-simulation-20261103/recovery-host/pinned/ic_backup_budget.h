#ifndef IC_BACKUP_BUDGET_H
#define IC_BACKUP_BUDGET_H
#include <stdint.h>
#include <stddef.h>
#define IC_BACKUP_UNIT UINT64_C(16777698) /* 16 MiB + 482; target ARM ABI */
#ifndef IC_BACKUP_BYTES
#define IC_BACKUP_BYTES UINT64_C(0)
#endif
#ifndef IC_BACKUP_SESSIONS
#define IC_BACKUP_SESSIONS 0u
#endif
/* No provisioning, repair, reset, cleanup, or refund API. */
int ic_backup_admit(const char *root, uint64_t bytes, unsigned slots,
                    char *dir, size_t capacity);
#endif
